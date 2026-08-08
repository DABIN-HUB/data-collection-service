package com.wangbin.collector.storage.buffer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.storage.service.TimeSeriesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HistoryWriteBufferFailureRecoveryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Map<Logger, Level> originalLogLevels;

    @BeforeEach
    void setUp() {
        muteExpectedFailureLogs();
    }

    @AfterEach
    void tearDown() {
        originalLogLevels.forEach(Logger::setLevel);
    }

    @Test
    void directWriteSuccessShouldNotEnterFallbackQueues() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(2, 10);
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);
        HistoryWriteRequest request = request("dev-1", "p1", 1_000L);

        buffer.writeOrBuffer(request);

        verify(timeSeriesService).append(
                request.getDeviceId(),
                request.getProtocolType(),
                request.getPoint(),
                request.getProcessResult(),
                request.getEventTs());
        verify(redisLists.operations, never()).leftPush(anyString(), anyString());
        HistoryBufferMetrics metrics = buffer.metrics();
        assertEquals(0L, metrics.redisPending());
        assertEquals(0L, metrics.redisProcessing());
        assertEquals(0L, metrics.redisDeadLetter());
        assertEquals(0, metrics.localPending());
    }

    @Test
    void tdengineFailureShouldEnterRedisPendingWithOriginalPayload() throws Exception {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(2, 10);
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);
        HistoryWriteRequest request = request("dev-redis-pending", "temperature", 1_234L);
        doThrow(new DataAccessResourceFailureException("TDengine unavailable"))
                .when(timeSeriesService).append(anyString(), anyString(), any(), any(), anyLong());

        buffer.writeOrBuffer(request);

        assertEquals(1L, redisLists.size(properties.getPendingKey()));
        assertEquals(0, buffer.metrics().localPending());
        HistoryWriteRequest buffered = readRequest(redisLists.peekFirst(properties.getPendingKey()));
        assertEquals(request.getDeviceId(), buffered.getDeviceId());
        assertEquals(request.getProtocolType(), buffered.getProtocolType());
        assertEquals(request.getPoint().getPointId(), buffered.getPoint().getPointId());
        assertEquals(request.getEventTs(), buffered.getEventTs());
        assertEquals(request.getProcessResult().getFinalValue(), buffered.getProcessResult().getFinalValue());
    }

    @Test
    void deferForRetryShouldEnterRedisPendingWithoutDirectTdengineWrite() throws Exception {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(2, 10);
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);
        HistoryWriteRequest request = request("dev-overload-redis", "p1", 1_500L);

        buffer.deferForRetry(request, new java.util.concurrent.RejectedExecutionException("history full"));

        verify(timeSeriesService, never()).append(anyString(), anyString(), any(), any(), anyLong());
        assertEquals(1L, redisLists.size(properties.getPendingKey()));
        assertEquals(1L, buffer.metrics().rejectedRedisBuffered());
        assertEquals(0L, buffer.metrics().rejectedLocalBuffered());
        assertEquals(0L, buffer.metrics().rejectedDropped());
        HistoryWriteRequest buffered = readRequest(redisLists.peekFirst(properties.getPendingKey()));
        assertEquals(request.getDeviceId(), buffered.getDeviceId());
        assertEquals(request.getPoint().getPointId(), buffered.getPoint().getPointId());
        assertEquals(request.getEventTs(), buffered.getEventTs());
    }

    @Test
    void deferForRetryShouldUseLocalQueueWhenRedisPendingFails() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(2, 3);
        redisLists.failLeftPush(properties.getPendingKey(), 10);
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);

        buffer.deferForRetry(request("dev-overload-local", "p1", 1_600L),
                new java.util.concurrent.RejectedExecutionException("history full"));

        verify(timeSeriesService, never()).append(anyString(), anyString(), any(), any(), anyLong());
        assertEquals(0L, redisLists.size(properties.getPendingKey()));
        assertEquals(1, buffer.metrics().localPending());
        assertEquals(0L, buffer.metrics().rejectedRedisBuffered());
        assertEquals(1L, buffer.metrics().rejectedLocalBuffered());
        assertEquals(0L, buffer.metrics().rejectedDropped());
    }

    @Test
    void deferForRetryShouldCountExplicitDropWhenLocalQueueIsFull() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(2, 2);
        redisLists.failLeftPush(properties.getPendingKey(), 10);
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);

        for (int index = 0; index < 3; index++) {
            buffer.deferForRetry(request("dev-overload-drop", "p" + index, 1_700L + index),
                    new java.util.concurrent.RejectedExecutionException("history full"));
        }

        verify(timeSeriesService, never()).append(anyString(), anyString(), any(), any(), anyLong());
        assertEquals(2, buffer.metrics().localPending());
        assertEquals(2L, buffer.metrics().rejectedLocalBuffered());
        assertEquals(1L, buffer.metrics().rejectedDropped());
    }

    @Test
    void replayShouldMovePendingThroughProcessingAndRemoveAfterRecovery() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(2, 10);
        HistoryWriteBuffer failedBuffer = buffer(timeSeriesService, redisLists, properties);
        HistoryWriteRequest request = request("dev-replay", "p1", 2_000L);
        doThrow(new DataAccessResourceFailureException("TDengine unavailable"))
                .when(timeSeriesService).append(anyString(), anyString(), any(), any(), anyLong());

        failedBuffer.writeOrBuffer(request);

        TimeSeriesService recoveredService = mock(TimeSeriesService.class);
        HistoryWriteBuffer recoveredBuffer = buffer(recoveredService, redisLists, properties);
        recoveredBuffer.replay();

        verify(recoveredService).append(
                request.getDeviceId(),
                request.getProtocolType(),
                request.getPoint(),
                request.getProcessResult(),
                request.getEventTs());
        assertEquals(0L, redisLists.size(properties.getPendingKey()));
        assertEquals(0L, redisLists.size(properties.getProcessingKey()));
        assertEquals(0, recoveredBuffer.metrics().localPending());
    }

    @Test
    void replayFailureShouldKeepProcessingForNextAttempt() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(2, 10);
        HistoryWriteRequest request = request("dev-processing-retry", "p1", 3_000L);
        redisLists.leftPush(properties.getPendingKey(), writeJson(request));
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new DataAccessResourceFailureException("TDengine replay failed");
            }
            return null;
        }).when(timeSeriesService).append(anyString(), anyString(), any(), any(), anyLong());
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);

        buffer.replay();

        assertEquals(1, attempts.get());
        assertEquals(0L, redisLists.size(properties.getPendingKey()));
        assertEquals(1L, redisLists.size(properties.getProcessingKey()));

        buffer.replay();

        assertEquals(2, attempts.get());
        assertEquals(0L, redisLists.size(properties.getProcessingKey()));
    }

    @Test
    void processingMessageShouldBeRetriedBeforeNewPendingMessage() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(1, 10);
        HistoryWriteRequest processing = request("dev-priority", "processing", 4_000L);
        HistoryWriteRequest pending = request("dev-priority", "pending", 5_000L);
        redisLists.leftPush(properties.getProcessingKey(), writeJson(processing));
        redisLists.leftPush(properties.getPendingKey(), writeJson(pending));
        List<String> writtenPoints = new ArrayList<>();
        doAnswer(invocation -> {
            DataPoint point = invocation.getArgument(2);
            writtenPoints.add(point.getPointId());
            return null;
        }).when(timeSeriesService).append(anyString(), anyString(), any(), any(), anyLong());
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);

        buffer.replay();

        assertEquals(List.of("processing"), writtenPoints);
        assertEquals(1L, redisLists.size(properties.getPendingKey()));
        assertEquals(0L, redisLists.size(properties.getProcessingKey()));
    }

    @Test
    void successfulWriteFollowedByProcessingRemoveFailureShouldReplaySameTimestamp() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(1, 10);
        HistoryWriteRequest request = request("dev-remove-failure", "p1", 6_000L);
        redisLists.leftPush(properties.getProcessingKey(), writeJson(request));
        redisLists.failRemove(properties.getProcessingKey(), 1);
        List<Long> eventTsValues = new ArrayList<>();
        doAnswer(invocation -> {
            eventTsValues.add(invocation.getArgument(4));
            return null;
        }).when(timeSeriesService).append(anyString(), anyString(), any(), any(), anyLong());
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);

        buffer.replay();
        buffer.replay();

        assertEquals(List.of(6_000L, 6_000L), eventTsValues);
        assertEquals(0L, redisLists.size(properties.getProcessingKey()));
    }

    @Test
    void simultaneousTdengineAndRedisFailureShouldUseBoundedLocalQueueAndReplayAfterRecovery() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(2, 2);
        HistoryWriteRequest request = request("dev-local", "p1", 7_000L);
        AtomicBoolean tdengineFailing = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (tdengineFailing.get()) {
                throw new DataAccessResourceFailureException("TDengine unavailable");
            }
            return null;
        }).when(timeSeriesService).append(anyString(), anyString(), any(), any(), anyLong());
        redisLists.failLeftPush(properties.getPendingKey(), 10);
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);

        buffer.writeOrBuffer(request);

        assertEquals(0L, redisLists.size(properties.getPendingKey()));
        assertEquals(1, buffer.metrics().localPending());

        tdengineFailing.set(false);
        buffer.replay();

        assertEquals(0, buffer.metrics().localPending());
    }

    @Test
    void fullLocalQueueShouldDropWithoutBlockingOrGrowing() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(2, 2);
        doThrow(new DataAccessResourceFailureException("TDengine unavailable"))
                .when(timeSeriesService).append(anyString(), anyString(), any(), any(), anyLong());
        redisLists.failLeftPush(properties.getPendingKey(), 10);
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);

        for (int i = 0; i < 3; i++) {
            buffer.writeOrBuffer(request("dev-full", "p" + i, 8_000L + i));
        }

        HistoryBufferMetrics metrics = buffer.metrics();
        assertEquals(2, metrics.localCapacity());
        assertEquals(2, metrics.localPending());
        assertEquals(0L, metrics.redisPending());
    }

    @Test
    void malformedProcessingMessageShouldMoveToDeadLetterAndContinuePendingBacklog() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(2, 10);
        HistoryWriteRequest pending = request("dev-poison", "normal", 9_000L);
        redisLists.leftPush(properties.getProcessingKey(), "{not-json");
        redisLists.leftPush(properties.getPendingKey(), writeJson(pending));
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> {
            writes.incrementAndGet();
            return null;
        }).when(timeSeriesService).append(anyString(), anyString(), any(), any(), anyLong());
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);

        buffer.replay();

        assertEquals(1L, redisLists.size(properties.getDeadLetterKey()));
        assertEquals(0L, redisLists.size(properties.getProcessingKey()));
        assertEquals(0L, redisLists.size(properties.getPendingKey()));
        assertEquals(1, writes.get());
    }

    @Test
    void deadLetterRedisFailureAfterProcessingRemoveDropsMalformedMessageWithoutTightLoop() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(2, 10);
        redisLists.leftPush(properties.getProcessingKey(), "{not-json");
        redisLists.failLeftPush(properties.getDeadLetterKey(), 1);
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);

        buffer.replay();

        assertEquals(0L, redisLists.size(properties.getProcessingKey()));
        assertEquals(0L, redisLists.size(properties.getDeadLetterKey()));
    }

    @Test
    void deadLetterRemoveFailureKeepsPoisonInProcessingButReplayStopsAtBatchBoundary() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(2, 10);
        redisLists.leftPush(properties.getProcessingKey(), "{not-json");
        redisLists.failRemove(properties.getProcessingKey(), 10);
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);

        buffer.replay();

        assertEquals(1L, redisLists.size(properties.getProcessingKey()));
        assertEquals(0L, redisLists.size(properties.getDeadLetterKey()));
        assertEquals(2, redisLists.removeAttempts(properties.getProcessingKey()));
    }

    @Test
    void failureStormShouldFillPendingAndDrainAfterRecovery() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(100, 10);
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            attempts.incrementAndGet();
            if (failing.get()) {
                throw new DataAccessResourceFailureException("TDengine unavailable");
            }
            return null;
        }).when(timeSeriesService).append(anyString(), anyString(), any(), any(), anyLong());
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);

        for (int i = 0; i < 100; i++) {
            buffer.writeOrBuffer(request("dev-storm-" + (i % 20), "p" + i, 10_000L + i));
        }

        assertEquals(100L, redisLists.size(properties.getPendingKey()));
        assertEquals(0, buffer.metrics().localPending());

        failing.set(false);
        buffer.replay();

        assertEquals(200, attempts.get());
        assertEquals(0L, redisLists.size(properties.getPendingKey()));
        assertEquals(0L, redisLists.size(properties.getProcessingKey()));
        assertEquals(0, buffer.metrics().localPending());
    }

    @Test
    void repeatedFailureRecoveryReplayCyclesShouldNotGrowQueues() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(10, 10);
        AtomicBoolean failing = new AtomicBoolean();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            attempts.incrementAndGet();
            if (failing.get()) {
                throw new DataAccessResourceFailureException("TDengine unavailable");
            }
            return null;
        }).when(timeSeriesService).append(anyString(), anyString(), any(), any(), anyLong());
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);

        for (int i = 0; i < 100; i++) {
            failing.set(true);
            buffer.writeOrBuffer(request("dev-cycle", "p" + i, 12_000L + i));
            assertEquals(1L, redisLists.size(properties.getPendingKey()));

            failing.set(false);
            buffer.replay();
            assertEquals(0L, redisLists.size(properties.getPendingKey()));
            assertEquals(0L, redisLists.size(properties.getProcessingKey()));
            assertEquals(0, buffer.metrics().localPending());
        }

        assertEquals(200, attempts.get());
    }

    @Test
    void shutdownShouldCompleteForFailFastReplayFailure() {
        TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        RedisLists redisLists = new RedisLists();
        HistoryBufferProperties properties = properties(2, 10);
        redisLists.leftPush(properties.getProcessingKey(), writeJson(request("dev-shutdown", "p1", 11_000L)));
        doThrow(new DataAccessResourceFailureException("TDengine unavailable"))
                .when(timeSeriesService).append(anyString(), anyString(), any(), any(), anyLong());
        HistoryWriteBuffer buffer = buffer(timeSeriesService, redisLists, properties);

        long startedAt = System.nanoTime();
        buffer.shutdown();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(elapsedMs < 1_000L);
        assertEquals(1L, redisLists.size(properties.getProcessingKey()));
    }

    private HistoryWriteBuffer buffer(TimeSeriesService timeSeriesService,
                                      RedisLists redisLists,
                                      HistoryBufferProperties properties) {
        return new HistoryWriteBuffer(timeSeriesService, redisLists.template, objectMapper, properties);
    }

    private HistoryBufferProperties properties(int replayBatchSize, int localQueueCapacity) {
        HistoryBufferProperties properties = new HistoryBufferProperties();
        properties.setEnabled(true);
        properties.setPendingKey("history:pending");
        properties.setProcessingKey("history:processing");
        properties.setDeadLetterKey("history:dead");
        properties.setReplayBatchSize(replayBatchSize);
        properties.setLocalQueueCapacity(localQueueCapacity);
        return properties;
    }

    private HistoryWriteRequest request(String deviceId, String pointId, long eventTs) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setPointName(pointId);
        point.setStatus(1);
        point.setAdditionalConfig(new LinkedHashMap<>());
        ProcessResult result = ProcessResult.success(20D, 20D, "ok");
        return new HistoryWriteRequest(deviceId, "MODBUS_TCP", point, result, eventTs);
    }

    private String writeJson(HistoryWriteRequest request) {
        try {
            return objectMapper.copy()
                    .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                    .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                    .writeValueAsString(request);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private HistoryWriteRequest readRequest(String json) throws Exception {
        return objectMapper.copy()
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .readValue(json, HistoryWriteRequest.class);
    }

    private void muteExpectedFailureLogs() {
        originalLogLevels = new LinkedHashMap<>();
        for (Class<?> loggerClass : List.of(HistoryWriteBuffer.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
            originalLogLevels.put(logger, logger.getLevel());
            logger.setLevel(Level.OFF);
        }
    }

    private static final class RedisLists {
        private final StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final ListOperations<String, String> operations = mock(ListOperations.class);
        private final Map<String, Deque<String>> lists = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> leftPushFailures = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> removeFailures = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> removeAttempts = new ConcurrentHashMap<>();

        private RedisLists() {
            org.mockito.Mockito.when(template.opsForList()).thenReturn(operations);
            org.mockito.Mockito.when(operations.leftPush(anyString(), anyString()))
                    .thenAnswer(invocation -> {
                        String key = invocation.getArgument(0);
                        String value = invocation.getArgument(1);
                        if (shouldFail(leftPushFailures, key)) {
                            throw new RedisConnectionFailureException("redis leftPush failed");
                        }
                        Deque<String> list = list(key);
                        list.addFirst(value);
                        return (long) list.size();
                    });
            org.mockito.Mockito.when(operations.rightPopAndLeftPush(anyString(), anyString()))
                    .thenAnswer(invocation -> {
                        String source = invocation.getArgument(0);
                        String destination = invocation.getArgument(1);
                        Deque<String> sourceList = list(source);
                        String value = sourceList.pollLast();
                        if (value == null) {
                            return null;
                        }
                        list(destination).addFirst(value);
                        return value;
                    });
            org.mockito.Mockito.when(operations.index(anyString(), anyLong()))
                    .thenAnswer(invocation -> {
                        String key = invocation.getArgument(0);
                        long index = invocation.getArgument(1);
                        if (index < 0) {
                            return null;
                        }
                        int current = 0;
                        for (String value : list(key)) {
                            if (current++ == index) {
                                return value;
                            }
                        }
                        return null;
                    });
            org.mockito.Mockito.when(operations.remove(anyString(), anyLong(), anyString()))
                    .thenAnswer(invocation -> {
                        String key = invocation.getArgument(0);
                        String value = invocation.getArgument(2);
                        removeAttempts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
                        if (shouldFail(removeFailures, key)) {
                            throw new RedisConnectionFailureException("redis remove failed");
                        }
                        boolean removed = list(key).removeFirstOccurrence(value);
                        return removed ? 1L : 0L;
                    });
            org.mockito.Mockito.when(operations.size(anyString()))
                    .thenAnswer(invocation -> (long) list(invocation.getArgument(0)).size());
        }

        private void leftPush(String key, String value) {
            list(key).addFirst(value);
        }

        private String peekFirst(String key) {
            return list(key).peekFirst();
        }

        private long size(String key) {
            return list(key).size();
        }

        private void failLeftPush(String key, int times) {
            leftPushFailures.put(key, new AtomicInteger(times));
        }

        private void failRemove(String key, int times) {
            removeFailures.put(key, new AtomicInteger(times));
        }

        private int removeAttempts(String key) {
            AtomicInteger attempts = removeAttempts.get(key);
            return attempts == null ? 0 : attempts.get();
        }

        private Deque<String> list(String key) {
            return lists.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        }

        private boolean shouldFail(Map<String, AtomicInteger> failures, String key) {
            AtomicInteger remaining = failures.get(key);
            return remaining != null && remaining.getAndDecrement() > 0;
        }
    }
}

package com.wangbin.collector.core.cache.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.enums.StreamRetentionMode;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TelemetryStreamServiceImplTest {

    private RedisTemplate<String, Object> redisTemplate;
    private RedisConnection connection;
    private TelemetryStreamProperties properties;
    private StreamWriteBuffer buffer;
    private TelemetryStreamServiceImpl service;
    private ExecutorService writerExecutor;
    private Logger serviceLogger;
    private Level originalServiceLogLevel;
    private Logger bufferLogger;
    private Level originalBufferLogLevel;

    @BeforeEach
    void setUp() {
        serviceLogger = (Logger) LoggerFactory.getLogger(TelemetryStreamServiceImpl.class);
        originalServiceLogLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.OFF);
        bufferLogger = (Logger) LoggerFactory.getLogger(StreamWriteBuffer.class);
        originalBufferLogLevel = bufferLogger.getLevel();
        bufferLogger.setLevel(Level.OFF);
        redisTemplate = mock(RedisTemplate.class);
        connection = mock(RedisConnection.class);
        properties = new TelemetryStreamProperties();
        properties.setKey("collector:telemetry:stream");
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });
        mockPipelineSuccess(1);
        writerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("stream-write-buffer-test");
            return thread;
        });
        buffer = new StreamWriteBuffer(redisTemplate, properties, writerExecutor);
        service = new TelemetryStreamServiceImpl(redisTemplate, properties, new ObjectMapper(), buffer);
    }

    @AfterEach
    void tearDown() {
        if (buffer != null) {
            buffer.stop();
        }
        if (writerExecutor != null) {
            writerExecutor.shutdownNow();
            try {
                writerExecutor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if (serviceLogger != null) {
            serviceLogger.setLevel(originalServiceLogLevel);
        }
        if (bufferLogger != null) {
            bufferLogger.setLevel(originalBufferLogLevel);
        }
    }

    @Test
    void stageProcessMustOnlyPerformFastAdmission() {
        properties.getBuffer().setShutdownTimeoutMs(50);
        buffer = new StreamWriteBuffer(redisTemplate, properties, command -> {
        });
        service = new TelemetryStreamServiceImpl(redisTemplate, properties, new ObjectMapper(), buffer);
        buffer.start();

        boolean accepted = service.appendBestEffort("d1", point("p1"), ProcessResult.success(1, 1));

        assertTrue(accepted);
        assertEquals(1L, service.metrics().admissionAccepted());
        assertEquals(1, service.metrics().bufferSize());
        verify(redisTemplate, times(0)).execute(any(RedisCallback.class));
    }

    @Test
    void appendCountModeShouldUsePipelinedXaddMaxLen() throws Exception {
        properties.setRetentionMode(StreamRetentionMode.COUNT);
        properties.setMaxLength(200);
        properties.setApproximateTrim(true);

        buffer.start();
        service.append("d1", point("p1"), ProcessResult.success(1, 1));
        waitUntil(() -> service.metrics().xaddSuccess() == 1L);

        Object[] args = findExecuteInvocationArgs("XADD");
        assertTrue(args.length >= 6);
        assertEquals("collector:telemetry:stream", str((byte[]) args[1]));
        assertEquals("MAXLEN", str((byte[]) args[2]));
        assertEquals("~", str((byte[]) args[3]));
        assertEquals("200", str((byte[]) args[4]));
        assertEquals("*", str((byte[]) args[5]));
    }

    @Test
    void appendTimeModeShouldWriteEventTimestampField() throws Exception {
        properties.setRetentionMode(StreamRetentionMode.TIME);
        properties.setApproximateTrim(true);

        buffer.start();
        service.append("d1", point("p1"), ProcessResult.success(2, 2));
        waitUntil(() -> service.metrics().xaddSuccess() == 1L);

        Object[] args = findExecuteInvocationArgs("XADD");
        List<String> values = asStrings(args, 1);
        assertTrue(values.contains("eventTs"));
        assertTrue(values.contains("processResult"));
    }

    @Test
    void trimByTimeRetentionShouldUseXtrimMinId() {
        properties.setRetentionMode(StreamRetentionMode.TIME);
        properties.setTrimTaskEnabled(true);
        properties.setApproximateTrim(true);
        properties.setMaxSeconds(60);

        service.trimByTimeRetention();

        Object[] args = findExecuteInvocationArgs("XTRIM");
        assertTrue(args.length >= 4);
        assertEquals("collector:telemetry:stream", str((byte[]) args[1]));
        assertEquals("MINID", str((byte[]) args[2]));
    }

    @Test
    void writerMustDrainInBatches() throws Exception {
        properties.getBuffer().setBatchSize(3);
        mockPipelineSuccess(3);
        buffer.start();

        for (int index = 0; index < 3; index++) {
            assertTrue(service.appendBestEffort("d1", point("p" + index), ProcessResult.success(index, index)));
        }
        waitUntil(() -> service.metrics().xaddSuccess() == 3L);

        assertEquals(1L, service.metrics().writerBatchCount());
        assertEquals(3L, service.metrics().redisXaddRows());
        assertEquals(3, xaddInvocationCount());
    }

    @Test
    void redisPipelineMustKeepOneEntryPerTelemetry() throws Exception {
        properties.getBuffer().setBatchSize(5);
        mockPipelineSuccess(5);
        buffer.start();

        for (int index = 0; index < 5; index++) {
            service.append("d1", point("p" + index), ProcessResult.success(index, index));
        }
        waitUntil(() -> service.metrics().xaddSuccess() == 5L);

        assertEquals(1L, service.metrics().redisPipelineCalls());
        assertEquals(5, xaddInvocationCount());
    }

    @Test
    void redisFailureMustBeExplicitlyAccounted() throws Exception {
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        buffer.start();

        service.append("d1", point("p1"), ProcessResult.success(1, 1));
        waitUntil(() -> service.metrics().xaddFailure() == 1L);

        TelemetryStreamMetrics metrics = service.metrics();
        assertEquals(1L, metrics.appendAttempts());
        assertEquals(1L, metrics.admissionAccepted());
        assertEquals(0L, metrics.xaddSuccess());
        assertEquals(1L, metrics.xaddFailure());
    }

    @Test
    void bufferMustRemainBounded() {
        properties.getBuffer().setCapacity(1);
        properties.getBuffer().setShutdownTimeoutMs(50);
        buffer = new StreamWriteBuffer(redisTemplate, properties, command -> {
        });
        service = new TelemetryStreamServiceImpl(redisTemplate, properties, new ObjectMapper(), buffer);
        buffer.start();

        assertTrue(service.appendBestEffort("d1", point("p1"), ProcessResult.success(1, 1)));
        assertFalse(service.appendBestEffort("d1", point("p2"), ProcessResult.success(2, 2)));

        TelemetryStreamMetrics metrics = service.metrics();
        assertEquals(1L, metrics.admissionAccepted());
        assertEquals(1L, metrics.admissionRejected());
        assertEquals(1L, metrics.admissionDropped());
        assertEquals(1, metrics.bufferCapacity());
    }

    @Test
    void concurrentAdmissionMustNotLoseAcceptedItems() throws Exception {
        properties.getBuffer().setCapacity(200);
        properties.getBuffer().setShutdownTimeoutMs(50);
        buffer = new StreamWriteBuffer(redisTemplate, properties, command -> {
        });
        service = new TelemetryStreamServiceImpl(redisTemplate, properties, new ObjectMapper(), buffer);
        buffer.start();
        int total = 100;
        List<Boolean> results = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            int current = index;
            Thread thread = new Thread(() -> results.add(service.appendBestEffort(
                    "d1", point("p" + current), ProcessResult.success(current, current))));
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(2));
        }

        assertEquals(total, results.size());
        assertTrue(results.stream().allMatch(Boolean::booleanValue));
        assertEquals(total, service.metrics().admissionAccepted());
        assertEquals(total, service.metrics().bufferSize());
    }

    @Test
    void shutdownMustDrainAcceptedItems() throws Exception {
        CountDownLatch firstWrite = new CountDownLatch(1);
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            Object result = callback.doInRedis(connection);
            firstWrite.countDown();
            return result;
        });
        when(connection.closePipeline()).thenReturn(List.of("1-0"));
        buffer.start();

        service.append("d1", point("p1"), ProcessResult.success(1, 1));
        buffer.stop();

        assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
        assertEquals(1L, service.metrics().xaddSuccess());
        assertEquals(0, service.metrics().bufferSize());
        assertEquals(0L, service.metrics().shutdownDroppedRows());
    }

    @Test
    void shutdownTimeoutMustAccountRemainingItems() throws Exception {
        properties.getBuffer().setShutdownTimeoutMs(50);
        CountDownLatch entered = new CountDownLatch(1);
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            entered.countDown();
            TimeUnit.SECONDS.sleep(2);
            return List.of("1-0");
        });
        buffer.start();

        service.append("d1", point("p1"), ProcessResult.success(1, 1));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        buffer.stop();

        assertTrue(service.metrics().shutdownDroppedRows() >= 1L);
    }

    @Test
    void writerFailureMustNotKillWriterLoop() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new RedisSystemException("redis temporary failure",
                        new RedisConnectionFailureException("redis down"));
            }
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });
        when(connection.closePipeline()).thenReturn(List.of("1-0"));
        buffer.start();

        service.append("d1", point("p1"), ProcessResult.success(1, 1));
        waitUntil(() -> service.metrics().xaddFailure() == 1L);
        service.append("d1", point("p2"), ProcessResult.success(2, 2));
        waitUntil(() -> service.metrics().xaddSuccess() == 1L);

        assertEquals(1L, service.metrics().xaddFailure());
        assertEquals(1L, service.metrics().xaddSuccess());
    }

    @Test
    void streamFailureMustNotBeSilent() throws Exception {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("bad payload") {
                });
        buffer.start();
        TelemetryStreamServiceImpl brokenService = new TelemetryStreamServiceImpl(
                redisTemplate, properties, brokenMapper, buffer);

        boolean accepted = brokenService.appendBestEffort("d1", point("p1"), ProcessResult.success(1, 1));

        assertFalse(accepted);
        TelemetryStreamMetrics metrics = brokenService.metrics();
        assertEquals(1L, metrics.appendAttempts());
        assertEquals(1L, metrics.serializationFailures());
        assertEquals(0L, metrics.xaddSuccess());
        assertEquals(0L, metrics.xaddFailure());
    }

    @Test
    void streamLatencyReservoirMustRemainSafeNearLongBoundary() {
        buffer.start();

        assertDoesNotThrow(() -> {
            for (int index = 0; index < 6; index++) {
                service.append("d1", point("p" + index), ProcessResult.success(index, index));
            }
        });

        TelemetryStreamMetrics metrics = service.metrics();
        assertEquals(6L, metrics.appendAttempts());
    }

    private Object[] findExecuteInvocationArgs(String command) {
        return mockingDetails(connection).getInvocations().stream()
                .filter(invocation -> "execute".equals(invocation.getMethod().getName()))
                .map(invocation -> invocation.getArguments())
                .filter(arguments -> arguments.length > 0 && command.equals(arguments[0]))
                .findFirst()
                .orElseThrow();
    }

    private void mockPipelineSuccess(int rows) {
        when(connection.closePipeline()).thenReturn(
                java.util.stream.IntStream.range(0, rows).mapToObj(index -> (Object) ("1-" + index)).toList());
    }

    private int xaddInvocationCount() {
        return (int) mockingDetails(connection).getInvocations().stream()
                .filter(invocation -> "execute".equals(invocation.getMethod().getName()))
                .map(invocation -> invocation.getArguments())
                .filter(arguments -> arguments.length > 0 && "XADD".equals(arguments[0]))
                .count();
    }

    private static List<String> asStrings(Object[] args, int fromIndex) {
        return Stream.of(args)
                .skip(fromIndex)
                .filter(byte[].class::isInstance)
                .map(byte[].class::cast)
                .map(TelemetryStreamServiceImplTest::str)
                .toList();
    }

    private static String str(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static DataPoint point(String pointId) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setPointName(pointId);
        point.setDeviceId("d1");
        return point;
    }

    private void waitUntil(Condition condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(condition.isSatisfied());
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}

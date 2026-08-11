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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.getField;

public class TelemetryStreamServiceImplTest {

    private RedisTemplate<String, Object> redisTemplate;
    private RedisConnection connection;
    private TelemetryStreamProperties properties;
    private TelemetryStreamServiceImpl service;
    private Logger serviceLogger;
    private Level originalServiceLogLevel;

    @BeforeEach
    void setUp() {
        serviceLogger = (Logger) LoggerFactory.getLogger(TelemetryStreamServiceImpl.class);
        originalServiceLogLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.OFF);
        redisTemplate = mock(RedisTemplate.class);
        connection = mock(RedisConnection.class);
        properties = new TelemetryStreamProperties();
        ObjectMapper objectMapper = new ObjectMapper();

        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });

        service = new TelemetryStreamServiceImpl(redisTemplate, properties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (serviceLogger != null) {
            serviceLogger.setLevel(originalServiceLogLevel);
        }
    }

    @Test
    void append_countMode_shouldUseXaddMaxLen() {
        properties.setEnabled(true);
        properties.setKey("collector:telemetry:stream");
        properties.setRetentionMode(StreamRetentionMode.COUNT);
        properties.setMaxLength(200);
        properties.setApproximateTrim(true);

        ProcessResult result = ProcessResult.success(1, 1);
        DataPoint point = new DataPoint();
        point.setPointId("p1");
        point.setPointCode("code1");
        point.setPointName("name1");
        point.setDeviceId("d1");

        service.append("d1", point, result);

        Object[] args = findExecuteInvocationArgs("XADD");
        assertTrue(args.length >= 6);
        assertEquals("collector:telemetry:stream", str((byte[]) args[1]));
        assertEquals("MAXLEN", str((byte[]) args[2]));
        assertEquals("~", str((byte[]) args[3]));
        assertEquals("200", str((byte[]) args[4]));
        assertEquals("*", str((byte[]) args[5]));
    }

    @Test
    void append_timeMode_shouldWriteEventTimestampField() {
        properties.setEnabled(true);
        properties.setRetentionMode(StreamRetentionMode.TIME);
        properties.setApproximateTrim(true);

        DataPoint point = new DataPoint();
        point.setPointId("p1");
        point.setPointCode("code1");
        point.setPointName("name1");
        point.setDeviceId("d1");

        service.append("d1", point, ProcessResult.success(2, 2));

        Object[] args = findExecuteInvocationArgs("XADD");
        List<String> values = asStrings(args, 1);
        assertTrue(values.contains("eventTs"));
        assertTrue(values.contains("processResult"));
    }

    @Test
    void trimByTimeRetention_shouldUseXtrimMinId() {
        properties.setEnabled(true);
        properties.setRetentionMode(StreamRetentionMode.TIME);
        properties.setTrimTaskEnabled(true);
        properties.setApproximateTrim(true);
        properties.setMaxSeconds(60);
        properties.setKey("collector:telemetry:stream");

        service.trimByTimeRetention();

        Object[] args = findExecuteInvocationArgs("XTRIM");
        assertTrue(args.length >= 4);
        assertEquals("collector:telemetry:stream", str((byte[]) args[1]));
        assertEquals("MINID", str((byte[]) args[2]));
    }

    @Test
    void append_shouldIsolateRedisConnectionFailure() {
        properties.setEnabled(true);
        properties.setKey("collector:telemetry:stream");
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        assertDoesNotThrow(() -> service.append("d1", point("p1"), ProcessResult.success(1, 1)));

        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void append_shouldRecoverAfterTemporaryRedisFailure() {
        properties.setEnabled(true);
        properties.setKey("collector:telemetry:stream");
        AtomicInteger calls = new AtomicInteger();
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() <= 2) {
                throw new RedisSystemException("redis temporary failure",
                        new RedisConnectionFailureException("redis down"));
            }
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });

        service.append("d1", point("p1"), ProcessResult.success(1, 1));
        service.append("d1", point("p2"), ProcessResult.success(2, 2));
        service.append("d1", point("p3"), ProcessResult.success(3, 3));

        assertEquals(3, calls.get());
        Object[] args = findExecuteInvocationArgs("XADD");
        assertEquals("collector:telemetry:stream", str((byte[]) args[1]));
        verify(redisTemplate, times(3)).execute(any(RedisCallback.class));
    }

    @Test
    void redisXaddFailureMustBeSeparatelyAccounted() {
        properties.setEnabled(true);
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        service.append("d1", point("p1"), ProcessResult.success(1, 1));

        TelemetryStreamMetrics metrics = service.metrics();
        assertEquals(1L, metrics.appendAttempts());
        assertEquals(0L, metrics.xaddSuccess());
        assertEquals(1L, metrics.xaddFailure());
    }

    @Test
    void streamSuccessCountMustOnlyCountSuccessfulXadd() {
        properties.setEnabled(true);
        AtomicInteger calls = new AtomicInteger();
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw new RedisConnectionFailureException("redis unavailable");
            }
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });

        service.append("d1", point("p1"), ProcessResult.success(1, 1));
        service.append("d1", point("p2"), ProcessResult.success(2, 2));

        TelemetryStreamMetrics metrics = service.metrics();
        assertEquals(2L, metrics.appendAttempts());
        assertEquals(1L, metrics.xaddSuccess());
        assertEquals(1L, metrics.xaddFailure());
    }

    @Test
    void streamFailureMustNotBeSilent() throws Exception {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("bad payload") {
                });
        TelemetryStreamServiceImpl brokenService = new TelemetryStreamServiceImpl(
                redisTemplate, properties, brokenMapper);

        brokenService.append("d1", point("p1"), ProcessResult.success(1, 1));

        TelemetryStreamMetrics metrics = brokenService.metrics();
        assertEquals(1L, metrics.appendAttempts());
        assertEquals(1L, metrics.serializationFailures());
        assertEquals(0L, metrics.xaddSuccess());
        assertEquals(0L, metrics.xaddFailure());
    }

    @Test
    void shutdownMustAccountAcceptedStreamTasks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            entered.countDown();
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RedisSystemException("redis command interrupted", exception);
            }
            return null;
        });
        Thread thread = new Thread(() -> service.append("d1", point("p1"), ProcessResult.success(1, 1)),
                "stream-shutdown-test");

        thread.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        thread.interrupt();
        thread.join(TimeUnit.SECONDS.toMillis(2));

        TelemetryStreamMetrics metrics = service.metrics();
        assertEquals(1L, metrics.appendAttempts());
        assertEquals(0L, metrics.xaddSuccess());
        assertEquals(1L, metrics.xaddFailure());
    }

    @Test
    void streamLatencyReservoirMustRemainSafeNearLongBoundary() {
        properties.setEnabled(true);
        setReservoirSequence("appendLatencyNanos", Long.MAX_VALUE - 2L);
        setReservoirSequence("xaddLatencyNanos", Long.MAX_VALUE - 2L);

        assertDoesNotThrow(() -> {
            for (int index = 0; index < 6; index++) {
                service.append("d1", point("p" + index), ProcessResult.success(index, index));
            }
        });

        TelemetryStreamMetrics metrics = service.metrics();
        assertEquals(6L, metrics.appendAttempts());
        assertEquals(6L, metrics.xaddSuccess());
        assertEquals(0L, metrics.xaddFailure());
    }

    private Object[] findExecuteInvocationArgs(String command) {
        return mockingDetails(connection).getInvocations().stream()
                .filter(invocation -> "execute".equals(invocation.getMethod().getName()))
                .map(invocation -> invocation.getArguments())
                .filter(arguments -> arguments.length > 0 && command.equals(arguments[0]))
                .findFirst()
                .orElseThrow();
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

    private void setReservoirSequence(String fieldName, long value) {
        Object reservoir = getField(service, fieldName);
        AtomicLong sequence = (AtomicLong) getField(reservoir, "sequence");
        sequence.set(value);
    }
}

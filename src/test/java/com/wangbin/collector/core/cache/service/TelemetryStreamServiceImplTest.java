package com.wangbin.collector.core.cache.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.enums.StreamRetentionMode;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TelemetryStreamServiceImplTest {

    private RedisTemplate<String, Object> redisTemplate;
    private RedisConnection connection;
    private TelemetryStreamProperties properties;
    private TelemetryStreamServiceImpl service;

    @BeforeEach
    void setUp() {
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
}

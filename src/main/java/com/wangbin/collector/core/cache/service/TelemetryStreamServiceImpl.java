package com.wangbin.collector.core.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.enums.StreamRetentionMode;
import com.wangbin.collector.core.cache.util.TelemetryStreamRecordBuilder;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryStreamServiceImpl implements TelemetryStreamService {

    private static final String CMD_XADD = "XADD";
    private static final String CMD_XTRIM = "XTRIM";
    private static final byte[] MAXLEN = bytes("MAXLEN");
    private static final byte[] MINID = bytes("MINID");
    private static final byte[] APPROX = bytes("~");
    private static final byte[] STAR = bytes("*");

    private final RedisTemplate<String, Object> redisTemplate;
    private final TelemetryStreamProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public void append(String deviceId, DataPoint point, ProcessResult processResult) {
        if (!properties.isEnabled() || processResult == null) {
            return;
        }

        long eventTs = Instant.now().toEpochMilli();
        Map<String, String> fields;
        try {
            fields = TelemetryStreamRecordBuilder.build(objectMapper, deviceId, point, processResult, eventTs);
        } catch (JsonProcessingException e) {
            log.error("serialize ProcessResult for stream failed, pointId={}", point != null ? point.getPointId() : null, e);
            return;
        }

        try {
            if (properties.getRetentionMode() == StreamRetentionMode.COUNT) {
                xaddWithCountRetention(fields);
                return;
            }
            xaddPlain(fields);
        } catch (Exception e) {
            log.error("append telemetry to redis stream failed, key={}", properties.getKey(), e);
        }
    }

    @Scheduled(fixedDelayString = "${spring.data.redis.stream.trim-interval-ms:${telemetry.stream.trim-interval-ms:5000}}")
    public void trimByTimeRetention() {
        if (!properties.isEnabled()
                || !properties.isTrimTaskEnabled()
                || properties.getRetentionMode() != StreamRetentionMode.TIME) {
            return;
        }
        if (properties.getMaxSeconds() <= 0) {
            return;
        }

        long minKeepTs = System.currentTimeMillis() - properties.getMaxSeconds() * 1000L;
        String minId = minKeepTs + "-0";

        List<byte[]> args = new ArrayList<>();
        args.add(bytes(properties.getKey()));
        args.add(MINID);
        if (properties.isApproximateTrim()) {
            args.add(APPROX);
        }
        args.add(bytes(minId));

        try {
            redisTemplate.execute((RedisCallback<Object>) connection -> connection.execute(CMD_XTRIM, args.toArray(new byte[0][])));
        } catch (Exception e) {
            log.error("trim telemetry stream by time failed, key={}, minId={}", properties.getKey(), minId, e);
        }
    }

    private void xaddWithCountRetention(Map<String, String> fields) {
        if (properties.getMaxLength() <= 0) {
            xaddPlain(fields);
            return;
        }

        List<byte[]> args = new ArrayList<>();
        args.add(bytes(properties.getKey()));
        args.add(MAXLEN);
        if (properties.isApproximateTrim()) {
            args.add(APPROX);
        }
        args.add(bytes(String.valueOf(properties.getMaxLength())));
        args.add(STAR);
        appendFields(args, fields);

        redisTemplate.execute((RedisCallback<Object>) connection -> connection.execute(CMD_XADD, args.toArray(new byte[0][])));
    }

    private void xaddPlain(Map<String, String> fields) {
        List<byte[]> args = new ArrayList<>();
        args.add(bytes(properties.getKey()));
        args.add(STAR);
        appendFields(args, fields);

        redisTemplate.execute((RedisCallback<Object>) connection -> connection.execute(CMD_XADD, args.toArray(new byte[0][])));
    }

    private static void appendFields(List<byte[]> args, Map<String, String> fields) {
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            args.add(bytes(entry.getKey()));
            args.add(bytes(entry.getValue()));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

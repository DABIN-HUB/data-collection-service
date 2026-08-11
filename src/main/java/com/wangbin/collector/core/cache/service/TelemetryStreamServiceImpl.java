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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Redis Stream 遥测写入服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryStreamServiceImpl implements TelemetryStreamService {

    private static final int METRIC_SAMPLE_LIMIT = 20_000;
    private static final String CMD_XADD = "XADD";
    private static final String CMD_XTRIM = "XTRIM";
    private static final byte[] MAXLEN = bytes("MAXLEN");
    private static final byte[] MINID = bytes("MINID");
    private static final byte[] APPROX = bytes("~");
    private static final byte[] STAR = bytes("*");

    private final RedisTemplate<String, Object> redisTemplate;
    private final TelemetryStreamProperties properties;
    private final ObjectMapper objectMapper;
    private final LongAdder appendAttempts = new LongAdder();
    private final LongAdder skippedAppends = new LongAdder();
    private final LongAdder serializationFailures = new LongAdder();
    private final LongAdder xaddSuccess = new LongAdder();
    private final LongAdder xaddFailure = new LongAdder();
    private final LatencyReservoir appendLatencyNanos = new LatencyReservoir(METRIC_SAMPLE_LIMIT);
    private final LatencyReservoir xaddLatencyNanos = new LatencyReservoir(METRIC_SAMPLE_LIMIT);

    /**
     * 追加单条遥测到 Redis Stream，失败仍隔离在 Stream stage 内。
     */
    @Override
    public void append(String deviceId, DataPoint point, ProcessResult processResult) {
        if (!properties.isEnabled() || processResult == null) {
            skippedAppends.increment();
            return;
        }

        appendAttempts.increment();
        long appendStartedAt = System.nanoTime();
        long eventTs = Instant.now().toEpochMilli();
        Map<String, String> fields;
        try {
            fields = TelemetryStreamRecordBuilder.build(objectMapper, deviceId, point, processResult, eventTs);
        } catch (JsonProcessingException e) {
            serializationFailures.increment();
            recordAppendLatency(System.nanoTime() - appendStartedAt);
            log.error("序列化 ProcessResult 到 Redis Stream 失败，点位={}",
                    point != null ? point.getPointId() : null, e);
            return;
        }

        long xaddStartedAt = System.nanoTime();
        try {
            if (properties.getRetentionMode() == StreamRetentionMode.COUNT) {
                xaddWithCountRetention(fields);
            } else {
                xaddPlain(fields);
            }
            xaddSuccess.increment();
            recordXaddLatency(System.nanoTime() - xaddStartedAt);
        } catch (Exception e) {
            xaddFailure.increment();
            recordXaddLatency(System.nanoTime() - xaddStartedAt);
            log.error("追加遥测到 Redis Stream 失败，键={}", properties.getKey(), e);
        } finally {
            recordAppendLatency(System.nanoTime() - appendStartedAt);
        }
    }

    /**
     * 返回当前 Redis Stream 写入指标。
     */
    @Override
    public TelemetryStreamMetrics metrics() {
        return new TelemetryStreamMetrics(
                appendAttempts.sum(),
                skippedAppends.sum(),
                serializationFailures.sum(),
                xaddSuccess.sum(),
                xaddFailure.sum(),
                percentileMillis(appendLatencyNanos, 0.50D),
                percentileMillis(appendLatencyNanos, 0.95D),
                percentileMillis(appendLatencyNanos, 0.99D),
                percentileMillis(xaddLatencyNanos, 0.50D),
                percentileMillis(xaddLatencyNanos, 0.95D),
                percentileMillis(xaddLatencyNanos, 0.99D));
    }

    /**
     * 按时间保留模式裁剪 Redis Stream。
     */
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
            redisTemplate.execute((RedisCallback<Object>) connection ->
                    connection.execute(CMD_XTRIM, args.toArray(new byte[0][])));
        } catch (Exception e) {
            log.error("按时间裁剪 Redis Stream 失败，键={}，minId={}", properties.getKey(), minId, e);
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

        redisTemplate.execute((RedisCallback<Object>) connection ->
                connection.execute(CMD_XADD, args.toArray(new byte[0][])));
    }

    private void xaddPlain(Map<String, String> fields) {
        List<byte[]> args = new ArrayList<>();
        args.add(bytes(properties.getKey()));
        args.add(STAR);
        appendFields(args, fields);

        redisTemplate.execute((RedisCallback<Object>) connection ->
                connection.execute(CMD_XADD, args.toArray(new byte[0][])));
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

    private void recordAppendLatency(long nanos) {
        recordLatency(appendLatencyNanos, nanos);
    }

    private void recordXaddLatency(long nanos) {
        recordLatency(xaddLatencyNanos, nanos);
    }

    private void recordLatency(LatencyReservoir target, long nanos) {
        target.add(nanos);
    }

    private double percentileMillis(LatencyReservoir values, double percentile) {
        long[] snapshot = values.snapshot();
        if (snapshot.length == 0) {
            return 0D;
        }
        int index = Math.min(snapshot.length - 1, (int) Math.ceil(snapshot.length * percentile) - 1);
        return snapshot[Math.max(0, index)] / 1_000_000.0D;
    }

    private static final class LatencyReservoir {
        private final AtomicLongArray values;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong totalRecorded = new AtomicLong();
        private final LongAdder internalErrors = new LongAdder();

        private LatencyReservoir(int capacity) {
            this.values = new AtomicLongArray(Math.max(1, capacity));
        }

        private void add(long nanos) {
            if (nanos <= 0L) {
                return;
            }
            try {
                long currentSequence = sequence.getAndIncrement();
                values.set((int) Long.remainderUnsigned(currentSequence, values.length()), nanos);
                incrementTotalRecorded();
            } catch (RuntimeException exception) {
                internalErrors.increment();
            }
        }

        private long[] snapshot() {
            long total = totalRecorded.get();
            int limit = (int) Math.min(Math.max(0L, total), values.length());
            long[] snapshot = new long[limit];
            int sampleCount = 0;
            for (int index = 0; index < values.length() && sampleCount < limit; index++) {
                long value = values.get(index);
                if (value > 0L) {
                    snapshot[sampleCount++] = value;
                }
            }
            if (sampleCount != snapshot.length) {
                snapshot = Arrays.copyOf(snapshot, sampleCount);
            }
            Arrays.sort(snapshot);
            return snapshot;
        }

        private void incrementTotalRecorded() {
            while (true) {
                long current = totalRecorded.get();
                if (current == Long.MAX_VALUE) {
                    return;
                }
                if (totalRecorded.compareAndSet(current, current + 1L)) {
                    return;
                }
            }
        }
    }
}

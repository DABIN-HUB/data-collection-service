package com.wangbin.collector.core.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.logging.RateLimitedLogReporter;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Redis Stream 遥测写入服务，正常路径只做有界 admission，Redis I/O 交给 writer 异步批量完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryStreamServiceImpl implements TelemetryStreamService {

    private static final int METRIC_SAMPLE_LIMIT = 20_000;
    private static final String CMD_XTRIM = "XTRIM";
    private static final byte[] MINID = bytes("MINID");
    private static final byte[] APPROX = bytes("~");

    private final RedisTemplate<String, Object> redisTemplate;
    private final TelemetryStreamProperties properties;
    private final ObjectMapper objectMapper;
    private final StreamWriteBuffer streamWriteBuffer;
    private final LongAdder appendAttempts = new LongAdder();
    private final LongAdder skippedAppends = new LongAdder();
    private final LongAdder serializationFailures = new LongAdder();
    private final StreamMetricReservoir appendLatencyNanos = new StreamMetricReservoir(METRIC_SAMPLE_LIMIT);
    private final StreamMetricReservoir admissionLatencyNanos = new StreamMetricReservoir(METRIC_SAMPLE_LIMIT);
    private final RateLimitedLogReporter admissionRejectedLogReporter = new RateLimitedLogReporter(log);

    /**
     * 兼容原有接口，失败通过 Stream metrics 显式表达。
     */
    @Override
    public void append(String deviceId, DataPoint point, ProcessResult processResult) {
        appendBestEffort(deviceId, point, processResult);
    }

    /**
     * 尝试把遥测写入有界 Stream buffer，返回是否成功获得 best-effort admission。
     */
    @Override
    public boolean appendBestEffort(String deviceId, DataPoint point, ProcessResult processResult) {
        if (!properties.isEnabled() || processResult == null) {
            skippedAppends.increment();
            return true;
        }

        appendAttempts.increment();
        long appendStartedAt = System.nanoTime();
        try {
            long eventTs = Instant.now().toEpochMilli();
            Map<String, String> fields = TelemetryStreamRecordBuilder.build(
                    objectMapper, deviceId, point, processResult, eventTs);
            long admissionStartedAt = System.nanoTime();
            StreamWriteBuffer.OfferResult result = streamWriteBuffer.offer(fields);
            admissionLatencyNanos.add(System.nanoTime() - admissionStartedAt);
            if (result != StreamWriteBuffer.OfferResult.ACCEPTED) {
                admissionRejectedLogReporter.warn("stream-admission-" + result,
                        "Redis Stream 写缓冲 admission 失败，结果={}，key={}，deviceId={}，pointId={}",
                        result, properties.getKey(), deviceId, point != null ? point.getPointId() : null);
                return false;
            }
            return true;
        } catch (JsonProcessingException exception) {
            serializationFailures.increment();
            log.error("序列化 ProcessResult 到 Redis Stream 失败，点位={}",
                    point != null ? point.getPointId() : null, exception);
            return false;
        } finally {
            appendLatencyNanos.add(System.nanoTime() - appendStartedAt);
        }
    }

    /**
     * 返回当前 Redis Stream 写入指标。
     */
    @Override
    public TelemetryStreamMetrics metrics() {
        StreamWriteBufferMetrics buffer = streamWriteBuffer.metrics();
        return new TelemetryStreamMetrics(
                appendAttempts.sum(),
                skippedAppends.sum(),
                serializationFailures.sum(),
                buffer.redisXaddRows(),
                buffer.redisXaddFailures(),
                appendLatencyNanos.percentileMillis(0.50D),
                appendLatencyNanos.percentileMillis(0.95D),
                appendLatencyNanos.percentileMillis(0.99D),
                buffer.redisBatchLatencyP50Ms(),
                buffer.redisBatchLatencyP95Ms(),
                buffer.redisBatchLatencyP99Ms(),
                buffer.admissionAccepted(),
                buffer.admissionRejected(),
                buffer.admissionDropped(),
                buffer.bufferSize(),
                buffer.bufferPeak(),
                buffer.bufferCapacity(),
                buffer.writerBatchCount(),
                buffer.writerRows(),
                buffer.writerBatchSizeP50(),
                buffer.writerBatchSizeP95(),
                buffer.writerBatchSizeP99(),
                buffer.redisPipelineCalls(),
                buffer.redisXaddRows(),
                buffer.redisXaddFailures(),
                admissionLatencyNanos.percentileMillis(0.50D),
                admissionLatencyNanos.percentileMillis(0.95D),
                admissionLatencyNanos.percentileMillis(0.99D),
                buffer.redisBatchLatencyP50Ms(),
                buffer.redisBatchLatencyP95Ms(),
                buffer.redisBatchLatencyP99Ms(),
                buffer.shutdownDroppedRows(),
                buffer.writerLoopFailures());
    }

    /**
     * 重置 Stream 观测采样，不清空已经 admission 的业务数据。
     */
    public void resetMetrics() {
        appendAttempts.reset();
        skippedAppends.reset();
        serializationFailures.reset();
        appendLatencyNanos.reset();
        admissionLatencyNanos.reset();
        streamWriteBuffer.resetMetrics();
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
        } catch (Exception exception) {
            log.error("按时间裁剪 Redis Stream 失败，key={}，minId={}", properties.getKey(), minId, exception);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

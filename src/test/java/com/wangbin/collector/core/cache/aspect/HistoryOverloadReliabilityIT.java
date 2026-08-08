package com.wangbin.collector.core.cache.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.cache.config.TelemetryExecutorNames;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.port.HistoryTelemetrySink;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.storage.buffer.HistoryBufferMetrics;
import com.wangbin.collector.storage.buffer.HistoryWriteBuffer;
import com.wangbin.collector.storage.service.TimeSeriesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Redis 与 TDengine 下验证 History stage 过载拒绝不会静默丢历史。
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "telemetry.tdengine.enabled=true",
        "telemetry.tdengine.buffer.enabled=true",
        "telemetry.tdengine.buffer.pending-key=collector:test:history:overload:pending:v1",
        "telemetry.tdengine.buffer.processing-key=collector:test:history:overload:processing:v1",
        "telemetry.tdengine.buffer.dead-letter-key=collector:test:history:overload:dead:v1",
        "telemetry.tdengine.buffer.replay-batch-size=200",
        "collector.report.enabled=false",
        "collector.config.loader=file"
})
class HistoryOverloadReliabilityIT {

    @Autowired
    private HistoryTelemetrySink historyTelemetrySink;

    @Autowired
    private HistoryWriteBuffer historyWriteBuffer;

    @Autowired
    private TimeSeriesService timeSeriesService;

    @Autowired
    private ConfigManager configManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void historyExecutorOverloadShouldDeferRejectedTelemetryAndReplayToTdengine() throws Exception {
        int records = 60;
        String deviceId = "history-overload-it-" + System.currentTimeMillis();
        List<DataPoint> points = points(deviceId, records);
        registerDevice(deviceId, points);
        clearHistoryKeys();
        CountingRejectedExecutionHandler rejected = new CountingRejectedExecutionHandler();
        ThreadPoolExecutor historyExecutor = executor("history-overload-it-", 1, 5, rejected);
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(
                List.of(new HistoryTelemetryPostProcessStage(historyTelemetrySink)),
                historyExecutor,
                historyExecutor,
                historyExecutor,
                historyExecutor);
        long startedAt = System.currentTimeMillis() - 1_000L;
        long submitStartedAt = System.nanoTime();
        List<Long> rejectedFallbackNanos = new ArrayList<>();
        try {
            for (int index = 0; index < records; index++) {
                DataPoint point = points.get(index);
                long rejectedBefore = rejected.count();
                long processStartedAt = System.nanoTime();
                pipeline.process(new TelemetryPostProcessContext(
                        deviceId,
                        point,
                        ProcessResult.success(index, index, "ok"),
                        index,
                        System.currentTimeMillis(),
                        null));
                if (rejected.count() > rejectedBefore) {
                    rejectedFallbackNanos.add(System.nanoTime() - processStartedAt);
                }
            }
            long submitElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submitStartedAt);
            historyExecutor.shutdown();
            assertTrue(historyExecutor.awaitTermination(30, TimeUnit.SECONDS));
            HistoryBufferMetrics afterSubmit = historyWriteBuffer.metrics();
            long rejectedBuffered = afterSubmit.rejectedRedisBuffered() + afterSubmit.rejectedLocalBuffered();
            assertEquals(rejected.count(), rejectedBuffered + afterSubmit.rejectedDropped());
            assertEquals(0L, afterSubmit.rejectedDropped());
            assertTrue(rejected.count() > 0L);

            drainHistoryBuffer();
            long endedAt = System.currentTimeMillis() + 1_000L;
            int written = countWritten(deviceId, points, startedAt, endedAt);
            assertEquals(records, written);
            writeSummary(records, rejected.count(), afterSubmit, submitElapsedMs, written, rejectedFallbackNanos);
        } finally {
            historyExecutor.shutdownNow();
            configManager.deleteLocalDeviceConfig(deviceId);
            clearHistoryKeys();
        }
    }

    private void drainHistoryBuffer() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            historyWriteBuffer.replay();
            HistoryBufferMetrics metrics = historyWriteBuffer.metrics();
            if (metrics.redisPending() == 0L
                    && metrics.redisProcessing() == 0L
                    && metrics.localPending() == 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100L);
        }
        HistoryBufferMetrics metrics = historyWriteBuffer.metrics();
        assertEquals(0L, metrics.redisPending());
        assertEquals(0L, metrics.redisProcessing());
        assertEquals(0, metrics.localPending());
    }

    private int countWritten(String deviceId, List<DataPoint> points, long startedAt, long endedAt) {
        int count = 0;
        for (DataPoint point : points) {
            if (!timeSeriesService.query(deviceId, point.getPointId(), startedAt, endedAt, 10).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private void writeSummary(int records,
                              long rejected,
                              HistoryBufferMetrics afterSubmit,
                              long submitElapsedMs,
                              int written,
                              List<Long> rejectedFallbackNanos) throws Exception {
        Path output = Path.of("target", "soak-results", "history-overload-reliability");
        Files.createDirectories(output);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("timestamp", Instant.now().toString());
        summary.put("records", records);
        summary.put("normalProcessed", records - rejected);
        summary.put("executorRejected", rejected);
        summary.put("rejectedRedisBuffered", afterSubmit.rejectedRedisBuffered());
        summary.put("rejectedLocalBuffered", afterSubmit.rejectedLocalBuffered());
        summary.put("rejectedDropped", afterSubmit.rejectedDropped());
        summary.put("submitElapsedMs", submitElapsedMs);
        summary.put("fallbackLatencyP50Ms", percentileMillis(rejectedFallbackNanos, 0.50D));
        summary.put("fallbackLatencyP95Ms", percentileMillis(rejectedFallbackNanos, 0.95D));
        summary.put("fallbackLatencyP99Ms", percentileMillis(rejectedFallbackNanos, 0.99D));
        summary.put("writtenAfterReplay", written);
        Files.writeString(output.resolve("summary.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                StandardCharsets.UTF_8);
    }

    private double percentileMillis(List<Long> nanos, double percentile) {
        if (nanos == null || nanos.isEmpty()) {
            return 0D;
        }
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(Long::compareTo);
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(index) / 1_000_000.0D;
    }

    private void registerDevice(String deviceId, List<DataPoint> points) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(deviceId);
        device.setProtocolType("MODBUS_TCP");
        device.setConnectionType("MODBUS_TCP");
        device.setIpAddress("127.0.0.1");
        device.setPort(1502);
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(1502);
        configManager.saveLocalDeviceConfig(device, connection, points, true);
    }

    private List<DataPoint> points(String deviceId, int count) {
        List<DataPoint> points = new ArrayList<>(count);
        int base = Math.abs(deviceId.hashCode());
        for (int index = 0; index < count; index++) {
            String pointCode = "p_" + String.format(Locale.ROOT, "%04d", index);
            DataPoint point = new DataPoint();
            point.setId((long) base + index + 1L);
            point.setPointId(deviceId + "-" + pointCode);
            point.setPointCode(pointCode);
            point.setPointName(pointCode);
            point.setDeviceId(deviceId);
            point.setDeviceName(deviceId);
            point.setAddress("4" + String.format(Locale.ROOT, "%04d", index + 1));
            point.setDataType("DOUBLE");
            point.setStatus(1);
            point.setAdditionalConfig(Map.of("historyEnabled", true));
            points.add(point);
        }
        return points;
    }

    private void clearHistoryKeys() {
        redisTemplate.delete(List.of(
                "collector:test:history:overload:pending:v1",
                "collector:test:history:overload:processing:v1",
                "collector:test:history:overload:dead:v1"));
    }

    private ThreadPoolExecutor executor(String namePrefix,
                                        int threads,
                                        int queueCapacity,
                                        RejectedExecutionHandler rejectedExecutionHandler) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    thread.setName(namePrefix + thread.getId());
                    return thread;
                },
                rejectedExecutionHandler);
    }

    private static final class CountingRejectedExecutionHandler implements RejectedExecutionHandler {
        private final AtomicLong count = new AtomicLong();

        @Override
        public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
            count.incrementAndGet();
            throw new java.util.concurrent.RejectedExecutionException(
                    TelemetryExecutorNames.HISTORY_STAGE + " full");
        }

        private long count() {
            return count.get();
        }
    }
}

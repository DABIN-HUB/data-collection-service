package com.wangbin.collector.soak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.storage.buffer.HistoryBatchMetrics;
import com.wangbin.collector.storage.buffer.HistoryBatchProperties;
import com.wangbin.collector.storage.buffer.HistoryBatchWriter;
import com.wangbin.collector.storage.buffer.HistoryBufferMetrics;
import com.wangbin.collector.storage.buffer.HistoryBufferProperties;
import com.wangbin.collector.storage.buffer.HistoryWriteBuffer;
import com.wangbin.collector.storage.buffer.HistoryWriteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史正常写入吞吐专项入口；显式指定 IT 时运行，不进入默认 surefire 通配。
 */
@SpringBootTest(properties = {
        "telemetry.tdengine.enabled=true",
        "telemetry.tdengine.batch.enabled=true",
        "collector.report.enabled=false",
        "collector.report.mqtt.enabled=false"
})
@ActiveProfiles("test")
class HistoryLiveWriteThroughputIT {

    private static final DateTimeFormatter RUN_ID_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());

    @Autowired
    private HistoryBatchWriter historyBatchWriter;

    @Autowired
    private HistoryWriteBuffer historyWriteBuffer;

    @Autowired
    private HistoryBufferProperties historyBufferProperties;

    @Autowired
    private HistoryBatchProperties historyBatchProperties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${history.live.rows-per-second:2000}")
    private int rowsPerSecond;

    @Value("${history.live.devices:10}")
    private int devices;

    @Value("${history.live.duration-seconds:120}")
    private long durationSeconds;

    @Value("${history.live.output:target/soak-results/history-live-write}")
    private String outputDirectory;

    @Test
    void historyLiveWriteShouldSustainConfiguredRate() throws Exception {
        String runId = "history-live-" + RUN_ID_FORMATTER.format(Instant.now());
        RedisKeys previous = overrideRedisKeys(runId);
        try {
            deleteRunKeys();
            HistoryBatchMetrics batchStart = historyBatchWriter.metrics();
            HistoryBufferMetrics bufferStart = historyWriteBuffer.metrics();
            long startedAt = System.nanoTime();
            long submitted = submitRows(runId, startedAt);
            waitForFlushDrain(TimeUnit.SECONDS.toNanos(120));
            long elapsedNanos = Math.max(1L, System.nanoTime() - startedAt);
            HistoryBatchMetrics batchEnd = historyBatchWriter.metrics();
            HistoryBufferMetrics bufferEnd = historyWriteBuffer.metrics();
            Summary summary = buildSummary(runId, submitted, elapsedNanos, batchStart, batchEnd, bufferStart, bufferEnd);
            writeSummary(summary);
            assertEquals(0, batchEnd.currentBufferedRows(), "history live benchmark buffered rows");
            assertEquals(0, batchEnd.inFlightFlushes(), "history live benchmark in-flight flushes");
        } finally {
            deleteRunKeys();
            restoreRedisKeys(previous);
        }
    }

    private long submitRows(String runId, long startedAtNanos) {
        long durationNanos = TimeUnit.SECONDS.toNanos(durationSeconds);
        long spacingNanos = Math.max(1L, 1_000_000_000L / Math.max(1, rowsPerSecond));
        long submitted = 0L;
        while (System.nanoTime() - startedAtNanos < durationNanos) {
            long eventTs = System.currentTimeMillis();
            int deviceIndex = (int) (submitted % Math.max(1, devices));
            int pointIndex = (int) (submitted / Math.max(1, devices));
            historyBatchWriter.accept(request(runId, deviceIndex, pointIndex, eventTs));
            submitted++;
            long target = startedAtNanos + submitted * spacingNanos;
            while (System.nanoTime() < target) {
                Thread.onSpinWait();
            }
        }
        return submitted;
    }

    private void waitForFlushDrain(long timeoutNanos) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutNanos;
        while (System.nanoTime() < deadline) {
            HistoryBatchMetrics metrics = historyBatchWriter.metrics();
            HistoryBufferMetrics buffer = historyWriteBuffer.metrics();
            if (metrics.currentBufferedRows() == 0
                    && metrics.inFlightFlushes() == 0
                    && metrics.flushExecutorQueueCurrent() == 0
                    && buffer.redisPending() == 0
                    && buffer.redisProcessing() == 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
    }

    private HistoryWriteRequest request(String runId, int deviceIndex, int pointIndex, long eventTs) {
        String deviceId = runId + "-dev-" + String.format("%04d", deviceIndex);
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(deviceId + "-p-" + String.format("%06d", pointIndex));
        point.setPointCode(point.getPointId());
        point.setPointName("历史写入压测点-" + pointIndex);
        point.setDataType(dataType(pointIndex));
        point.setStatus(1);
        return new HistoryWriteRequest(deviceId, "MODBUS_TCP", point,
                ProcessResult.success(value(pointIndex), value(pointIndex), "history live write benchmark"), eventTs);
    }

    private String dataType(int index) {
        return switch (Math.floorMod(index, 4)) {
            case 0 -> "LONG";
            case 1 -> "DOUBLE";
            case 2 -> "BOOLEAN";
            default -> "STRING";
        };
    }

    private Object value(int index) {
        return switch (Math.floorMod(index, 4)) {
            case 0 -> index;
            case 1 -> index + 0.125D;
            case 2 -> index % 2 == 0;
            default -> "value-" + index;
        };
    }

    private Summary buildSummary(String runId,
                                 long submitted,
                                 long elapsedNanos,
                                 HistoryBatchMetrics batchStart,
                                 HistoryBatchMetrics batchEnd,
                                 HistoryBufferMetrics bufferStart,
                                 HistoryBufferMetrics bufferEnd) {
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0D;
        long flushedRows = batchEnd.flushedRows() - batchStart.flushedRows();
        long flushedBatches = batchEnd.flushedBatches() - batchStart.flushedBatches();
        long rejectedBatches = batchEnd.flushExecutorRejectedBatches() - batchStart.flushExecutorRejectedBatches();
        long fallbackRows = batchEnd.fallbackRows() - batchStart.fallbackRows();
        long pendingRows = bufferEnd.redisPending();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("batchSize", historyBatchProperties.getBatchSize());
        config.put("flushIntervalMs", historyBatchProperties.getFlushIntervalMs());
        config.put("flushScanIntervalMs", historyBatchProperties.getFlushScanIntervalMs());
        config.put("flushThreads", historyBatchProperties.getFlushExecutor().getCoreSize());
        config.put("flushQueue", historyBatchProperties.getFlushExecutor().getQueueCapacity());
        return new Summary(
                runId, submitted, rowsPerSecond, devices, durationSeconds,
                submitted / elapsedSeconds, flushedRows / elapsedSeconds,
                flushedBatches / elapsedSeconds, flushedRows, flushedBatches,
                rejectedBatches, fallbackRows, pendingRows,
                batchEnd.flushExecutorQueuePeak(), batchEnd.flushExecutorActivePeak(),
                batchEnd.averageBatchSize(), batchEnd.batchSizeP50(), batchEnd.batchSizeP95(),
                batchEnd.batchSizeMax(), batchEnd.sizeFlushBatches() - batchStart.sizeFlushBatches(),
                batchEnd.timerFlushBatches() - batchStart.timerFlushBatches(),
                batchEnd.sizeFlushRows() - batchStart.sizeFlushRows(),
                batchEnd.timerFlushRows() - batchStart.timerFlushRows(),
                batchEnd.sizeAverageBatchSize(), batchEnd.timerAverageBatchSize(),
                batchEnd.flushLatencyP50Ms(), batchEnd.flushLatencyP95Ms(), batchEnd.flushLatencyP99Ms(),
                bufferEnd.replaySuccessfulRows() - bufferStart.replaySuccessfulRows(),
                bufferEnd.replayPausedForLivePressureCount() - bufferStart.replayPausedForLivePressureCount(),
                config);
    }

    private void writeSummary(Summary summary) throws Exception {
        Path directory = Path.of(outputDirectory + "-" + RUN_ID_FORMATTER.format(Instant.now()));
        Files.createDirectories(directory);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("summary.json").toFile(), summary);
    }

    private RedisKeys overrideRedisKeys(String runId) {
        RedisKeys previous = new RedisKeys(
                historyBufferProperties.getPendingKey(),
                historyBufferProperties.getProcessingKey(),
                historyBufferProperties.getDeadLetterKey());
        historyBufferProperties.setPendingKey("collector:soak:" + runId + ":history:pending:v1");
        historyBufferProperties.setProcessingKey("collector:soak:" + runId + ":history:processing:v1");
        historyBufferProperties.setDeadLetterKey("collector:soak:" + runId + ":history:dead:v1");
        return previous;
    }

    private void restoreRedisKeys(RedisKeys previous) {
        historyBufferProperties.setPendingKey(previous.pendingKey());
        historyBufferProperties.setProcessingKey(previous.processingKey());
        historyBufferProperties.setDeadLetterKey(previous.deadLetterKey());
    }

    private void deleteRunKeys() {
        List<String> keys = new ArrayList<>();
        keys.add(historyBufferProperties.getPendingKey());
        keys.add(historyBufferProperties.getProcessingKey());
        keys.add(historyBufferProperties.getDeadLetterKey());
        redisTemplate.delete(keys);
    }

    private record RedisKeys(String pendingKey, String processingKey, String deadLetterKey) {
    }

    private record Summary(String runId,
                           long submittedRows,
                           int targetRowsPerSecond,
                           int devices,
                           long durationSeconds,
                           double submittedRowsPerSecond,
                           double tdengineRowsPerSecond,
                           double batchCallsPerSecond,
                           long flushedRows,
                           long flushedBatches,
                           long flushRejectedBatches,
                           long fallbackRows,
                           long pendingRows,
                           int flushQueuePeak,
                           int flushActivePeak,
                           double averageBatchSize,
                           int batchSizeP50,
                           int batchSizeP95,
                           int batchSizeMax,
                           long sizeFlushBatches,
                           long timerFlushBatches,
                           long sizeFlushRows,
                           long timerFlushRows,
                           double sizeAverageBatchSize,
                           double timerAverageBatchSize,
                           double batchWriteP50Ms,
                           double batchWriteP95Ms,
                           double batchWriteP99Ms,
                           long replaySuccessfulRows,
                           long replayPausedForLivePressureCount,
                           Map<String, Object> config) {
    }
}

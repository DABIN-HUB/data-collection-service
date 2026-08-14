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
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.service.TimeSeriesService;
import com.wangbin.collector.storage.service.TdengineWriteMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史正常写入吞吐专项入口；显式指定 IT 时运行，不进入默认 surefire 通配。
 */
@SpringBootTest(properties = {
        "telemetry.tdengine.enabled=true",
        "telemetry.tdengine.batch.enabled=true",
        "collector.report.enabled=false",
        "collector.report.mqtt.enabled=false",
        "collector.config.loader=file"
})
@ActiveProfiles("test")
class HistoryLiveWriteThroughputIT {

    private static final DateTimeFormatter RUN_ID_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());
    private static final String RUN_ID = "history-live-" + RUN_ID_FORMATTER.format(Instant.now());
    private static final String REDIS_NAMESPACE = "collector:soak:" + RUN_ID + ":history";

    @DynamicPropertySource
    static void historyLiveWriteProperties(DynamicPropertyRegistry registry) {
        registry.add("telemetry.tdengine.buffer.pending-key", () -> REDIS_NAMESPACE + ":pending:v1");
        registry.add("telemetry.tdengine.buffer.processing-key", () -> REDIS_NAMESPACE + ":processing:v1");
        registry.add("telemetry.tdengine.buffer.dead-letter-key", () -> REDIS_NAMESPACE + ":dead:v1");
    }

    @Autowired
    private HistoryBatchWriter historyBatchWriter;

    @Autowired
    private HistoryWriteBuffer historyWriteBuffer;

    @Autowired
    private HistoryBufferProperties historyBufferProperties;

    @Autowired
    private HistoryBatchProperties historyBatchProperties;

    @Autowired
    private TdengineProperties tdengineProperties;

    @Autowired
    private TimeSeriesService timeSeriesService;

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

    @Value("${history.live.traffic-mode:uniform}")
    private String trafficMode;

    @Value("${history.live.cluster-rows:500}")
    private int clusterRows;

    @Value("${history.live.burst-window-seconds:5}")
    private int burstWindowSeconds;

    @Value("${history.live.burst-active-seconds:2}")
    private int burstActiveSeconds;

    @Value("${history.live.output:target/soak-results/history-live-write}")
    private String outputDirectory;

    @Test
    void historyLiveWriteShouldSustainConfiguredRate() throws Exception {
        try {
            deleteRunKeys();
            HistoryBatchMetrics batchStart = historyBatchWriter.metrics();
            HistoryBufferMetrics bufferStart = historyWriteBuffer.metrics();
            timeSeriesService.resetWriteMetrics();
            TdengineWriteMetrics writeStart = timeSeriesService.writeMetrics();
            long startedAt = System.nanoTime();
            long submitted = submitRows(RUN_ID, startedAt);
            waitForFlushDrain(TimeUnit.SECONDS.toNanos(120));
            long elapsedNanos = Math.max(1L, System.nanoTime() - startedAt);
            HistoryBatchMetrics batchEnd = historyBatchWriter.metrics();
            HistoryBufferMetrics bufferEnd = historyWriteBuffer.metrics();
            TdengineWriteMetrics writeEnd = timeSeriesService.writeMetrics();
            Summary summary = buildSummary(RUN_ID, submitted, elapsedNanos, batchStart, batchEnd,
                    bufferStart, bufferEnd, writeStart, writeEnd);
            writeSummary(summary);
            assertEquals(0, batchEnd.currentBufferedRows(), "history live benchmark buffered rows");
            assertEquals(0, batchEnd.inFlightFlushes(), "history live benchmark in-flight flushes");
        } finally {
            deleteRunKeys();
        }
    }

    private long submitRows(String runId, long startedAtNanos) {
        if ("clustered".equalsIgnoreCase(trafficMode) || "device-clustered-burst".equalsIgnoreCase(trafficMode)) {
            return submitClusteredBurstRows(runId, startedAtNanos);
        }
        return submitUniformRows(runId, startedAtNanos);
    }

    private long submitUniformRows(String runId, long startedAtNanos) {
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

    private long submitClusteredBurstRows(String runId, long startedAtNanos) {
        long durationNanos = TimeUnit.SECONDS.toNanos(durationSeconds);
        long windowNanos = TimeUnit.SECONDS.toNanos(Math.max(1, burstWindowSeconds));
        long activeNanos = TimeUnit.SECONDS.toNanos(Math.max(1, Math.min(burstActiveSeconds, burstWindowSeconds)));
        long rowsPerWindow = Math.max(1L, (long) rowsPerSecond * Math.max(1, burstWindowSeconds));
        long spacingNanos = Math.max(1L, activeNanos / rowsPerWindow);
        long submitted = 0L;
        long windowIndex = 0L;
        while (System.nanoTime() - startedAtNanos < durationNanos) {
            long windowStart = startedAtNanos + windowIndex * windowNanos;
            long activeEnd = Math.min(startedAtNanos + durationNanos, windowStart + activeNanos);
            long rowsInWindow = 0L;
            while (rowsInWindow < rowsPerWindow && System.nanoTime() < activeEnd) {
                long eventTs = System.currentTimeMillis();
                int deviceIndex = (int) Math.floorMod(submitted / Math.max(1, clusterRows), Math.max(1, devices));
                int pointIndex = (int) submitted;
                historyBatchWriter.accept(request(runId, deviceIndex, pointIndex, eventTs));
                submitted++;
                rowsInWindow++;
                waitUntil(windowStart + rowsInWindow * spacingNanos);
            }
            windowIndex++;
            waitUntil(Math.min(startedAtNanos + durationNanos, windowStart + windowNanos));
        }
        return submitted;
    }

    private void waitUntil(long targetNanos) {
        while (true) {
            long remaining = targetNanos - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            if (remaining > TimeUnit.MILLISECONDS.toNanos(1)) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            } else {
                Thread.onSpinWait();
            }
        }
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
                                 HistoryBufferMetrics bufferEnd,
                                 TdengineWriteMetrics writeStart,
                                 TdengineWriteMetrics writeEnd) {
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0D;
        long flushedRows = batchEnd.flushedRows() - batchStart.flushedRows();
        long flushedBatches = batchEnd.flushedBatches() - batchStart.flushedBatches();
        long rejectedBatches = batchEnd.flushExecutorRejectedBatches() - batchStart.flushExecutorRejectedBatches();
        long fallbackRows = batchEnd.fallbackRows() - batchStart.fallbackRows();
        long pendingRows = bufferEnd.redisPending();
        long writeRequests = writeEnd.writeRequests() - writeStart.writeRequests();
        long writeRows = writeEnd.writtenRows() - writeStart.writtenRows();
        long singleRequests = writeEnd.singleTableWriteRequests() - writeStart.singleTableWriteRequests();
        long multiRequests = writeEnd.multiTableWriteRequests() - writeStart.multiTableWriteRequests();
        long writeFailures = writeEnd.writeFailures() - writeStart.writeFailures();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("batchSize", historyBatchProperties.getBatchSize());
        config.put("flushIntervalMs", historyBatchProperties.getFlushIntervalMs());
        config.put("flushScanIntervalMs", historyBatchProperties.getFlushScanIntervalMs());
        config.put("flushThreads", historyBatchProperties.getFlushExecutor().getCoreSize());
        config.put("flushQueue", historyBatchProperties.getFlushExecutor().getQueueCapacity());
        config.put("multiTableEnabled", tdengineProperties.getWrite().isMultiTableEnabled());
        config.put("tdengineWriteMode", tdengineProperties.getWrite().getMode());
        config.put("maxTablesPerRequest", tdengineProperties.getWrite().getMaxTablesPerRequest());
        config.put("maxRowsPerRequest", tdengineProperties.getWrite().getMaxRowsPerRequest());
        config.put("aggregationWaitMs", tdengineProperties.getWrite().getAggregationWaitMs());
        return new Summary(
                runId, submitted, rowsPerSecond, devices, durationSeconds,
                trafficMode, clusterRows, burstWindowSeconds, burstActiveSeconds,
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
                writeRequests, writeRows, singleRequests, multiRequests, writeFailures,
                measurementRate(writeStart.writeRequests(), writeEnd.writeRequests(), elapsedSeconds),
                measurementRate(writeStart.writtenRows(), writeEnd.writtenRows(), elapsedSeconds),
                writeRequests <= 0L ? 0D : writeRows / (double) writeRequests,
                batchEnd.tdengineRowsPerRequestP95(), batchEnd.tdengineRowsPerRequestMax(),
                batchEnd.tdengineTablesPerRequest(), batchEnd.tdengineTablesPerRequestP95(),
                batchEnd.tdengineTablesPerRequestMax(), writeEnd.writeLatencyP50Ms(),
                writeEnd.writeLatencyP95Ms(), writeEnd.writeLatencyP99Ms(),
                writeEnd.connectionAcquireP50Ms(), writeEnd.connectionAcquireP95Ms(),
                writeEnd.connectionAcquireP99Ms(), writeEnd.sqlBuildP50Ms(), writeEnd.sqlBuildP95Ms(),
                writeEnd.sqlBuildP99Ms(), writeEnd.dbExecuteP50Ms(), writeEnd.dbExecuteP95Ms(),
                writeEnd.dbExecuteP99Ms(), writeEnd.totalWriteP50Ms(), writeEnd.totalWriteP95Ms(),
                writeEnd.totalWriteP99Ms(), writeEnd.sampleCount(), writeEnd.totalRecordedSamples(),
                writeEnd.overwrittenSamples(),
                batchEnd.dbQueueWaitP50Ms(), batchEnd.dbQueueWaitP95Ms(), batchEnd.dbQueueWaitP99Ms(),
                batchEnd.dbExecuteLatencyP50Ms(), batchEnd.dbExecuteLatencyP95Ms(),
                batchEnd.dbExecuteLatencyP99Ms(), batchEnd.maxConcurrentWritesSameSubTable(),
                batchEnd.sameSubTableConcurrentWriteCount(), batchEnd.subTableWriteLatencyP95Ms(),
                batchEnd.logicalPendingBatches(), batchEnd.logicalPendingBatchesPeak(),
                batchEnd.logicalPendingRows(), batchEnd.logicalPendingRowsPeak(),
                batchEnd.actualExecutorQueueSize(), batchEnd.actualExecutorQueuePeak(),
                batchEnd.actualExecutorActiveCount(), batchEnd.actualExecutorActivePeak(),
                batchEnd.subTableQueueRows(), batchEnd.oldestPendingAgeMs(),
                batchEnd.mergeRowsPerRequest(), batchEnd.mergeRowsPerRequestP95(),
                batchEnd.mergeRowsPerRequestMax(), batchEnd.mergeBatchesPerRequest(),
                batchEnd.mergeBatchesPerRequestP95(), batchEnd.mergeBatchesPerRequestMax(),
                writeEnd.ensureSubTableCalls() - writeStart.ensureSubTableCalls(),
                writeEnd.ensureSubTableCacheHits() - writeStart.ensureSubTableCacheHits(),
                writeEnd.ensureSubTableCacheMisses() - writeStart.ensureSubTableCacheMisses(),
                config);
    }

    private void writeSummary(Summary summary) throws Exception {
        Path directory = Path.of(outputDirectory + "-" + RUN_ID_FORMATTER.format(Instant.now()));
        Files.createDirectories(directory);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("summary.json").toFile(), summary);
    }

    private void deleteRunKeys() {
        List<String> keys = new ArrayList<>();
        keys.add(historyBufferProperties.getPendingKey());
        keys.add(historyBufferProperties.getProcessingKey());
        keys.add(historyBufferProperties.getDeadLetterKey());
        redisTemplate.delete(keys);
    }

    private record Summary(String runId,
                           long submittedRows,
                           int targetRowsPerSecond,
                           int devices,
                           long durationSeconds,
                           String trafficMode,
                           int clusterRows,
                           int burstWindowSeconds,
                           int burstActiveSeconds,
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
                           long tdengineWriteRequests,
                           long tdengineWriteRows,
                           long tdengineSingleTableRequests,
                           long tdengineMultiTableRequests,
                           long tdengineWriteFailures,
                           double tdengineWriteRequestsPerSecond,
                           double tdengineWriteRowsPerSecond,
                           double tdengineRowsPerRequest,
                           int tdengineRowsPerRequestP95,
                           int tdengineRowsPerRequestMax,
                           double tdengineTablesPerRequest,
                           int tdengineTablesPerRequestP95,
                           int tdengineTablesPerRequestMax,
                           double tdengineWriteP50Ms,
                           double tdengineWriteP95Ms,
                           double tdengineWriteP99Ms,
                           double connectionAcquireP50Ms,
                           double connectionAcquireP95Ms,
                           double connectionAcquireP99Ms,
                           double sqlBuildP50Ms,
                           double sqlBuildP95Ms,
                           double sqlBuildP99Ms,
                           double dbExecuteP50Ms,
                           double dbExecuteP95Ms,
                           double dbExecuteP99Ms,
                           double totalWriteP50Ms,
                           double totalWriteP95Ms,
                           double totalWriteP99Ms,
                           int latencySampleCount,
                           long latencyTotalRecorded,
                           long latencyOverwrittenSamples,
                           double dbQueueWaitP50Ms,
                           double dbQueueWaitP95Ms,
                           double dbQueueWaitP99Ms,
                           double dbExecuteLatencyP50Ms,
                           double dbExecuteLatencyP95Ms,
                           double dbExecuteLatencyP99Ms,
                           int maxConcurrentWritesSameSubTable,
                           long sameSubTableConcurrentWriteCount,
                           Map<String, Double> subTableWriteLatencyP95Ms,
                           int logicalPendingBatches,
                           int logicalPendingBatchesPeak,
                           int logicalPendingRows,
                           int logicalPendingRowsPeak,
                           int actualExecutorQueueSize,
                           int actualExecutorQueuePeak,
                           int actualExecutorActiveCount,
                           int actualExecutorActivePeak,
                           Map<String, Integer> subTableQueueRows,
                           long oldestPendingAgeMs,
                           double mergeRowsPerRequest,
                           int mergeRowsPerRequestP95,
                           int mergeRowsPerRequestMax,
                           double mergeBatchesPerRequest,
                           int mergeBatchesPerRequestP95,
                           int mergeBatchesPerRequestMax,
                           long ensureSubTableCalls,
                           long ensureSubTableCacheHits,
                           long ensureSubTableCacheMisses,
                           Map<String, Object> config) {
    }

    static double measurementRate(long startCount, long endCount, double elapsedSeconds) {
        if (elapsedSeconds <= 0D) {
            return 0D;
        }
        return Math.max(0L, endCount - startCount) / elapsedSeconds;
    }
}

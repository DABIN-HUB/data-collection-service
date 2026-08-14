package com.wangbin.collector.soak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.storage.buffer.HistoryBatchMetrics;
import com.wangbin.collector.storage.buffer.HistoryBufferMetrics;
import com.wangbin.collector.storage.buffer.HistoryBufferProperties;
import com.wangbin.collector.storage.buffer.HistoryBatchWriteResult;
import com.wangbin.collector.storage.buffer.HistoryWriteBuffer;
import com.wangbin.collector.storage.buffer.HistoryWriteRequest;
import com.wangbin.collector.storage.service.TimeSeriesService;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史 Redis pending 恢复吞吐专项入口；默认不进入 surefire 通配测试，只在显式指定 IT 时运行。
 */
@SpringBootTest(classes = HistoryRecoveryThroughputIT.TestApplication.class, properties = {
        "spring.main.web-application-type=none",
        "telemetry.tdengine.enabled=true",
        "telemetry.tdengine.buffer.enabled=true",
        "collector.report.enabled=false",
        "collector.config.loader=file"
})
class HistoryRecoveryThroughputIT {

    @Configuration
    @EnableAutoConfiguration
    @MapperScan("com.wangbin.collector.storage.repository")
    @ComponentScan(basePackages = "com.wangbin.collector",
            excludeFilters = {
                    @ComponentScan.Filter(
                            type = FilterType.ASSIGNABLE_TYPE,
                            classes = com.wangbin.collector.Application.class),
                    @ComponentScan.Filter(
                            type = FilterType.REGEX,
                            pattern = "com\\.wangbin\\.collector\\..*Test.*")
            })
    static class TestApplication {
    }

    private static final DateTimeFormatter RUN_ID_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());

    @Autowired
    private HistoryWriteBuffer historyWriteBuffer;

    @Autowired
    private HistoryBufferProperties historyBufferProperties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TimeSeriesService timeSeriesService;

    @Autowired
    private ObjectProvider<com.wangbin.collector.storage.buffer.HistoryBatchWriter> historyBatchWriterProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Environment environment;

    @Test
    void benchmarkTwentyThousandPendingDrainWithBatchReplay() throws Exception {
        Options options = Options.from(environment);
        Files.createDirectories(options.outputDir());
        RedisKeys previous = switchRedisNamespace(options.runId());
        try {
            deleteCurrentNamespace();
            warmTdengineSubTables(options);
            prefillPending(options);
            assertEquals(options.records(), pendingSize(), "history pending preload count");

            HistoryBufferMetrics before = historyWriteBuffer.metrics();
            long startedAt = System.nanoTime();
            waitUntilDrained(options.timeoutSeconds());
            long elapsedNanos = System.nanoTime() - startedAt;
            HistoryBufferMetrics after = historyWriteBuffer.metrics();
            HistoryBatchMetrics batchMetrics = historyBatchWriterProvider.getIfAvailable() != null
                    ? historyBatchWriterProvider.getIfAvailable().metrics() : null;

            assertEquals(0L, after.redisPending(), "history pending should drain");
            assertEquals(0L, after.redisProcessing(), "history processing should drain");
            double rowsPerSecond = options.records() * 1_000_000_000.0D / Math.max(1L, elapsedNanos);
            writeSummary(options, before, after, batchMetrics, elapsedNanos, rowsPerSecond);
            assertTrue(rowsPerSecond >= options.minRowsPerSecond(),
                    "history replay rows/s below expectation: " + rowsPerSecond);
        } finally {
            deleteCurrentNamespace();
            restoreRedisNamespace(previous);
        }
    }

    private void warmTdengineSubTables(Options options) {
        List<TimeSeriesService.AppendRequest> requests = new ArrayList<>(options.devices());
        long eventTs = System.currentTimeMillis() - 60_000L;
        for (int index = 0; index < options.devices(); index++) {
            String deviceId = deviceId(options, index);
            requests.add(new TimeSeriesService.AppendRequest(
                    deviceId, "MODBUS_TCP", point(deviceId, "warm-" + index, index),
                    ProcessResult.success(index, index, "history recovery warmup"),
                    eventTs + index));
        }
        timeSeriesService.appendBatch(requests);
    }

    private void prefillPending(Options options) {
        int chunkSize = Math.max(1, historyBufferProperties.getReplayBatchSize());
        List<HistoryWriteRequest> chunk = new ArrayList<>(chunkSize);
        long baseEventTs = System.currentTimeMillis();
        for (int index = 0; index < options.records(); index++) {
            String deviceId = deviceId(options, index % options.devices());
            DataPoint point = point(deviceId, "p-" + index, index);
            ProcessResult result = ProcessResult.success(index, index, "history recovery pending");
            chunk.add(new HistoryWriteRequest(deviceId, "MODBUS_TCP", point, result, baseEventTs + index));
            if (chunk.size() >= chunkSize) {
                deferChunk(chunk);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            deferChunk(chunk);
        }
    }

    private void deferChunk(List<HistoryWriteRequest> chunk) {
        HistoryBatchWriteResult result = historyWriteBuffer.deferBatchForRetry(
                List.copyOf(chunk), new RejectedExecutionException("history recovery benchmark preload"));
        assertEquals(chunk.size(), result.redisBufferedRows(), "preload should enter Redis pending");
    }

    private void waitUntilDrained(long timeoutSeconds) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadlineNanos) {
            historyWriteBuffer.replay();
            if (pendingSize() == 0L && processingSize() == 0L) {
                return;
            }
            Thread.sleep(Math.max(1L, Math.min(100L, historyBufferProperties.getReplayIntervalMs())));
        }
    }

    private long pendingSize() {
        Long size = redisTemplate.opsForList().size(historyBufferProperties.getPendingKey());
        return size == null ? 0L : size;
    }

    private long processingSize() {
        Long size = redisTemplate.opsForList().size(historyBufferProperties.getProcessingKey());
        return size == null ? 0L : size;
    }

    private DataPoint point(String deviceId, String pointId, int index) {
        DataPoint point = new DataPoint();
        point.setId((long) index + 1L);
        point.setDeviceId(deviceId);
        point.setDeviceName(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setPointName("history-recovery-" + pointId);
        point.setAddress("4" + String.format(Locale.ROOT, "%04d", (index % 9999) + 1));
        point.setDataType(switch (index % 4) {
            case 0 -> "LONG";
            case 1 -> "DOUBLE";
            case 2 -> "BOOLEAN";
            default -> "STRING";
        });
        point.setReadWrite("R");
        point.setStatus(1);
        point.setAdditionalConfig(Map.of("historyEnabled", true));
        return point;
    }

    private String deviceId(Options options, int index) {
        return options.devicePrefix() + "-dev-" + index;
    }

    private RedisKeys switchRedisNamespace(String runId) {
        RedisKeys previous = new RedisKeys(
                historyBufferProperties.getPendingKey(),
                historyBufferProperties.getProcessingKey(),
                historyBufferProperties.getDeadLetterKey());
        historyBufferProperties.setPendingKey("collector:soak:" + runId + ":history:pending:v1");
        historyBufferProperties.setProcessingKey("collector:soak:" + runId + ":history:processing:v1");
        historyBufferProperties.setDeadLetterKey("collector:soak:" + runId + ":history:dead:v1");
        return previous;
    }

    private void restoreRedisNamespace(RedisKeys previous) {
        historyBufferProperties.setPendingKey(previous.pendingKey());
        historyBufferProperties.setProcessingKey(previous.processingKey());
        historyBufferProperties.setDeadLetterKey(previous.deadLetterKey());
    }

    private void deleteCurrentNamespace() {
        redisTemplate.delete(List.of(
                historyBufferProperties.getPendingKey(),
                historyBufferProperties.getProcessingKey(),
                historyBufferProperties.getDeadLetterKey()));
    }

    private void writeSummary(Options options,
                              HistoryBufferMetrics before,
                              HistoryBufferMetrics after,
                              HistoryBatchMetrics batchMetrics,
                              long elapsedNanos,
                              double rowsPerSecond) throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", options.runId());
        summary.put("records", options.records());
        summary.put("devices", options.devices());
        summary.put("replayBatchSize", historyBufferProperties.getReplayBatchSize());
        summary.put("replayMaxBatchesPerCycle", historyBufferProperties.getReplayMaxBatchesPerCycle());
        summary.put("replayIntervalMs", historyBufferProperties.getReplayIntervalMs());
        summary.put("oldReplayTheoreticalRowsPerSecond", 200D / 3D);
        summary.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        summary.put("rowsPerSecond", rowsPerSecond);
        summary.put("replayClaimedRowsDelta", after.replayClaimedRows() - before.replayClaimedRows());
        summary.put("replaySuccessfulRowsDelta", after.replaySuccessfulRows() - before.replaySuccessfulRows());
        summary.put("replayFailedRowsDelta", after.replayFailedRows() - before.replayFailedRows());
        summary.put("replayBatchCountDelta", after.replayBatchCount() - before.replayBatchCount());
        summary.put("replayBatchSizeP95", after.replayBatchSizeP95());
        summary.put("replayBatchSizeMax", after.replayBatchSizeMax());
        summary.put("replayBatchWriteP50Ms", after.replayBatchWriteP50Ms());
        summary.put("replayBatchWriteP95Ms", after.replayBatchWriteP95Ms());
        summary.put("replayBatchWriteP99Ms", after.replayBatchWriteP99Ms());
        summary.put("redisPendingFinal", after.redisPending());
        summary.put("redisProcessingFinal", after.redisProcessing());
        summary.put("redisDeadLetterFinal", after.redisDeadLetter());
        summary.put("historyFlushQueueCurrent", batchMetrics == null ? -1 : batchMetrics.flushExecutorQueueCurrent());
        summary.put("historyFlushQueuePeak", batchMetrics == null ? -1 : batchMetrics.flushExecutorQueuePeak());
        summary.put("generatedAt", Instant.now().toString());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                options.outputDir().resolve("summary.json").toFile(), summary);
    }

    private record RedisKeys(String pendingKey, String processingKey, String deadLetterKey) {
    }

    private record Options(String runId,
                           int records,
                           int devices,
                           long timeoutSeconds,
                           double minRowsPerSecond,
                           String devicePrefix,
                           Path outputDir) {

        static Options from(Environment environment) {
            String runId = "history-recovery-" + RUN_ID_FORMATTER.format(Instant.now());
            int records = intValue(environment, "history.recovery.records", 20_000);
            int devices = intValue(environment, "history.recovery.devices", 20);
            String devicePrefix = "history-recovery-" + runId;
            return new Options(
                    runId,
                    records,
                    devices,
                    longValue(environment, "history.recovery.timeout-seconds", 120L),
                    doubleValue(environment, "history.recovery.min-rows-per-second", 500D),
                    devicePrefix,
                    Path.of(value(environment, "history.recovery.output",
                            "target/soak-results/" + runId)));
        }

        private static String value(Environment environment, String key, String defaultValue) {
            String value = environment.getProperty(key);
            return value == null || value.isBlank() ? defaultValue : value.trim();
        }

        private static int intValue(Environment environment, String key, int defaultValue) {
            return (int) longValue(environment, key, defaultValue);
        }

        private static long longValue(Environment environment, String key, long defaultValue) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Long.parseLong(value.trim());
        }

        private static double doubleValue(Environment environment, String key, double defaultValue) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Double.parseDouble(value.trim());
        }
    }
}

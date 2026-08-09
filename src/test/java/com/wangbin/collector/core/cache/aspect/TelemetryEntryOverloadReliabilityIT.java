package com.wangbin.collector.core.cache.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.ingress.RedisTelemetryIngressBuffer;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBufferMetrics;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBufferProperties;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用真实 Redis 验证遥测入口过载不会静默丢失整批后处理。
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "telemetry.tdengine.enabled=true",
        "collector.report.enabled=false",
        "collector.config.loader=file"
})
class TelemetryEntryOverloadReliabilityIT {

    private static final String PENDING_KEY = "collector:test:telemetry:entry:pending:v1";
    private static final String PROCESSING_KEY = "collector:test:telemetry:entry:processing:v1";
    private static final String DEAD_KEY = "collector:test:telemetry:entry:dead:v1";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void entryExecutorOverloadShouldDeferRejectedBatchesAndReplayThroughPipeline() throws Exception {
        clearKeys();
        BlockingCountingStage cache = new BlockingCountingStage(TelemetryStageType.CACHE);
        CountingStage stream = new CountingStage(TelemetryStageType.STREAM);
        CountingStage history = new CountingStage(TelemetryStageType.HISTORY);
        CountingStage report = new CountingStage(TelemetryStageType.REPORT);
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(
                List.of(cache, stream, history, report),
                Runnable::run,
                Runnable::run,
                Runnable::run,
                Runnable::run);
        RedisTelemetryIngressBuffer buffer = new RedisTelemetryIngressBuffer(
                redisTemplate,
                objectMapper,
                properties(),
                pipeline,
                new CollectionTaskGuard());
        ThreadPoolExecutor entryExecutor = executor("entry-overload-it-", 1, 1);
        CollectorDataPostProcessor processor = new CollectorDataPostProcessor(
                entryExecutor,
                pipeline,
                new CollectionTaskGuard(),
                buffer);
        String deviceId = "entry-overload-it-" + System.currentTimeMillis();
        List<DataPoint> points = points(deviceId, 10);
        Map<String, Object> values = values(points);
        List<Long> rejectedLatencies = new ArrayList<>();
        try {
            processor.saveBatchAsync(deviceId, points, values, null);
            assertTrue(cache.awaitEntered());
            processor.saveBatchAsync(deviceId, points, values, null);
            for (int index = 0; index < 5; index++) {
                long startedAt = System.nanoTime();
                processor.saveBatchAsync(deviceId, points, values, null);
                rejectedLatencies.add(System.nanoTime() - startedAt);
            }
            waitUntil(() -> buffer.metrics().rejectedTasks() == 5L
                    && buffer.metrics().rejectedItems() == 50L
                    && buffer.metrics().redisBufferedItems() == 50L);
            cache.release();
            waitUntil(() -> cache.count() == 20L
                    && stream.count() == 20L
                    && history.count() == 20L
                    && report.count() == 20L
                    && entryExecutor.getQueue().isEmpty()
                    && entryExecutor.getActiveCount() == 0);

            drain(buffer);

            assertEquals(70L, cache.count());
            assertEquals(70L, stream.count());
            assertEquals(70L, history.count());
            assertEquals(70L, report.count());
            TelemetryIngressBufferMetrics metrics = buffer.metrics();
            assertEquals(0L, metrics.redisPending());
            assertEquals(0L, metrics.redisProcessing());
            assertEquals(0, metrics.localPending());
            assertEquals(0L, metrics.droppedItems());
            assertEquals(50L, metrics.replayCompletedItems());
            writeSummary(metrics, rejectedLatencies, cache.count());
        } finally {
            entryExecutor.shutdownNow();
            assertTrue(entryExecutor.awaitTermination(2, TimeUnit.SECONDS));
            clearKeys();
        }
    }

    private void drain(RedisTelemetryIngressBuffer buffer) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            buffer.replay();
            TelemetryIngressBufferMetrics metrics = buffer.metrics();
            if (metrics.redisPending() == 0L
                    && metrics.redisProcessing() == 0L
                    && metrics.localPending() == 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20L);
        }
        TelemetryIngressBufferMetrics metrics = buffer.metrics();
        assertEquals(0L, metrics.redisPending());
        assertEquals(0L, metrics.redisProcessing());
        assertEquals(0, metrics.localPending());
    }

    private void writeSummary(TelemetryIngressBufferMetrics metrics,
                              List<Long> rejectedLatencies,
                              long pipelineReceived) throws Exception {
        Path output = Path.of("target", "soak-results", "telemetry-entry-overload-reliability");
        Files.createDirectories(output);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("timestamp", Instant.now().toString());
        summary.put("normalAcceptedItems", 20);
        summary.put("entryRejectedTasks", metrics.rejectedTasks());
        summary.put("entryRejectedItems", metrics.rejectedItems());
        summary.put("entryRedisBufferedItems", metrics.redisBufferedItems());
        summary.put("entryLocalBufferedItems", metrics.localBufferedItems());
        summary.put("entryDroppedItems", metrics.droppedItems());
        summary.put("entryReplayCompletedItems", metrics.replayCompletedItems());
        summary.put("pipelineReceivedItems", pipelineReceived);
        summary.put("fallbackLatencyP50Ms", percentileMillis(rejectedLatencies, 0.50D));
        summary.put("fallbackLatencyP95Ms", percentileMillis(rejectedLatencies, 0.95D));
        summary.put("fallbackLatencyP99Ms", percentileMillis(rejectedLatencies, 0.99D));
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

    private TelemetryIngressBufferProperties properties() {
        TelemetryIngressBufferProperties properties = new TelemetryIngressBufferProperties();
        properties.setPendingKey(PENDING_KEY);
        properties.setProcessingKey(PROCESSING_KEY);
        properties.setDeadLetterKey(DEAD_KEY);
        properties.setReplayBatchSize(100);
        properties.setLocalQueueCapacity(100);
        return properties;
    }

    private List<DataPoint> points(String deviceId, int count) {
        List<DataPoint> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            DataPoint point = new DataPoint();
            point.setDeviceId(deviceId);
            point.setPointId("p" + index);
            point.setPointCode("p" + index);
            point.setStatus(1);
            point.setCacheEnabled(1);
            point.setAdditionalConfig(Map.of(
                    "historyEnabled", true,
                    "streamEnabled", true,
                    "reportEnabled", true,
                    "reportField", "p" + index));
            points.add(point);
        }
        return points;
    }

    private Map<String, Object> values(List<DataPoint> points) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < points.size(); index++) {
            values.put(points.get(index).getPointId(), index);
        }
        return values;
    }

    private ThreadPoolExecutor executor(String namePrefix, int threads, int queueCapacity) {
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
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void waitUntil(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        assertTrue(condition.isSatisfied());
    }

    private void clearKeys() {
        redisTemplate.delete(List.of(PENDING_KEY, PROCESSING_KEY, DEAD_KEY));
    }

    private static class CountingStage implements TelemetryPostProcessStage {
        private final TelemetryStageType type;
        private final LongAdder count = new LongAdder();

        private CountingStage(TelemetryStageType type) {
            this.type = type;
        }

        @Override
        public TelemetryStageType type() {
            return type;
        }

        @Override
        public String name() {
            return type.name().toLowerCase(java.util.Locale.ROOT);
        }

        @Override
        public boolean enabled(TelemetryPostProcessContext context) {
            return true;
        }

        @Override
        public void process(TelemetryPostProcessContext context) {
            count.increment();
        }

        protected long count() {
            return count.sum();
        }
    }

    private static final class BlockingCountingStage extends CountingStage {
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingCountingStage(TelemetryStageType type) {
            super(type);
        }

        @Override
        public void process(TelemetryPostProcessContext context) {
            super.process(context);
            if (count() == 1L) {
                firstEntered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private boolean awaitEntered() throws InterruptedException {
            return firstEntered.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}

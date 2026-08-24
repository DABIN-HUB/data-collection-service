package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBuffer;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBufferMetrics;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBufferResult;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryEntryOverloadReliabilityTest {

    private ThreadPoolExecutor entryExecutor;

    @AfterEach
    void tearDown() throws InterruptedException {
        shutdown(entryExecutor);
    }

    @Test
    void oneRejectedEntryBatchShouldNotDisappear() throws Exception {
        entryExecutor = fixedPool("entry-overload", 1, 1);
        BlockingStage stage = new BlockingStage();
        RecordingIngressBuffer buffer = new RecordingIngressBuffer();
        CollectorDataPostProcessor processor = processor(stage, buffer);
        String deviceId = "entry-overload-dev";
        List<DataPoint> points = points(deviceId, 4);
        Map<String, Object> values = values(points);

        processor.saveBatchAsync(deviceId, points, values, null);
        assertTrue(stage.awaitEntered());
        processor.saveBatchAsync(deviceId, points, values, null);
        processor.saveBatchAsync(deviceId, points, values, null);

        waitUntil(() -> buffer.rejectedTasks() == 1L && buffer.rejectedItems() == 4L);
        assertEquals(4L, buffer.redisBufferedItems());
        assertEquals(0L, buffer.droppedItems());

        stage.release();
        waitUntil(() -> stage.processedCount() == 8L
                && entryExecutor.getQueue().isEmpty()
                && entryExecutor.getActiveCount() == 0);
    }

    @Test
    void entryRejectionFallbackShouldRunOnCollectorCallerThreadWithoutRunningPipeline() throws Exception {
        entryExecutor = fixedPool("entry-thread", 1, 1);
        BlockingStage stage = new BlockingStage();
        RecordingIngressBuffer buffer = new RecordingIngressBuffer();
        CollectorDataPostProcessor processor = processor(stage, buffer);
        String deviceId = "entry-thread-dev";
        List<DataPoint> points = points(deviceId, 3);
        Map<String, Object> values = values(points);

        processor.saveBatchAsync(deviceId, points, values, null);
        assertTrue(stage.awaitEntered());
        processor.saveBatchAsync(deviceId, points, values, null);
        Thread caller = new Thread(
                () -> processor.saveBatchAsync(deviceId, points, values, null),
                "collector-read-caller");
        caller.start();
        caller.join(2_000L);

        waitUntil(() -> buffer.rejectedTasks() == 1L);
        assertEquals(List.of("collector-read-caller"), buffer.deferThreads());
        assertEquals(1L, stage.processedCount());

        stage.release();
        waitUntil(() -> stage.processedCount() == 6L
                && entryExecutor.getQueue().isEmpty()
                && entryExecutor.getActiveCount() == 0);
    }

    @Test
    void entryRejectionShouldCountItemsInsteadOfOnlyRejectedTask() throws Exception {
        entryExecutor = fixedPool("entry-accounting", 1, 1);
        BlockingStage stage = new BlockingStage();
        RecordingIngressBuffer buffer = new RecordingIngressBuffer();
        CollectorDataPostProcessor processor = processor(stage, buffer);
        String deviceId = "entry-accounting-dev";
        List<DataPoint> points = points(deviceId, 5);
        Map<String, Object> values = values(points);

        processor.saveBatchAsync(deviceId, points, values, null);
        assertTrue(stage.awaitEntered());
        processor.saveBatchAsync(deviceId, points, values, null);
        processor.saveBatchAsync(deviceId, points, values, null);

        waitUntil(() -> buffer.rejectedTasks() == 1L);
        assertEquals(1L, buffer.rejectedTasks());
        assertEquals(5L, buffer.rejectedItems());

        stage.release();
        waitUntil(() -> entryExecutor.getQueue().isEmpty() && entryExecutor.getActiveCount() == 0);
    }

    @Test
    void unexpectedDeferExceptionMustIncrementExplicitDroppedItems() throws Exception {
        entryExecutor = fixedPool("entry-defer-error", 1, 1);
        BlockingStage stage = new BlockingStage();
        ThrowingIngressBuffer buffer = new ThrowingIngressBuffer();
        CollectorDataPostProcessor processor = processor(stage, buffer);
        String deviceId = "entry-defer-error-dev";
        List<DataPoint> points = points(deviceId, 4);
        Map<String, Object> values = values(points);

        processor.saveBatchAsync(deviceId, points, values, null);
        assertTrue(stage.awaitEntered());
        processor.saveBatchAsync(deviceId, points, values, null);
        processor.saveBatchAsync(deviceId, points, values, null);

        waitUntil(() -> buffer.deferAttempts() == 1L && buffer.droppedItems() == 4L);

        stage.release();
        waitUntil(() -> entryExecutor.getQueue().isEmpty() && entryExecutor.getActiveCount() == 0);
    }

    @Test
    void highVolumeEntryRejectMustRateLimitWarnLogsAndKeepExactMetrics() throws Exception {
        entryExecutor = fixedPool("entry-log-limit", 1, 1);
        BlockingStage stage = new BlockingStage();
        RecordingIngressBuffer buffer = new RecordingIngressBuffer();
        CollectorDataPostProcessor processor = processor(stage, buffer);
        String deviceId = "entry-log-limit-dev";
        List<DataPoint> points = points(deviceId, 3);
        Map<String, Object> values = values(points);

        processor.saveBatchAsync(deviceId, points, values, null);
        assertTrue(stage.awaitEntered());
        for (int index = 0; index < 12; index++) {
            processor.saveBatchAsync(deviceId, points, values, null);
        }

        waitUntil(() -> buffer.rejectedTasks() == 11L && buffer.rejectedItems() == 33L);
        CollectorDataPostProcessorMetrics metrics = processor.metrics();
        assertEquals(11L, buffer.rejectedTasks());
        assertEquals(33L, buffer.rejectedItems());
        assertEquals(1L, metrics.entryLogRateLimitedEvents());
        assertEquals(10L, metrics.entryLogSuppressedEvents());

        stage.release();
        waitUntil(() -> entryExecutor.getQueue().isEmpty() && entryExecutor.getActiveCount() == 0);
    }

    private CollectorDataPostProcessor processor(TelemetryPostProcessStage stage, TelemetryIngressBuffer buffer) {
        return new CollectorDataPostProcessor(
                entryExecutor,
                new TelemetryPostProcessPipeline(
                        List.of(stage),
                        Runnable::run,
                        Runnable::run,
                        Runnable::run,
                        Runnable::run),
                new CollectionTaskGuard(),
                buffer);
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
            points.add(point);
        }
        return points;
    }

    private Map<String, Object> values(List<DataPoint> points) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        for (int index = 0; index < points.size(); index++) {
            values.put(points.get(index).getPointId(), index);
        }
        return values;
    }

    private ThreadPoolExecutor fixedPool(String namePrefix, int threads, int queueCapacity) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    thread.setName(namePrefix + "-" + thread.getId());
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void waitUntil(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        assertTrue(condition.isSatisfied());
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static final class BlockingStage implements TelemetryPostProcessStage {
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final LongAdder processed = new LongAdder();

        @Override
        public TelemetryStageType type() {
            return TelemetryStageType.CACHE;
        }

        @Override
        public String name() {
            return "blocking-entry-test";
        }

        @Override
        public boolean enabled(TelemetryPostProcessContext context) {
            return true;
        }

        @Override
        public void process(TelemetryPostProcessContext context) {
            processed.increment();
            if (processed.sum() == 1L) {
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

        private long processedCount() {
            return processed.sum();
        }
    }

    private static final class RecordingIngressBuffer implements TelemetryIngressBuffer {
        private final AtomicInteger rejectedTasks = new AtomicInteger();
        private final LongAdder rejectedItems = new LongAdder();
        private final LongAdder redisBufferedItems = new LongAdder();
        private final LongAdder droppedItems = new LongAdder();
        private final List<String> deferThreads = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public TelemetryIngressBufferResult defer(List<TelemetryPostProcessContext> contexts, RuntimeException cause) {
            int items = contexts == null ? 0 : contexts.size();
            rejectedTasks.incrementAndGet();
            rejectedItems.add(items);
            redisBufferedItems.add(items);
            deferThreads.add(Thread.currentThread().getName());
            return new TelemetryIngressBufferResult(items, items, 0, 0);
        }

        @Override
        public TelemetryIngressBufferMetrics metrics() {
            return TelemetryIngressBufferMetrics.empty();
        }

        private long rejectedTasks() {
            return rejectedTasks.get();
        }

        private long rejectedItems() {
            return rejectedItems.sum();
        }

        private long redisBufferedItems() {
            return redisBufferedItems.sum();
        }

        private long droppedItems() {
            return droppedItems.sum();
        }

        private List<String> deferThreads() {
            return List.copyOf(deferThreads);
        }
    }

    private static final class ThrowingIngressBuffer implements TelemetryIngressBuffer {
        private final LongAdder deferAttempts = new LongAdder();
        private final LongAdder droppedItems = new LongAdder();

        @Override
        public TelemetryIngressBufferResult defer(List<TelemetryPostProcessContext> contexts, RuntimeException cause) {
            deferAttempts.increment();
            throw new IllegalStateException("entry defer failed");
        }

        @Override
        public void recordDropped(int itemCount, RuntimeException cause) {
            droppedItems.add(itemCount);
        }

        @Override
        public TelemetryIngressBufferMetrics metrics() {
            return TelemetryIngressBufferMetrics.empty();
        }

        private long deferAttempts() {
            return deferAttempts.sum();
        }

        private long droppedItems() {
            return droppedItems.sum();
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}

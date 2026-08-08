package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryPipelineBackpressureTest {

    private ThreadPoolExecutor entryExecutor;
    private ThreadPoolExecutor stageExecutor;

    @AfterEach
    void tearDown() throws InterruptedException {
        shutdown(entryExecutor);
        shutdown(stageExecutor);
    }

    @Test
    void slowAndFailingDownstreamStagesShouldDrainAndKeepNextRoundRunning() throws Exception {
        entryExecutor = fixedPool("telemetry-entry", 1, 128);
        stageExecutor = fixedPool("telemetry-stage", 1, 512);
        SlowStage slowStage = new SlowStage();
        FailingStage failingStage = new FailingStage();
        CollectionTaskGuard guard = new CollectionTaskGuard();
        CollectorDataPostProcessor processor = new CollectorDataPostProcessor(
                entryExecutor,
                new TelemetryPostProcessPipeline(
                        List.of(slowStage, failingStage),
                        stageExecutor,
                        stageExecutor,
                        stageExecutor,
                        stageExecutor),
                guard);
        String deviceId = "telemetry-dev";
        long generation = guard.activateNextGeneration(deviceId);
        List<DataPoint> points = points(deviceId, 6);
        Map<String, Object> firstRoundValues = partialValues(points);
        Map<String, Object> secondRoundValues = partialValues(points);

        guard.runWithContext(deviceId, generation,
                () -> processor.saveBatchAsync(deviceId, points, firstRoundValues, null));
        assertTrue(slowStage.awaitFirstEntered());
        guard.runWithContext(deviceId, generation,
                () -> processor.saveBatchAsync(deviceId, points, secondRoundValues, null));
        assertTrue(stageExecutor.getQueue().size() > 0);

        slowStage.release();
        waitUntil(() -> slowStage.processedCount() == 6L
                && failingStage.attemptCount() == 6L
                && entryExecutor.getQueue().isEmpty()
                && stageExecutor.getQueue().isEmpty()
                && stageExecutor.getActiveCount() == 0);

        assertEquals(6L, slowStage.processedCount());
        assertEquals(6L, failingStage.attemptCount());
        assertEquals(0, entryExecutor.getQueue().size());
        assertEquals(0, stageExecutor.getQueue().size());
    }

    private List<DataPoint> points(String deviceId, int count) {
        java.util.ArrayList<DataPoint> points = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            DataPoint point = new DataPoint();
            point.setDeviceId(deviceId);
            point.setPointId("p" + i);
            point.setPointCode("p" + i);
            point.setStatus(1);
            points.add(point);
        }
        return points;
    }

    private Map<String, Object> partialValues(List<DataPoint> points) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < points.size(); i += 2) {
            values.put(points.get(i).getPointId(), i);
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
                });
    }

    private void waitUntil(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(condition.isSatisfied());
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static final class SlowStage implements TelemetryPostProcessStage {
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final LongAdder processed = new LongAdder();

        @Override
        public TelemetryStageType type() {
            return TelemetryStageType.CACHE;
        }

        @Override
        public String name() {
            return "slow-test-stage";
        }

        @Override
        public boolean enabled(TelemetryPostProcessContext context) {
            return true;
        }

        @Override
        public void process(TelemetryPostProcessContext context) {
            if (processed.sum() == 0L) {
                firstEntered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            processed.increment();
        }

        private boolean awaitFirstEntered() throws InterruptedException {
            return firstEntered.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }

        private long processedCount() {
            return processed.sum();
        }
    }

    private static final class FailingStage implements TelemetryPostProcessStage {
        private final LongAdder attempts = new LongAdder();

        @Override
        public TelemetryStageType type() {
            return TelemetryStageType.REPORT;
        }

        @Override
        public String name() {
            return "failing-test-stage";
        }

        @Override
        public boolean enabled(TelemetryPostProcessContext context) {
            return true;
        }

        @Override
        public void process(TelemetryPostProcessContext context) {
            attempts.increment();
            throw new IllegalStateException("simulated telemetry downstream failure");
        }

        private long attemptCount() {
            return attempts.sum();
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}

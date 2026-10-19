package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.service.TelemetryStreamMetrics;
import com.wangbin.collector.core.cache.service.TelemetryStreamService;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.collector.runtime.AcquisitionRuntimeTracker;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelemetryPipelineBackpressureTest {

    private final CollectionTaskGuard guard = new CollectionTaskGuard();
    private final AcquisitionRuntimeTracker tracker = new AcquisitionRuntimeTracker(guard);
    private final ConfigManager configManager = mock(ConfigManager.class);

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
        CollectorDataPostProcessor processor = new CollectorDataPostProcessor(
                entryExecutor,
                new TelemetryPostProcessPipeline(
                        List.of(slowStage, failingStage),
                        stageExecutor,
                        stageExecutor,
                        stageExecutor,
                        stageExecutor,
                        tracker,
                        (device, currentGeneration, sampleAt) -> {},
                        configManager),
                guard);
        String deviceId = "telemetry-dev";
        long generation = guard.activateNextGeneration(deviceId);
        List<DataPoint> points = points(deviceId, 6);
        registerPoints(deviceId, generation, points);
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

    @Test
    void slowRedisXaddMustExposeStreamStageCapacityPressure() throws Exception {
        entryExecutor = fixedPool("stream-entry", 1, 128);
        CountingRejectedExecutionHandler rejected = new CountingRejectedExecutionHandler();
        stageExecutor = fixedPool("stream-stage", 1, 1, rejected);
        BlockingStreamService streamService = new BlockingStreamService();
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(
                List.of(new StreamTelemetryPostProcessStage(streamService, new TelemetryStreamProperties())),
                Runnable::run,
                stageExecutor,
                Runnable::run,
                Runnable::run,
                tracker,
                (device, currentGeneration, sampleAt) -> {},
                configManager);
        String deviceId = "stream-pressure-dev";
        long generation = guard.activateNextGeneration(deviceId);
        List<DataPoint> points = points(deviceId, 4);
        registerPoints(deviceId, generation, points);

        for (DataPoint point : points) {
            pipeline.process(context(point, generation));
        }

        assertTrue(streamService.awaitEntered());
        waitUntil(() -> stageExecutor.getQueue().size() == 1 && rejected.count() > 0);
        assertEquals(1, stageExecutor.getActiveCount());
        assertEquals(1, stageExecutor.getQueue().size());
        streamService.release();
        waitUntil(() -> stageExecutor.getQueue().isEmpty() && stageExecutor.getActiveCount() == 0);
    }

    @Test
    void streamExecutorRejectMustBeExplicitlyAccounted() throws Exception {
        CountingRejectedExecutionHandler rejected = new CountingRejectedExecutionHandler();
        stageExecutor = fixedPool("stream-reject", 1, 1, rejected);
        BlockingStreamService streamService = new BlockingStreamService();
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(
                List.of(new StreamTelemetryPostProcessStage(streamService, new TelemetryStreamProperties())),
                Runnable::run,
                stageExecutor,
                Runnable::run,
                Runnable::run,
                tracker,
                (device, currentGeneration, sampleAt) -> {},
                configManager);
        String deviceId = "stream-reject-dev";
        long generation = guard.activateNextGeneration(deviceId);
        List<DataPoint> points = points(deviceId, 5);
        registerPoints(deviceId, generation, points);

        for (DataPoint point : points) {
            pipeline.process(context(point, generation));
        }

        assertTrue(streamService.awaitEntered());
        waitUntil(() -> rejected.count() >= 3);
        assertTrue(rejected.count() >= 3);
        waitUntil(() -> streamService.compensatedCount() >= 3L);
        assertEquals(streamService.compensatedCount(),
                pipeline.metrics().stageRejectedCompensatedEvents());
        assertEquals(0L, pipeline.metrics().stageRejectedUncompensatedEvents());
        streamService.release();
    }

    @Test
    void blockedDownstreamMustNotHoldDeviceStopGate() throws Exception {
        entryExecutor = fixedPool("stop-entry", 1, 1);
        stageExecutor = fixedPool("stop-stream", 1, 1);
        BlockingStreamService streamService = new BlockingStreamService();
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(
                List.of(new StreamTelemetryPostProcessStage(streamService, new TelemetryStreamProperties())),
                Runnable::run, stageExecutor, Runnable::run, Runnable::run,
                tracker, (device, currentGeneration, sampleAt) -> {}, configManager);
        String deviceId = "downstream-stop-dev";
        long generation = guard.activateNextGeneration(deviceId);
        List<DataPoint> points = points(deviceId, 1);
        registerPoints(deviceId, generation, points);
        pipeline.process(context(points.get(0), generation));
        assertTrue(streamService.awaitEntered());
        try {
            entryExecutor.submit(() -> guard.clearDevice(deviceId)).get(1, TimeUnit.SECONDS);
            assertEquals(false, guard.isCurrent(deviceId, generation));
        } finally {
            streamService.release();
        }
    }

    private TelemetryPostProcessContext context(DataPoint point, long generation) {
        ProcessResult result = result(1);
        long sampleAt = ((Number) result.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue();
        return new TelemetryPostProcessContext(point.getDeviceId(), point, result, result,
                sampleAt, generation, guard, null, false);
    }

    private void registerPoints(String deviceId, long generation, List<DataPoint> points) {
        tracker.open(deviceId, generation, points);
        for (DataPoint point : points) {
            when(configManager.getDataPointByPointId(deviceId, point.getPointId())).thenReturn(point);
        }
        when(configManager.runIfConfigurationCurrent(eq(deviceId), eq(1L), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return true;
                });
    }

    private ProcessResult result(Object value) {
        ProcessResult result = ProcessResult.success(value, value);
        result.setQuality(100);
        result.addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, System.currentTimeMillis());
        result.addMetadata(ProcessResultMetadataKeys.SOURCE, "POLLING");
        result.addMetadata(ProcessResultMetadataKeys.CONFIG_VERSION, 1L);
        return result;
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
            values.put(points.get(i).getPointId(), result(i));
        }
        return values;
    }

    private ThreadPoolExecutor fixedPool(String namePrefix, int threads, int queueCapacity) {
        return fixedPool(namePrefix, threads, queueCapacity, new ThreadPoolExecutor.AbortPolicy());
    }

    private ThreadPoolExecutor fixedPool(String namePrefix,
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
                    thread.setName(namePrefix + "-" + thread.getId());
                    return thread;
                },
                rejectedExecutionHandler);
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

    private static final class BlockingStreamService implements TelemetryStreamService {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final LongAdder attempts = new LongAdder();
        private final LongAdder compensated = new LongAdder();

        @Override
        public void append(String deviceId, DataPoint point, ProcessResult processResult) {
            attempts.increment();
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean appendBestEffort(String deviceId, DataPoint point, ProcessResult processResult) {
            attempts.increment();
            compensated.increment();
            return true;
        }

        @Override
        public TelemetryStreamMetrics metrics() {
            return TelemetryStreamMetrics.empty();
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }

        private long compensatedCount() {
            return compensated.sum();
        }
    }

    private static final class CountingRejectedExecutionHandler implements RejectedExecutionHandler {
        private final AtomicInteger rejected = new AtomicInteger();

        @Override
        public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
            rejected.incrementAndGet();
            throw new java.util.concurrent.RejectedExecutionException("stream stage full");
        }

        private int count() {
            return rejected.get();
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}

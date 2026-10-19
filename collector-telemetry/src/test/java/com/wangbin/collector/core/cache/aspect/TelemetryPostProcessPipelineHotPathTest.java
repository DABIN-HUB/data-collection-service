package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.collector.scheduler.CollectionProcessingReceipt;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.collector.runtime.AcquisitionRuntimeTracker;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.realtime.RealtimeChangeTracker;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.util.ReflectionTestUtils.getField;

class TelemetryPostProcessPipelineHotPathTest {

    @Test
    void pipelineMustPreserveConfiguredStageOrderWithoutPerItemSort() {
        List<String> calls = new ArrayList<>();
        OrderedStage stream = new OrderedStage("stream", TelemetryStageType.STREAM, 20, calls);
        OrderedStage cache = new OrderedStage("cache", TelemetryStageType.CACHE, 10, calls);
        OrderedStage history = new OrderedStage("history", TelemetryStageType.HISTORY, 30, calls);
        TelemetryPostProcessPipeline pipeline = pipeline(List.of(history, stream, cache), Runnable::run);

        pipeline.process(context("order-dev", "p1"));

        assertEquals(List.of("cache", "stream", "history"), calls);
        assertEquals(List.of(cache, stream, history), pipeline.orderedStageSnapshot());
    }

    @Test
    void stageSnapshotMustBeImmutableDuringProcess() {
        List<String> calls = new ArrayList<>();
        List<TelemetryPostProcessStage> source = new ArrayList<>();
        OrderedStage first = new OrderedStage("first", TelemetryStageType.CACHE, 10, calls);
        OrderedStage second = new OrderedStage("second", TelemetryStageType.STREAM, 20, calls);
        source.add(first);
        TelemetryPostProcessPipeline pipeline = pipeline(source, Runnable::run);

        source.add(second);
        pipeline.process(context("snapshot-dev", "p1"));

        assertEquals(List.of("first"), calls);
        assertThrows(UnsupportedOperationException.class, () -> pipeline.orderedStageSnapshot().add(second));
    }

    @Test
    void stageRegistrationChangeMustRebuildOrderedSnapshot() {
        List<String> calls = new ArrayList<>();
        List<TelemetryPostProcessStage> source = new ArrayList<>();
        OrderedStage first = new OrderedStage("first", TelemetryStageType.CACHE, 20, calls);
        OrderedStage second = new OrderedStage("second", TelemetryStageType.STREAM, 10, calls);
        source.add(first);
        TelemetryPostProcessPipeline pipeline = pipeline(source, Runnable::run);

        source.add(second);
        pipeline.rebuildStageSnapshot();
        pipeline.process(context("rebuild-dev", "p1"));

        assertEquals(List.of("second", "first"), calls);
    }

    @Test
    void highVolumeStageRejectMustKeepExactMetrics() {
        CountingRejectedStage stage = new CountingRejectedStage("history", TelemetryStageType.HISTORY, true);
        TelemetryPostProcessPipeline pipeline = pipeline(List.of(stage), rejectingExecutor());

        for (int index = 0; index < 20; index++) {
            pipeline.process(context("history-reject-dev", "p" + index));
        }

        TelemetryPipelineMetrics metrics = pipeline.metrics();
        assertEquals(20L, stage.rejected());
        assertEquals(20L, metrics.stageRejectedEvents());
        assertEquals(20L, metrics.stageRejectedCompensatedEvents());
        assertEquals(0L, metrics.stageRejectedUncompensatedEvents());
    }

    @Test
    void highVolumeStageRejectMustRateLimitWarnLogs() {
        CountingRejectedStage stage = new CountingRejectedStage("stream", TelemetryStageType.STREAM, false);
        TelemetryPostProcessPipeline pipeline = pipeline(List.of(stage), rejectingExecutor());

        for (int index = 0; index < 12; index++) {
            pipeline.process(context("stream-reject-dev", "p" + index));
        }

        TelemetryPipelineMetrics metrics = pipeline.metrics();
        assertEquals(12L, metrics.stageRejectedEvents());
        assertEquals(1L, metrics.logRateLimitedEvents());
        assertEquals(11L, metrics.logSuppressedEvents());
    }

    @Test
    void rateLimitedLoggingMustNotSuppressRealErrorAccounting() {
        FailingStage stage = new FailingStage();
        TelemetryPostProcessPipeline pipeline = pipeline(List.of(stage), Runnable::run);

        for (int index = 0; index < 5; index++) {
            pipeline.process(context("error-dev", "p" + index));
        }

        TelemetryPipelineMetrics metrics = pipeline.metrics();
        assertEquals(5L, stage.attempts());
        assertEquals(0L, metrics.stageRejectedEvents());
        assertEquals(0L, metrics.logSuppressedEvents());
    }

    @Test
    void historyRejectFallbackMustRemainExact() {
        CountingRejectedStage stage = new CountingRejectedStage("history", TelemetryStageType.HISTORY, true);
        TelemetryPostProcessPipeline pipeline = pipeline(List.of(stage), rejectingExecutor());

        for (int index = 0; index < 7; index++) {
            pipeline.process(context("history-exact-dev", "p" + index));
        }

        assertEquals(7L, stage.rejected());
        assertEquals(7L, pipeline.metrics().stageRejectedCompensatedEvents());
    }

    @Test
    void streamRejectMustRemainBestEffortAndExplicit() {
        CountingRejectedStage stage = new CountingRejectedStage("stream", TelemetryStageType.STREAM, false);
        TelemetryPostProcessPipeline pipeline = pipeline(List.of(stage), rejectingExecutor());

        for (int index = 0; index < 6; index++) {
            pipeline.process(context("stream-best-effort-dev", "p" + index));
        }

        TelemetryPipelineMetrics metrics = pipeline.metrics();
        assertEquals(6L, stage.rejected());
        assertEquals(6L, metrics.stageRejectedUncompensatedEvents());
        assertEquals(0L, metrics.stageRejectedCompensatedEvents());
    }

    @Test
    void pipelineHotPathMustNotAllocateSortedStageListPerItem() {
        CountingOrderStage first = new CountingOrderStage("first", TelemetryStageType.CACHE, 10);
        CountingOrderStage second = new CountingOrderStage("second", TelemetryStageType.STREAM, 20);
        TelemetryPostProcessPipeline pipeline = pipeline(List.of(second, first), Runnable::run);
        first.resetOrderReads();
        second.resetOrderReads();

        for (int index = 0; index < 10; index++) {
            pipeline.process(context("hot-path-dev", "p" + index));
        }

        assertEquals(0L, first.orderReads());
        assertEquals(0L, second.orderReads());
        assertEquals(10L, first.processed());
        assertEquals(10L, second.processed());
    }

    @Test
    void metricsFailureMustNeverBreakTelemetryPipeline() {
        CountingRejectedStage stage = new CountingRejectedStage("cache", TelemetryStageType.CACHE, true);
        TelemetryPostProcessPipeline pipeline = pipeline(List.of(stage), Runnable::run);
        TelemetryLatencyReservoir reservoir = (TelemetryLatencyReservoir) getField(pipeline, "processLatencyNanos");
        reservoir.failNextAddForTest();

        assertDoesNotThrow(() -> pipeline.process(context("metrics-fail-dev", "p1")));

        TelemetryPipelineMetrics metrics = pipeline.metrics();
        assertEquals(1L, metrics.processedItems());
        assertEquals(1L, metrics.metricsInternalErrors());
    }

    @Test
    void realCoreReceiptMustWaitForCacheDisabledPointAndKeepSourceTime() {
        TelemetryPostProcessContext context = context("receipt-wait", "p1");
        context.point().setCacheEnabled(0);
        long sampleAt = System.currentTimeMillis() - 5000L;
        context.processResult().addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, sampleAt);
        AcquisitionRuntimeTracker tracker = new AcquisitionRuntimeTracker(context.guard());
        tracker.open(context.deviceId(), context.generation(), List.of(context.point()));
        MultiLevelCacheManager cache = mock(MultiLevelCacheManager.class);
        CacheTelemetryPostProcessStage stage = new CacheTelemetryPostProcessStage(cache, new RealtimeChangeTracker(), tracker);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(List.of(stage), queue::add,
                Runnable::run, Runnable::run, Runnable::run, tracker, (device, generation, time) -> {}, null);
        CompletableFuture<Boolean> completed = pipeline.processCore(context);
        assertFalse(completed.isDone());
        assertEquals(0L, tracker.snapshot(context.deviceId(), context.generation()).firstValueAt());
        queue.remove().run();
        assertTrue(completed.getNow(false));
        assertEquals(sampleAt, tracker.snapshot(context.deviceId(), context.generation()).firstValueAt());
        verifyNoInteractions(cache);
    }

    @Test
    void queuedOldGenerationMustNotWriteCacheOrNewFacts() {
        TelemetryPostProcessContext context = context("queued-old", "p1");
        AcquisitionRuntimeTracker tracker = new AcquisitionRuntimeTracker(context.guard());
        tracker.open(context.deviceId(), context.generation(), List.of(context.point()));
        MultiLevelCacheManager cache = mock(MultiLevelCacheManager.class);
        CacheTelemetryPostProcessStage stage = new CacheTelemetryPostProcessStage(cache, new RealtimeChangeTracker(), tracker);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(List.of(stage), queue::add,
                Runnable::run, Runnable::run, Runnable::run, tracker, (device, generation, time) -> {}, null);
        CompletableFuture<Boolean> completed = pipeline.processCore(context);
        context.guard().clearDevice(context.deviceId());
        long next = context.guard().activateNextGeneration(context.deviceId());
        tracker.open(context.deviceId(), next, List.of(context.point()));
        queue.remove().run();
        assertFalse(completed.getNow(true));
        verifyNoInteractions(cache);
        assertEquals(0L, tracker.snapshot(context.deviceId(), next).firstValueAt());
    }

    @Test
    void unknownArrayQualityAndFailedCacheMustNotGrantSuccess() {
        TelemetryPostProcessContext context = context("bad-array", "p1");
        context.processResult().setProcessedValue(List.of(1, 2));
        context.processResult().setQuality(-1);
        context.point().setCacheEnabled(0);
        AcquisitionRuntimeTracker tracker = new AcquisitionRuntimeTracker(context.guard());
        tracker.open(context.deviceId(), context.generation(), List.of(context.point()));
        MultiLevelCacheManager cache = mock(MultiLevelCacheManager.class);
        CacheTelemetryPostProcessStage stage = new CacheTelemetryPostProcessStage(cache, new RealtimeChangeTracker(), tracker);
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(List.of(stage), Runnable::run,
                Runnable::run, Runnable::run, Runnable::run, tracker, (device, generation, time) -> {}, null);
        assertFalse(pipeline.processCore(context).getNow(true));
        assertEquals(0L, tracker.snapshot(context.deviceId(), context.generation()).firstValueAt());
        assertEquals("QUALITY_BAD", tracker.snapshot(context.deviceId(), context.generation()).points().get("p1").failureReason());
        context.point().setCacheEnabled(1);
        context.processResult().setQuality(100);
        context.processResult().addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, System.currentTimeMillis());
        assertTrue(pipeline.processCore(context).isCompletedExceptionally());
        assertEquals(0L, tracker.snapshot(context.deviceId(), context.generation()).firstValueAt());
    }

    @Test
    void recoveryMustWaitForHistoryWithoutWritingLiveCache() {
        TelemetryPostProcessContext live = context("recovery", "p1");
        TelemetryPostProcessContext recovery = new TelemetryPostProcessContext(live.deviceId(), live.point(),
                live.processResult(), live.cacheValue(), live.eventTs(), live.generation(), live.guard(), null, true);
        List<String> calls = new ArrayList<>();
        ArrayDeque<Runnable> historyQueue = new ArrayDeque<>();
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(List.of(
                new OrderedStage("cache", TelemetryStageType.CACHE, 10, calls),
                new OrderedStage("history", TelemetryStageType.HISTORY, 30, calls)),
                Runnable::run, Runnable::run, historyQueue::add, Runnable::run);
        CompletableFuture<Void> completed = pipeline.processRecovery(recovery);
        assertFalse(completed.isDone());
        assertTrue(calls.isEmpty());
        historyQueue.remove().run();
        assertTrue(completed.isDone());
        assertEquals(List.of("history"), calls);
    }

    @Test
    void historyFailureMustNotInvalidateCompletedCoreReceipt() {
        TelemetryPostProcessContext context = context("history-error", "p1");
        List<String> calls = new ArrayList<>();
        OrderedStage history = new OrderedStage("history", TelemetryStageType.HISTORY, 30, calls) {
            @Override
            public void process(TelemetryPostProcessContext ignored) {
                throw new IllegalStateException("历史写入失败");
            }
        };
        TelemetryPostProcessPipeline pipeline = pipeline(List.of(
                new OrderedStage("cache", TelemetryStageType.CACHE, 10, calls), history), Runnable::run);
        assertTrue(pipeline.processCore(context).getNow(false));
    }

    @Test
    void cancelledReceiptMustPreventQueuedCoreSideEffects() {
        TelemetryPostProcessContext original = context("cancelled-receipt", "p1");
        CollectionProcessingReceipt receipt = new CollectionProcessingReceipt(List.of(original.point()));
        receipt.claim("p1");
        TelemetryPostProcessContext context = new TelemetryPostProcessContext(original.deviceId(), original.point(),
                original.processResult(), original.cacheValue(), original.eventTs(), original.generation(),
                original.guard(), receipt, false);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        List<String> calls = new ArrayList<>();
        TelemetryPostProcessPipeline pipeline = pipeline(List.of(
                new OrderedStage("cache", TelemetryStageType.CACHE, 10, calls)), queue::add);
        CompletableFuture<Boolean> completed = pipeline.processCore(context);
        receipt.cancel("COMM_ERROR");
        queue.remove().run();
        assertFalse(completed.getNow(true));
        assertTrue(calls.isEmpty());
        assertFalse(receipt.claim("p1"));
    }

    @Test
    void queuedCoreMustRecheckSourceConfigurationAtFinalCommit() {
        TelemetryPostProcessContext context = context("queued-config", "p1");
        context.processResult().addMetadata(ProcessResultMetadataKeys.CONFIG_VERSION, 7L);
        ConfigManager config = mock(ConfigManager.class);
        when(config.runIfConfigurationCurrent(eq(context.deviceId()), eq(7L), any())).thenReturn(false);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        List<String> calls = new ArrayList<>();
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(List.of(
                new OrderedStage("cache", TelemetryStageType.CACHE, 10, calls)), queue::add,
                Runnable::run, Runnable::run, Runnable::run, null, null, config);
        CompletableFuture<Boolean> completed = pipeline.processCore(context);
        queue.remove().run();
        assertTrue(completed.isCompletedExceptionally());
        assertTrue(calls.isEmpty());
    }

    private TelemetryPostProcessPipeline pipeline(List<TelemetryPostProcessStage> stages, Executor executor) {
        return new TelemetryPostProcessPipeline(stages, executor, executor, executor, executor);
    }

    private Executor rejectingExecutor() {
        return command -> {
            throw new RejectedExecutionException("stage full");
        };
    }

    private TelemetryPostProcessContext context(String deviceId, String pointId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setStatus(1);
        point.setCacheEnabled(1);
        CollectionTaskGuard guard = new CollectionTaskGuard();
        long generation = guard.activateNextGeneration(deviceId);
        ProcessResult result = ProcessResult.success(1, 1);
        result.addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, System.currentTimeMillis());
        result.addMetadata(ProcessResultMetadataKeys.SOURCE, "POLLING");
        return new TelemetryPostProcessContext(deviceId, point, result, result,
                System.currentTimeMillis(), generation, guard, null, false);
    }

    private static class OrderedStage implements TelemetryPostProcessStage, Ordered {
        private final String name;
        private final TelemetryStageType type;
        private final int order;
        private final List<String> calls;

        private OrderedStage(String name, TelemetryStageType type, int order, List<String> calls) {
            this.name = name;
            this.type = type;
            this.order = order;
            this.calls = calls;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public TelemetryStageType type() {
            return type;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean enabled(TelemetryPostProcessContext context) {
            return true;
        }

        @Override
        public void process(TelemetryPostProcessContext context) {
            calls.add(name);
        }
    }

    private static final class CountingOrderStage extends OrderedStage {
        private final LongAdder orderReads = new LongAdder();
        private final LongAdder processed = new LongAdder();

        private CountingOrderStage(String name, TelemetryStageType type, int order) {
            super(name, type, order, new ArrayList<>());
        }

        @Override
        public int getOrder() {
            orderReads.increment();
            return super.getOrder();
        }

        @Override
        public void process(TelemetryPostProcessContext context) {
            processed.increment();
        }

        private void resetOrderReads() {
            orderReads.reset();
        }

        private long orderReads() {
            return orderReads.sum();
        }

        private long processed() {
            return processed.sum();
        }
    }

    private static final class CountingRejectedStage implements TelemetryPostProcessStage {
        private final String name;
        private final TelemetryStageType type;
        private final boolean compensated;
        private final LongAdder rejected = new LongAdder();

        private CountingRejectedStage(String name, TelemetryStageType type, boolean compensated) {
            this.name = name;
            this.type = type;
            this.compensated = compensated;
        }

        @Override
        public TelemetryStageType type() {
            return type;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean enabled(TelemetryPostProcessContext context) {
            return true;
        }

        @Override
        public void process(TelemetryPostProcessContext context) {
        }

        @Override
        public boolean onRejected(TelemetryPostProcessContext context, RejectedExecutionException exception) {
            rejected.increment();
            return compensated;
        }

        private long rejected() {
            return rejected.sum();
        }
    }

    private static final class FailingStage implements TelemetryPostProcessStage {
        private final LongAdder attempts = new LongAdder();

        @Override
        public TelemetryStageType type() {
            return TelemetryStageType.CACHE;
        }

        @Override
        public String name() {
            return "failing";
        }

        @Override
        public boolean enabled(TelemetryPostProcessContext context) {
            return true;
        }

        @Override
        public void process(TelemetryPostProcessContext context) {
            attempts.increment();
            throw new IllegalStateException("boom");
        }

        private long attempts() {
            return attempts.sum();
        }
    }
}

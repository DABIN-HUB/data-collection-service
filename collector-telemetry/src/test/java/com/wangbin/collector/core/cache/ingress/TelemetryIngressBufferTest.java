package com.wangbin.collector.core.cache.ingress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessContext;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessPipeline;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessStage;
import com.wangbin.collector.core.cache.aspect.TelemetryStageType;
import com.wangbin.collector.core.cache.config.TelemetryExecutorNames;
import com.wangbin.collector.core.collector.runtime.AcquisitionRuntimeTracker;
import com.wangbin.collector.core.collector.scheduler.CollectionProcessingReceipt;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.port.DeviceDataActivityReporter;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelemetryIngressBufferTest {

    private ThreadPoolExecutor historyExecutor;
    private final CountingStage replayCache = new CountingStage(TelemetryStageType.CACHE);
    private final CountingStage replayStream = new CountingStage(TelemetryStageType.STREAM);
    private final AcquisitionRuntimeTracker pipelineTracker = mock(AcquisitionRuntimeTracker.class);
    private final DeviceDataActivityReporter activityReporter = mock(DeviceDataActivityReporter.class);

    @AfterEach
    void tearDown() throws InterruptedException {
        if (historyExecutor != null) {
            historyExecutor.shutdownNow();
            assertTrue(historyExecutor.awaitTermination(2, TimeUnit.SECONDS));
        }
        assertEquals(0L, replayCache.count());
        assertEquals(0L, replayStream.count());
        verifyNoInteractions(pipelineTracker, activityReporter);
    }

    @Test
    void rejectedEntryShouldPersistToRedisWithoutRunningPipelineOnCaller() {
        FakeRedisLists redis = new FakeRedisLists();
        CountingStage stage = new CountingStage(TelemetryStageType.HISTORY);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(stage)), properties(10));

        TelemetryIngressBufferResult result = buffer.defer(
                List.of(context("dev-redis", "p1")), new java.util.concurrent.RejectedExecutionException("entry full"));

        assertEquals(1, result.inputItems());
        assertEquals(1, result.redisBufferedItems());
        assertEquals(0, result.droppedItems());
        assertEquals(1L, redis.size("entry:pending"));
        assertEquals(0L, stage.count());
    }

    @Test
    void redisFailureShouldFallbackToBoundedLocalQueue() {
        FakeRedisLists redis = new FakeRedisLists();
        redis.failLeftPushAll();
        CountingStage stage = new CountingStage(TelemetryStageType.HISTORY);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(stage)), properties(3));

        TelemetryIngressBufferResult result = buffer.defer(List.of(
                context("dev-local", "p1"),
                context("dev-local", "p2")), new java.util.concurrent.RejectedExecutionException("entry full"));

        assertEquals(0, result.redisBufferedItems());
        assertEquals(2, result.localBufferedItems());
        assertEquals(0, result.droppedItems());
        assertEquals(2, buffer.metrics().localPending());

        buffer.replay();

        assertEquals(2L, stage.count());
        assertEquals(0, buffer.metrics().localPending());
    }

    @Test
    void localFullShouldExplicitlyCountDroppedItems() {
        FakeRedisLists redis = new FakeRedisLists();
        redis.failLeftPushAll();
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(new CountingStage(TelemetryStageType.HISTORY))),
                properties(3));

        TelemetryIngressBufferResult result = buffer.defer(List.of(
                context("dev-drop", "p1"),
                context("dev-drop", "p2"),
                context("dev-drop", "p3"),
                context("dev-drop", "p4"),
                context("dev-drop", "p5")), new java.util.concurrent.RejectedExecutionException("entry full"));

        assertEquals(5, result.inputItems());
        assertEquals(3, result.localBufferedItems());
        assertEquals(2, result.droppedItems());
        assertEquals(3, buffer.metrics().localPending());
        assertEquals(2L, buffer.metrics().droppedItems());
    }

    @Test
    void replayShouldDispatchOnlyHistoryAndReport() {
        FakeRedisLists redis = new FakeRedisLists();
        redis.failLeftPushAll();
        CountingStage cache = new CountingStage(TelemetryStageType.CACHE);
        CountingStage stream = new CountingStage(TelemetryStageType.STREAM);
        CountingStage history = new CountingStage(TelemetryStageType.HISTORY);
        CountingStage report = new CountingStage(TelemetryStageType.REPORT);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(cache, stream, history, report)),
                properties(10));

        buffer.defer(List.of(context("dev-stage", "p1")), new java.util.concurrent.RejectedExecutionException("entry full"));
        buffer.replay();

        assertEquals(0L, cache.count());
        assertEquals(0L, stream.count());
        assertEquals(1L, history.count());
        assertEquals(1L, report.count());
    }

    @Test
    void replaySuccessButPendingRemoveFailureShouldRemainAtLeastOnce() {
        FakeRedisLists redis = new FakeRedisLists();
        CountingStage stage = new CountingStage(TelemetryStageType.HISTORY);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(stage)), properties(10));
        buffer.defer(List.of(context("dev-remove", "p1")), new java.util.concurrent.RejectedExecutionException("entry full"));
        redis.failRemove();

        buffer.replay();

        assertEquals(1L, stage.count());
        assertEquals(1L, buffer.metrics().pendingRemoveFailures());
        assertEquals(1L, redis.size("entry:processing"));
        assertEquals(0L, buffer.metrics().replayCompletedItems());

        redis.recoverRemove();
        buffer.replay();

        assertEquals(2L, stage.count());
        assertEquals(0L, redis.size("entry:processing"));
        assertEquals(1L, buffer.metrics().replayCompletedItems());
    }

    @Test
    void redisPendingShouldWaitForHistoryAndReportCompletion() {
        FakeRedisLists redis = new FakeRedisLists();
        CountingStage history = new CountingStage(TelemetryStageType.HISTORY);
        CountingStage report = new CountingStage(TelemetryStageType.REPORT);
        Deque<Runnable> historyTasks = new ArrayDeque<>();
        Deque<Runnable> reportTasks = new ArrayDeque<>();
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(
                List.of(replayCache, replayStream, history, report), Runnable::run, Runnable::run,
                historyTasks::addLast, reportTasks::addLast, pipelineTracker, activityReporter, null);
        TelemetryIngressBufferProperties properties = properties(10);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline, properties);
        buffer.defer(List.of(context("dev-pending", "p1")),
                new java.util.concurrent.RejectedExecutionException("entry full"));

        buffer.replay();
        buffer.replay();

        assertEquals(1, historyTasks.size());
        assertEquals(1, reportTasks.size());
        assertEquals(0L, history.count());
        assertEquals(0L, report.count());
        assertEquals(0L, redis.size(properties.getPendingKey()));
        assertEquals(1L, redis.size(properties.getProcessingKey()));
        assertEquals(0L, buffer.metrics().replayCompletedItems());

        historyTasks.removeFirst().run();

        assertEquals(1L, history.count());
        assertEquals(0L, report.count());
        assertEquals(1L, redis.size(properties.getProcessingKey()));
        assertEquals(0L, buffer.metrics().replayCompletedItems());

        reportTasks.removeFirst().run();

        assertEquals(1L, report.count());
        assertEquals(0L, redis.size(properties.getProcessingKey()));
        assertEquals(1L, buffer.metrics().replayCompletedItems());
    }

    @Test
    void malformedDeferredPayloadShouldNotBlockFollowingMessages() {
        FakeRedisLists redis = new FakeRedisLists();
        CountingStage stage = new CountingStage(TelemetryStageType.HISTORY);
        TelemetryIngressBufferProperties properties = properties(10);
        properties.setReplayBatchSize(2);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(stage)), properties);
        buffer.defer(List.of(context("dev-poison", "normal")), new java.util.concurrent.RejectedExecutionException("entry full"));
        redis.leftPush(properties.getProcessingKey(), "{not-json");

        buffer.replay();

        assertEquals(0L, stage.count());
        assertEquals(1L, redis.size(properties.getPendingKey()));
        assertEquals(0L, redis.size(properties.getProcessingKey()));
        assertEquals(1L, redis.size(properties.getDeadLetterKey()));
        assertEquals(1L, buffer.metrics().poisonDeadLetterItems());

        // 隔离结束当前批次，下一回放周期必须继续处理后续消息。
        buffer.replay();

        assertEquals(1L, stage.count());
        assertEquals(1L, redis.size(properties.getDeadLetterKey()));
        assertEquals(1L, buffer.metrics().poisonDeadLetterItems());
        assertEquals(0L, redis.size(properties.getPendingKey()));
        assertEquals(0L, redis.size(properties.getProcessingKey()));
    }

    @Test
    void restartShouldRecoverRedisPending() {
        FakeRedisLists redis = new FakeRedisLists();
        TelemetryIngressBufferProperties properties = properties(10);
        RedisTelemetryIngressBuffer first = buffer(redis, pipeline(List.of(new CountingStage(TelemetryStageType.HISTORY))),
                properties, new CollectionTaskGuard(), "runtime-a");
        first.defer(List.of(context("dev-restart", "p1")), new java.util.concurrent.RejectedExecutionException("entry full"));
        CountingStage restartedStage = new CountingStage(TelemetryStageType.HISTORY);
        RedisTelemetryIngressBuffer restarted = buffer(redis, pipeline(List.of(restartedStage)), properties,
                new CollectionTaskGuard(), "runtime-b");

        restarted.replay();

        assertEquals(1L, restartedStage.count());
        assertEquals(0L, redis.size(properties.getPendingKey()));
        assertEquals(0L, redis.size(properties.getProcessingKey()));
    }

    @Test
    void crossRuntimePersistedEnvelopeMustReplayEvenWhenGenerationDiffers() {
        FakeRedisLists redis = new FakeRedisLists();
        TelemetryIngressBufferProperties properties = properties(10);
        RedisTelemetryIngressBuffer first = buffer(redis, pipeline(List.of(new CountingStage(TelemetryStageType.HISTORY))),
                properties, new CollectionTaskGuard(), "runtime-a");
        first.defer(List.of(context("dev-cross-runtime", "p1", 7L)),
                new java.util.concurrent.RejectedExecutionException("entry full"));
        CountingStage restartedStage = new CountingStage(TelemetryStageType.HISTORY);
        CollectionTaskGuard restartedGuard = new CollectionTaskGuard();
        restartedGuard.activateNextGeneration("dev-cross-runtime");
        RedisTelemetryIngressBuffer restarted = buffer(redis, pipeline(List.of(restartedStage)), properties,
                restartedGuard, "runtime-b");

        restarted.replay();

        assertEquals(1L, restartedStage.count());
        assertEquals(1L, restarted.metrics().crossRuntimeRecoveredItems());
        assertEquals(0L, restarted.metrics().droppedItems());
        assertEquals(0L, redis.size(properties.getPendingKey()));
        assertEquals(0L, redis.size(properties.getProcessingKey()));
    }

    @Test
    void crossRuntimeSameGenerationNumberMustNotBeTreatedAsSameRuntime() {
        FakeRedisLists redis = new FakeRedisLists();
        TelemetryIngressBufferProperties properties = properties(10);
        RedisTelemetryIngressBuffer first = buffer(redis, pipeline(List.of(new CountingStage(TelemetryStageType.HISTORY))),
                properties, new CollectionTaskGuard(), "runtime-a");
        first.defer(List.of(context("dev-generation-collision", "p1", 1L)),
                new java.util.concurrent.RejectedExecutionException("entry full"));
        CountingStage restartedStage = new CountingStage(TelemetryStageType.HISTORY);
        CollectionTaskGuard restartedGuard = mock(CollectionTaskGuard.class);
        when(restartedGuard.isCurrent("dev-generation-collision", 1L)).thenReturn(true);
        RedisTelemetryIngressBuffer restarted = buffer(redis, pipeline(List.of(restartedStage)), properties,
                restartedGuard, "runtime-b");

        restarted.replay();

        assertEquals(1L, restartedStage.count());
        assertEquals(1L, restarted.metrics().crossRuntimeRecoveredItems());
        verify(restartedGuard, never()).isCurrent("dev-generation-collision", 1L);
    }

    @Test
    void sameRuntimeOldGenerationMustBeDropped() {
        FakeRedisLists redis = new FakeRedisLists();
        TelemetryIngressBufferProperties properties = properties(10);
        CollectionTaskGuard guard = new CollectionTaskGuard();
        long oldGeneration = guard.activateNextGeneration("dev-same-runtime-stale");
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(new CountingStage(TelemetryStageType.HISTORY))),
                properties, guard, "runtime-a");
        buffer.defer(List.of(context("dev-same-runtime-stale", "p1", oldGeneration)),
                new java.util.concurrent.RejectedExecutionException("entry full"));
        guard.activateNextGeneration("dev-same-runtime-stale");
        CountingStage stage = new CountingStage(TelemetryStageType.HISTORY);
        RedisTelemetryIngressBuffer restarted = buffer(redis, pipeline(List.of(stage)), properties, guard, "runtime-a");

        restarted.replay();

        assertEquals(0L, stage.count());
        assertEquals(1L, restarted.metrics().droppedItems());
        assertEquals(1L, restarted.metrics().staleSameRuntimeDroppedItems());
        assertEquals(0L, redis.size(properties.getPendingKey()));
        assertEquals(0L, redis.size(properties.getProcessingKey()));
    }

    @Test
    void sameRuntimeCurrentGenerationMustReplay() {
        FakeRedisLists redis = new FakeRedisLists();
        TelemetryIngressBufferProperties properties = properties(10);
        CollectionTaskGuard guard = new CollectionTaskGuard();
        long generation = guard.activateNextGeneration("dev-same-runtime-current");
        CountingStage stage = new CountingStage(TelemetryStageType.HISTORY);
        TelemetryPostProcessContext source = context("dev-same-runtime-current", "p1", generation);
        CollectionProcessingReceipt receipt = new CollectionProcessingReceipt(List.of(source.point()));
        assertTrue(receipt.claim(source.point().getPointId()));
        AcquisitionRuntimeTracker tracker = new AcquisitionRuntimeTracker(guard);
        tracker.open(source.deviceId(), generation, List.of(source.point()));
        TelemetryPostProcessContext live = new TelemetryPostProcessContext(source.deviceId(), source.point(),
                source.processResult(), source.cacheValue(), source.eventTs(), generation, guard, receipt, false);
        assertTrue(live.live());
        assertTrue(live.validValue());
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(
                List.of(replayCache, replayStream, stage), Runnable::run, Runnable::run,
                Runnable::run, Runnable::run, tracker, activityReporter, null);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline, properties, guard, "runtime-a");
        buffer.defer(List.of(live),
                new java.util.concurrent.RejectedExecutionException("entry full"));

        buffer.replay();

        assertEquals(1L, stage.count());
        assertEquals(0L, buffer.metrics().droppedItems());
        assertEquals(0L, tracker.snapshot(source.deviceId(), generation).firstValueAt());
        assertEquals(0L, tracker.snapshot(source.deviceId(), generation).points().get("p1").lastObservedAt());
        assertFalse(receipt.completion().isDone());
        assertEquals(0L, redis.size(properties.getPendingKey()));
        assertEquals(0L, redis.size(properties.getProcessingKey()));
    }

    @Test
    void legacyEnvelopeWithoutRuntimeIdMustRecover() throws Exception {
        FakeRedisLists redis = new FakeRedisLists();
        TelemetryIngressBufferProperties properties = properties(10);
        redis.leftPush(properties.getPendingKey(), legacyJson(context("dev-legacy", "p1", 7L)));
        CountingStage stage = new CountingStage(TelemetryStageType.HISTORY);
        CollectionTaskGuard guard = mock(CollectionTaskGuard.class);
        when(guard.isCurrent("dev-legacy", 7L)).thenReturn(false);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(stage)), properties, guard, "runtime-b");

        buffer.replay();

        assertEquals(1L, stage.count());
        assertEquals(1L, buffer.metrics().legacyEnvelopeRecoveredItems());
        assertEquals(0L, buffer.metrics().droppedItems());
        verify(guard, never()).isCurrent("dev-legacy", 7L);
    }

    @Test
    void restartWithRealNonNullGenerationMustRecoverRedisPending() {
        FakeRedisLists redis = new FakeRedisLists();
        TelemetryIngressBufferProperties properties = properties(10);
        CollectionTaskGuard firstGuard = new CollectionTaskGuard();
        long generation = firstGuard.activateNextGeneration("dev-real-generation");
        RedisTelemetryIngressBuffer first = buffer(redis, pipeline(List.of(new CountingStage(TelemetryStageType.HISTORY))),
                properties, firstGuard, "runtime-a");
        first.defer(List.of(context("dev-real-generation", "p1", generation)),
                new java.util.concurrent.RejectedExecutionException("entry full"));
        CountingStage restartedStage = new CountingStage(TelemetryStageType.HISTORY);
        RedisTelemetryIngressBuffer restarted = buffer(redis, pipeline(List.of(restartedStage)), properties,
                new CollectionTaskGuard(), "runtime-b");

        restarted.replay();

        assertEquals(1L, restartedStage.count());
        assertEquals(1L, restarted.metrics().crossRuntimeRecoveredItems());
        assertEquals(0L, restarted.metrics().droppedItems());
    }

    @Test
    void replayBeforeDeviceStartMustNotFailGenerationCheck() {
        FakeRedisLists redis = new FakeRedisLists();
        TelemetryIngressBufferProperties properties = properties(10);
        RedisTelemetryIngressBuffer first = buffer(redis, pipeline(List.of(new CountingStage(TelemetryStageType.HISTORY))),
                properties, new CollectionTaskGuard(), "runtime-a");
        first.defer(List.of(context("dev-before-start", "p1", 3L)),
                new java.util.concurrent.RejectedExecutionException("entry full"));
        CountingStage restartedStage = new CountingStage(TelemetryStageType.HISTORY);
        RedisTelemetryIngressBuffer restarted = buffer(redis, pipeline(List.of(restartedStage)), properties,
                new CollectionTaskGuard(), "runtime-b");

        restarted.replay();

        assertEquals(1L, restartedStage.count());
        assertEquals(0L, restarted.metrics().droppedItems());
    }

    @Test
    void shutdownAfterGenerationClearMustNotDropRedisPending() {
        FakeRedisLists redis = new FakeRedisLists();
        TelemetryIngressBufferProperties properties = properties(10);
        CollectionTaskGuard guard = new CollectionTaskGuard();
        long generation = guard.activateNextGeneration("dev-shutdown-clear");
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(new CountingStage(TelemetryStageType.HISTORY))),
                properties, guard, "runtime-a");
        buffer.defer(List.of(context("dev-shutdown-clear", "p1", generation)),
                new java.util.concurrent.RejectedExecutionException("entry full"));
        guard.clearDevice("dev-shutdown-clear");

        buffer.shutdown();

        assertEquals(1L, redis.size(properties.getPendingKey()));
        assertEquals(0L, redis.size(properties.getProcessingKey()));
        assertEquals(0L, buffer.metrics().droppedItems());

        CountingStage restartedStage = new CountingStage(TelemetryStageType.HISTORY);
        RedisTelemetryIngressBuffer restarted = buffer(redis, pipeline(List.of(restartedStage)), properties,
                new CollectionTaskGuard(), "runtime-b");
        restarted.replay();

        assertEquals(1L, restartedStage.count());
        assertEquals(0L, redis.size(properties.getPendingKey()));
    }

    @Test
    void pendingRemoveFailureMustNotRewriteRuntimeId() throws Exception {
        FakeRedisLists redis = new FakeRedisLists();
        TelemetryIngressBufferProperties properties = properties(10);
        RedisTelemetryIngressBuffer first = buffer(redis, pipeline(List.of(new CountingStage(TelemetryStageType.HISTORY))),
                properties, new CollectionTaskGuard(), "runtime-a");
        first.defer(List.of(context("dev-remove-runtime", "p1", 9L)),
                new java.util.concurrent.RejectedExecutionException("entry full"));
        CountingStage restartedStage = new CountingStage(TelemetryStageType.HISTORY);
        RedisTelemetryIngressBuffer restarted = buffer(redis, pipeline(List.of(restartedStage)), properties,
                new CollectionTaskGuard(), "runtime-b");
        redis.failRemove();

        restarted.replay();

        assertEquals(1L, restartedStage.count());
        assertEquals(1L, restarted.metrics().pendingRemoveFailures());
        String processing = redis.peekFirst(properties.getProcessingKey());
        assertNotNull(processing);
        TelemetryIngressEnvelope envelope = envelopeMapper().readValue(processing, TelemetryIngressEnvelope.class);
        assertEquals("runtime-a", envelope.runtimeId());
    }

    @Test
    void envelopeJsonRoundTripWithRuntimeIdAndTypedValues() {
        FakeRedisLists redis = new FakeRedisLists();
        TelemetryIngressBufferProperties properties = properties(10);
        CountingStage stage = new CountingStage(TelemetryStageType.HISTORY);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(stage)), properties,
                new CollectionTaskGuard(), "runtime-a");

        long collectTime = 1_700_000_000_123L;
        long eventTs = 1_700_000_010_456L;
        buffer.defer(List.of(
                        contextWithValue("dev-typed", "long", 1_234_567_890_123L, 1L, collectTime, eventTs),
                        contextWithValue("dev-typed", "double", 12.5D, 1L, collectTime, eventTs),
                        contextWithValue("dev-typed", "boolean", true, 1L, collectTime, eventTs),
                        contextWithValue("dev-typed", "string", "正常", 1L, collectTime, eventTs)),
                new java.util.concurrent.RejectedExecutionException("entry full"));
        RedisTelemetryIngressBuffer restarted = buffer(redis, pipeline(List.of(stage)), properties,
                new CollectionTaskGuard(), "runtime-b");

        restarted.replay();

        assertEquals(4L, stage.count());
        assertEquals(1_234_567_890_123L, ((Number) stage.contexts().get(0).cacheValue()).longValue());
        assertEquals(12.5D, ((Number) stage.contexts().get(1).cacheValue()).doubleValue());
        assertEquals(true, stage.contexts().get(2).cacheValue());
        assertEquals("正常", stage.contexts().get(3).cacheValue());
        for (TelemetryPostProcessContext recovered : stage.contexts()) {
            assertTrue(recovered.historicalOnly());
            assertFalse(recovered.live());
            assertNull(recovered.receipt());
            assertEquals("SUBSCRIPTION", recovered.source());
            assertEquals(collectTime, recovered.sampleAt());
            assertEquals(eventTs, recovered.eventTs());
            assertEquals(1L, recovered.generation());
            assertTrue(recovered.validValue());
        }
        assertEquals(4L, restarted.metrics().crossRuntimeRecoveredItems());
        assertEquals(0L, redis.size(properties.getPendingKey()));
        assertEquals(0L, redis.size(properties.getProcessingKey()));
    }

    @Test
    void runtimeIdentityMustBeStableWithinOneJvmContext() {
        RuntimeInstanceIdentity identity = new RuntimeInstanceIdentity();

        String first = identity.runtimeId();
        String second = identity.runtimeId();

        assertEquals(first, second);
        assertEquals(first, UUID.fromString(first).toString());
    }

    @Test
    void shutdownShouldNotReplayLocalFallbackDuringBeanDestroy() {
        FakeRedisLists redis = new FakeRedisLists();
        redis.failLeftPushAll();
        CountingStage stage = new CountingStage(TelemetryStageType.HISTORY);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(stage)), properties(10));
        buffer.defer(List.of(
                context("dev-shutdown", "p1"),
                context("dev-shutdown", "p2")), new java.util.concurrent.RejectedExecutionException("entry full"));

        buffer.shutdown();

        assertEquals(0L, stage.count());
        assertEquals(2, buffer.metrics().localPending());
    }

    @Test
    void entryDeferredThenHistoryRejectedShouldUseHistoryDeferWithoutLoop() throws Exception {
        FakeRedisLists redis = new FakeRedisLists();
        redis.failLeftPushAll();
        CountingRejectedExecutionHandler rejected = new CountingRejectedExecutionHandler();
        historyExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    thread.setName("entry-history-stage-" + thread.getId());
                    return thread;
                },
                rejected);
        BlockingDeferringHistoryStage stage = new BlockingDeferringHistoryStage();
        TelemetryPostProcessPipeline pipeline = pipeline(List.of(stage), historyExecutor);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline, properties(10));

        CompletableFuture<Void> occupying = pipeline.processRecovery(context("dev-entry-history", "occupy"));
        assertTrue(stage.awaitEntered());
        CompletableFuture<Void> queued = pipeline.processRecovery(context("dev-entry-history", "queued"));
        assertEquals(1, historyExecutor.getQueue().size());

        buffer.defer(List.of(
                context("dev-entry-history", "p1"),
                context("dev-entry-history", "p2"),
                context("dev-entry-history", "p3"),
                context("dev-entry-history", "p4")), new java.util.concurrent.RejectedExecutionException("entry full"));
        buffer.replay();

        assertEquals(1L, rejected.count());
        assertEquals(rejected.count(), stage.deferAttempts());
        assertEquals(4, buffer.metrics().localPending());
        assertEquals(0L, buffer.metrics().replayCompletedItems());

        // 失败不在同一周期自旋；下一次显式回放最多再尝试一次队首消息。
        buffer.replay();

        assertEquals(2L, rejected.count());
        assertEquals(rejected.count(), stage.deferAttempts());
        assertEquals(4, buffer.metrics().localPending());
        assertEquals(0L, buffer.metrics().replayCompletedItems());

        stage.release();
        occupying.get(1, TimeUnit.SECONDS);
        queued.get(1, TimeUnit.SECONDS);
        waitUntil(() -> historyExecutor.getQueue().isEmpty() && historyExecutor.getActiveCount() == 0);

        buffer.replay();
        waitUntil(() -> buffer.metrics().localPending() == 0
                && historyExecutor.getQueue().isEmpty() && historyExecutor.getActiveCount() == 0);

        assertEquals(6L, stage.attempts());
        assertEquals(2L, rejected.count());
        assertEquals(rejected.count(), stage.deferAttempts());
        assertEquals(4L, buffer.metrics().replayCompletedItems());
    }

    private RedisTelemetryIngressBuffer buffer(FakeRedisLists redis,
                                               TelemetryPostProcessPipeline pipeline,
                                               TelemetryIngressBufferProperties properties) {
        return buffer(redis, pipeline, properties, new CollectionTaskGuard(), "runtime-test");
    }

    private RedisTelemetryIngressBuffer buffer(FakeRedisLists redis,
                                               TelemetryPostProcessPipeline pipeline,
                                               TelemetryIngressBufferProperties properties,
                                               CollectionTaskGuard collectionTaskGuard) {
        return buffer(redis, pipeline, properties, collectionTaskGuard, "runtime-test");
    }

    private RedisTelemetryIngressBuffer buffer(FakeRedisLists redis,
                                               TelemetryPostProcessPipeline pipeline,
                                               TelemetryIngressBufferProperties properties,
                                               CollectionTaskGuard collectionTaskGuard,
                                               String runtimeId) {
        return new RedisTelemetryIngressBuffer(
                redis.template(),
                new ObjectMapper(),
                properties,
                pipeline,
                collectionTaskGuard,
                new RuntimeInstanceIdentity(runtimeId));
    }

    private TelemetryPostProcessPipeline pipeline(List<TelemetryPostProcessStage> stages) {
        return pipeline(stages, Runnable::run);
    }

    private TelemetryPostProcessPipeline pipeline(List<TelemetryPostProcessStage> stages, Executor historyExecutor) {
        List<TelemetryPostProcessStage> allStages = new ArrayList<>(stages);
        allStages.add(replayCache);
        allStages.add(replayStream);
        return new TelemetryPostProcessPipeline(
                allStages,
                Runnable::run,
                Runnable::run,
                historyExecutor,
                Runnable::run,
                pipelineTracker,
                activityReporter,
                null);
    }

    private TelemetryIngressBufferProperties properties(int localCapacity) {
        TelemetryIngressBufferProperties properties = new TelemetryIngressBufferProperties();
        properties.setPendingKey("entry:pending");
        properties.setProcessingKey("entry:processing");
        properties.setDeadLetterKey("entry:dead");
        properties.setReplayBatchSize(100);
        properties.setLocalQueueCapacity(localCapacity);
        return properties;
    }

    private TelemetryPostProcessContext context(String deviceId, String pointId) {
        return context(deviceId, pointId, null);
    }

    private TelemetryPostProcessContext context(String deviceId, String pointId, Long generation) {
        long now = System.currentTimeMillis();
        return contextWithValue(deviceId, pointId, 1, generation, now, now);
    }

    private TelemetryPostProcessContext contextWithValue(String deviceId, String pointId, Object value, Long generation,
                                                        long collectTime, long eventTs) {
        ProcessResult result = ProcessResult.success(value, value, "ok");
        result.addMetadata(ProcessResultMetadataKeys.SOURCE, "SUBSCRIPTION");
        result.addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, collectTime);
        return new TelemetryPostProcessContext(
                deviceId,
                point(deviceId, pointId),
                result,
                value,
                eventTs,
                generation);
    }

    private String legacyJson(TelemetryPostProcessContext context) throws Exception {
        ObjectMapper mapper = envelopeMapper();
        ObjectNode node = mapper.valueToTree(TelemetryIngressEnvelope.from(context, null));
        node.remove("runtimeId");
        return mapper.writeValueAsString(node);
    }

    private ObjectMapper envelopeMapper() {
        return new ObjectMapper()
                .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.ALL,
                        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
                        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY);
    }

    private DataPoint point(String deviceId, String pointId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setStatus(1);
        point.setAdditionalConfig(Map.of("historyEnabled", true));
        return point;
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

    private static final class CountingStage implements TelemetryPostProcessStage {
        private final TelemetryStageType type;
        private final LongAdder count = new LongAdder();
        private final List<TelemetryPostProcessContext> contexts = new java.util.concurrent.CopyOnWriteArrayList<>();

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
            assertTrue(context.historicalOnly());
            assertFalse(context.live());
            assertNull(context.receipt());
            count.increment();
            contexts.add(context);
        }

        private long count() {
            return count.sum();
        }

        private List<TelemetryPostProcessContext> contexts() {
            return List.copyOf(contexts);
        }
    }

    private static final class BlockingDeferringHistoryStage implements TelemetryPostProcessStage {
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final LongAdder attempts = new LongAdder();
        private final LongAdder deferAttempts = new LongAdder();

        @Override
        public TelemetryStageType type() {
            return TelemetryStageType.HISTORY;
        }

        @Override
        public String name() {
            return "history";
        }

        @Override
        public boolean enabled(TelemetryPostProcessContext context) {
            return true;
        }

        @Override
        public void process(TelemetryPostProcessContext context) {
            attempts.increment();
            if (attempts.sum() == 1L) {
                firstEntered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public boolean onRejected(TelemetryPostProcessContext context, java.util.concurrent.RejectedExecutionException exception) {
            deferAttempts.increment();
            return true;
        }

        private boolean awaitEntered() throws InterruptedException {
            return firstEntered.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }

        private long deferAttempts() {
            return deferAttempts.sum();
        }

        private long attempts() {
            return attempts.sum();
        }
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

    @SuppressWarnings("unchecked")
    private static final class FakeRedisLists {
        private final StringRedisTemplate template = mock(StringRedisTemplate.class);
        private final ListOperations<String, String> operations = mock(ListOperations.class);
        private final Map<String, Deque<String>> lists = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile boolean failLeftPushAll;
        private volatile boolean failRemove;

        private FakeRedisLists() {
            when(template.opsForList()).thenReturn(operations);
            when(operations.leftPushAll(anyString(), any(Collection.class))).thenAnswer(invocation -> {
                if (failLeftPushAll) {
                    throw new RedisConnectionFailureException("entry redis unavailable");
                }
                String key = invocation.getArgument(0);
                Collection<String> values = invocation.getArgument(1);
                for (String value : values) {
                    leftPush(key, value);
                }
                return size(key);
            });
            when(operations.leftPush(anyString(), anyString())).thenAnswer(invocation -> {
                leftPush(invocation.getArgument(0), invocation.getArgument(1));
                return size(invocation.getArgument(0));
            });
            when(operations.rightPopAndLeftPush(anyString(), anyString())).thenAnswer(invocation -> {
                String source = invocation.getArgument(0);
                String target = invocation.getArgument(1);
                String value = rightPop(source);
                if (value != null) {
                    leftPush(target, value);
                }
                return value;
            });
            when(operations.index(anyString(), anyLong())).thenAnswer(invocation -> {
                Deque<String> values = lists.get(invocation.getArgument(0));
                return values == null || values.isEmpty() ? null : values.peekFirst();
            });
            when(operations.remove(anyString(), anyLong(), anyString())).thenAnswer(invocation -> {
                if (failRemove) {
                    throw new RedisConnectionFailureException("entry redis remove failed");
                }
                return remove(invocation.getArgument(0), invocation.getArgument(2));
            });
            when(operations.size(anyString())).thenAnswer(invocation -> size(invocation.getArgument(0)));
        }

        private StringRedisTemplate template() {
            return template;
        }

        private void failLeftPushAll() {
            failLeftPushAll = true;
        }

        private void failRemove() {
            failRemove = true;
        }

        private void recoverRemove() {
            failRemove = false;
        }

        private String peekFirst(String key) {
            Deque<String> values = lists.get(key);
            return values == null ? null : values.peekFirst();
        }

        private void leftPush(String key, String value) {
            lists.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addFirst(value);
        }

        private String rightPop(String key) {
            Deque<String> values = lists.get(key);
            return values == null ? null : values.pollLast();
        }

        private long remove(String key, String value) {
            Deque<String> values = lists.get(key);
            if (values == null) {
                return 0L;
            }
            return values.removeFirstOccurrence(value) ? 1L : 0L;
        }

        private long size(String key) {
            Deque<String> values = lists.get(key);
            return values == null ? 0L : values.size();
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}

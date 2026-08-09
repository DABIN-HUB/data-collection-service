package com.wangbin.collector.core.cache.ingress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessContext;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessPipeline;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessStage;
import com.wangbin.collector.core.cache.aspect.TelemetryStageType;
import com.wangbin.collector.core.cache.config.TelemetryExecutorNames;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.processor.ProcessResult;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelemetryIngressBufferTest {

    private ThreadPoolExecutor historyExecutor;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (historyExecutor != null) {
            historyExecutor.shutdownNow();
            assertTrue(historyExecutor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectedEntryShouldPersistToRedisWithoutRunningPipelineOnCaller() {
        FakeRedisLists redis = new FakeRedisLists();
        CountingStage stage = new CountingStage(TelemetryStageType.CACHE);
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
        CountingStage stage = new CountingStage(TelemetryStageType.CACHE);
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
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(new CountingStage(TelemetryStageType.CACHE))),
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
    void replayShouldInvokePipelineDirectlyAndDispatchAllStages() {
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

        assertEquals(1L, cache.count());
        assertEquals(1L, stream.count());
        assertEquals(1L, history.count());
        assertEquals(1L, report.count());
    }

    @Test
    void replaySuccessButPendingRemoveFailureShouldRemainAtLeastOnce() {
        FakeRedisLists redis = new FakeRedisLists();
        CountingStage stage = new CountingStage(TelemetryStageType.CACHE);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(stage)), properties(10));
        buffer.defer(List.of(context("dev-remove", "p1")), new java.util.concurrent.RejectedExecutionException("entry full"));
        redis.failRemove();

        buffer.replay();

        assertEquals(1L, stage.count());
        assertEquals(1L, buffer.metrics().pendingRemoveFailures());
        assertEquals(1L, redis.size("entry:processing"));

        redis.recoverRemove();
        buffer.replay();

        assertEquals(2L, stage.count());
        assertEquals(0L, redis.size("entry:processing"));
    }

    @Test
    void malformedDeferredPayloadShouldNotBlockFollowingMessages() {
        FakeRedisLists redis = new FakeRedisLists();
        CountingStage stage = new CountingStage(TelemetryStageType.CACHE);
        TelemetryIngressBufferProperties properties = properties(10);
        properties.setReplayBatchSize(2);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(stage)), properties);
        buffer.defer(List.of(context("dev-poison", "normal")), new java.util.concurrent.RejectedExecutionException("entry full"));
        redis.leftPush(properties.getProcessingKey(), "{not-json");

        buffer.replay();

        assertEquals(1L, stage.count());
        assertEquals(1L, redis.size(properties.getDeadLetterKey()));
        assertEquals(1L, buffer.metrics().poisonDeadLetterItems());
    }

    @Test
    void restartShouldRecoverRedisPending() {
        FakeRedisLists redis = new FakeRedisLists();
        TelemetryIngressBufferProperties properties = properties(10);
        RedisTelemetryIngressBuffer first = buffer(redis, pipeline(List.of(new CountingStage(TelemetryStageType.CACHE))),
                properties);
        first.defer(List.of(context("dev-restart", "p1")), new java.util.concurrent.RejectedExecutionException("entry full"));
        CountingStage restartedStage = new CountingStage(TelemetryStageType.CACHE);
        RedisTelemetryIngressBuffer restarted = buffer(redis, pipeline(List.of(restartedStage)), properties);

        restarted.replay();

        assertEquals(1L, restartedStage.count());
        assertEquals(0L, redis.size(properties.getPendingKey()));
        assertEquals(0L, redis.size(properties.getProcessingKey()));
    }

    @Test
    void shutdownShouldReplayLocalFallbackOnceWithoutThreadLeak() {
        FakeRedisLists redis = new FakeRedisLists();
        redis.failLeftPushAll();
        CountingStage stage = new CountingStage(TelemetryStageType.CACHE);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline(List.of(stage)), properties(10));
        buffer.defer(List.of(
                context("dev-shutdown", "p1"),
                context("dev-shutdown", "p2")), new java.util.concurrent.RejectedExecutionException("entry full"));

        buffer.shutdown();

        assertEquals(2L, stage.count());
        assertEquals(0L, buffer.metrics().localPending());
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
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(
                List.of(stage),
                historyExecutor,
                historyExecutor,
                historyExecutor,
                historyExecutor);
        RedisTelemetryIngressBuffer buffer = buffer(redis, pipeline, properties(10));

        buffer.defer(List.of(
                context("dev-entry-history", "p1"),
                context("dev-entry-history", "p2"),
                context("dev-entry-history", "p3"),
                context("dev-entry-history", "p4")), new java.util.concurrent.RejectedExecutionException("entry full"));
        buffer.replay();

        assertTrue(stage.awaitEntered());
        assertTrue(rejected.count() > 0L);
        assertEquals(rejected.count(), stage.deferAttempts());
        assertEquals(0, buffer.metrics().localPending());

        stage.release();
        waitUntil(() -> historyExecutor.getQueue().isEmpty() && historyExecutor.getActiveCount() == 0);
    }

    private RedisTelemetryIngressBuffer buffer(FakeRedisLists redis,
                                               TelemetryPostProcessPipeline pipeline,
                                               TelemetryIngressBufferProperties properties) {
        return new RedisTelemetryIngressBuffer(
                redis.template(),
                new ObjectMapper(),
                properties,
                pipeline,
                new CollectionTaskGuard());
    }

    private TelemetryPostProcessPipeline pipeline(List<TelemetryPostProcessStage> stages) {
        return new TelemetryPostProcessPipeline(
                stages,
                Runnable::run,
                Runnable::run,
                Runnable::run,
                Runnable::run);
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
        return new TelemetryPostProcessContext(
                deviceId,
                point(deviceId, pointId),
                ProcessResult.success(1, 1, "ok"),
                1,
                System.currentTimeMillis(),
                null);
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

        private long count() {
            return count.sum();
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

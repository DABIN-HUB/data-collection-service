package com.wangbin.collector.core.cache.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.service.TelemetryStreamService;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

import java.util.HashMap;
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

class RedisFailureIsolationTest {

    private ThreadPoolExecutor entryExecutor;
    private ThreadPoolExecutor streamExecutor;
    private Logger pipelineLogger;
    private Level originalPipelineLogLevel;

    @BeforeEach
    void muteExpectedFailureLogs() {
        pipelineLogger = (Logger) LoggerFactory.getLogger(TelemetryPostProcessPipeline.class);
        originalPipelineLogLevel = pipelineLogger.getLevel();
        pipelineLogger.setLevel(Level.OFF);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (pipelineLogger != null) {
            pipelineLogger.setLevel(originalPipelineLogLevel);
        }
        shutdown(entryExecutor);
        shutdown(streamExecutor);
    }

    @Test
    void redisWriteFailureAndRecoveryShouldDrainTelemetryQueues() throws Exception {
        entryExecutor = fixedPool("redis-entry", 2, 128);
        streamExecutor = fixedPool("redis-stream", 2, 128);
        FlakyTelemetryStreamService streamService = new FlakyTelemetryStreamService(6);
        CollectorDataPostProcessor processor = processor(streamService);
        CollectionTaskGuard guard = new CollectionTaskGuard();
        String deviceId = "redis-recovery-dev";
        long generation = guard.activateNextGeneration(deviceId);
        List<DataPoint> points = points(deviceId, 3);
        Map<String, Object> values = values(points);

        guard.runWithContext(deviceId, generation, () -> processor.saveBatchAsync(deviceId, points, values, null));
        guard.runWithContext(deviceId, generation, () -> processor.saveBatchAsync(deviceId, points, values, null));
        guard.runWithContext(deviceId, generation, () -> processor.saveBatchAsync(deviceId, points, values, null));

        waitUntil(() -> streamService.attempts() == 9L
                && streamService.successes() == 3L
                && entryExecutor.getQueue().isEmpty()
                && streamExecutor.getQueue().isEmpty()
                && entryExecutor.getActiveCount() == 0
                && streamExecutor.getActiveCount() == 0);

        assertEquals(6L, streamService.failures());
        assertEquals(3L, streamService.successes());
    }

    @Test
    void slowRedisStreamShouldNotBlockTelemetryEntryExecutor() throws Exception {
        entryExecutor = fixedPool("redis-slow-entry", 2, 128);
        streamExecutor = fixedPool("redis-slow-stream", 1, 128);
        BlockingTelemetryStreamService streamService = new BlockingTelemetryStreamService();
        CollectorDataPostProcessor processor = processor(streamService);
        CollectionTaskGuard guard = new CollectionTaskGuard();
        long generationA = guard.activateNextGeneration("redis-slow-a");
        long generationB = guard.activateNextGeneration("redis-slow-b");
        DataPoint pointA = point("redis-slow-a", "p1");
        DataPoint pointB = point("redis-slow-b", "p1");

        guard.runWithContext("redis-slow-a", generationA,
                () -> processor.savePointAsync("redis-slow-a", pointA, 1));
        assertTrue(streamService.awaitEntered());

        guard.runWithContext("redis-slow-b", generationB,
                () -> processor.savePointAsync("redis-slow-b", pointB, 2));
        waitUntil(() -> entryExecutor.getQueue().isEmpty() && entryExecutor.getActiveCount() == 0);
        assertEquals(1, streamExecutor.getActiveCount());
        assertTrue(streamExecutor.getQueue().size() > 0);

        streamService.release();
        waitUntil(() -> streamService.attempts() == 2L
                && streamExecutor.getQueue().isEmpty()
                && streamExecutor.getActiveCount() == 0);
    }

    @Test
    void redisFailureStormShouldDrainQueuesWithoutThreadGrowth() throws Exception {
        entryExecutor = fixedPool("redis-storm-entry", 4, 512);
        streamExecutor = fixedPool("redis-storm-stream", 4, 512);
        AlwaysFailingTelemetryStreamService streamService = new AlwaysFailingTelemetryStreamService();
        CollectorDataPostProcessor processor = processor(streamService);
        CollectionTaskGuard guard = new CollectionTaskGuard();
        int deviceCount = 20;
        int pointsPerDevice = 1;

        for (int i = 0; i < deviceCount; i++) {
            String deviceId = "redis-storm-" + i;
            long generation = guard.activateNextGeneration(deviceId);
            List<DataPoint> points = points(deviceId, pointsPerDevice);
            guard.runWithContext(deviceId, generation,
                    () -> processor.saveBatchAsync(deviceId, points, values(points), null));
        }

        long expectedAttempts = deviceCount * (long) pointsPerDevice;
        waitUntil(() -> streamService.attempts() == expectedAttempts
                && entryExecutor.getQueue().isEmpty()
                && streamExecutor.getQueue().isEmpty()
                && entryExecutor.getActiveCount() == 0
                && streamExecutor.getActiveCount() == 0);

        assertTrue(entryExecutor.getPoolSize() <= 4);
        assertTrue(streamExecutor.getPoolSize() <= 4);
        assertEquals(expectedAttempts, streamService.failures());
    }

    @Test
    void shutdownShouldTerminateWhenRedisStageIsBlocked() throws Exception {
        entryExecutor = fixedPool("redis-shutdown-entry", 1, 32);
        streamExecutor = fixedPool("redis-shutdown-stream", 1, 32);
        BlockingTelemetryStreamService streamService = new BlockingTelemetryStreamService();
        CollectorDataPostProcessor processor = processor(streamService);
        CollectionTaskGuard guard = new CollectionTaskGuard();
        String deviceId = "redis-shutdown-dev";
        long generation = guard.activateNextGeneration(deviceId);

        guard.runWithContext(deviceId, generation,
                () -> processor.savePointAsync(deviceId, point(deviceId, "p1"), 1));
        assertTrue(streamService.awaitEntered());

        entryExecutor.shutdownNow();
        streamExecutor.shutdownNow();

        assertTrue(entryExecutor.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(streamExecutor.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(streamService.interruptedCount() > 0);
    }

    private CollectorDataPostProcessor processor(TelemetryStreamService streamService) {
        TelemetryStreamProperties properties = new TelemetryStreamProperties();
        properties.setEnabled(true);
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(
                List.of(new StreamTelemetryPostProcessStage(streamService, properties)),
                streamExecutor,
                streamExecutor,
                streamExecutor,
                streamExecutor);
        return new CollectorDataPostProcessor(entryExecutor, pipeline, new CollectionTaskGuard());
    }

    private List<DataPoint> points(String deviceId, int count) {
        java.util.ArrayList<DataPoint> points = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(point(deviceId, "p" + i));
        }
        return points;
    }

    private DataPoint point(String deviceId, String pointId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setStatus(1);
        point.setAdditionalConfig(new HashMap<>());
        return point;
    }

    private Map<String, Object> values(List<DataPoint> points) {
        Map<String, Object> values = new HashMap<>();
        for (DataPoint point : points) {
            values.put(point.getPointId(), ProcessResult.success(1, 1));
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
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static final class FlakyTelemetryStreamService implements TelemetryStreamService {
        private final AtomicInteger remainingFailures;
        private final LongAdder attempts = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder successes = new LongAdder();

        private FlakyTelemetryStreamService(int failuresBeforeSuccess) {
            this.remainingFailures = new AtomicInteger(failuresBeforeSuccess);
        }

        @Override
        public void append(String deviceId, DataPoint point, ProcessResult processResult) {
            attempts.increment();
            if (remainingFailures.getAndDecrement() > 0) {
                failures.increment();
                throw new RedisConnectionFailureException("redis temporarily unavailable");
            }
            successes.increment();
        }

        private long attempts() {
            return attempts.sum();
        }

        private long failures() {
            return failures.sum();
        }

        private long successes() {
            return successes.sum();
        }
    }

    private static final class AlwaysFailingTelemetryStreamService implements TelemetryStreamService {
        private final LongAdder attempts = new LongAdder();
        private final LongAdder failures = new LongAdder();

        @Override
        public void append(String deviceId, DataPoint point, ProcessResult processResult) {
            attempts.increment();
            failures.increment();
            throw new RedisSystemException("redis command failed",
                    new RedisConnectionFailureException("redis unavailable"));
        }

        private long attempts() {
            return attempts.sum();
        }

        private long failures() {
            return failures.sum();
        }
    }

    private static final class BlockingTelemetryStreamService implements TelemetryStreamService {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final LongAdder attempts = new LongAdder();
        private final LongAdder interrupted = new LongAdder();

        @Override
        public void append(String deviceId, DataPoint point, ProcessResult processResult) {
            attempts.increment();
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.increment();
                Thread.currentThread().interrupt();
            }
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }

        private long attempts() {
            return attempts.sum();
        }

        private long interruptedCount() {
            return interrupted.sum();
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}

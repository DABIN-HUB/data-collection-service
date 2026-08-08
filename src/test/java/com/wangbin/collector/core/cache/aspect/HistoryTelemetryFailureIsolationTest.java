package com.wangbin.collector.core.cache.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.port.HistoryTelemetrySink;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryTelemetryFailureIsolationTest {

    private ThreadPoolExecutor entryExecutor;
    private ThreadPoolExecutor historyExecutor;
    private Logger pipelineLogger;
    private Logger postProcessorLogger;
    private Level originalPipelineLogLevel;
    private Level originalPostProcessorLogLevel;

    @BeforeEach
    void muteExpectedFailureLogs() {
        pipelineLogger = (Logger) LoggerFactory.getLogger(TelemetryPostProcessPipeline.class);
        postProcessorLogger = (Logger) LoggerFactory.getLogger(CollectorDataPostProcessor.class);
        originalPipelineLogLevel = pipelineLogger.getLevel();
        originalPostProcessorLogLevel = postProcessorLogger.getLevel();
        pipelineLogger.setLevel(Level.OFF);
        postProcessorLogger.setLevel(Level.OFF);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pipelineLogger.setLevel(originalPipelineLogLevel);
        postProcessorLogger.setLevel(originalPostProcessorLogLevel);
        shutdown(entryExecutor);
        shutdown(historyExecutor);
    }

    @Test
    void historySaveFailureShouldDrainAndAllowNextRound() throws Exception {
        entryExecutor = fixedPool("history-entry", 1, 128);
        historyExecutor = fixedPool("history-stage", 1, 128);
        FailingThenHealthyHistorySink sink = new FailingThenHealthyHistorySink();
        CollectionTaskGuard guard = new CollectionTaskGuard();
        CollectorDataPostProcessor processor = processor(sink, guard);
        String deviceId = "history-fail-dev";
        long generation = guard.activateNextGeneration(deviceId);

        guard.runWithContext(deviceId, generation,
                () -> processor.savePointAsync(deviceId, point(deviceId, "p1"), ProcessResult.success(1, 1)));
        guard.runWithContext(deviceId, generation,
                () -> processor.savePointAsync(deviceId, point(deviceId, "p2"), ProcessResult.success(2, 2)));

        waitUntil(() -> sink.attempts() == 2L
                && sink.successes() == 1L
                && entryExecutor.getQueue().isEmpty()
                && historyExecutor.getQueue().isEmpty()
                && entryExecutor.getActiveCount() == 0
                && historyExecutor.getActiveCount() == 0);

        assertEquals(1L, sink.failures());
        assertEquals(1L, sink.successes());
    }

    @Test
    void historyEnabledFailureShouldNotKillTelemetryEntryExecutor() throws Exception {
        entryExecutor = fixedPool("history-enabled-entry", 1, 128);
        historyExecutor = fixedPool("history-enabled-stage", 1, 128);
        EnabledFailingThenHealthySink sink = new EnabledFailingThenHealthySink();
        CollectionTaskGuard guard = new CollectionTaskGuard();
        CollectorDataPostProcessor processor = processor(sink, guard);
        String deviceId = "history-enabled-dev";
        long generation = guard.activateNextGeneration(deviceId);

        guard.runWithContext(deviceId, generation,
                () -> processor.savePointAsync(deviceId, point(deviceId, "p1"), ProcessResult.success(1, 1)));
        guard.runWithContext(deviceId, generation,
                () -> processor.savePointAsync(deviceId, point(deviceId, "p2"), ProcessResult.success(2, 2)));

        waitUntil(() -> sink.enabledAttempts() == 2L
                && sink.saveAttempts() == 1L
                && entryExecutor.getQueue().isEmpty()
                && historyExecutor.getQueue().isEmpty()
                && entryExecutor.getActiveCount() == 0
                && historyExecutor.getActiveCount() == 0);

        assertEquals(1L, sink.enabledFailures());
        assertEquals(1L, sink.saveAttempts());
    }

    @Test
    void slowHistoryStageShouldNotBlockTelemetryEntryExecutor() throws Exception {
        entryExecutor = fixedPool("history-slow-entry", 2, 128);
        historyExecutor = fixedPool("history-slow-stage", 1, 128);
        BlockingHistorySink sink = new BlockingHistorySink();
        CollectionTaskGuard guard = new CollectionTaskGuard();
        CollectorDataPostProcessor processor = processor(sink, guard);
        long generationA = guard.activateNextGeneration("history-slow-a");
        long generationB = guard.activateNextGeneration("history-slow-b");

        guard.runWithContext("history-slow-a", generationA,
                () -> processor.savePointAsync("history-slow-a", point("history-slow-a", "p1"), 1));
        assertTrue(sink.awaitEntered());

        guard.runWithContext("history-slow-b", generationB,
                () -> processor.savePointAsync("history-slow-b", point("history-slow-b", "p1"), 2));
        waitUntil(() -> entryExecutor.getQueue().isEmpty() && entryExecutor.getActiveCount() == 0);

        assertEquals(1, historyExecutor.getActiveCount());
        assertTrue(historyExecutor.getQueue().size() > 0);

        sink.release();
        waitUntil(() -> sink.attempts() == 2L
                && historyExecutor.getQueue().isEmpty()
                && historyExecutor.getActiveCount() == 0);
    }

    private CollectorDataPostProcessor processor(HistoryTelemetrySink sink, CollectionTaskGuard guard) {
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(
                List.of(new HistoryTelemetryPostProcessStage(sink)),
                historyExecutor,
                historyExecutor,
                historyExecutor,
                historyExecutor);
        return new CollectorDataPostProcessor(entryExecutor, pipeline, guard);
    }

    private DataPoint point(String deviceId, String pointId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setStatus(1);
        point.setAdditionalConfig(java.util.Map.of("historyEnabled", true));
        return point;
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
        long deadline = System.currentTimeMillis() + 3_000L;
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

    private static final class FailingThenHealthyHistorySink implements HistoryTelemetrySink {
        private final AtomicInteger attempts = new AtomicInteger();
        private final LongAdder failures = new LongAdder();
        private final LongAdder successes = new LongAdder();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void savePoint(String deviceId, DataPoint point, ProcessResult processResult) {
            if (attempts.incrementAndGet() == 1) {
                failures.increment();
                throw new DataAccessResourceFailureException("TDengine write failed");
            }
            successes.increment();
        }

        private long attempts() {
            return attempts.get();
        }

        private long failures() {
            return failures.sum();
        }

        private long successes() {
            return successes.sum();
        }
    }

    private static final class EnabledFailingThenHealthySink implements HistoryTelemetrySink {
        private final AtomicInteger enabledAttempts = new AtomicInteger();
        private final LongAdder enabledFailures = new LongAdder();
        private final LongAdder saveAttempts = new LongAdder();

        @Override
        public boolean isEnabled() {
            if (enabledAttempts.incrementAndGet() == 1) {
                enabledFailures.increment();
                throw new DataAccessResourceFailureException("TDengine health check failed");
            }
            return true;
        }

        @Override
        public void savePoint(String deviceId, DataPoint point, ProcessResult processResult) {
            saveAttempts.increment();
        }

        private long enabledAttempts() {
            return enabledAttempts.get();
        }

        private long enabledFailures() {
            return enabledFailures.sum();
        }

        private long saveAttempts() {
            return saveAttempts.sum();
        }
    }

    private static final class BlockingHistorySink implements HistoryTelemetrySink {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final LongAdder attempts = new LongAdder();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void savePoint(String deviceId, DataPoint point, ProcessResult processResult) {
            attempts.increment();
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
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
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}

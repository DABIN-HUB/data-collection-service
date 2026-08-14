package com.wangbin.collector.core.cache.config;

import com.wangbin.collector.common.config.ObservedRejectedExecutionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryStreamExecutorConfigTest {

    @Test
    void streamExecutorShouldUseConfiguredCoreWorkersBeforeQueuePressure() throws Exception {
        ThreadPoolTaskExecutor executor = executor(2, 4, 2000);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int index = 0; index < 4; index++) {
                executor.execute(blockingTask(entered, release));
            }

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            waitUntil(() -> executor.getActiveCount() == 2
                    && executor.getThreadPoolExecutor().getQueue().size() == 2);
            assertEquals(2, executor.getPoolSize());
            assertEquals(2, executor.getActiveCount());
            assertEquals(2, executor.getThreadPoolExecutor().getQueue().size());
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    @Test
    void coreFourMustNotRequireQueueSaturationToActivateWorkers() throws Exception {
        TelemetryExecutorProperties properties = new TelemetryExecutorProperties();
        ThreadPoolTaskExecutor executor = executor(
                properties.getStream().getCoreSize(),
                properties.getStream().getMaxSize(),
                properties.getStream().getQueueCapacity());
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int index = 0; index < 4; index++) {
                executor.execute(blockingTask(entered, release));
            }

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            waitUntil(() -> executor.getActiveCount() == 4
                    && executor.getThreadPoolExecutor().getQueue().isEmpty());
            assertEquals(4, executor.getPoolSize());
            assertEquals(4, executor.getActiveCount());
            assertEquals(0, executor.getThreadPoolExecutor().getQueue().size());
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    @Test
    void streamExecutorQueueMustRemainBounded() throws Exception {
        ThreadPoolTaskExecutor executor = executor(4, 4, 2);
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int index = 0; index < 6; index++) {
                executor.execute(blockingTask(entered, release));
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            waitUntil(() -> executor.getActiveCount() == 4
                    && executor.getThreadPoolExecutor().getQueue().size() == 2);

            assertThrows(RejectedExecutionException.class,
                    () -> executor.execute(blockingTask(new CountDownLatch(0), release)));
            assertEquals(2, executor.getThreadPoolExecutor().getQueue().size());
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    @Test
    void streamRejectAccountingMustRemainCorrect() throws Exception {
        ThreadPoolTaskExecutor executor = executor(1, 1, 1);
        ObservedRejectedExecutionHandler rejected =
                (ObservedRejectedExecutionHandler) executor.getThreadPoolExecutor().getRejectedExecutionHandler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(blockingTask(entered, release));
            executor.execute(blockingTask(new CountDownLatch(0), release));
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            assertThrows(RejectedExecutionException.class,
                    () -> executor.execute(blockingTask(new CountDownLatch(0), release)));
            assertEquals(1L, rejected.getRejectedCount());
        } finally {
            release.countDown();
            shutdown(executor);
        }
    }

    private ThreadPoolTaskExecutor executor(int coreSize, int maxSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("telemetry-stream-test-");
        executor.setRejectedExecutionHandler(new ObservedRejectedExecutionHandler(
                TelemetryExecutorNames.STREAM_STAGE, new ThreadPoolExecutor.AbortPolicy()));
        executor.initialize();
        return executor;
    }

    private Runnable blockingTask(CountDownLatch entered, CountDownLatch release) {
        return () -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private void waitUntil(Condition condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(condition.isSatisfied());
    }

    private void shutdown(ThreadPoolTaskExecutor executor) throws InterruptedException {
        if (executor != null) {
            executor.shutdown();
            assertTrue(executor.getThreadPoolExecutor().awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}

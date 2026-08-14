package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.config.CollectorProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ReconnectCoordinatorTest {

    private CollectionManager collectionManager;
    private CollectorProperties collectorProperties;
    private CollectionTaskGuard collectionTaskGuard;
    private SchedulerRuntimeState runtimeState;
    private final List<ThreadPoolExecutor> executors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        collectionManager = mock(CollectionManager.class);
        collectorProperties = new CollectorProperties();
        collectorProperties.getScheduler().setReconnectBaseDelayMs(100);
        collectorProperties.getScheduler().setReconnectMaxDelayMs(500);
        collectionTaskGuard = new CollectionTaskGuard();
        runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(1, 1000);
    }

    @AfterEach
    void tearDown() {
        executors.forEach(ThreadPoolExecutor::shutdownNow);
    }

    @Test
    void duplicateReconnectShouldBeSuppressed() throws Exception {
        ThreadPoolExecutor executor = fixedPool("reconnect-duplicate", 1, 8);
        ReconnectCoordinator coordinator = coordinator(executor);
        String deviceId = "dev-dup";
        long generation = markRunning(deviceId);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        }).when(collectionManager).reconnectDevice(deviceId);

        coordinator.scheduleIfNeeded(deviceId, generation);
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        coordinator.scheduleIfNeeded(deviceId, generation);
        release.countDown();
        waitUntil(() -> !coordinator.isReconnecting(deviceId));

        assertEquals(1L, coordinator.getAttemptCount());
        verify(collectionManager).reconnectDevice(deviceId);
    }

    @Test
    void exponentialBackoffShouldUseConfiguredBounds() {
        ReconnectCoordinator coordinator = coordinator(fixedPool("reconnect-backoff", 1, 8));

        assertEquals(100L, coordinator.computeReconnectDelayMs(1));
        assertEquals(200L, coordinator.computeReconnectDelayMs(2));
        assertEquals(400L, coordinator.computeReconnectDelayMs(3));
        assertEquals(500L, coordinator.computeReconnectDelayMs(4));
    }

    @Test
    void staleGenerationReconnectShouldBeRejectedBeforeExecution() throws Exception {
        ThreadPoolExecutor executor = fixedPool("reconnect-stale", 1, 8);
        CountDownLatch blocker = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                blocker.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ReconnectCoordinator coordinator = coordinator(executor);
        String deviceId = "dev-stale";
        long oldGeneration = markRunning(deviceId);

        coordinator.scheduleIfNeeded(deviceId, oldGeneration);
        runtimeState.markRunning(deviceId, collectionTaskGuard.activateNextGeneration(deviceId));
        blocker.countDown();
        waitUntil(() -> !coordinator.isReconnecting(deviceId));

        verify(collectionManager, never()).reconnectDevice(deviceId);
    }

    @Test
    void successfulReconnectShouldResetFailureState() throws Exception {
        ReconnectCoordinator coordinator = coordinator(fixedPool("reconnect-reset", 1, 8));
        String deviceId = "dev-reset";
        long generation = markRunning(deviceId);
        doThrow(new RuntimeException("fail"))
                .doNothing()
                .when(collectionManager).reconnectDevice(deviceId);

        coordinator.scheduleIfNeeded(deviceId, generation);
        waitUntil(() -> coordinator.getFailureCount() == 1L);
        long nextRetryAt = coordinator.getNextRetryAt(deviceId);
        long waitMs = Math.max(0L, nextRetryAt - System.currentTimeMillis()) + 30L;
        TimeUnit.MILLISECONDS.sleep(waitMs);
        coordinator.scheduleIfNeeded(deviceId, generation);
        waitUntil(() -> coordinator.getSuccessCount() == 1L);

        assertEquals(0L, coordinator.getNextRetryAt(deviceId));
        assertEquals(1L, coordinator.getFailureCount());
        assertEquals(1L, coordinator.getSuccessCount());
    }

    @Test
    void reconnectExecutorRejectionShouldScheduleBackoff() throws Exception {
        ThreadPoolExecutor executor = rejectingExecutor("reconnect-reject");
        CountDownLatch blocker = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                blocker.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ReconnectCoordinator coordinator = coordinator(executor);
        String deviceId = "dev-reject";
        long generation = markRunning(deviceId);

        coordinator.scheduleIfNeeded(deviceId, generation);
        blocker.countDown();

        assertEquals(1L, coordinator.getAttemptCount());
        assertEquals(1L, coordinator.getFailureCount());
        assertTrue(coordinator.getNextRetryAt(deviceId) > System.currentTimeMillis());
    }

    @Test
    void oldGenerationReconnectAfterStopRestartMustNotPolluteCurrentDevice() throws Exception {
        ReconnectCoordinator coordinator = coordinator(fixedPool("reconnect-restart", 1, 8));
        String deviceId = "dev-restart";
        long oldGeneration = markRunning(deviceId);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        }).when(collectionManager).reconnectDevice(deviceId);

        coordinator.scheduleIfNeeded(deviceId, oldGeneration);
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        runtimeState.removeDevice(deviceId);
        collectionTaskGuard.clearDevice(deviceId);
        runtimeState.markRunning(deviceId, collectionTaskGuard.activateNextGeneration(deviceId));
        release.countDown();
        waitUntil(() -> !coordinator.isReconnecting(deviceId));

        verify(collectionManager).disconnectDevice(deviceId);
        assertEquals(0L, coordinator.getSuccessCount());
    }

    private ReconnectCoordinator coordinator(ThreadPoolExecutor executor) {
        executors.add(executor);
        return new ReconnectCoordinator(
                collectionManager,
                collectorProperties,
                collectionTaskGuard,
                runtimeState,
                executor);
    }

    private long markRunning(String deviceId) {
        long generation = collectionTaskGuard.activateNextGeneration(deviceId);
        runtimeState.markRunning(deviceId, generation);
        return generation;
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

    private ThreadPoolExecutor rejectingExecutor(String namePrefix) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    thread.setName(namePrefix + "-" + thread.getId());
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executors.add(executor);
        return executor;
    }

    private void waitUntil(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(condition.isSatisfied());
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}

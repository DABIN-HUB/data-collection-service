package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerRuntimeStateTest {

    private SchedulerRuntimeState runtimeState;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(1, 1000);
        executorService = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("runtime-state-test-" + thread.getId());
            return thread;
        });
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void activeDeviceIdsShouldIncludeStartingAndRunningAsDefensiveSnapshot() {
        runtimeState.markStartingIfNotActive("dev-starting");
        runtimeState.markStartingGeneration("dev-starting", 1L);
        runtimeState.markRunning("dev-running", 2L);

        Set<String> activeDeviceIds = runtimeState.getActiveDeviceIds();

        assertTrue(activeDeviceIds.contains("dev-starting"));
        assertTrue(activeDeviceIds.contains("dev-running"));
        assertThrows(UnsupportedOperationException.class, () -> activeDeviceIds.add("dev-extra"));
        assertFalse(runtimeState.getActiveDeviceIds().contains("dev-extra"));
    }

    @Test
    void commitRunningShouldRequireMatchingStartingGeneration() {
        String deviceId = "dev-generation";
        runtimeState.markStartingIfNotActive(deviceId);
        runtimeState.markStartingGeneration(deviceId, 1L);

        boolean committed = runtimeState.commitRunning(deviceId, 2L, List.of(task(deviceId, 2L)));

        assertFalse(committed);
        assertFalse(runtimeState.isRunning(deviceId));
        assertTrue(runtimeState.isStartingGeneration(deviceId, 1L));
    }

    @Test
    void semanticStateOperationsShouldNotBlockIndependentExternalWork() throws Exception {
        CountDownLatch externalActionStarted = new CountDownLatch(1);
        CountDownLatch releaseExternalAction = new CountDownLatch(1);
        CompletableFuture<Void> blockedExternalAction = CompletableFuture.runAsync(() -> {
            runtimeState.markStartingIfNotActive("dev-blocked");
            externalActionStarted.countDown();
            await(releaseExternalAction);
        }, executorService);

        assertTrue(externalActionStarted.await(1, TimeUnit.SECONDS));
        CompletableFuture<Boolean> independentStateChange = CompletableFuture.supplyAsync(() -> {
            boolean marked = runtimeState.markStartingIfNotActive("dev-independent");
            runtimeState.markStartingGeneration("dev-independent", 2L);
            return marked && runtimeState.getActiveDeviceIds().contains("dev-independent");
        }, executorService);

        assertTrue(independentStateChange.get(1, TimeUnit.SECONDS));
        releaseExternalAction.countDown();
        blockedExternalAction.get(1, TimeUnit.SECONDS);
    }

    private DeviceBatchTask task(String deviceId, long generation) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId("p1");
        return new DeviceBatchTask(deviceId, List.of(point), 0, generation, runtimeState.getTimeSliceRevision());
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.runtime.AcquisitionRuntimeTracker;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceBatchExecutorTest {

    private CollectionManager collectionManager;
    private ConfigManager configManager;
    private CollectedDataProcessor collectedDataProcessor;
    private CollectionTaskGuard collectionTaskGuard;
    private SchedulerRuntimeState runtimeState;
    private ThreadPoolExecutor batchDispatcher;
    private ThreadPoolExecutor asyncCollectorExecutor;
    private ThreadPoolExecutor dataProcessorExecutor;
    private ExecutorService testExecutor;
    private DeviceBatchExecutor batchExecutor;
    private PerformanceMonitor performanceMonitor;
    private CollectionStatistics collectionStatistics;

    @BeforeEach
    void setUp() {
        collectionManager = mock(CollectionManager.class);
        configManager = mock(ConfigManager.class);
        collectedDataProcessor = mock(CollectedDataProcessor.class);
        collectionTaskGuard = new CollectionTaskGuard();
        runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(1, 1000);
        batchDispatcher = fixedPool("batch-test", 2);
        asyncCollectorExecutor = fixedPool("collect-test", 2);
        dataProcessorExecutor = fixedPool("process-test");
        testExecutor = Executors.newSingleThreadExecutor();
        CollectorProperties collectorProperties = new CollectorProperties();
        collectorProperties.getScheduler().setCollectTimeoutMs(100);
        performanceMonitor = spy(new PerformanceMonitor());
        collectionStatistics = mock(CollectionStatistics.class);
        batchExecutor = new DeviceBatchExecutor(
                collectionManager,
                configManager,
                collectionStatistics,
                collectorProperties,
                collectedDataProcessor,
                collectionTaskGuard,
                runtimeState,
                performanceMonitor,
                mock(ReconnectCoordinator.class),
                batchDispatcher,
                asyncCollectorExecutor,
                dataProcessorExecutor);
    }

    @AfterEach
    void tearDown() {
        batchDispatcher.shutdownNow();
        asyncCollectorExecutor.shutdownNow();
        dataProcessorExecutor.shutdownNow();
        testExecutor.shutdownNow();
    }

    @Test
    void collectTimeoutShouldCancelFuture() throws Exception {
        String deviceId = "dev-timeout";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection(deviceId));
        when(collectionManager.readPoints(eq(deviceId), anyList())).thenAnswer(invocation -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            }
            return Map.of("p1", 1);
        });

        batchExecutor.processDeviceBatch(new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                generation,
                runtimeState.getTimeSliceRevision()));

        waitUntil(interrupted::get);
        assertTrue(interrupted.get());
        verify(collectedDataProcessor, never()).process(eq(deviceId), anyList(), eq(Map.of("p1", 1)));
        verify(performanceMonitor).recordBatchOutcome(eq(deviceId), eq(generation), eq(0), eq(1),
                anyLong(), eq(0L));
        assertEquals(1L, performanceMonitor.devicePerformance.get(deviceId).failedBatches.get());
    }

    @Test
    void stoppedDeviceInflightResultMustNotProcess() throws Exception {
        String deviceId = "dev-stop";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        CompletableFuture<Map<String, Object>> gate = new CompletableFuture<>();
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection(deviceId));
        when(collectionManager.readPoints(eq(deviceId), anyList()))
                .thenAnswer(invocation -> gate.get(2, TimeUnit.SECONDS));
        DeviceBatchTask task = new DeviceBatchTask(deviceId, List.of(point), 0, generation, runtimeState.getTimeSliceRevision());

        CompletableFuture<Void> runFuture = CompletableFuture.runAsync(
                () -> batchExecutor.processDeviceBatch(task),
                testExecutor
        );
        TimeUnit.MILLISECONDS.sleep(100);
        runtimeState.removeDeviceTasks(deviceId);
        batchExecutor.cancelDeviceInFlightTasks(deviceId);
        runtimeState.removeDevice(deviceId);
        collectionTaskGuard.clearDevice(deviceId);
        gate.complete(Map.of("p1", 1));
        runFuture.get(2, TimeUnit.SECONDS);

        verify(collectedDataProcessor, never()).process(eq(deviceId), anyList(), eq(Map.of("p1", 1)));
    }

    @Test
    void staleGenerationResultMustNotProcess() {
        String deviceId = "dev-stale";
        DataPoint point = point(deviceId, "p1");
        long oldGeneration = markRunning(deviceId);
        DeviceBatchTask staleTask = new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                oldGeneration,
                runtimeState.getTimeSliceRevision());
        runtimeState.removeDevice(deviceId);
        collectionTaskGuard.clearDevice(deviceId);
        runtimeState.markRunning(deviceId, collectionTaskGuard.activateNextGeneration(deviceId));

        batchExecutor.processDeviceBatch(staleTask);

        verify(collectionManager, never()).readPoints(eq(deviceId), anyList());
        verify(collectedDataProcessor, never()).process(eq(deviceId), anyList(), eq(Map.of("p1", 1)));
    }

    @Test
    void inFlightCollectionMustNotDropResultOnlyBecauseRevisionChanged() throws Exception {
        String deviceId = "dev-revision-inflight";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        CountDownLatch readStarted = new CountDownLatch(1);
        CompletableFuture<Map<String, Object>> gate = new CompletableFuture<>();
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection(deviceId));
        when(collectionManager.readPoints(eq(deviceId), anyList())).thenAnswer(invocation -> {
            readStarted.countDown();
            Map<String, Object> values = gate.get(2, TimeUnit.SECONDS);
            CollectionProcessingReceipt receipt = collectionTaskGuard.captureCurrentContext().receipt();
            receipt.claim("p1");
            receipt.complete("p1", true, null, System.currentTimeMillis());
            return values;
        });
        DeviceBatchTask task = new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                generation,
                runtimeState.getTimeSliceRevision());

        CompletableFuture<Void> dispatchFuture = batchExecutor.submit(task, List.of(point));
        assertNotNull(dispatchFuture);
        assertTrue(readStarted.await(1, TimeUnit.SECONDS));
        runtimeState.updateTimeSliceConfig(2, 1_000);
        gate.complete(Map.of("p1", 1));
        dispatchFuture.get(2, TimeUnit.SECONDS);

        verify(collectedDataProcessor, timeout(1_000)).process(eq(deviceId), anyList(), eq(Map.of("p1", 1)));
    }

    @Test
    void inFlightCollectionMustStillDropResultWhenGenerationChanged() throws Exception {
        String deviceId = "dev-generation-inflight";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        CountDownLatch readStarted = new CountDownLatch(1);
        CompletableFuture<Map<String, Object>> gate = new CompletableFuture<>();
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection(deviceId));
        when(collectionManager.readPoints(eq(deviceId), anyList())).thenAnswer(invocation -> {
            readStarted.countDown();
            return gate.get(2, TimeUnit.SECONDS);
        });
        DeviceBatchTask task = new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                generation,
                runtimeState.getTimeSliceRevision());

        CompletableFuture<Void> dispatchFuture = batchExecutor.submit(task, List.of(point));
        assertNotNull(dispatchFuture);
        assertTrue(readStarted.await(1, TimeUnit.SECONDS));
        runtimeState.removeDevice(deviceId);
        collectionTaskGuard.clearDevice(deviceId);
        runtimeState.markRunning(deviceId, collectionTaskGuard.activateNextGeneration(deviceId));
        gate.complete(Map.of("p1", 1));
        dispatchFuture.get(2, TimeUnit.SECONDS);

        verify(collectedDataProcessor, never()).process(eq(deviceId), anyList(), eq(Map.of("p1", 1)));
        assertEquals(0, runtimeState.getInFlightPointScheduleSizeForTest());
    }

    @Test
    void staleRevisionTaskMustNotBeNewlySubmittedAfterReplan() {
        String deviceId = "dev-stale-revision";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        DeviceBatchTask staleTask = new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                generation,
                runtimeState.getTimeSliceRevision());

        runtimeState.updateTimeSliceConfig(2, 1_000);
        CompletableFuture<Void> dispatchFuture = batchExecutor.submit(staleTask, List.of(point));

        assertNull(dispatchFuture);
        verify(collectionManager, never()).readPoints(eq(deviceId), anyList());
    }

    @Test
    void replanBetweenRevisionCheckAndPointClaimMustNotDoubleDispatch() throws Exception {
        String deviceId = "dev-replan-race";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        AtomicInteger readCalls = new AtomicInteger(0);
        AtomicInteger activeReads = new AtomicInteger(0);
        AtomicInteger maxActiveReads = new AtomicInteger(0);
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection(deviceId));
        when(collectionManager.readPoints(eq(deviceId), anyList())).thenAnswer(invocation -> {
            int active = activeReads.incrementAndGet();
            maxActiveReads.updateAndGet(previous -> Math.max(previous, active));
            readCalls.incrementAndGet();
            readStarted.countDown();
            releaseRead.await(2, TimeUnit.SECONDS);
            activeReads.decrementAndGet();
            return Map.of("p1", 1);
        });
        DeviceBatchTask oldTask = new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                generation,
                runtimeState.getTimeSliceRevision());
        AtomicBoolean replanDone = new AtomicBoolean(false);
        AtomicReference<CompletableFuture<Void>> newDispatchFuture = new AtomicReference<>();
        DeviceBatchExecutor racingExecutor = new DeviceBatchExecutor(
                collectionManager,
                configManager,
                mock(CollectionStatistics.class),
                new CollectorProperties(),
                collectedDataProcessor,
                collectionTaskGuard,
                runtimeState,
                new PerformanceMonitor(),
                mock(ReconnectCoordinator.class),
                batchDispatcher,
                asyncCollectorExecutor,
                dataProcessorExecutor) {
            @Override
            boolean isBatchTaskDispatchable(DeviceBatchTask batchTask) {
                boolean dispatchable = super.isBatchTaskDispatchable(batchTask);
                if (dispatchable && batchTask == oldTask && replanDone.compareAndSet(false, true)) {
                    runtimeState.updateTimeSliceConfig(2, 1_000);
                    DeviceBatchTask newTask = new DeviceBatchTask(
                            deviceId,
                            List.of(point),
                            0,
                            generation,
                            runtimeState.getTimeSliceRevision());
                    CompletableFuture<Void> future = submit(newTask, List.of(point));
                    newDispatchFuture.set(future);
                    assertNotNull(future);
                    try {
                        assertTrue(readStarted.await(1, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                return dispatchable;
            }
        };

        CompletableFuture<Void> oldDispatchFuture = racingExecutor.submit(oldTask, List.of(point));

        assertNull(oldDispatchFuture);
        assertNotNull(newDispatchFuture.get());
        releaseRead.countDown();
        newDispatchFuture.get().get(2, TimeUnit.SECONDS);
        assertEquals(1, readCalls.get());
        assertEquals(1, maxActiveReads.get());
        assertEquals(0, runtimeState.getInFlightPointScheduleSizeForTest());
    }

    @Test
    void twoDifferentRevisionTasksMustNotClaimSamePointConcurrently() {
        String deviceId = "dev-two-revisions";
        AtomicLongNanos now = new AtomicLongNanos();
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        DeviceBatchTask oldTask = task(deviceId, generation, runtimeState.getTimeSliceRevision(), List.of(point), 1_000L, now);

        SchedulerRuntimeState.PointDispatchClaim oldClaim = oldTask.claimDuePoints(runtimeState, List.of(point));
        runtimeState.updateTimeSliceConfig(2, 1_000);
        DeviceBatchTask newTask = task(deviceId, generation, runtimeState.getTimeSliceRevision(), List.of(point), 1_000L, now);
        SchedulerRuntimeState.PointDispatchClaim newClaim = newTask.claimDuePoints(runtimeState, List.of(point));

        assertFalse(oldClaim.isEmpty());
        assertTrue(newClaim.isEmpty());
        assertEquals(1, runtimeState.getInFlightPointScheduleSizeForTest());
        runtimeState.completeClaim(oldClaim);
        assertEquals(0, runtimeState.getInFlightPointScheduleSizeForTest());
    }

    @Test
    void failedOldDispatchRollbackMustNotRemoveNewOwner() {
        String deviceId = "dev-owner-rollback";
        AtomicLongNanos now = new AtomicLongNanos();
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        DeviceBatchTask task = task(deviceId, generation, runtimeState.getTimeSliceRevision(), List.of(point), 1_000L, now);
        SchedulerRuntimeState.PointDispatchClaim oldClaim = task.claimDuePoints(runtimeState, List.of(point));
        runtimeState.completeClaim(oldClaim);
        now.setMillis(1_000);
        SchedulerRuntimeState.PointDispatchClaim newClaim = task.claimDuePoints(runtimeState, List.of(point));

        runtimeState.rollbackClaim(oldClaim);
        SchedulerRuntimeState.PointDispatchClaim thirdClaim = task.claimDuePoints(runtimeState, List.of(point));

        assertFalse(newClaim.isEmpty());
        assertTrue(thirdClaim.isEmpty());
        assertEquals(1, runtimeState.getInFlightPointScheduleSizeForTest());
        runtimeState.completeClaim(newClaim);
    }

    @Test
    void executorRejectedClaimMustRestorePreviousCadence() {
        String deviceId = "dev-reject-rollback";
        AtomicLongNanos now = new AtomicLongNanos();
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        DeviceBatchTask task = task(deviceId, generation, runtimeState.getTimeSliceRevision(), List.of(point), 10_000L, now);
        SchedulerRuntimeState.PointDispatchClaim firstClaim = task.claimDuePoints(runtimeState, List.of(point));
        runtimeState.completeClaim(firstClaim);
        now.setMillis(10_000);
        ThreadPoolExecutor rejectedDispatcher = fixedPool("reject-dispatch", 1);
        rejectedDispatcher.shutdownNow();
        DeviceBatchExecutor rejectingExecutor = new DeviceBatchExecutor(
                collectionManager,
                configManager,
                mock(CollectionStatistics.class),
                new CollectorProperties(),
                collectedDataProcessor,
                collectionTaskGuard,
                runtimeState,
                new PerformanceMonitor(),
                mock(ReconnectCoordinator.class),
                rejectedDispatcher,
                asyncCollectorExecutor,
                dataProcessorExecutor);

        CompletableFuture<Void> dispatchFuture = rejectingExecutor.submit(task, List.of(point));
        now.setMillis(5_000);
        SchedulerRuntimeState.PointDispatchClaim probe = task.claimDuePoints(runtimeState, List.of(point));

        assertNull(dispatchFuture);
        assertTrue(probe.isEmpty());
        assertEquals(0, runtimeState.getInFlightPointScheduleSizeForTest());
        assertEquals(1L, rejectingExecutor.getBatchDispatchRejectedCount());
    }

    @Test
    void claimedPointMustRemainOwnedAfterRevisionChanges() {
        String deviceId = "dev-claim-revision";
        AtomicLongNanos now = new AtomicLongNanos();
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        DeviceBatchTask task = task(deviceId, generation, runtimeState.getTimeSliceRevision(), List.of(point), 1_000L, now);
        SchedulerRuntimeState.PointDispatchClaim claim = task.claimDuePoints(runtimeState, List.of(point));

        runtimeState.updateTimeSliceConfig(2, 1_000);
        DeviceBatchTask newTask = task(deviceId, generation, runtimeState.getTimeSliceRevision(), List.of(point), 1_000L, now);
        SchedulerRuntimeState.PointDispatchClaim newClaim = newTask.claimDuePoints(runtimeState, List.of(point));

        assertFalse(claim.isEmpty());
        assertTrue(newClaim.isEmpty());
        assertEquals(1, runtimeState.getInFlightPointScheduleSizeForTest());
        runtimeState.completeClaim(claim);
    }

    @Test
    void generationChangeBeforeClaimMustRejectOldTask() {
        String deviceId = "dev-generation-before-claim";
        DataPoint point = point(deviceId, "p1");
        long oldGeneration = markRunning(deviceId);
        DeviceBatchTask oldTask = new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                oldGeneration,
                runtimeState.getTimeSliceRevision());
        runtimeState.removeDevice(deviceId);
        collectionTaskGuard.clearDevice(deviceId);
        runtimeState.markRunning(deviceId, collectionTaskGuard.activateNextGeneration(deviceId));

        SchedulerRuntimeState.PointDispatchClaim claim = oldTask.claimDuePoints(runtimeState, List.of(point));

        assertTrue(claim.isEmpty());
        assertEquals(0, runtimeState.getInFlightPointScheduleSizeForTest());
    }

    @Test
    void plannerRegroupingMustNotDoubleClaimSamePoint() {
        String deviceId = "dev-regroup";
        AtomicLongNanos now = new AtomicLongNanos();
        DataPoint p1 = point(deviceId, "p1");
        DataPoint p2 = point(deviceId, "p2");
        long generation = markRunning(deviceId);
        DeviceBatchTask oldTask = task(deviceId, generation, runtimeState.getTimeSliceRevision(), List.of(p1, p2), 1_000L, now);
        SchedulerRuntimeState.PointDispatchClaim oldClaim = oldTask.claimDuePoints(runtimeState, List.of(p1));
        runtimeState.updateTimeSliceConfig(2, 1_000);
        DeviceBatchTask regroupP1 = task(deviceId, generation, runtimeState.getTimeSliceRevision(), List.of(p1), 1_000L, now);
        DeviceBatchTask regroupP2 = task(deviceId, generation, runtimeState.getTimeSliceRevision(), List.of(p2), 1_000L, now);

        SchedulerRuntimeState.PointDispatchClaim p1Claim = regroupP1.claimDuePoints(runtimeState, List.of(p1));
        SchedulerRuntimeState.PointDispatchClaim p2Claim = regroupP2.claimDuePoints(runtimeState, List.of(p2));

        assertFalse(oldClaim.isEmpty());
        assertTrue(p1Claim.isEmpty());
        assertFalse(p2Claim.isEmpty());
        assertEquals(2, runtimeState.getInFlightPointScheduleSizeForTest());
        runtimeState.completeClaim(oldClaim);
        runtimeState.completeClaim(p2Claim);
    }

    @Test
    void differentPointsMayStillBeClaimedConcurrently() {
        String deviceId = "dev-different-points";
        AtomicLongNanos now = new AtomicLongNanos();
        DataPoint p1 = point(deviceId, "p1");
        DataPoint p2 = point(deviceId, "p2");
        long generation = markRunning(deviceId);
        DeviceBatchTask task1 = task(deviceId, generation, runtimeState.getTimeSliceRevision(), List.of(p1), 1_000L, now);
        DeviceBatchTask task2 = task(deviceId, generation, runtimeState.getTimeSliceRevision(), List.of(p2), 1_000L, now);

        SchedulerRuntimeState.PointDispatchClaim claim1 = task1.claimDuePoints(runtimeState, List.of(p1));
        SchedulerRuntimeState.PointDispatchClaim claim2 = task2.claimDuePoints(runtimeState, List.of(p2));

        assertFalse(claim1.isEmpty());
        assertFalse(claim2.isEmpty());
        assertEquals(2, runtimeState.getInFlightPointScheduleSizeForTest());
        runtimeState.completeClaim(claim1);
        runtimeState.completeClaim(claim2);
    }

    @Test
    void concurrentClaimStressMustHaveSingleOwnerPerPoint() throws Exception {
        String deviceId = "dev-claim-stress";
        AtomicLongNanos now = new AtomicLongNanos();
        long generation = markRunning(deviceId);
        List<DataPoint> points = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            points.add(point(deviceId, "p" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SchedulerRuntimeState.PointDispatchClaim>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            DeviceBatchTask task = task(deviceId, generation, runtimeState.getTimeSliceRevision(), points, 1_000L, now);
            futures.add(pool.submit(() -> {
                start.await(1, TimeUnit.SECONDS);
                return task.claimDuePoints(runtimeState, points);
            }));
        }

        start.countDown();
        int claimedPoints = 0;
        List<SchedulerRuntimeState.PointDispatchClaim> claims = new ArrayList<>();
        for (Future<SchedulerRuntimeState.PointDispatchClaim> future : futures) {
            SchedulerRuntimeState.PointDispatchClaim claim = future.get(2, TimeUnit.SECONDS);
            claims.add(claim);
            claimedPoints += claim.points().size();
        }
        claims.forEach(runtimeState::completeClaim);
        pool.shutdownNow();

        assertEquals(100, claimedPoints);
        assertEquals(0, runtimeState.getInFlightPointScheduleSizeForTest());
    }

    @Test
    void collectorFailureMustReleaseClaimedPoints() throws Exception {
        String deviceId = "dev-collector-failure-release";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection(deviceId));
        when(collectionManager.readPoints(eq(deviceId), anyList())).thenThrow(new IllegalStateException("collector failure"));
        DeviceBatchTask task = new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                generation,
                runtimeState.getTimeSliceRevision());

        CompletableFuture<Void> dispatchFuture = batchExecutor.submit(task, List.of(point));
        assertNotNull(dispatchFuture);
        dispatchFuture.get(2, TimeUnit.SECONDS);

        assertEquals(0, runtimeState.getInFlightPointScheduleSizeForTest());
        verify(collectedDataProcessor, never()).process(eq(deviceId), anyList(), eq(Map.of("p1", 1)));
    }

    @Test
    void processErrorReceiptMustNotBeOverwrittenByNullReadValue() throws Exception {
        String deviceId = "dev-core-process-error";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        AcquisitionRuntimeTracker tracker = new AcquisitionRuntimeTracker(collectionTaskGuard);
        tracker.open(deviceId, generation, List.of(point));
        batchExecutor.setAcquisitionRuntimeTracker(tracker);
        AtomicReference<CollectionProcessingReceipt> receipt = new AtomicReference<>();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("p1", null);
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection(deviceId));
        when(collectionManager.readPoints(eq(deviceId), anyList())).thenAnswer(invocation -> {
            CollectionProcessingReceipt current = collectionTaskGuard.captureCurrentContext().receipt();
            assertTrue(current.claim("p1"));
            receipt.set(current);
            return values;
        });

        batchExecutor.processDeviceBatch(new DeviceBatchTask(deviceId, List.of(point), 0, generation,
                runtimeState.getTimeSliceRevision()));
        TimeUnit.MILLISECONDS.sleep(10);
        receipt.get().complete("p1", false, "PROCESS_ERROR", 0L);

        AcquisitionRuntimeTracker.PointFactSnapshot fact = tracker.snapshot(deviceId, generation).points().get("p1");
        assertEquals("PROCESS_ERROR", fact.failureReason());
        assertEquals(1, fact.consecutiveFailures());
        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).totalPoints.get());
        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).firstSuccessTime);
        assertEquals(0, batchExecutor.getTotalInFlightFutureCountForTest());
    }

    @Test
    void unfinishedCoreReceiptMustTimeOutAndReleaseInFlightTask() throws Exception {
        String deviceId = "dev-core-timeout";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        AcquisitionRuntimeTracker tracker = new AcquisitionRuntimeTracker(collectionTaskGuard);
        tracker.open(deviceId, generation, List.of(point));
        batchExecutor.setAcquisitionRuntimeTracker(tracker);
        AtomicReference<CollectionProcessingReceipt> receipt = new AtomicReference<>();
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection(deviceId));
        when(collectionManager.readPoints(eq(deviceId), anyList())).thenAnswer(invocation -> {
            CollectionProcessingReceipt current = collectionTaskGuard.captureCurrentContext().receipt();
            assertTrue(current.claim("p1"));
            receipt.set(current);
            return Map.of("p1", 1);
        });
        DeviceBatchTask task = new DeviceBatchTask(deviceId, List.of(point), 0, generation,
                runtimeState.getTimeSliceRevision());

        batchExecutor.processDeviceBatch(task);
        Map<String, CollectionProcessingReceipt.PointCompletion> results =
                receipt.get().completion().get(2, TimeUnit.SECONDS);
        waitUntil(() -> batchExecutor.getTotalInFlightFutureCountForTest() == 0);

        assertTrue(receipt.get().isCancelled());
        assertFalse(results.get("p1").valid());
        assertEquals("PROCESS_ERROR", results.get("p1").reason());
        assertEquals(0, batchExecutor.getTotalInFlightFutureCountForTest());
        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).totalPoints.get());
        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).firstSuccessTime);
        assertEquals(1L, performanceMonitor.devicePerformance.get(deviceId).failedBatches.get());
        assertEquals("PROCESS_ERROR", tracker.snapshot(deviceId, generation).points().get("p1").failureReason());
        verify(collectedDataProcessor, never()).process(eq(deviceId), anyList(), eq(Map.of("p1", 1)));
        receipt.get().complete("p1", true, null, System.currentTimeMillis());
        assertFalse(receipt.get().completion().join().get("p1").valid());
    }

    @Test
    void partialReceiptMustCountUniqueObservedPointsOnly() throws Exception {
        String deviceId = "dev-core-partial";
        DataPoint good = point(deviceId, "p1");
        DataPoint bad = point(deviceId, "p2");
        DataPoint missing = point(deviceId, "p3");
        DataPoint cached = point(deviceId, "p4");
        DataPoint stale = point(deviceId, "p5");
        DataPoint nullValue = point(deviceId, "p6");
        List<DataPoint> points = List.of(good, bad, missing, cached, stale, nullValue, good);
        long generation = markRunning(deviceId);
        long sampleAt = System.currentTimeMillis();
        Map<String, Object> values = new LinkedHashMap<>(Map.of("p1", 1, "p2", 2, "p4", 4, "p5", 5));
        values.put("p6", null);
        stubRead(deviceId, values, receipt -> {
            for (String id : List.of("p1", "p2", "p4", "p5", "p6")) assertTrue(receipt.claim(id));
            receipt.complete("p1", true, null, sampleAt);
            receipt.complete("p2", false, "QUALITY_BAD", sampleAt);
            receipt.complete("p4", false, "CACHE_READ", sampleAt);
            receipt.complete("p5", false, "STALE_SAMPLE", sampleAt);
            receipt.complete("p6", true, null, sampleAt);
        });
        DeviceBatchTask task = new DeviceBatchTask(deviceId, points, 0, generation,
                runtimeState.getTimeSliceRevision());

        batchExecutor.processDeviceBatch(task);

        verify(performanceMonitor).recordBatchOutcome(eq(deviceId), eq(generation), eq(1), eq(3),
                anyLong(), eq(sampleAt));
        assertEquals(1L, performanceMonitor.devicePerformance.get(deviceId).totalPoints.get());
        assertEquals(sampleAt, performanceMonitor.devicePerformance.get(deviceId).firstSuccessTime);
        assertEquals(1L, performanceMonitor.devicePerformance.get(deviceId).failedBatches.get());
        assertTrue(task.getNextAllowedExecutionTime() > 0L);
        verify(collectionStatistics).collectionFailed(deviceId);
        verify(collectionStatistics, never()).collectionSuccess(eq(deviceId), anyLong());
        verify(collectedDataProcessor, timeout(1_000)).process(eq(deviceId), eq(List.of(good)), eq(Map.of("p1", 1)));
        assertEquals(0, batchExecutor.getTotalInFlightFutureCountForTest());
    }

    @Test
    void nonemptyReadMapMustWaitForAsyncCoreReceiptBeforeSuccess() throws Exception {
        String deviceId = "dev-core-pending";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        AtomicReference<CollectionProcessingReceipt> receipt = new AtomicReference<>();
        stubRead(deviceId, Map.of("p1", 1), current -> {
            assertTrue(current.claim("p1"));
            receipt.set(current);
        });

        batchExecutor.processDeviceBatch(new DeviceBatchTask(deviceId, List.of(point), 0, generation,
                runtimeState.getTimeSliceRevision()));

        assertEquals(1, batchExecutor.getInFlightProcessFutureCountForTest());
        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).totalPoints.get());
        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).firstSuccessTime);
        verify(collectionStatistics, never()).collectionSuccess(eq(deviceId), anyLong());
        verify(collectedDataProcessor, never()).process(eq(deviceId), anyList(), anyMap());
        long sampleAt = System.currentTimeMillis();
        receipt.get().complete("p1", true, null, sampleAt);

        assertEquals(1L, performanceMonitor.devicePerformance.get(deviceId).totalPoints.get());
        assertEquals(sampleAt, performanceMonitor.devicePerformance.get(deviceId).firstSuccessTime);
        assertEquals(1L, performanceMonitor.devicePerformance.get(deviceId).successfulBatches.get());
        verify(collectionStatistics).collectionSuccess(eq(deviceId), anyLong());
        verify(collectedDataProcessor, timeout(1_000)).process(eq(deviceId), eq(List.of(point)), eq(Map.of("p1", 1)));
        assertEquals(0, batchExecutor.getTotalInFlightFutureCountForTest());
    }

    @Test
    void nonemptyMapWithoutCoreReceiptMustNotGrantSuccess() {
        String deviceId = "dev-core-unclaimed";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        stubRead(deviceId, Map.of("p1", 1), receipt -> { });

        batchExecutor.processDeviceBatch(new DeviceBatchTask(deviceId, List.of(point), 0, generation,
                runtimeState.getTimeSliceRevision()));

        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).totalPoints.get());
        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).firstSuccessTime);
        assertEquals(1L, performanceMonitor.devicePerformance.get(deviceId).failedBatches.get());
        verify(collectionStatistics).collectionFailed(deviceId);
        verify(collectionStatistics, never()).collectionSuccess(eq(deviceId), anyLong());
        verify(collectedDataProcessor, never()).process(eq(deviceId), anyList(), anyMap());
    }

    @Test
    void cacheAndStaleReceiptsMustNotCountAsBatchAttempts() {
        String deviceId = "dev-core-cache-stale";
        List<DataPoint> points = List.of(point(deviceId, "p1"), point(deviceId, "p2"));
        long generation = markRunning(deviceId);
        stubRead(deviceId, Map.of("p1", 1, "p2", 2), receipt -> {
            assertTrue(receipt.claim("p1"));
            assertTrue(receipt.claim("p2"));
            receipt.complete("p1", false, "CACHE_READ", System.currentTimeMillis());
            receipt.complete("p2", false, "STALE_SAMPLE", System.currentTimeMillis());
        });
        DeviceBatchTask task = new DeviceBatchTask(deviceId, points, 0, generation,
                runtimeState.getTimeSliceRevision());

        batchExecutor.processDeviceBatch(task);

        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).totalPoints.get());
        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).failedBatches.get());
        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).successfulBatches.get());
        assertEquals(0L, task.getNextAllowedExecutionTime());
        verify(collectionStatistics, never()).collectionFailed(deviceId);
        verify(collectionStatistics, never()).collectionSuccess(eq(deviceId), anyLong());
        verify(collectedDataProcessor, never()).process(eq(deviceId), anyList(), anyMap());
        assertEquals(0, batchExecutor.getTotalInFlightFutureCountForTest());
    }

    @Test
    void cancellingDeviceCoreFutureMustCancelReceipt() {
        String deviceId = "dev-core-cancel";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        AtomicReference<CollectionProcessingReceipt> receipt = new AtomicReference<>();
        stubRead(deviceId, Map.of("p1", 1), current -> {
            assertTrue(current.claim("p1"));
            receipt.set(current);
        });
        batchExecutor.processDeviceBatch(new DeviceBatchTask(deviceId, List.of(point), 0, generation,
                runtimeState.getTimeSliceRevision()));

        batchExecutor.cancelDeviceInFlightTasks(deviceId);

        assertTrue(receipt.get().isCancelled());
        assertFalse(receipt.get().commitIfOpen(() -> { throw new AssertionError("取消后不能提交核心副作用"); }));
        receipt.get().complete("p1", true, null, System.currentTimeMillis());
        assertFalse(receipt.get().completion().join().get("p1").valid());
        assertEquals(0, batchExecutor.getTotalInFlightFutureCountForTest());
        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).totalPoints.get());
        verify(collectedDataProcessor, never()).process(eq(deviceId), anyList(), anyMap());
    }

    @Test
    void cancelledTaskBeforeCoreRegistrationMustCancelReceiptImmediately() {
        String deviceId = "dev-core-registration-cancel";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        AtomicReference<CollectionProcessingReceipt> receipt = new AtomicReference<>();
        stubRead(deviceId, Map.of("p1", 1), current -> {
            assertTrue(current.claim("p1"));
            receipt.set(current);
        });
        AtomicInteger validityChecks = new AtomicInteger();
        DeviceBatchExecutor racingExecutor = new DeviceBatchExecutor(collectionManager, configManager,
                collectionStatistics, new CollectorProperties(), collectedDataProcessor, collectionTaskGuard,
                runtimeState, performanceMonitor, mock(ReconnectCoordinator.class), batchDispatcher,
                asyncCollectorExecutor, dataProcessorExecutor) {
            @Override
            boolean isBatchTaskExecutionStillValid(DeviceBatchTask task) {
                boolean valid = super.isBatchTaskExecutionStillValid(task);
                if (validityChecks.incrementAndGet() == 2) task.cancel();
                return valid;
            }
        };
        DeviceBatchTask task = new DeviceBatchTask(deviceId, List.of(point), 0, generation,
                runtimeState.getTimeSliceRevision());

        racingExecutor.processDeviceBatch(task);

        assertTrue(task.isCancelled());
        assertTrue(receipt.get().isCancelled());
        assertEquals(0, racingExecutor.getTotalInFlightFutureCountForTest());
        verify(collectedDataProcessor, never()).process(eq(deviceId), anyList(), anyMap());
    }

    @Test
    void oldGenerationCoreReceiptMustNotSettleNewRuntimeWindow() {
        String deviceId = "dev-core-old-generation";
        DataPoint point = point(deviceId, "p1");
        long generation = markRunning(deviceId);
        AtomicReference<CollectionProcessingReceipt> receipt = new AtomicReference<>();
        stubRead(deviceId, Map.of("p1", 1), current -> {
            assertTrue(current.claim("p1"));
            receipt.set(current);
        });
        batchExecutor.processDeviceBatch(new DeviceBatchTask(deviceId, List.of(point), 0, generation,
                runtimeState.getTimeSliceRevision()));
        long newGeneration = markRunning(deviceId);

        receipt.get().complete("p1", true, null, System.currentTimeMillis());

        assertEquals(newGeneration, performanceMonitor.devicePerformance.get(deviceId).runtimeGeneration);
        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).totalPoints.get());
        assertEquals(0L, performanceMonitor.devicePerformance.get(deviceId).firstSuccessTime);
        verify(collectionStatistics, never()).collectionSuccess(eq(deviceId), anyLong());
        verify(collectedDataProcessor, never()).process(eq(deviceId), anyList(), anyMap());
        assertEquals(0, batchExecutor.getTotalInFlightFutureCountForTest());
    }

    private void stubRead(String deviceId, Map<String, Object> values,
                          Consumer<CollectionProcessingReceipt> core) {
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection(deviceId));
        when(collectionManager.readPoints(eq(deviceId), anyList())).thenAnswer(invocation -> {
            CollectionTaskGuard.CollectionTaskContext context = collectionTaskGuard.captureCurrentContext();
            assertNotNull(context);
            assertNotNull(context.receipt());
            core.accept(context.receipt());
            return values;
        });
    }

    private long markRunning(String deviceId) {
        long generation = collectionTaskGuard.activateNextGeneration(deviceId);
        runtimeState.markRunning(deviceId, generation);
        performanceMonitor.resetDeviceRuntimeWindow(deviceId, generation);
        return generation;
    }

    private DeviceBatchTask task(String deviceId,
                                 long generation,
                                 long revision,
                                 List<DataPoint> points,
                                 long intervalMs,
                                 AtomicLongNanos now) {
        return new DeviceBatchTask(
                deviceId,
                points,
                0,
                generation,
                revision,
                ignored -> intervalMs,
                now::get);
    }

    private DataPoint point(String deviceId, String pointId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        return point;
    }

    private DeviceConnection connection(String deviceId) {
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(502);
        connection.setReadTimeout(50);
        connection.setTimeout(50);
        return connection;
    }

    private void waitUntil(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private ThreadPoolExecutor fixedPool(String namePrefix) {
        return fixedPool(namePrefix, 1);
    }

    private ThreadPoolExecutor fixedPool(String namePrefix, int threads) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(16),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    thread.setName(namePrefix + "-" + thread.getId());
                    return thread;
                });
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }

    private static final class AtomicLongNanos {
        private final java.util.concurrent.atomic.AtomicLong value = new java.util.concurrent.atomic.AtomicLong(0L);

        private long get() {
            return value.get();
        }

        private void setMillis(long millis) {
            value.set(TimeUnit.MILLISECONDS.toNanos(millis));
        }
    }
}

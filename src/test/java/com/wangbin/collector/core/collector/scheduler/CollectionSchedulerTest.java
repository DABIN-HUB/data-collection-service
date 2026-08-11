package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.port.SystemResourceProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CollectionSchedulerTest {

    private CollectionManager collectionManager;
    private ConfigManager configManager;
    private CollectorProperties collectorProperties;
    private SchedulerRuntimeState runtimeState;
    private PerformanceMonitor performanceMonitor;
    private DeviceLifecycleCoordinator lifecycleCoordinator;
    private DeviceBatchExecutor batchExecutor;
    private ReconnectCoordinator reconnectCoordinator;
    private SystemResourceProbe systemResourceProbe;
    private ScheduledExecutorService timeSliceScheduler;
    private CollectionScheduler scheduler;

    @BeforeEach
    void setUp() {
        collectionManager = mock(CollectionManager.class);
        configManager = mock(ConfigManager.class);
        collectorProperties = new CollectorProperties();
        collectorProperties.getScheduler().setInitialTimeSliceCount(1);
        collectorProperties.getScheduler().setMaxTimeSliceCount(4);
        collectorProperties.getScheduler().setInitialTimeSliceIntervalMs(1000);
        collectorProperties.getScheduler().setMinTimeSliceIntervalMs(50);
        runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(1, 1000);
        performanceMonitor = new PerformanceMonitor();
        lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        batchExecutor = mock(DeviceBatchExecutor.class);
        reconnectCoordinator = mock(ReconnectCoordinator.class);
        systemResourceProbe = mock(SystemResourceProbe.class);
        timeSliceScheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler = new CollectionScheduler(
                collectionManager,
                configManager,
                mock(CollectionStatistics.class),
                collectorProperties,
                systemResourceProbe,
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                batchExecutor,
                reconnectCoordinator,
                timeSliceScheduler);
    }

    @Test
    void collectionSchedulerShouldReadCpuLoadThroughSystemResourceProbe() {
        when(systemResourceProbe.getProcessCpuLoad()).thenReturn(75D);

        assertEquals(75D, scheduler.resolveProcessCpuLoad());
    }

    @AfterEach
    void tearDown() {
        timeSliceScheduler.shutdownNow();
    }

    @Test
    void collectionSchedulerShouldRebuildAssignmentsOnSliceCountChange() {
        String deviceId = "dev-rebuild";
        DataPoint point = point(deviceId, "p1");
        long generation = 1L;
        runtimeState.markRunning(deviceId, generation);
        DeviceBatchTask oldTask = new DeviceBatchTask(deviceId, List.of(point), 0, generation, runtimeState.getTimeSliceRevision());
        runtimeState.addBatchTasks(List.of(oldTask));
        when(configManager.getDataPoints(deviceId)).thenReturn(List.of(point));
        doAnswer(invocation -> {
            runtimeState.addBatchTasks(List.of(new DeviceBatchTask(
                    deviceId,
                    invocation.getArgument(2),
                    0,
                    generation,
                    runtimeState.getTimeSliceRevision())));
            return null;
        }).when(lifecycleCoordinator).scheduleDevicePoints(eq(deviceId), eq(generation), anyList());

        scheduler.applyTimeSliceConfigUpdate(3, 1000);

        DeviceBatchTask newTask = runtimeState.getSliceTasks(0).get(0);
        assertNotEquals(oldTask.timeSliceRevision, newTask.timeSliceRevision);
    }

    @Test
    void collectionSchedulerShouldSkipStaleRevisionTasks() {
        String deviceId = "dev-revision";
        DataPoint point = point(deviceId, "p1");
        long generation = 1L;
        runtimeState.markRunning(deviceId, generation);
        DeviceBatchTask staleTask = new DeviceBatchTask(deviceId, List.of(point), 0, generation, runtimeState.getTimeSliceRevision());
        runtimeState.updateTimeSliceConfig(1, 1000);
        runtimeState.resetTimeSliceBuckets(1);
        runtimeState.addBatchTasks(List.of(staleTask));
        when(batchExecutor.isBatchTaskActive(staleTask)).thenReturn(true);

        scheduler.executeTimeSlice(0, runtimeState.getTimeSliceRevision());

        verify(batchExecutor, never()).submit(eq(staleTask), anyLong());
    }

    @Test
    void collectionSchedulerShouldKeepPeriodicTaskWhenSliceExecutionTimeout() {
        String deviceId = "dev-slice-timeout";
        DataPoint point = point(deviceId, "p1");
        long generation = 1L;
        runtimeState.initializeTimeSlices(1, 80);
        runtimeState.markRunning(deviceId, generation);
        DeviceBatchTask task = new DeviceBatchTask(deviceId, List.of(point), 0, generation, runtimeState.getTimeSliceRevision());
        runtimeState.addBatchTasks(List.of(task));
        when(batchExecutor.isBatchTaskActive(task)).thenReturn(true);
        when(batchExecutor.submit(eq(task), anyLong())).thenReturn(new CompletableFuture<>());

        scheduler.executeTimeSlice(0, runtimeState.getTimeSliceRevision());

        assertFalse(task.isCancelled());
    }

    @Test
    void dueScanMustNotBlockSchedulerThreadOnCollectorFuture() {
        String deviceId = "dev-non-blocking-scan";
        DataPoint point = point(deviceId, "p1");
        long generation = 1L;
        collectorProperties.getScheduler().setDueScanIntervalMs(500);
        runtimeState.initializeTimeSlices(1, 500);
        runtimeState.markRunning(deviceId, generation);
        DeviceBatchTask task = new DeviceBatchTask(deviceId, List.of(point), 0, generation, runtimeState.getTimeSliceRevision());
        runtimeState.addBatchTasks(List.of(task));
        when(batchExecutor.isBatchTaskActive(task)).thenReturn(true);
        when(batchExecutor.submit(eq(task), anyLong())).thenReturn(new CompletableFuture<>());

        long started = System.nanoTime();
        scheduler.executeTimeSlice(0, runtimeState.getTimeSliceRevision());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(true, elapsedMs < 250L);
    }

    @Test
    void executeTimeSliceShouldUseOneClaimTimeForAllTasksInSameSlice() {
        runtimeState.initializeTimeSlices(1, 1000);
        long generation = 1L;
        DeviceBatchTask firstTask = new DeviceBatchTask(
                "dev-slice-time-a",
                List.of(point("dev-slice-time-a", "p1")),
                0,
                generation,
                runtimeState.getTimeSliceRevision());
        DeviceBatchTask secondTask = new DeviceBatchTask(
                "dev-slice-time-b",
                List.of(point("dev-slice-time-b", "p1")),
                0,
                generation,
                runtimeState.getTimeSliceRevision());
        runtimeState.markRunning(firstTask.deviceId, generation);
        runtimeState.markRunning(secondTask.deviceId, generation);
        runtimeState.addBatchTasks(List.of(firstTask, secondTask));
        when(batchExecutor.isBatchTaskActive(any(DeviceBatchTask.class))).thenReturn(true);
        List<Long> claimTimes = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            claimTimes.add(invocation.getArgument(1, Long.class));
            return CompletableFuture.completedFuture(null);
        }).when(batchExecutor).submit(any(DeviceBatchTask.class), anyLong());

        scheduler.executeTimeSlice(0, runtimeState.getTimeSliceRevision());

        assertEquals(2, claimTimes.size());
        assertEquals(claimTimes.get(0), claimTimes.get(1));
    }

    @Test
    void eightyBatchTasksShouldNotCollapseIntoTwoHugeSlices() {
        collectorProperties.getScheduler().setMaxTimeSliceCount(12);

        int sliceCount = scheduler.calculateOptimalSliceCount(10, 80, 0.1D);

        assertEquals(10, sliceCount);
    }

    @Test
    void lowCpuMustNotAggressivelyCollapseSlices() {
        collectorProperties.getScheduler().setMaxTimeSliceCount(12);

        int sliceCount = scheduler.calculateOptimalSliceCount(10, 16, 0.1D);

        assertEquals(3, sliceCount);
    }

    @Test
    void workloadIncreaseShouldIncreaseOrMaintainSliceCount() {
        collectorProperties.getScheduler().setMaxTimeSliceCount(12);

        int lowWorkloadSlices = scheduler.calculateOptimalSliceCount(10, 16, 0.5D);
        int highWorkloadSlices = scheduler.calculateOptimalSliceCount(10, 80, 0.5D);

        assertEquals(3, lowWorkloadSlices);
        assertEquals(10, highWorkloadSlices);
    }

    @Test
    void startDeviceShouldReplanImmediatelyAfterWorkloadIncrease() {
        collectorProperties.getScheduler().setMaxTimeSliceCount(12);
        collectorProperties.getScheduler().setTargetTasksPerTimeSlice(8);
        runtimeState.initializeTimeSlices(4, 1_500);
        doAnswer(invocation -> {
            String deviceId = invocation.getArgument(0);
            long generation = invocation.getArgument(1);
            List<DataPoint> points = invocation.getArgument(2);
            List<DeviceBatchTask> rebuiltTasks = points.stream()
                    .map(point -> new DeviceBatchTask(
                            deviceId,
                            List.of(point),
                            0,
                            generation,
                            runtimeState.getTimeSliceRevision()))
                    .toList();
            runtimeState.addBatchTasks(rebuiltTasks);
            return null;
        }).when(lifecycleCoordinator).scheduleDevicePoints(anyString(), anyLong(), anyList());
        for (int deviceIndex = 0; deviceIndex < 10; deviceIndex++) {
            String deviceId = "dev-workload-" + deviceIndex;
            List<DataPoint> points = IntStream.range(0, 8)
                    .mapToObj(taskIndex -> point(deviceId, "p-" + taskIndex))
                    .toList();
            when(configManager.getDataPoints(deviceId)).thenReturn(points);
            runtimeState.markRunning(deviceId, 1L);
            List<DeviceBatchTask> tasks = points.stream()
                    .map(point -> new DeviceBatchTask(
                            deviceId,
                            List.of(point),
                            0,
                            1L,
                            runtimeState.getTimeSliceRevision()))
                    .toList();
            runtimeState.addBatchTasks(tasks);
        }
        when(lifecycleCoordinator.startDevice("dev-workload-trigger")).thenReturn(true);

        boolean started = scheduler.startDevice("dev-workload-trigger");

        assertEquals(true, started);
        assertEquals(10, runtimeState.getTimeSliceCount());
        assertEquals(8, runtimeState.getMaxTasksPerTimeSliceForTest());
    }

    @Test
    void startDeviceReplanMustNotStartSchedulerWhenSchedulerIsNotActive() {
        collectorProperties.getScheduler().setMaxTimeSliceCount(12);
        collectorProperties.getScheduler().setTargetTasksPerTimeSlice(8);
        ScheduledExecutorService manualScheduler = mock(ScheduledExecutorService.class);
        scheduler = new CollectionScheduler(
                collectionManager,
                configManager,
                mock(CollectionStatistics.class),
                collectorProperties,
                systemResourceProbe,
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                batchExecutor,
                reconnectCoordinator,
                manualScheduler);
        String deviceId = "dev-manual-replan";
        runtimeState.markRunning(deviceId, 1L);
        List<DeviceBatchTask> tasks = IntStream.range(0, 16)
                .mapToObj(index -> new DeviceBatchTask(
                        deviceId,
                        List.of(point(deviceId, "p-" + index)),
                        0,
                        1L,
                        runtimeState.getTimeSliceRevision()))
                .toList();
        runtimeState.addBatchTasks(tasks);
        when(lifecycleCoordinator.startDevice(deviceId)).thenReturn(true);

        boolean started = scheduler.startDevice(deviceId);

        assertEquals(true, started);
        assertEquals(2, runtimeState.getTimeSliceCount());
        verify(manualScheduler, never()).scheduleAtFixedRate(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any(TimeUnit.class));
    }

    @Test
    void dueMissByFewMillisecondsMustNotWaitFullCollectionInterval() {
        AtomicLong nowNanos = new AtomicLong(0L);
        runtimeState = new SchedulerRuntimeState(nowNanos::get);
        List<Long> scheduledPeriods = new CopyOnWriteArrayList<>();
        CollectionScheduler localScheduler = schedulerWithCapturedSchedule(nowNanos, scheduledPeriods, new CopyOnWriteArrayList<>());
        collectorProperties.getScheduler().setDueScanIntervalMs(500);
        runtimeState.initializeTimeSlices(10, 500);
        String deviceId = "dev-due-miss-5s";
        DataPoint point = point(deviceId, "p1");
        long generation = 1L;
        runtimeState.markRunning(deviceId, generation);
        DeviceBatchTask task = new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                generation,
                runtimeState.getTimeSliceRevision(),
                ignored -> 5_000L,
                nowNanos::get);
        runtimeState.addBatchTasks(List.of(task));
        List<Long> claimedMillis = captureClaims(nowNanos);

        localScheduler.startTimeSliceScheduling();
        long periodMs = scheduledPeriods.get(0);
        executeAtMillis(localScheduler, nowNanos, 0L);
        executeAtMillis(localScheduler, nowNanos, 4_999L);
        executeAtMillis(localScheduler, nowNanos, 4_999L + periodMs);

        assertEquals(2, claimedMillis.size());
        assertEquals(0L, claimedMillis.get(0));
        assertEquals(5_049L, claimedMillis.get(1));
    }

    @Test
    void fiveSecondCadenceP95MustNotJumpToTenSeconds() {
        AtomicLong nowNanos = new AtomicLong(0L);
        runtimeState = new SchedulerRuntimeState(nowNanos::get);
        List<Long> scheduledPeriods = new CopyOnWriteArrayList<>();
        CollectionScheduler localScheduler = schedulerWithCapturedSchedule(nowNanos, scheduledPeriods, new CopyOnWriteArrayList<>());
        collectorProperties.getScheduler().setDueScanIntervalMs(500);
        runtimeState.initializeTimeSlices(10, 500);
        String deviceId = "dev-five-second-late";
        DataPoint point = point(deviceId, "p1");
        runtimeState.markRunning(deviceId, 1L);
        runtimeState.addBatchTasks(List.of(new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                1L,
                runtimeState.getTimeSliceRevision(),
                ignored -> 5_000L,
                nowNanos::get)));
        List<Long> claimedMillis = captureClaims(nowNanos);

        localScheduler.startTimeSliceScheduling();
        executeAtMillis(localScheduler, nowNanos, 0L);
        executeAtMillis(localScheduler, nowNanos, 4_999L);
        executeAtMillis(localScheduler, nowNanos, 4_999L + scheduledPeriods.get(0));

        assertEquals(2, claimedMillis.size());
        assertEquals(true, claimedMillis.get(1) - claimedMillis.get(0) <= 6_000L);
    }

    @Test
    void tenSecondCadenceP95MustNotJumpToTwentySeconds() {
        AtomicLong nowNanos = new AtomicLong(0L);
        runtimeState = new SchedulerRuntimeState(nowNanos::get);
        List<Long> scheduledPeriods = new CopyOnWriteArrayList<>();
        CollectionScheduler localScheduler = schedulerWithCapturedSchedule(nowNanos, scheduledPeriods, new CopyOnWriteArrayList<>());
        collectorProperties.getScheduler().setDueScanIntervalMs(500);
        runtimeState.initializeTimeSlices(10, 1_000);
        String deviceId = "dev-ten-second-late";
        DataPoint point = point(deviceId, "p1");
        runtimeState.markRunning(deviceId, 1L);
        runtimeState.addBatchTasks(List.of(new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                1L,
                runtimeState.getTimeSliceRevision(),
                ignored -> 10_000L,
                nowNanos::get)));
        List<Long> claimedMillis = captureClaims(nowNanos);

        localScheduler.startTimeSliceScheduling();
        executeAtMillis(localScheduler, nowNanos, 0L);
        executeAtMillis(localScheduler, nowNanos, 9_999L);
        executeAtMillis(localScheduler, nowNanos, 9_999L + scheduledPeriods.get(0));

        assertEquals(2, claimedMillis.size());
        assertEquals(true, claimedMillis.get(1) - claimedMillis.get(0) <= 11_000L);
    }

    @Test
    void dueScanFrequencyMustBeIndependentFromBusinessCollectionInterval() {
        AtomicLong nowNanos = new AtomicLong(0L);
        runtimeState = new SchedulerRuntimeState(nowNanos::get);
        List<Long> periods = new CopyOnWriteArrayList<>();
        List<Long> initialDelays = new CopyOnWriteArrayList<>();
        CollectionScheduler localScheduler = schedulerWithCapturedSchedule(nowNanos, periods, initialDelays);
        collectorProperties.getScheduler().setDueScanIntervalMs(500);
        runtimeState.initializeTimeSlices(12, 834);

        localScheduler.startTimeSliceScheduling();

        assertEquals(1, periods.size());
        assertEquals(50L, periods.get(0));
        assertEquals(0L, initialDelays.get(0));
    }

    @Test
    void persistentSlicePhasesMustRemainEvenlyDistributed() {
        AtomicLong nowNanos = new AtomicLong(0L);
        runtimeState = new SchedulerRuntimeState(nowNanos::get);
        List<Long> periods = new CopyOnWriteArrayList<>();
        CollectionScheduler localScheduler = schedulerWithCapturedSchedule(
                nowNanos,
                periods,
                new CopyOnWriteArrayList<>());
        collectorProperties.getScheduler().setDueScanIntervalMs(500);
        collectorProperties.getScheduler().setMinTimeSliceIntervalMs(50);
        runtimeState.initializeTimeSlices(12, 417);

        localScheduler.startTimeSliceScheduling();

        assertEquals(1, periods.size());
        assertEquals(50L, periods.get(0));
        assertEquals(true, maxScansPerWindowBucket(12, periods.get(0), 100L) <= 2);
    }

    @Test
    void fixedRateDelayMustNotCollapseMultipleLogicalSlicesIntoBurst() {
        AtomicLong nowNanos = new AtomicLong(0L);
        List<Long> fixedDelayPeriods = new CopyOnWriteArrayList<>();
        List<Long> fixedRatePeriods = new CopyOnWriteArrayList<>();
        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        doAnswer(invocation -> {
            fixedDelayPeriods.add(invocation.getArgument(2, Long.class));
            return scheduledFuture;
        }).when(scheduledExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any(TimeUnit.class));
        doAnswer(invocation -> {
            fixedRatePeriods.add(invocation.getArgument(2, Long.class));
            return scheduledFuture;
        }).when(scheduledExecutor).scheduleAtFixedRate(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any(TimeUnit.class));
        runtimeState.initializeTimeSlices(12, 417);
        CollectionScheduler localScheduler = schedulerWithExecutor(scheduledExecutor, nowNanos);

        localScheduler.startTimeSliceScheduling();

        assertEquals(List.of(50L), fixedDelayPeriods);
        assertEquals(List.of(), fixedRatePeriods);
    }

    @Test
    void phaseWheelMustExposeCatchUpTicks() {
        PerformanceMonitor localMonitor = new PerformanceMonitor();

        localMonitor.recordPhaseWheelTick(0, TimeUnit.MILLISECONDS.toNanos(0L), 50);
        localMonitor.recordPhaseWheelTick(1, TimeUnit.MILLISECONDS.toNanos(5L), 50);
        localMonitor.recordPhaseWheelTick(2, TimeUnit.MILLISECONDS.toNanos(8L), 50);

        PerformanceMonitor.PhaseWheelStatsSnapshot snapshot = localMonitor.getPhaseWheelStatsSnapshot();
        assertEquals(3L, snapshot.tickCount());
        assertEquals(2L, snapshot.catchUpTickCount());
        assertEquals(1L, snapshot.consecutiveCatchUpCount());
        assertTrue(snapshot.tickGapMaxMs() <= 5L);
    }

    @Test
    void slowSliceExecutionMustNotSilentlyCreateUnboundedCatchUp() {
        AtomicLong nowNanos = new AtomicLong(0L);
        List<Runnable> scheduledTasks = new CopyOnWriteArrayList<>();
        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        doAnswer(invocation -> {
            scheduledTasks.add(invocation.getArgument(0, Runnable.class));
            return scheduledFuture;
        }).when(scheduledExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any(TimeUnit.class));
        runtimeState.initializeTimeSlices(3, 417);
        CollectionScheduler localScheduler = schedulerWithExecutor(scheduledExecutor, nowNanos);
        when(batchExecutor.isBatchTaskActive(any(DeviceBatchTask.class))).thenReturn(true);

        localScheduler.startTimeSliceScheduling();
        assertEquals(1, scheduledTasks.size());
        scheduledTasks.get(0).run();
        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(500L));
        scheduledTasks.get(0).run();

        PerformanceMonitor.PhaseWheelStatsSnapshot snapshot = performanceMonitor.getPhaseWheelStatsSnapshot();
        assertEquals(2L, snapshot.tickCount());
        assertEquals(0L, snapshot.catchUpTickCount());
        assertTrue(snapshot.tickGapMinMs() >= 500L);
    }

    @Test
    void sliceOffsetsMustNotAliasModuloDueScanPeriod() {
        long legacyMaxScansPer100Ms = maxLegacyModuloScansPerWindowBucket(12, 417L, 500L, 100L);
        long phaseWheelMaxScansPer100Ms = maxScansPerWindowBucket(12, 50L, 100L);

        assertEquals(4L, legacyMaxScansPer100Ms);
        assertEquals(2L, phaseWheelMaxScansPer100Ms);
    }

    @Test
    void replanMustPreservePersistentPhaseDistribution() {
        AtomicLong nowNanos = new AtomicLong(0L);
        runtimeState = new SchedulerRuntimeState(nowNanos::get);
        List<Long> periods = new CopyOnWriteArrayList<>();
        CollectionScheduler localScheduler = schedulerWithCapturedSchedule(
                nowNanos,
                periods,
                new CopyOnWriteArrayList<>());
        collectorProperties.getScheduler().setDueScanIntervalMs(500);
        collectorProperties.getScheduler().setMinTimeSliceIntervalMs(50);
        runtimeState.initializeTimeSlices(4, 1_250);
        localScheduler.startTimeSliceScheduling();

        localScheduler.applyTimeSliceConfigUpdate(12, 417);

        assertEquals(List.of(125L, 50L), periods);
    }

    @Test
    void phaseDistributionMustNotChangeBusinessCadence() {
        AtomicLong nowNanos = new AtomicLong(0L);
        runtimeState = new SchedulerRuntimeState(nowNanos::get);
        CollectionScheduler localScheduler = schedulerWithCapturedSchedule(
                nowNanos,
                new CopyOnWriteArrayList<>(),
                new CopyOnWriteArrayList<>());
        runtimeState.initializeTimeSlices(12, 417);
        String deviceId = "dev-phase-cadence";
        DataPoint point = point(deviceId, "p1");
        runtimeState.markRunning(deviceId, 1L);
        runtimeState.addBatchTasks(List.of(new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                1L,
                runtimeState.getTimeSliceRevision(),
                ignored -> 5_000L,
                nowNanos::get)));
        List<Long> claimedMillis = captureClaims(nowNanos);

        executeAtMillis(localScheduler, nowNanos, 0L);
        executeAtMillis(localScheduler, nowNanos, 4_999L);
        executeAtMillis(localScheduler, nowNanos, 5_000L);

        assertEquals(List.of(0L, 5_000L), claimedMillis);
    }

    @Test
    void phaseDistributionMustNotBreakAtomicClaim() {
        String deviceId = "dev-phase-claim";
        DataPoint point = point(deviceId, "p1");
        long generation = 1L;
        runtimeState.markRunning(deviceId, generation);
        runtimeState.initializeTimeSlices(2, 250);
        DeviceBatchTask firstTask = new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                generation,
                runtimeState.getTimeSliceRevision());
        DeviceBatchTask secondTask = new DeviceBatchTask(
                deviceId,
                List.of(point),
                1,
                generation,
                runtimeState.getTimeSliceRevision());

        SchedulerRuntimeState.PointDispatchClaim firstClaim = firstTask.claimDuePoints(runtimeState, List.of(point), 0L);
        SchedulerRuntimeState.PointDispatchClaim secondClaim = secondTask.claimDuePoints(runtimeState, List.of(point), 0L);

        assertEquals(false, firstClaim.isEmpty());
        assertEquals(true, secondClaim.isEmpty());
        runtimeState.completeClaim(firstClaim);
    }

    @Test
    void phaseDistributionMustNotCreateDuplicateDispatch() {
        AtomicLong nowNanos = new AtomicLong(0L);
        runtimeState = new SchedulerRuntimeState(nowNanos::get);
        CollectionScheduler localScheduler = schedulerWithCapturedSchedule(
                nowNanos,
                new CopyOnWriteArrayList<>(),
                new CopyOnWriteArrayList<>());
        runtimeState.initializeTimeSlices(2, 250);
        String deviceId = "dev-phase-duplicate";
        DataPoint point = point(deviceId, "p1");
        runtimeState.markRunning(deviceId, 1L);
        DeviceBatchTask firstTask = new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                1L,
                runtimeState.getTimeSliceRevision(),
                ignored -> 5_000L,
                nowNanos::get);
        DeviceBatchTask secondTask = new DeviceBatchTask(
                deviceId,
                List.of(point),
                1,
                1L,
                runtimeState.getTimeSliceRevision(),
                ignored -> 5_000L,
                nowNanos::get);
        runtimeState.addBatchTasks(List.of(firstTask, secondTask));
        List<Long> claimedMillis = captureClaims(nowNanos);

        localScheduler.executeTimeSlice(0, runtimeState.getTimeSliceRevision());
        localScheduler.executeTimeSlice(1, runtimeState.getTimeSliceRevision());

        assertEquals(1, claimedMillis.size());
    }

    @Test
    void dueScanChangeMustNotChangeCollectionCadence() {
        AtomicLong nowNanos = new AtomicLong(0L);
        runtimeState = new SchedulerRuntimeState(nowNanos::get);
        List<Long> periods = new CopyOnWriteArrayList<>();
        CollectionScheduler localScheduler = schedulerWithCapturedSchedule(nowNanos, periods, new CopyOnWriteArrayList<>());
        collectorProperties.getScheduler().setDueScanIntervalMs(250);
        runtimeState.initializeTimeSlices(1, 5_000);
        String deviceId = "dev-scan-change";
        DataPoint point = point(deviceId, "p1");
        runtimeState.markRunning(deviceId, 1L);
        runtimeState.addBatchTasks(List.of(new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                1L,
                runtimeState.getTimeSliceRevision(),
                ignored -> 5_000L,
                nowNanos::get)));
        List<Long> claimedMillis = captureClaims(nowNanos);

        localScheduler.startTimeSliceScheduling();
        executeAtMillis(localScheduler, nowNanos, 0L);
        executeAtMillis(localScheduler, nowNanos, 4_999L);
        executeAtMillis(localScheduler, nowNanos, 5_000L);

        assertEquals(250L, periods.get(0));
        assertEquals(List.of(0L, 5_000L), claimedMillis);
    }

    @Test
    void dueScanMustNotCreateCatchUpStorm() {
        AtomicLong nowNanos = new AtomicLong(0L);
        runtimeState = new SchedulerRuntimeState(nowNanos::get);
        CollectionScheduler localScheduler = schedulerWithCapturedSchedule(
                nowNanos,
                new CopyOnWriteArrayList<>(),
                new CopyOnWriteArrayList<>());
        runtimeState.initializeTimeSlices(1, 500);
        String deviceId = "dev-no-catch-up-scan";
        DataPoint point = point(deviceId, "p1");
        runtimeState.markRunning(deviceId, 1L);
        runtimeState.addBatchTasks(List.of(new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                1L,
                runtimeState.getTimeSliceRevision(),
                ignored -> 5_000L,
                nowNanos::get)));
        List<Long> claimedMillis = captureClaims(nowNanos);

        executeAtMillis(localScheduler, nowNanos, 0L);
        executeAtMillis(localScheduler, nowNanos, 20_000L);
        executeAtMillis(localScheduler, nowNanos, 20_500L);
        executeAtMillis(localScheduler, nowNanos, 21_000L);

        assertEquals(List.of(0L, 20_000L), claimedMillis);
    }

    @Test
    void dueScanMustNotIncreaseBurstBeyondConfiguredEnvelope() {
        AtomicLong nowNanos = new AtomicLong(0L);
        runtimeState = new SchedulerRuntimeState(nowNanos::get);
        runtimeState.initializeTimeSlices(10, 500);
        CollectionScheduler localScheduler = schedulerWithCapturedSchedule(
                nowNanos,
                new CopyOnWriteArrayList<>(),
                new CopyOnWriteArrayList<>());
        List<DeviceBatchTask> tasks = IntStream.range(0, 10)
                .mapToObj(index -> {
                    String deviceId = "dev-burst-envelope-" + index;
                    runtimeState.markRunning(deviceId, 1L);
                    return new DeviceBatchTask(
                            deviceId,
                            List.of(point(deviceId, "p1")),
                            0,
                            1L,
                            runtimeState.getTimeSliceRevision(),
                            ignored -> 5_000L,
                            nowNanos::get);
                })
                .toList();
        runtimeState.addBatchTasks(tasks);
        List<Long> claimedMillis = captureClaims(nowNanos);

        nowNanos.set(0L);
        for (int slice = 0; slice < 10; slice++) {
            localScheduler.executeTimeSlice(slice, runtimeState.getTimeSliceRevision());
        }
        assertEquals(1, claimedMillis.size());

        nowNanos.set(TimeUnit.MILLISECONDS.toNanos(500L));
        for (int slice = 0; slice < 10; slice++) {
            localScheduler.executeTimeSlice(slice, runtimeState.getTimeSliceRevision());
        }
        assertEquals(2, claimedMillis.size());
    }

    @Test
    void workloadDecreaseShouldNotCreateUnsafeBurst() {
        collectorProperties.getScheduler().setMaxTimeSliceCount(12);

        int sliceCount = scheduler.calculateOptimalSliceCount(10, 24, 0.1D);

        assertEquals(3, sliceCount);
    }

    @Test
    void estimatedPointWorkloadShouldIncreaseSliceCount() {
        collectorProperties.getScheduler().setMaxTimeSliceCount(12);

        int sliceCount = scheduler.calculateOptimalSliceCount(2, 2, 10_000, 0.5D);

        assertEquals(10, sliceCount);
    }

    @Test
    void timeSliceIntervalShouldStayWithinBusinessCadence() {
        collectorProperties.getScheduler().setMinTimeSliceIntervalMs(300);

        int interval = scheduler.capTimeSliceIntervalForCadence(1_500, 10, 5_000);

        assertEquals(500, interval);
    }

    @Test
    void timeSliceTunerMustNotShrinkBelowCadenceAlignedInterval() {
        collectorProperties.getScheduler().setMinTimeSliceIntervalMs(300);

        int interval = scheduler.capTimeSliceIntervalForCadence(300, 12, 5_000);

        assertEquals(417, interval);
    }

    @Test
    void sliceCountMustRespectMinimumTimeSliceIntervalCadenceLimit() {
        collectorProperties.getScheduler().setMinTimeSliceIntervalMs(300);

        int sliceCount = scheduler.capSliceCountForCadence(12, 1_000);

        assertEquals(3, sliceCount);
    }

    @Test
    void sharedSliceClaimTimeShouldPreserveNextCadenceBoundary() {
        String deviceId = "dev-shared-slice-time";
        DataPoint point = point(deviceId, "p1");
        long generation = 1L;
        runtimeState.markRunning(deviceId, generation);
        DeviceBatchTask task = new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                generation,
                runtimeState.getTimeSliceRevision(),
                ignored -> 10_000L,
                () -> TimeUnit.MILLISECONDS.toNanos(100L));

        SchedulerRuntimeState.PointDispatchClaim firstClaim =
                task.claimDuePoints(runtimeState, List.of(point), 0L);
        runtimeState.completeClaim(firstClaim);
        SchedulerRuntimeState.PointDispatchClaim nextClaim = task.claimDuePoints(
                runtimeState,
                List.of(point),
                TimeUnit.MILLISECONDS.toNanos(10_008L));

        assertFalse(nextClaim.isEmpty());
        runtimeState.completeClaim(nextClaim);
    }

    @Test
    void timeSliceDistributionShouldBalanceTaskCount() {
        runtimeState.initializeTimeSlices(10, 500);
        List<DeviceBatchTask> tasks = IntStream.range(0, 80)
                .mapToObj(index -> new DeviceBatchTask(
                        "dev-" + index,
                        List.of(point("dev-" + index, "p1")),
                        0,
                        1L,
                        runtimeState.getTimeSliceRevision()))
                .toList();

        runtimeState.addBatchTasks(tasks);

        int maxTasksPerSlice = IntStream.range(0, runtimeState.getTimeSliceCount())
                .map(index -> runtimeState.getSliceTasks(index).size())
                .max()
                .orElse(0);
        assertEquals(8, maxTasksPerSlice);
    }

    @Test
    void timeSliceDistributionShouldBalanceEstimatedPoints() {
        runtimeState.initializeTimeSlices(4, 500);
        List<DeviceBatchTask> tasks = List.of(
                weightedTask("dev-weighted-a", 400),
                weightedTask("dev-weighted-b", 400),
                weightedTask("dev-weighted-c", 100),
                weightedTask("dev-weighted-d", 100),
                weightedTask("dev-weighted-e", 100),
                weightedTask("dev-weighted-f", 100)
        );

        runtimeState.addBatchTasks(tasks);

        int maxPointsPerSlice = IntStream.range(0, runtimeState.getTimeSliceCount())
                .map(index -> runtimeState.getSliceTasks(index).stream()
                        .mapToInt(task -> task.points.size())
                        .sum())
                .max()
                .orElse(0);
        assertEquals(400, maxPointsPerSlice);
    }

    private DataPoint point(String deviceId, String pointId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        return point;
    }

    private CollectionScheduler schedulerWithCapturedSchedule(AtomicLong nowNanos,
                                                              List<Long> periods,
                                                              List<Long> initialDelays) {
        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        doAnswer(invocation -> {
            initialDelays.add(invocation.getArgument(1, Long.class));
            periods.add(invocation.getArgument(2, Long.class));
            return scheduledFuture;
        }).when(scheduledExecutor).scheduleAtFixedRate(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any(TimeUnit.class));
        doAnswer(invocation -> {
            initialDelays.add(invocation.getArgument(1, Long.class));
            periods.add(invocation.getArgument(2, Long.class));
            return scheduledFuture;
        }).when(scheduledExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                any(TimeUnit.class));
        return schedulerWithExecutor(scheduledExecutor, nowNanos);
    }

    private CollectionScheduler schedulerWithExecutor(ScheduledExecutorService scheduledExecutor, AtomicLong nowNanos) {
        return new CollectionScheduler(
                collectionManager,
                configManager,
                mock(CollectionStatistics.class),
                collectorProperties,
                systemResourceProbe,
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                batchExecutor,
                reconnectCoordinator,
                scheduledExecutor,
                nowNanos::get);
    }

    private List<Long> captureClaims(AtomicLong nowNanos) {
        List<Long> claimedMillis = new CopyOnWriteArrayList<>();
        when(batchExecutor.isBatchTaskActive(any(DeviceBatchTask.class))).thenReturn(true);
        doAnswer(invocation -> {
            DeviceBatchTask task = invocation.getArgument(0);
            long claimNanos = invocation.getArgument(1, Long.class);
            SchedulerRuntimeState.PointDispatchClaim claim = task.claimDuePoints(runtimeState, task.points, claimNanos);
            if (!claim.isEmpty()) {
                claimedMillis.add(TimeUnit.NANOSECONDS.toMillis(claimNanos));
                runtimeState.completeClaim(claim);
            }
            return CompletableFuture.completedFuture(null);
        }).when(batchExecutor).submit(any(DeviceBatchTask.class), anyLong());
        return claimedMillis;
    }

    private void executeAtMillis(CollectionScheduler localScheduler, AtomicLong nowNanos, long millis) {
        nowNanos.set(TimeUnit.MILLISECONDS.toNanos(millis));
        localScheduler.executeTimeSlice(0, runtimeState.getTimeSliceRevision());
    }

    private long maxScansPerWindowBucket(int sliceCount, long phaseWheelTickMs, long bucketMs) {
        return IntStream.range(0, sliceCount)
                .mapToLong(slice -> slice * phaseWheelTickMs / Math.max(1L, bucketMs))
                .boxed()
                .collect(java.util.stream.Collectors.groupingBy(
                        bucket -> bucket,
                        java.util.stream.Collectors.counting()))
                .values()
                .stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
    }

    private long maxLegacyModuloScansPerWindowBucket(int sliceCount,
                                                     long timeSliceIntervalMs,
                                                     long dueScanIntervalMs,
                                                     long bucketMs) {
        return IntStream.range(0, sliceCount)
                .mapToLong(slice -> ((long) slice * timeSliceIntervalMs) % Math.max(1L, dueScanIntervalMs))
                .map(phase -> phase / Math.max(1L, bucketMs))
                .boxed()
                .collect(java.util.stream.Collectors.groupingBy(
                        bucket -> bucket,
                        java.util.stream.Collectors.counting()))
                .values()
                .stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
    }

    private DeviceBatchTask weightedTask(String deviceId, int pointCount) {
        List<DataPoint> points = IntStream.range(0, pointCount)
                .mapToObj(index -> point(deviceId, "p" + index))
                .toList();
        return new DeviceBatchTask(deviceId, points, 0, 1L, runtimeState.getTimeSliceRevision());
    }
}

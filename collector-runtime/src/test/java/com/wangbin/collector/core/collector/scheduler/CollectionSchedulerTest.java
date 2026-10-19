package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimePhase;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import com.wangbin.collector.core.collector.runtime.AcquisitionRuntimeTracker;
import com.wangbin.collector.core.collector.runtime.RuntimeStateCoordinator;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeState;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.port.SystemResourceProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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
import static org.mockito.Mockito.inOrder;
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
    private CollectionTaskGuard sampleGuard;
    private AcquisitionRuntimeTracker sampleTracker;

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
        sampleGuard = new CollectionTaskGuard();
        sampleTracker = new AcquisitionRuntimeTracker(sampleGuard);
        scheduler = schedulerWithExecutor(timeSliceScheduler, new AtomicLong(System.nanoTime()));
    }

    @Test
    void collectionSchedulerShouldReadCpuLoadThroughSystemResourceProbe() {
        when(systemResourceProbe.getProcessCpuLoad()).thenReturn(75D);

        assertEquals(75D, scheduler.resolveProcessCpuLoad());
    }

    @Test
    void destroyShouldCloseConfigRestartBeforeStoppingDevices() {
        TimeSliceSchedulingCoordinator schedulingCoordinator = mock(TimeSliceSchedulingCoordinator.class);
        TimeSliceExecutionCoordinator executionCoordinator = mock(TimeSliceExecutionCoordinator.class);
        TimeSliceConfigCoordinator configCoordinator = mock(TimeSliceConfigCoordinator.class);
        SchedulerMaintenanceCoordinator maintenanceCoordinator = mock(SchedulerMaintenanceCoordinator.class);
        ConfigRestartCoordinator restartCoordinator = mock(ConfigRestartCoordinator.class);
        CollectionScheduler localScheduler = new CollectionScheduler(
                collectionManager,
                mock(CollectionStatistics.class),
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                batchExecutor,
                reconnectCoordinator,
                schedulingCoordinator,
                executionCoordinator,
                configCoordinator,
                maintenanceCoordinator,
                restartCoordinator, sampleTracker);

        localScheduler.destroy();

        InOrder inOrder = inOrder(restartCoordinator, lifecycleCoordinator, maintenanceCoordinator, schedulingCoordinator);
        inOrder.verify(lifecycleCoordinator).beginShutdown();
        inOrder.verify(restartCoordinator).cancelAll();
        inOrder.verify(maintenanceCoordinator).cancel();
        inOrder.verify(schedulingCoordinator).cancelTimeSliceScheduling();
        inOrder.verify(lifecycleCoordinator).stopAllDevices();
    }

    @Test
    void reloadMustRefreshConfigurationWithoutGlobalStopOrStart() {
        ConfigRestartCoordinator restart = mock(ConfigRestartCoordinator.class);
        SchedulerMaintenanceCoordinator maintenance = mock(SchedulerMaintenanceCoordinator.class);
        CollectionScheduler localScheduler = new CollectionScheduler(collectionManager, mock(CollectionStatistics.class),
                runtimeState, performanceMonitor, lifecycleCoordinator, batchExecutor, reconnectCoordinator,
                mock(TimeSliceSchedulingCoordinator.class), mock(TimeSliceExecutionCoordinator.class),
                mock(TimeSliceConfigCoordinator.class), maintenance, restart, sampleTracker);
        localScheduler.setConfigManager(configManager);
        when(configManager.getAllDeviceIds()).thenReturn(List.of("dev-reload-a", "dev-reload-b"));
        localScheduler.reloadAllDevices();
        verify(configManager).refreshDeviceConfig("dev-reload-a");
        verify(configManager).refreshDeviceConfig("dev-reload-b");
        verify(restart).reloadChangedDevices();
        verify(lifecycleCoordinator, never()).stopAllDevices();
        verify(lifecycleCoordinator, never()).startAllDevices();
        verify(maintenance, never()).scheduleStartAllDevices(anyLong(), any(TimeUnit.class));
    }

    @Test
    void runtimeReadyRequiresOnlinePhaseWithoutErasingFirstSample() {
        String deviceId = "dev-runtime-ready";
        long generation = sampleGuard.activateNextGeneration(deviceId);
        DataPoint samplePoint = point(deviceId, "sample");
        sampleTracker.open(deviceId, generation, List.of(samplePoint));
        runtimeState.markRunning(deviceId, generation);
        performanceMonitor.resetDeviceRuntimeWindow(deviceId, generation);
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);

        DeviceRuntimeSnapshot waiting = scheduler.getDeviceRuntimeSnapshot(deviceId);
        assertEquals(DeviceRuntimePhase.WAITING_FIRST_SAMPLE, waiting.phase());
        assertFalse(waiting.ready());
        assertTrue(waiting.connected());

        performanceMonitor.recordBatchSuccess(deviceId, generation, 1, 10L);
        assertEquals(0L, scheduler.getDeviceRuntimeSnapshot(deviceId).firstSampleAt(), "批次统计不能替代核心处理事实");
        sampleTracker.recordCoreProcessedPoint(deviceId, samplePoint, generation, true, 100, System.currentTimeMillis(), "POLLING");
        sampleTracker.recordProtocolReady(deviceId, generation);
        DeviceRuntimeSnapshot online = scheduler.getDeviceRuntimeSnapshot(deviceId);
        assertEquals(DeviceRuntimePhase.ONLINE, online.phase());
        assertTrue(online.ready());
        assertTrue(online.firstSampleAt() > 0);

        performanceMonitor.recordBatchFailure(deviceId, generation);
        DeviceRuntimeSnapshot degraded = scheduler.getDeviceRuntimeSnapshot(deviceId);
        assertEquals(DeviceRuntimePhase.DEGRADED, degraded.phase());
        assertFalse(degraded.ready());
        assertTrue(degraded.connected());
        assertEquals(online.firstSampleAt(), degraded.firstSampleAt());

        for (int attempt = 0; attempt < 4; attempt++) {
            performanceMonitor.recordBatchFailure(deviceId, generation);
        }
        DeviceRuntimeSnapshot failed = scheduler.getDeviceRuntimeSnapshot(deviceId);
        assertEquals(DeviceRuntimePhase.FAILED, failed.phase());
        assertFalse(failed.ready());
        assertTrue(failed.connected());
        assertEquals(online.firstSampleAt(), failed.firstSampleAt());

        when(reconnectCoordinator.isReconnecting(deviceId)).thenReturn(true);
        DeviceRuntimeSnapshot reconnecting = scheduler.getDeviceRuntimeSnapshot(deviceId);
        assertEquals(DeviceRuntimePhase.RECONNECTING, reconnecting.phase());
        assertFalse(reconnecting.ready());
        assertTrue(reconnecting.connected());
        assertEquals(online.firstSampleAt(), reconnecting.firstSampleAt());
    }

    @Test
    void runtimeReadyMustFollowConnectionAndCurrentGeneration() {
        String deviceId = "dev-runtime-generation";
        long generation = sampleGuard.activateNextGeneration(deviceId);
        DataPoint samplePoint = point(deviceId, "sample");
        sampleTracker.open(deviceId, generation, List.of(samplePoint));
        runtimeState.markRunning(deviceId, generation);
        performanceMonitor.resetDeviceRuntimeWindow(deviceId, generation);
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);
        performanceMonitor.recordBatchSuccess(deviceId, generation, 1, 10L);
        sampleTracker.recordCoreProcessedPoint(deviceId, samplePoint, generation, true, 100, System.currentTimeMillis(), "POLLING");
        sampleTracker.recordProtocolReady(deviceId, generation);
        assertTrue(scheduler.getDeviceRuntimeSnapshot(deviceId).ready());

        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(false);
        DeviceRuntimeSnapshot disconnected = scheduler.getDeviceRuntimeSnapshot(deviceId);
        assertEquals(DeviceRuntimePhase.FAILED, disconnected.phase());
        assertFalse(disconnected.ready());
        assertFalse(disconnected.connected());
        assertTrue(disconnected.firstSampleAt() > 0);

        long nextGeneration = sampleGuard.activateNextGeneration(deviceId);
        sampleTracker.open(deviceId, nextGeneration, List.of(samplePoint));
        runtimeState.markRunning(deviceId, nextGeneration);
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);
        DeviceRuntimeSnapshot newGeneration = scheduler.getDeviceRuntimeSnapshot(deviceId);
        assertEquals(DeviceRuntimePhase.WAITING_FIRST_SAMPLE, newGeneration.phase());
        assertFalse(newGeneration.ready());
        assertEquals(0L, newGeneration.firstSampleAt());
    }

    @Test
    void subscriptionWithoutFirstMessageMustRemainWaitingBeyondPollingDeadline() {
        String deviceId = "subscription-wait";
        DataPoint point = healthPoint(deviceId, "event", "SUBSCRIPTION");
        long generation = sampleGuard.activateNextGeneration(deviceId);
        sampleTracker.open(deviceId, generation, List.of(point));
        sampleTracker.recordProtocolReady(deviceId, generation);
        RuntimeStateCoordinator coordinator = healthCoordinator(deviceId, generation, List.of(point));

        DeviceRuntimeState state = coordinator.snapshot(deviceId);
        assertEquals(DeviceRuntimeState.DeviceHealth.ONLINE_NO_DATA, state.health());
        assertEquals(DeviceRuntimeState.AcquisitionStatus.WAITING, state.acquisition());
        assertEquals(0L, state.firstValueDeadlineAt());
        assertEquals(0, state.failedPointCount());
        assertFalse(state.ready());
    }

    @Test
    void eventValueOnlyExpiresWhenAnExplicitValidityPeriodIsConfigured() {
        String deviceId = "event-freshness";
        DataPoint point = healthPoint(deviceId, "event", "EVENT");
        long generation = sampleGuard.activateNextGeneration(deviceId);
        sampleTracker.open(deviceId, generation, List.of(point));
        sampleTracker.recordProtocolReady(deviceId, generation);
        sampleTracker.recordCoreProcessedPoint(deviceId, point, generation, true, 100,
                System.currentTimeMillis() - 60_000L, "EVENT");
        RuntimeStateCoordinator coordinator = healthCoordinator(deviceId, generation, List.of(point));

        assertEquals(DeviceRuntimeState.DeviceHealth.ONLINE_HEALTHY, coordinator.snapshot(deviceId).health());
        point.setCacheDuration(1);
        DeviceRuntimeState expired = coordinator.snapshot(deviceId);
        assertEquals(1, expired.stalePointCount());
        assertEquals(0, expired.goodPointCount());
        assertFalse(expired.ready());
    }

    @Test
    void healthCountsMustDistinguishGoodFailedWaitingAndNonParticipatingPoints() {
        String deviceId = "partial-counts";
        List<DataPoint> points = IntStream.range(0, 12)
                .mapToObj(index -> healthPoint(deviceId, "p" + index, "SUBSCRIPTION")).toList();
        points.get(10).setStatus(0);
        points.get(11).setReadWrite("W");
        long generation = sampleGuard.activateNextGeneration(deviceId);
        sampleTracker.open(deviceId, generation, points);
        sampleTracker.recordProtocolReady(deviceId, generation);
        long at = System.currentTimeMillis();
        for (int index = 0; index < 3; index++) {
            sampleTracker.recordCoreProcessedPoint(deviceId, points.get(index), generation, true, 100, at, "EVENT");
        }
        sampleTracker.recordPointFailure(deviceId, generation, points.get(3).getPointId(), "DECODE_ERROR", at);
        DeviceRuntimeSnapshot runtime = healthCoordinator(deviceId, generation, points).runtimeSnapshot(deviceId);

        assertEquals(12, runtime.configuredPointCount());
        assertEquals(10, runtime.participatingPointCount());
        assertEquals(3, runtime.goodPointCount());
        assertEquals(1, runtime.failedPointCount());
        assertEquals(6, runtime.waitingPointCount());
        assertEquals(DeviceRuntimeState.DeviceHealth.ONLINE_PARTIAL, runtime.deviceHealth());
        assertFalse(runtime.ready());
    }

    @Test
    void successfulRecoveryAtSameTimestampMustNotRetainClearedFailure() {
        String deviceId = "same-timestamp-recovery";
        DataPoint point = healthPoint(deviceId, "p1", "EVENT");
        long generation = sampleGuard.activateNextGeneration(deviceId);
        sampleTracker.open(deviceId, generation, List.of(point));
        sampleTracker.recordProtocolReady(deviceId, generation);
        long at = System.currentTimeMillis();
        sampleTracker.recordPointFailure(deviceId, generation, point.getPointId(), "COMM_ERROR", at);
        sampleTracker.recordCoreProcessedPoint(deviceId, point, generation, true, 100, at, "EVENT");
        DeviceRuntimeState state = healthCoordinator(deviceId, generation, List.of(point)).snapshot(deviceId);

        assertEquals(DeviceRuntimeState.DeviceHealth.ONLINE_HEALTHY, state.health());
        assertEquals(0, state.failedPointCount());
        assertEquals(1, state.goodPointCount());
    }

    private DataPoint healthPoint(String deviceId, String pointId, String mode) {
        DataPoint point = point(deviceId, pointId);
        point.setStatus(1);
        point.setReadWrite("R");
        point.setCollectionMode(mode);
        return point;
    }

    private RuntimeStateCoordinator healthCoordinator(String deviceId, long generation, List<DataPoint> points) {
        CollectionScheduler runtimeQuery = mock(CollectionScheduler.class);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        when(configManager.getDevice(deviceId)).thenReturn(device);
        when(configManager.getDataPoints(deviceId)).thenReturn(points);
        when(runtimeQuery.getDesiredState(deviceId)).thenReturn(SchedulerRuntimeState.DesiredState.RUNNING);
        when(runtimeQuery.getDeviceRuntimeSnapshot(deviceId)).thenReturn(new DeviceRuntimeSnapshot(deviceId,
                DeviceRuntimePhase.WAITING_FIRST_SAMPLE, true, false, true, false, 0L,
                System.currentTimeMillis() - 120_000L, generation, 0L, 0, 0L, null, System.currentTimeMillis()));
        return new RuntimeStateCoordinator(runtimeQuery, configManager, sampleTracker,
                new com.wangbin.collector.core.collector.runtime.PointRuntimeStateService());
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
        scheduler = schedulerWithExecutor(manualScheduler, new AtomicLong(System.nanoTime()));
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
        TimeSliceExecutionCoordinator executionCoordinator = new TimeSliceExecutionCoordinator(
                runtimeState,
                performanceMonitor,
                batchExecutor,
                nowNanos::get);
        TimeSliceSchedulingCoordinator schedulingCoordinator = new TimeSliceSchedulingCoordinator(
                collectorProperties,
                runtimeState,
                performanceMonitor,
                executionCoordinator,
                scheduledExecutor,
                nowNanos::get);
        TimeSliceConfigCoordinator configCoordinator = new TimeSliceConfigCoordinator(
                collectorProperties,
                configManager,
                systemResourceProbe,
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                batchExecutor,
                schedulingCoordinator);
        SchedulerMaintenanceCoordinator maintenanceCoordinator = new SchedulerMaintenanceCoordinator(
                collectorProperties,
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                configCoordinator,
                scheduledExecutor);
        ConfigRestartCoordinator restartCoordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                configCoordinator,
                scheduledExecutor);
        return new CollectionScheduler(
                collectionManager,
                mock(CollectionStatistics.class),
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                batchExecutor,
                reconnectCoordinator,
                schedulingCoordinator,
                executionCoordinator,
                configCoordinator,
                maintenanceCoordinator,
                restartCoordinator, sampleTracker);
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

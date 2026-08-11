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
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    private DeviceBatchTask weightedTask(String deviceId, int pointCount) {
        List<DataPoint> points = IntStream.range(0, pointCount)
                .mapToObj(index -> point(deviceId, "p" + index))
                .toList();
        return new DeviceBatchTask(deviceId, points, 0, 1L, runtimeState.getTimeSliceRevision());
    }
}

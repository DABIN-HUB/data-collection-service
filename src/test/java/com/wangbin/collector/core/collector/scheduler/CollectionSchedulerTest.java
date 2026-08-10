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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyList;
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

        verify(batchExecutor, never()).submit(eq(staleTask), anyList());
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
        when(batchExecutor.submit(eq(task), anyList())).thenReturn(new CompletableFuture<>());

        scheduler.executeTimeSlice(0, runtimeState.getTimeSliceRevision());

        assertFalse(task.isCancelled());
    }

    private DataPoint point(String deviceId, String pointId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        return point;
    }
}

package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.monitor.health.CollectionServiceHealthTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private CollectionStatistics collectionStatistics;
    private CollectionServiceHealthTracker healthTracker;
    private DeviceBatchPlanner deviceBatchPlanner;
    private CollectedDataProcessor collectedDataProcessor;
    private CollectionTaskGuard collectionTaskGuard;
    private CollectorProperties collectorProperties;
    private ScheduledExecutorService timeSliceScheduler;
    private ThreadPoolExecutor batchDispatcher;
    private ThreadPoolExecutor asyncCollectorPool;
    private ThreadPoolExecutor dataProcessorPool;
    private CollectionScheduler scheduler;

    @BeforeEach
    void setUp() {
        collectionManager = mock(CollectionManager.class);
        configManager = mock(ConfigManager.class);
        collectionStatistics = mock(CollectionStatistics.class);
        healthTracker = mock(CollectionServiceHealthTracker.class);
        deviceBatchPlanner = mock(DeviceBatchPlanner.class);
        collectedDataProcessor = mock(CollectedDataProcessor.class);
        collectionTaskGuard = new CollectionTaskGuard();
        collectorProperties = new CollectorProperties();
        collectorProperties.getScheduler().setCollectTimeoutMs(50);
        collectorProperties.getScheduler().setDynamicAdjustIntervalMs(60000);
        collectorProperties.getScheduler().setInitialTimeSliceCount(1);
        collectorProperties.getScheduler().setMaxTimeSliceCount(4);
        collectorProperties.getScheduler().setInitialTimeSliceIntervalMs(1000);
        collectorProperties.getScheduler().setMinTimeSliceIntervalMs(50);
        collectorProperties.getAdaptiveCollection().setEnabled(false);

        timeSliceScheduler = Executors.newSingleThreadScheduledExecutor();
        batchDispatcher = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        asyncCollectorPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        dataProcessorPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);

        scheduler = new CollectionScheduler(timeSliceScheduler, batchDispatcher, asyncCollectorPool, dataProcessorPool);
        ReflectionTestUtils.setField(scheduler, "collectionManager", collectionManager);
        ReflectionTestUtils.setField(scheduler, "configManager", configManager);
        ReflectionTestUtils.setField(scheduler, "collectionStatistics", collectionStatistics);
        ReflectionTestUtils.setField(scheduler, "collectorProperties", collectorProperties);
        ReflectionTestUtils.setField(scheduler, "collectionServiceHealthTracker", healthTracker);
        ReflectionTestUtils.setField(scheduler, "deviceBatchPlanner", deviceBatchPlanner);
        ReflectionTestUtils.setField(scheduler, "collectedDataProcessor", collectedDataProcessor);
        ReflectionTestUtils.setField(scheduler, "collectionTaskGuard", collectionTaskGuard);
        ReflectionTestUtils.invokeMethod(scheduler, "resetTimeSliceTaskBuckets", 2);
    }

    @Test
    void collectionSchedulerShouldCancelCollectFutureOnCollectTimeout() throws Exception {
        setupSingleDevice("dev-timeout");
        AtomicBoolean interrupted = new AtomicBoolean(false);
        when(collectionManager.readPoints(eq("dev-timeout"), anyList())).thenAnswer(invocation -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            }
            return Map.of("p1", 1);
        });

        scheduler.startDevice("dev-timeout");
        DeviceBatchTask task = firstScheduledTask(0);
        ReflectionTestUtils.invokeMethod(scheduler, "processDeviceBatch", task);

        TimeUnit.MILLISECONDS.sleep(50);
        assertFalse(Boolean.FALSE.equals(interrupted.get()));
        verify(collectedDataProcessor, never()).process(eq("dev-timeout"), anyList(), eq(Map.of("p1", 1)), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void collectionSchedulerShouldNotProcessStoppedDeviceInflightResults() throws Exception {
        setupSingleDevice("dev-stop");
        CompletableFuture<Map<String, Object>> gate = new CompletableFuture<>();
        when(collectionManager.readPoints(eq("dev-stop"), anyList())).thenAnswer(invocation -> gate.get(2, TimeUnit.SECONDS));

        scheduler.startDevice("dev-stop");
        DeviceBatchTask task = firstScheduledTask(0);

        CompletableFuture<Void> runFuture = CompletableFuture.runAsync(
                () -> ReflectionTestUtils.invokeMethod(scheduler, "processDeviceBatch", task)
        );
        TimeUnit.MILLISECONDS.sleep(100);
        scheduler.stopDevice("dev-stop");
        gate.complete(Map.of("p1", 1));
        runFuture.get(2, TimeUnit.SECONDS);

        verify(collectedDataProcessor, never()).process(eq("dev-stop"), anyList(), eq(Map.of("p1", 1)), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void collectionSchedulerShouldIgnoreStaleGenerationBatchResult() throws Exception {
        setupSingleDevice("dev-stale");
        when(collectionManager.readPoints(eq("dev-stale"), anyList())).thenReturn(Map.of("p1", 1));

        scheduler.startDevice("dev-stale");
        DeviceBatchTask staleTask = firstScheduledTask(0);
        scheduler.stopDevice("dev-stale");
        scheduler.startDevice("dev-stale");

        ReflectionTestUtils.invokeMethod(scheduler, "processDeviceBatch", staleTask);

        verify(collectedDataProcessor, never()).process(eq("dev-stale"), anyList(), eq(Map.of("p1", 1)), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void collectionSchedulerShouldRebuildAssignmentsOnSliceCountChange() {
        setupSingleDevice("dev-rebuild");
        scheduler.startDevice("dev-rebuild");
        DeviceBatchTask oldTask = firstScheduledTask(0);

        ReflectionTestUtils.invokeMethod(scheduler, "applyTimeSliceConfigUpdate", 3, 1000);

        DeviceBatchTask newTask = firstScheduledTask(0);
        assertNotEquals(oldTask.timeSliceRevision, newTask.timeSliceRevision);
    }

    @Test
    void collectionSchedulerShouldSkipStaleRevisionTasks() {
        setupSingleDevice("dev-revision");
        when(collectionManager.readPoints(eq("dev-revision"), anyList())).thenReturn(Map.of("p1", 1));
        scheduler.startDevice("dev-revision");
        DeviceBatchTask staleTask = firstScheduledTask(0);

        ReflectionTestUtils.invokeMethod(scheduler, "applyTimeSliceConfigUpdate", 3, 1000);
        ReflectionTestUtils.invokeMethod(scheduler, "processDeviceBatch", staleTask);

        verify(collectionManager, never()).readPoints(eq("dev-revision"), anyList());
    }

    @Test
    void collectionSchedulerShouldCancelSliceTasksWhenSliceExecutionTimeout() throws Exception {
        setupSingleDevice("dev-slice-timeout");
        collectorProperties.getScheduler().setCollectTimeoutMs(500);
        when(collectionManager.readPoints(eq("dev-slice-timeout"), anyList())).thenAnswer(invocation -> {
            Thread.sleep(1000);
            return Map.of("p1", 1);
        });

        ReflectionTestUtils.setField(scheduler, "timeSliceInterval", new AtomicInteger(80));
        scheduler.startDevice("dev-slice-timeout");
        AtomicLong revisionHolder = (AtomicLong) ReflectionTestUtils.getField(scheduler, "timeSliceRevision");
        long revision = revisionHolder.get();

        ReflectionTestUtils.invokeMethod(scheduler, "executeTimeSlice", 0, revision);

        TimeUnit.MILLISECONDS.sleep(100);
        DeviceBatchTask task = firstScheduledTask(0);
        assertTrue(task.isCancelled());
    }

    private void setupSingleDevice(String deviceId) {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId(deviceId);
        deviceInfo.setProtocolType("MODBUS_TCP");

        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId("p1");
        point.setPointCode("p1");

        when(configManager.getDevice(deviceId)).thenReturn(deviceInfo);
        when(configManager.getDataPoints(deviceId)).thenReturn(List.of(point));
        when(configManager.getDataPointsAndAdaptiveConfig(deviceId)).thenReturn(List.of(point));
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);

        doAnswer(invocation -> null).when(collectionManager).registerDevice(deviceInfo);
        doAnswer(invocation -> null).when(collectionManager).connectDevice(deviceId);
        doAnswer(invocation -> null).when(collectionManager).disconnectDevice(deviceId);
        doAnswer(invocation -> null).when(collectionManager).rebuildReadPlans(eq(deviceId), anyList());

        when(deviceBatchPlanner.plan(eq(deviceId), anyList(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<DataPoint> points = invocation.getArgument(1);
                    int timeSliceIndex = 0;
                    long generation = invocation.getArgument(3);
                    long revision = invocation.getArgument(4);
                    return List.of(new DeviceBatchTask(deviceId, points, timeSliceIndex, generation, revision));
                });
    }

    @SuppressWarnings("unchecked")
    private DeviceBatchTask firstScheduledTask(int timeSliceIndex) {
        Map<Integer, List<DeviceBatchTask>> tasks =
                (Map<Integer, List<DeviceBatchTask>>) ReflectionTestUtils.getField(scheduler, "timeSliceTasks");
        return tasks.get(timeSliceIndex).get(0);
    }
}

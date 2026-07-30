package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.protocol.bacnet.BacnetIpCollector;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CollectionSchedulerTest {

    private CollectionManager collectionManager;
    private ConfigManager configManager;
    private CollectionStatistics collectionStatistics;
    private CollectionServiceHealthTracker healthTracker;
    private DeviceBatchPlanner deviceBatchPlanner;
    private ProtocolBatchStrategy protocolBatchStrategy;
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
        protocolBatchStrategy = mock(ProtocolBatchStrategy.class);
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
        ReflectionTestUtils.setField(scheduler, "protocolBatchStrategy", protocolBatchStrategy);
        ReflectionTestUtils.setField(scheduler, "collectedDataProcessor", collectedDataProcessor);
        ReflectionTestUtils.setField(scheduler, "collectionTaskGuard", collectionTaskGuard);
        when(protocolBatchStrategy.defaultBatchSize(anyString())).thenReturn(10);
        when(protocolBatchStrategy.maxBatchSize(anyString())).thenReturn(100);
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

        TimeUnit.MILLISECONDS.sleep(100);
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
    void collectionSchedulerShouldKeepPeriodicTaskWhenSliceExecutionTimeout() throws Exception {
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
        assertFalse(task.isCancelled());
    }

    @Test
    void collectionSchedulerShouldTimeoutBlockedConnectWithoutBlockingOtherStarts() {
        collectorProperties.getScheduler().setDeviceStartTimeoutMs(100);
        setupSingleDevice("dev-connect-timeout");
        setupSingleDevice("dev-connect-ok");

        doAnswer(invocation -> {
            Thread.sleep(2000);
            return null;
        }).when(collectionManager).connectDevice("dev-connect-timeout");
        doAnswer(invocation -> null).when(collectionManager).connectDevice("dev-connect-ok");

        boolean timeoutResult = scheduler.startDevice("dev-connect-timeout");
        boolean secondResult = scheduler.startDevice("dev-connect-ok");

        assertFalse(timeoutResult);
        assertTrue(secondResult);
        verify(collectionManager).cleanupDevice("dev-connect-timeout");
    }

    @Test
    void collectionSchedulerShouldAutoSubscribeBacnetSubscriptionPointsAndSkipPollingPlan() {
        String deviceId = "dev-bacnet";
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId(deviceId);
        deviceInfo.setProtocolType("BACNET_IP");
        deviceInfo.setConnectionType("BACNET_IP");

        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("BACNET_IP");
        connection.setHost("127.0.0.1");
        connection.setPort(47808);
        connection.setConnectTimeout(50);
        connection.setReadTimeout(50);
        connection.setTimeout(50);
        connection.setExtJson(Map.of("covEnabled", true));

        DataPoint subscriptionPoint = new DataPoint();
        subscriptionPoint.setDeviceId(deviceId);
        subscriptionPoint.setPointId("p1");
        subscriptionPoint.setPointCode("p1");
        subscriptionPoint.setStatus(1);
        subscriptionPoint.setCollectionMode("SUBSCRIPTION");

        DataPoint pollingPoint = new DataPoint();
        pollingPoint.setDeviceId(deviceId);
        pollingPoint.setPointId("p2");
        pollingPoint.setPointCode("p2");
        pollingPoint.setStatus(1);
        pollingPoint.setCollectionMode("POLLING");

        BacnetIpCollector bacnetCollector = spy(new BacnetIpCollector());
        bacnetCollector.init(deviceInfo);
        ReflectionTestUtils.setField(bacnetCollector, "dataQualityProcessor", mock(com.wangbin.collector.core.processor.DataQualityProcessor.class));
        ReflectionTestUtils.setField(bacnetCollector, "configManager", configManager);

        when(configManager.getDevice(deviceId)).thenReturn(deviceInfo);
        when(configManager.getDataPoints(deviceId)).thenReturn(List.of(subscriptionPoint, pollingPoint));
        when(configManager.getDataPointsAndAdaptiveConfig(deviceId)).thenReturn(List.of(subscriptionPoint, pollingPoint));
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection);
        when(configManager.getDeviceContext(deviceId))
                .thenReturn(DeviceContext.of(deviceInfo, connection, List.of(subscriptionPoint, pollingPoint)));
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);
        when(collectionManager.getCollector(deviceId)).thenReturn(bacnetCollector);

        doAnswer(invocation -> null).when(collectionManager).registerDevice(deviceInfo);
        doAnswer(invocation -> null).when(collectionManager).connectDevice(deviceId);
        doAnswer(invocation -> null).when(collectionManager).disconnectDevice(deviceId);
        doAnswer(invocation -> null).when(collectionManager).cleanupDevice(anyString());
        doAnswer(invocation -> null).when(collectionManager).rebuildReadPlans(eq(deviceId), anyList());
        doAnswer(invocation -> null).when(collectionManager).subscribePoints(eq(deviceId), anyList());

        when(deviceBatchPlanner.plan(eq(deviceId), anyList(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<DataPoint> points = invocation.getArgument(1);
                    return List.of(new DeviceBatchTask(deviceId, points, 0, invocation.getArgument(3), invocation.getArgument(4)));
                });

        boolean started = scheduler.startDevice(deviceId);

        assertTrue(started);
        verify(collectionManager).subscribePoints(eq(deviceId), eq(List.of(subscriptionPoint)));
        DeviceBatchTask task = firstScheduledTask(0);
        assertEquals(1, task.points.size());
        assertEquals("p2", task.points.get(0).getPointId());
    }

    private void setupSingleDevice(String deviceId) {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId(deviceId);
        deviceInfo.setProtocolType("MODBUS_TCP");

        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(502);
        connection.setConnectTimeout(50);
        connection.setReadTimeout(50);
        connection.setTimeout(50);

        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId("p1");
        point.setPointCode("p1");

        when(configManager.getDevice(deviceId)).thenReturn(deviceInfo);
        when(configManager.getDataPoints(deviceId)).thenReturn(List.of(point));
        when(configManager.getDataPointsAndAdaptiveConfig(deviceId)).thenReturn(List.of(point));
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection);
        when(collectionManager.isDeviceConnected(deviceId)).thenReturn(true);

        doAnswer(invocation -> null).when(collectionManager).registerDevice(deviceInfo);
        doAnswer(invocation -> null).when(collectionManager).connectDevice(deviceId);
        doAnswer(invocation -> null).when(collectionManager).disconnectDevice(deviceId);
        doAnswer(invocation -> null).when(collectionManager).cleanupDevice(anyString());
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

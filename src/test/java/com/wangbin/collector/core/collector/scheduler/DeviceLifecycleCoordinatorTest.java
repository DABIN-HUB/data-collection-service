package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.protocol.bacnet.BacnetIpCollector;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.monitor.health.CollectionServiceHealthTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceLifecycleCoordinatorTest {

    private CollectionManager collectionManager;
    private ConfigManager configManager;
    private DeviceBatchPlanner deviceBatchPlanner;
    private SchedulerRuntimeState runtimeState;
    private CollectorProperties collectorProperties;
    private ThreadPoolExecutor deviceStartExecutor;
    private DeviceLifecycleCoordinator lifecycleCoordinator;

    @BeforeEach
    void setUp() {
        collectionManager = mock(CollectionManager.class);
        configManager = mock(ConfigManager.class);
        deviceBatchPlanner = mock(DeviceBatchPlanner.class);
        runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(1, 1000);
        collectorProperties = new CollectorProperties();
        collectorProperties.getAdaptiveCollection().setEnabled(false);
        collectorProperties.getScheduler().setDeviceStartTimeoutMs(1000);
        ProtocolBatchStrategy protocolBatchStrategy = mock(ProtocolBatchStrategy.class);
        when(protocolBatchStrategy.defaultBatchSize(anyString())).thenReturn(10);
        when(protocolBatchStrategy.maxBatchSize(anyString())).thenReturn(100);
        deviceStartExecutor = fixedPool("lifecycle-start", 1);
        lifecycleCoordinator = new DeviceLifecycleCoordinator(
                collectionManager,
                configManager,
                mock(CollectionStatistics.class),
                collectorProperties,
                mock(CollectionServiceHealthTracker.class),
                deviceBatchPlanner,
                protocolBatchStrategy,
                new CollectionTaskGuard(),
                new PointRuntimeStateService(),
                runtimeState,
                new PerformanceMonitor(),
                mock(DeviceBatchExecutor.class),
                mock(ReconnectCoordinator.class),
                deviceStartExecutor);
    }

    @AfterEach
    void tearDown() {
        deviceStartExecutor.shutdownNow();
    }

    @Test
    void blockedConnectTimeoutDoesNotBlockOtherDeviceStarts() throws Exception {
        setupSingleDevice("dev-connect-timeout");
        setupSingleDevice("dev-connect-ok");
        doAnswer(invocation -> {
            Thread.sleep(2000);
            return null;
        }).when(collectionManager).connectDevice("dev-connect-timeout");
        doAnswer(invocation -> null).when(collectionManager).connectDevice("dev-connect-ok");

        boolean timeoutResult = lifecycleCoordinator.startDevice("dev-connect-timeout");
        boolean secondResult = lifecycleCoordinator.startDevice("dev-connect-ok");

        assertFalse(timeoutResult);
        assertTrue(secondResult);
        verify(collectionManager).cleanupDevice("dev-connect-timeout");
    }

    @Test
    void bacnetSubscriptionPointsAreAutoSubscribedAndExcludedFromPollingPlan() throws Exception {
        String deviceId = "dev-bacnet";
        DeviceInfo deviceInfo = device(deviceId, "BACNET_IP");
        DeviceConnection connection = connection(deviceId, "BACNET_IP");
        connection.setExtJson(Map.of("covEnabled", true));
        DataPoint subscriptionPoint = point(deviceId, "p1");
        subscriptionPoint.setCollectionMode("SUBSCRIPTION");
        DataPoint pollingPoint = point(deviceId, "p2");
        pollingPoint.setCollectionMode("POLLING");
        BacnetIpCollector bacnetCollector = spy(new BacnetIpCollector());
        bacnetCollector.init(deviceInfo);
        ReflectionTestUtils.setField(bacnetCollector, "configManager", configManager);

        when(configManager.getDevice(deviceId)).thenReturn(deviceInfo);
        when(configManager.getDataPoints(deviceId)).thenReturn(List.of(subscriptionPoint, pollingPoint));
        when(configManager.getDataPointsAndAdaptiveConfig(deviceId)).thenReturn(List.of(subscriptionPoint, pollingPoint));
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection);
        when(configManager.getDeviceContext(deviceId))
                .thenReturn(DeviceContext.of(deviceInfo, connection, List.of(subscriptionPoint, pollingPoint)));
        when(collectionManager.getCollector(deviceId)).thenReturn(bacnetCollector);
        doAnswer(invocation -> null).when(collectionManager).registerDevice(deviceInfo);
        doAnswer(invocation -> null).when(collectionManager).connectDevice(deviceId);
        doAnswer(invocation -> null).when(collectionManager).rebuildReadPlans(eq(deviceId), anyList());
        doAnswer(invocation -> null).when(collectionManager).subscribePoints(eq(deviceId), anyList());
        when(deviceBatchPlanner.plan(eq(deviceId), anyList(), eq(1), org.mockito.ArgumentMatchers.anyLong(), eq(1L)))
                .thenAnswer(invocation -> List.of(new DeviceBatchTask(
                        deviceId,
                        invocation.getArgument(1),
                        0,
                        invocation.getArgument(3),
                        invocation.getArgument(4))));

        boolean started = lifecycleCoordinator.startDevice(deviceId);

        assertTrue(started);
        verify(collectionManager).subscribePoints(eq(deviceId), eq(List.of(subscriptionPoint)));
        DeviceBatchTask task = runtimeState.getSliceTasks(0).get(0);
        assertEquals(1, task.points.size());
        assertEquals("p2", task.points.get(0).getPointId());
    }

    private void setupSingleDevice(String deviceId) throws Exception {
        DeviceInfo deviceInfo = device(deviceId, "MODBUS_TCP");
        DeviceConnection connection = connection(deviceId, "MODBUS_TCP");
        DataPoint point = point(deviceId, "p1");
        when(configManager.getDevice(deviceId)).thenReturn(deviceInfo);
        when(configManager.getDataPoints(deviceId)).thenReturn(List.of(point));
        when(configManager.getDataPointsAndAdaptiveConfig(deviceId)).thenReturn(List.of(point));
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection);
        when(configManager.getDeviceContext(deviceId))
                .thenReturn(DeviceContext.of(deviceInfo, connection, List.of(point)));
        doAnswer(invocation -> null).when(collectionManager).registerDevice(deviceInfo);
        doAnswer(invocation -> null).when(collectionManager).cleanupDevice(anyString());
        doAnswer(invocation -> null).when(collectionManager).rebuildReadPlans(eq(deviceId), anyList());
        when(deviceBatchPlanner.plan(eq(deviceId), anyList(), eq(1), org.mockito.ArgumentMatchers.anyLong(), eq(1L)))
                .thenAnswer(invocation -> List.of(new DeviceBatchTask(
                        deviceId,
                        invocation.getArgument(1),
                        0,
                        invocation.getArgument(3),
                        invocation.getArgument(4))));
    }

    private DeviceInfo device(String deviceId, String protocol) {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId(deviceId);
        deviceInfo.setProtocolType(protocol);
        deviceInfo.setConnectionType(protocol);
        return deviceInfo;
    }

    private DeviceConnection connection(String deviceId, String protocol) {
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType(protocol);
        connection.setHost("127.0.0.1");
        connection.setPort(502);
        connection.setConnectTimeout(50);
        connection.setReadTimeout(50);
        connection.setTimeout(50);
        return connection;
    }

    private DataPoint point(String deviceId, String pointId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setStatus(1);
        return point;
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
}

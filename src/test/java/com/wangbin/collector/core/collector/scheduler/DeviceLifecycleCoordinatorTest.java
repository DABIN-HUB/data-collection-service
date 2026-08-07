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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceLifecycleCoordinatorTest {

    private CollectionManager collectionManager;
    private ConfigManager configManager;
    private DeviceBatchPlanner deviceBatchPlanner;
    private SchedulerRuntimeState runtimeState;
    private CollectorProperties collectorProperties;
    private ThreadPoolExecutor deviceStartExecutor;
    private ExecutorService lifecycleCallExecutor;
    private CollectionTaskGuard collectionTaskGuard;
    private CollectionStatistics collectionStatistics;
    private CollectionServiceHealthTracker healthTracker;
    private DeviceBatchExecutor deviceBatchExecutor;
    private ReconnectCoordinator reconnectCoordinator;
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
        deviceStartExecutor = fixedPool("lifecycle-start", 2);
        lifecycleCallExecutor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("lifecycle-call-" + thread.getId());
            return thread;
        });
        collectionTaskGuard = new CollectionTaskGuard();
        collectionStatistics = mock(CollectionStatistics.class);
        healthTracker = mock(CollectionServiceHealthTracker.class);
        deviceBatchExecutor = mock(DeviceBatchExecutor.class);
        reconnectCoordinator = mock(ReconnectCoordinator.class);
        lifecycleCoordinator = new DeviceLifecycleCoordinator(
                collectionManager,
                configManager,
                collectionStatistics,
                collectorProperties,
                healthTracker,
                deviceBatchPlanner,
                protocolBatchStrategy,
                collectionTaskGuard,
                new PointRuntimeStateService(),
                runtimeState,
                new PerformanceMonitor(),
                deviceBatchExecutor,
                reconnectCoordinator,
                deviceStartExecutor);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        shutdownExecutor(deviceStartExecutor);
        shutdownExecutor(lifecycleCallExecutor);
    }

    @Test
    void stopWhileStartingMustNeverMarkRunning() throws Exception {
        String deviceId = "dev-stop-starting";
        setupSingleDevice(deviceId);
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        blockConnectUntil(deviceId, connectEntered, releaseConnect);

        CompletableFuture<Boolean> startFuture = startAsync(deviceId);
        assertTrue(connectEntered.await(1, TimeUnit.SECONDS));
        long oldGeneration = runtimeState.getStartingGeneration(deviceId);
        assertTrue(oldGeneration > 0L);
        assertTrue(runtimeState.isStarting(deviceId));

        CompletableFuture<Boolean> stopFuture = CompletableFuture.supplyAsync(
                () -> lifecycleCoordinator.stopDevice(deviceId),
                lifecycleCallExecutor);
        assertTrue(stopFuture.get(1, TimeUnit.SECONDS));
        releaseConnect.countDown();

        assertFalse(startFuture.get(1, TimeUnit.SECONDS));
        assertFalse(runtimeState.isRunning(deviceId));
        assertFalse(runtimeState.isStarting(deviceId));
        assertFalse(collectionTaskGuard.isCurrent(deviceId, oldGeneration));
        assertTrue(runtimeState.getSliceTasks(0).isEmpty());
        verify(collectionStatistics, never()).startCollection(eq(deviceId), anyInt());
        verify(healthTracker, never()).markDeviceStarted(deviceId);
        verify(collectionManager, never()).rebuildReadPlans(eq(deviceId), anyList());
    }

    @Test
    void staleStartGenerationMustNotBecomeRunning() throws Exception {
        String deviceId = "dev-stale-start";
        setupSingleDevice(deviceId);
        CountDownLatch oldConnectEntered = new CountDownLatch(1);
        CountDownLatch releaseOldConnect = new CountDownLatch(1);
        AtomicInteger connectCalls = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (connectCalls.incrementAndGet() == 1) {
                oldConnectEntered.countDown();
                awaitReleaseIgnoringInterrupt(releaseOldConnect);
            }
            return null;
        }).when(collectionManager).connectDevice(deviceId);

        CompletableFuture<Boolean> oldStartFuture = startAsync(deviceId);
        assertTrue(oldConnectEntered.await(1, TimeUnit.SECONDS));
        long oldGeneration = runtimeState.getStartingGeneration(deviceId);
        assertTrue(oldGeneration > 0L);

        assertTrue(lifecycleCoordinator.stopDevice(deviceId));
        CompletableFuture<Boolean> newStartFuture = startAsync(deviceId);
        assertTrue(newStartFuture.get(1, TimeUnit.SECONDS));
        DeviceScheduleInfo newScheduleInfo = runtimeState.getScheduleInfo(deviceId);
        assertNotNull(newScheduleInfo);
        long newGeneration = newScheduleInfo.getGeneration();
        assertNotEquals(oldGeneration, newGeneration);

        releaseOldConnect.countDown();
        assertFalse(oldStartFuture.get(1, TimeUnit.SECONDS));
        assertTrue(runtimeState.isRunning(deviceId));
        assertEquals(newGeneration, runtimeState.getScheduleInfo(deviceId).getGeneration());
        assertTrue(runtimeState.getSliceTasks(0).stream().allMatch(task -> task.generation == newGeneration));
        assertFalse(collectionTaskGuard.isCurrent(deviceId, oldGeneration));
        assertTrue(collectionTaskGuard.isCurrent(deviceId, newGeneration));
        verify(collectionStatistics, times(1)).startCollection(eq(deviceId), anyInt());
        verify(healthTracker, times(1)).markDeviceStarted(deviceId);
    }

    @Test
    void shutdownMustCancelStartingDevices() throws Exception {
        String runningDevice = "dev-running";
        String startingDevice1 = "dev-starting-b";
        String startingDevice2 = "dev-starting-c";
        setupSingleDevice(runningDevice);
        setupSingleDevice(startingDevice1);
        setupSingleDevice(startingDevice2);
        CountDownLatch startingEntered = new CountDownLatch(2);
        CountDownLatch releaseStarting = new CountDownLatch(1);
        doAnswer(invocation -> null).when(collectionManager).connectDevice(runningDevice);
        blockConnectUntilCancelledOrReleased(startingDevice1, startingEntered, releaseStarting);
        blockConnectUntilCancelledOrReleased(startingDevice2, startingEntered, releaseStarting);

        assertTrue(lifecycleCoordinator.startDevice(runningDevice));
        CompletableFuture<Boolean> startingFuture1 = startAsync(startingDevice1);
        CompletableFuture<Boolean> startingFuture2 = startAsync(startingDevice2);
        assertTrue(startingEntered.await(1, TimeUnit.SECONDS));
        long generation1 = runtimeState.getStartingGeneration(startingDevice1);
        long generation2 = runtimeState.getStartingGeneration(startingDevice2);
        assertTrue(generation1 > 0L);
        assertTrue(generation2 > 0L);

        lifecycleCoordinator.stopAllDevices();
        releaseStarting.countDown();

        assertFalse(startingFuture1.get(1, TimeUnit.SECONDS));
        assertFalse(startingFuture2.get(1, TimeUnit.SECONDS));
        assertTrue(runtimeState.getActiveDeviceIds().isEmpty());
        assertFalse(runtimeState.isRunning(runningDevice));
        assertFalse(runtimeState.isStarting(startingDevice1));
        assertFalse(runtimeState.isStarting(startingDevice2));
        assertFalse(collectionTaskGuard.isCurrent(startingDevice1, generation1));
        assertFalse(collectionTaskGuard.isCurrent(startingDevice2, generation2));
        verify(healthTracker, never()).markDeviceStarted(startingDevice1);
        verify(healthTracker, never()).markDeviceStarted(startingDevice2);
    }

    @Test
    void blockedConnectMustNotBlockIndependentDeviceStart() throws Exception {
        String blockedDevice = "dev-connect-blocked";
        String healthyDevice = "dev-connect-healthy";
        setupSingleDevice(blockedDevice);
        setupSingleDevice(healthyDevice);
        CountDownLatch blockedEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocked = new CountDownLatch(1);
        blockConnectUntil(blockedDevice, blockedEntered, releaseBlocked);
        doAnswer(invocation -> null).when(collectionManager).connectDevice(healthyDevice);

        CompletableFuture<Boolean> blockedStart = startAsync(blockedDevice);
        assertTrue(blockedEntered.await(1, TimeUnit.SECONDS));
        assertTrue(runtimeState.isStarting(blockedDevice));

        CompletableFuture<Boolean> healthyStart = startAsync(healthyDevice);

        assertTrue(healthyStart.get(1, TimeUnit.SECONDS));
        assertTrue(runtimeState.isRunning(healthyDevice));
        assertFalse(runtimeState.isRunning(blockedDevice));
        assertTrue(lifecycleCoordinator.stopDevice(blockedDevice));
        releaseBlocked.countDown();
        assertFalse(blockedStart.get(1, TimeUnit.SECONDS));
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
        doAnswer(invocation -> null).when(collectionManager).disconnectDevice(deviceId);
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

    private CompletableFuture<Boolean> startAsync(String deviceId) {
        return CompletableFuture.supplyAsync(() -> lifecycleCoordinator.startDevice(deviceId), lifecycleCallExecutor);
    }

    private void blockConnectUntil(String deviceId, CountDownLatch entered, CountDownLatch release) throws Exception {
        doAnswer(invocation -> {
            entered.countDown();
            awaitReleaseIgnoringInterrupt(release);
            return null;
        }).when(collectionManager).connectDevice(deviceId);
    }

    private void blockConnectUntilCancelledOrReleased(String deviceId,
                                                     CountDownLatch entered,
                                                     CountDownLatch release) throws Exception {
        doAnswer(invocation -> {
            entered.countDown();
            try {
                release.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(collectionManager).connectDevice(deviceId);
    }

    private void awaitReleaseIgnoringInterrupt(CountDownLatch release) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (release.getCount() > 0 && System.nanoTime() < deadline) {
            try {
                long remainingNanos = Math.max(1L, deadline - System.nanoTime());
                if (release.await(remainingNanos, TimeUnit.NANOSECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
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

    private void shutdownExecutor(ExecutorService executorService) throws InterruptedException {
        executorService.shutdownNow();
        assertTrue(executorService.awaitTermination(1, TimeUnit.SECONDS));
    }
}

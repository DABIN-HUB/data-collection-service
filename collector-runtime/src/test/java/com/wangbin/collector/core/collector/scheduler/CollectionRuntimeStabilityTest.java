package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.port.CollectionHealthReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionRuntimeStabilityTest {

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void slowDeviceShouldNotPermanentlyBlockIndependentDevice() throws Exception {
        CollectionManager collectionManager = mock(CollectionManager.class);
        ConfigManager configManager = mock(ConfigManager.class);
        CollectorProperties properties = collectorProperties();
        CollectionTaskGuard guard = new CollectionTaskGuard();
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(1, 200);
        PerformanceMonitor performanceMonitor = new PerformanceMonitor();
        CountingCollectedDataProcessor processor = new CountingCollectedDataProcessor(properties, performanceMonitor);
        ThreadPoolExecutor batchDispatcher = fixedPool("stability-batch", 2, 16);
        ThreadPoolExecutor collectorExecutor = fixedPool("stability-collect", 2, 16);
        ThreadPoolExecutor processorExecutor = fixedPool("stability-process", 1, 16);
        ThreadPoolExecutor reconnectExecutor = fixedPool("stability-reconnect", 1, 16);
        ScheduledExecutorService timeSliceScheduler = scheduled("stability-slice");
        ExecutorService testExecutor = Executors.newSingleThreadExecutor(runnable -> daemonThread(runnable, "stability-test"));
        executors.add(testExecutor);

        ReconnectCoordinator reconnectCoordinator = new ReconnectCoordinator(
                collectionManager, properties, guard, runtimeState, reconnectExecutor);
        DeviceBatchExecutor batchExecutor = new DeviceBatchExecutor(
                collectionManager,
                configManager,
                mock(CollectionStatistics.class),
                properties,
                processor,
                guard,
                runtimeState,
                performanceMonitor,
                reconnectCoordinator,
                batchDispatcher,
                collectorExecutor,
                processorExecutor);
        DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        TimeSliceExecutionCoordinator executionCoordinator = new TimeSliceExecutionCoordinator(
                runtimeState,
                performanceMonitor,
                batchExecutor);
        TimeSliceSchedulingCoordinator schedulingCoordinator = new TimeSliceSchedulingCoordinator(
                properties,
                runtimeState,
                performanceMonitor,
                executionCoordinator,
                timeSliceScheduler,
                System::nanoTime);
        TimeSliceConfigCoordinator configCoordinator = new TimeSliceConfigCoordinator(
                properties,
                configManager,
                null,
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                batchExecutor,
                schedulingCoordinator);
        SchedulerMaintenanceCoordinator maintenanceCoordinator = new SchedulerMaintenanceCoordinator(
                properties,
                runtimeState,
                performanceMonitor,
                lifecycleCoordinator,
                configCoordinator,
                timeSliceScheduler);
        ConfigRestartCoordinator configRestartCoordinator = new ConfigRestartCoordinator(
                lifecycleCoordinator,
                configCoordinator,
                timeSliceScheduler);
        CollectionScheduler scheduler = new CollectionScheduler(
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
                configRestartCoordinator);
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastProcessed = processor.latchFor("fast", 1);

        configureRunningBatch(runtimeState, guard, configManager, "slow");
        configureRunningBatch(runtimeState, guard, configManager, "fast");
        when(collectionManager.isDeviceConnected(anyString())).thenReturn(true);
        when(collectionManager.readPoints(eq("slow"), anyList())).thenAnswer(invocation -> {
            slowEntered.countDown();
            releaseSlow.await(2, TimeUnit.SECONDS);
            return values(invocation.getArgument(1));
        });
        when(collectionManager.readPoints(eq("fast"), anyList())).thenAnswer(invocation -> values(invocation.getArgument(1)));

        CompletableFuture<Void> sliceFuture = CompletableFuture.runAsync(
                () -> scheduler.executeTimeSlice(0, runtimeState.getTimeSliceRevision()),
                testExecutor);

        assertTrue(slowEntered.await(1, TimeUnit.SECONDS));
        assertTrue(fastProcessed.await(1, TimeUnit.SECONDS));
        assertEquals(1L, processor.processedPoints("fast"));
        assertEquals(0L, batchExecutor.getBatchDispatchRejectedCount());
        assertEquals(0L, batchExecutor.getCollectRejectedCount());
        assertEquals(0L, batchExecutor.getProcessRejectedCount());

        releaseSlow.countDown();
        waitUntil(() -> processor.processedPoints("slow") == 1L);
        waitUntil(() -> batchExecutor.getTotalInFlightFutureCountForTest() == 0
                && batchDispatcher.getQueue().isEmpty()
                && collectorExecutor.getQueue().isEmpty()
                && processorExecutor.getQueue().isEmpty());
        sliceFuture.get(2, TimeUnit.SECONDS);
    }

    @Test
    void reconnectStormShouldDeduplicateBackoffAndRecover() throws Exception {
        CollectionManager collectionManager = mock(CollectionManager.class);
        CollectorProperties properties = collectorProperties();
        properties.getScheduler().setReconnectBaseDelayMs(500);
        properties.getScheduler().setReconnectMaxDelayMs(1000);
        CollectionTaskGuard guard = new CollectionTaskGuard();
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(1, 1000);
        ThreadPoolExecutor reconnectExecutor = fixedPool("storm-reconnect", 8, 128);
        ReconnectCoordinator coordinator = new ReconnectCoordinator(
                collectionManager, properties, guard, runtimeState, reconnectExecutor);
        int deviceCount = 50;
        List<String> deviceIds = new ArrayList<>(deviceCount);
        List<Long> generations = new ArrayList<>(deviceCount);
        AtomicBoolean failReconnect = new AtomicBoolean(true);
        AtomicInteger reconnectCalls = new AtomicInteger(0);
        CountDownLatch firstWaveEntered = new CountDownLatch(8);
        CountDownLatch releaseFirstWave = new CountDownLatch(1);
        doAnswer(invocation -> {
            reconnectCalls.incrementAndGet();
            firstWaveEntered.countDown();
            releaseFirstWave.await(2, TimeUnit.SECONDS);
            if (failReconnect.get()) {
                throw new IllegalStateException("simulated disconnect");
            }
            return null;
        }).when(collectionManager).reconnectDevice(anyString());

        for (int i = 0; i < deviceCount; i++) {
            String deviceId = "storm-" + i;
            deviceIds.add(deviceId);
            long generation = guard.activateNextGeneration(deviceId);
            generations.add(generation);
            runtimeState.markRunning(deviceId, generation);
        }

        for (int i = 0; i < deviceCount; i++) {
            coordinator.scheduleIfNeeded(deviceIds.get(i), generations.get(i));
            coordinator.scheduleIfNeeded(deviceIds.get(i), generations.get(i));
        }

        assertEquals(deviceCount, coordinator.getAttemptCount());
        assertEquals(deviceCount, coordinator.getReconnectingDeviceCount());
        assertTrue(firstWaveEntered.await(1, TimeUnit.SECONDS));
        releaseFirstWave.countDown();
        waitUntil(() -> coordinator.getFailureCount() == deviceCount);
        waitUntil(() -> coordinator.getReconnectingDeviceCount() == 0);
        assertEquals(deviceCount, reconnectCalls.get());

        for (int i = 0; i < deviceCount; i++) {
            coordinator.scheduleIfNeeded(deviceIds.get(i), generations.get(i));
        }
        assertEquals(deviceCount, coordinator.getAttemptCount());

        TimeUnit.MILLISECONDS.sleep(550);
        failReconnect.set(false);
        for (int i = 0; i < deviceCount; i++) {
            coordinator.scheduleIfNeeded(deviceIds.get(i), generations.get(i));
        }
        waitUntil(() -> coordinator.getSuccessCount() == deviceCount);
        waitUntil(() -> coordinator.getReconnectingDeviceCount() == 0);

        for (int i = 0; i < deviceCount; i++) {
            runtimeState.removeDevice(deviceIds.get(i));
            guard.clearDevice(deviceIds.get(i));
            coordinator.clear(deviceIds.get(i));
            coordinator.scheduleIfNeeded(deviceIds.get(i), generations.get(i));
        }
        assertEquals(deviceCount * 2L, coordinator.getAttemptCount());
        assertEquals(deviceCount, coordinator.getFailureCount());
        assertEquals(deviceCount, coordinator.getSuccessCount());
    }

    @Test
    void repeatedStartStopRestartShouldCleanLifecycleState() throws Exception {
        CollectionManager collectionManager = mock(CollectionManager.class);
        ConfigManager configManager = mock(ConfigManager.class);
        CollectorProperties properties = collectorProperties();
        CollectionTaskGuard guard = new CollectionTaskGuard();
        SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(2, 1000);
        PerformanceMonitor performanceMonitor = new PerformanceMonitor();
        DeviceBatchPlanner planner = mock(DeviceBatchPlanner.class);
        ProtocolBatchStrategy protocolBatchStrategy = mock(ProtocolBatchStrategy.class);
        ThreadPoolExecutor batchDispatcher = fixedPool("loop-batch", 2, 32);
        ThreadPoolExecutor collectorExecutor = fixedPool("loop-collect", 2, 32);
        ThreadPoolExecutor processorExecutor = fixedPool("loop-process", 2, 32);
        ThreadPoolExecutor startExecutor = fixedPool("loop-start", 4, 64);
        ThreadPoolExecutor reconnectExecutor = fixedPool("loop-reconnect", 2, 64);
        CollectionStatistics statistics = mock(CollectionStatistics.class);
        CollectionHealthReporter healthReporter = mock(CollectionHealthReporter.class);
        ReconnectCoordinator reconnectCoordinator = new ReconnectCoordinator(
                collectionManager, properties, guard, runtimeState, reconnectExecutor);
        DeviceBatchExecutor batchExecutor = new DeviceBatchExecutor(
                collectionManager,
                configManager,
                statistics,
                properties,
                new CountingCollectedDataProcessor(properties, performanceMonitor),
                guard,
                runtimeState,
                performanceMonitor,
                reconnectCoordinator,
                batchDispatcher,
                collectorExecutor,
                processorExecutor);
        PointRuntimeStateService pointRuntimeStateService = new PointRuntimeStateService();
        DeviceStartPreparer startPreparer = new DeviceStartPreparer(
                configManager,
                properties,
                guard,
                pointRuntimeStateService,
                runtimeState,
                reconnectCoordinator);
        DeviceLifecycleCleanup lifecycleCleanup = new DeviceLifecycleCleanup(
                collectionManager,
                statistics,
                healthReporter,
                pointRuntimeStateService,
                runtimeState,
                batchExecutor,
                reconnectCoordinator,
                guard);
        DeviceLifecycleCoordinator lifecycleCoordinator = new DeviceLifecycleCoordinator(
                collectionManager,
                statistics,
                healthReporter,
                planner,
                protocolBatchStrategy,
                guard,
                runtimeState,
                performanceMonitor,
                startPreparer,
                lifecycleCleanup,
                startExecutor);
        int deviceCount = 5;
        List<String> deviceIds = new ArrayList<>(deviceCount);
        for (int i = 0; i < deviceCount; i++) {
            String deviceId = "loop-" + i;
            deviceIds.add(deviceId);
            DeviceInfo device = device(deviceId);
            DeviceConnection connection = connection(deviceId);
            List<DataPoint> points = List.of(point(deviceId, "p1"));
            when(configManager.getDevice(deviceId)).thenReturn(device);
            when(configManager.getDataPoints(deviceId)).thenReturn(points);
            when(configManager.getDataPointsAndAdaptiveConfig(deviceId)).thenReturn(points);
            when(configManager.getConnectionConfig(deviceId)).thenReturn(connection);
            when(planner.plan(eq(deviceId), anyList(), anyInt(), anyLong(), anyLong()))
                    .thenAnswer(invocation -> List.of(new DeviceBatchTask(
                            deviceId,
                            invocation.getArgument(1),
                            0,
                            invocation.getArgument(3),
                            invocation.getArgument(4))));
        }
        when(protocolBatchStrategy.defaultBatchSize(anyString())).thenReturn(10);
        when(protocolBatchStrategy.maxBatchSize(anyString())).thenReturn(100);
        doAnswer(invocation -> null).when(collectionManager).registerDevice(org.mockito.ArgumentMatchers.any(DeviceInfo.class));
        doAnswer(invocation -> null).when(collectionManager).connectDevice(anyString());
        doAnswer(invocation -> null).when(collectionManager).rebuildReadPlans(anyString(), anyList());
        doAnswer(invocation -> null).when(collectionManager).disconnectDevice(anyString());
        doAnswer(invocation -> null).when(collectionManager).cleanupDevice(anyString());

        for (int i = 0; i < 100; i++) {
            String deviceId = deviceIds.get(i % deviceCount);
            assertTrue(lifecycleCoordinator.startDevice(deviceId));
            long generation = runtimeState.getScheduleInfo(deviceId).getGeneration();
            assertTrue(lifecycleCoordinator.stopDevice(deviceId));
            assertFalse(guard.isCurrent(deviceId, generation));
        }

        assertTrue(runtimeState.getActiveDeviceIds().isEmpty());
        assertEquals(0L, runtimeState.getTotalTaskCount());
        assertEquals(0, lifecycleCoordinator.startingFutureCountForTest());
        waitUntil(() -> lifecycleCoordinator.lifecycleLockHolderCountForTest() == 0);
        assertEquals(0, batchExecutor.getTotalInFlightFutureCountForTest());
    }

    private void configureRunningBatch(SchedulerRuntimeState runtimeState,
                                       CollectionTaskGuard guard,
                                       ConfigManager configManager,
                                       String deviceId) {
        long generation = guard.activateNextGeneration(deviceId);
        runtimeState.markRunning(deviceId, generation);
        DataPoint point = point(deviceId, "p1");
        when(configManager.getConnectionConfig(deviceId)).thenReturn(connection(deviceId));
        runtimeState.addBatchTasks(List.of(new DeviceBatchTask(
                deviceId,
                List.of(point),
                0,
                generation,
                runtimeState.getTimeSliceRevision())));
    }

    private Map<String, Object> values(List<DataPoint> points) {
        java.util.HashMap<String, Object> values = new java.util.HashMap<>();
        for (DataPoint point : points) {
            values.put(point.getPointId(), 1);
        }
        return values;
    }

    private CollectorProperties collectorProperties() {
        CollectorProperties properties = new CollectorProperties();
        properties.getAdaptiveCollection().setEnabled(false);
        properties.getScheduler().setCollectTimeoutMs(1000);
        properties.getScheduler().setDeviceStartTimeoutMs(1000);
        properties.getScheduler().setReconnectBaseDelayMs(100);
        properties.getScheduler().setReconnectMaxDelayMs(1000);
        return properties;
    }

    private DeviceInfo device(String deviceId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setProtocolType("MODBUS_TCP");
        device.setConnectionType("MODBUS_TCP");
        return device;
    }

    private DeviceConnection connection(String deviceId) {
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(502);
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(1000);
        connection.setTimeout(1000);
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

    private ThreadPoolExecutor fixedPool(String namePrefix, int threads, int queueCapacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                runnable -> daemonThread(runnable, namePrefix));
        executors.add(executor);
        return executor;
    }

    private ScheduledExecutorService scheduled(String namePrefix) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                runnable -> daemonThread(runnable, namePrefix));
        executors.add(executor);
        return executor;
    }

    private Thread daemonThread(Runnable runnable, String prefix) {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName(prefix + "-" + thread.getId());
        return thread;
    }

    private void waitUntil(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(condition.isSatisfied());
    }

    private static final class CountingCollectedDataProcessor extends CollectedDataProcessor {
        private final java.util.concurrent.ConcurrentHashMap<String, LongAdder> processedByDevice = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, CountDownLatch> latches = new java.util.concurrent.ConcurrentHashMap<>();
        private final PerformanceMonitor performanceMonitor;

        private CountingCollectedDataProcessor(CollectorProperties collectorProperties, PerformanceMonitor performanceMonitor) {
            super(collectorProperties, new PointRuntimeStateService(), performanceMonitor);
            this.performanceMonitor = performanceMonitor;
        }

        @Override
        void process(String deviceId, List<DataPoint> points, Map<String, Object> values) {
            for (DataPoint point : points) {
                if (values.containsKey(point.getPointId())) {
                    processedByDevice.computeIfAbsent(deviceId, ignored -> new LongAdder()).increment();
                    performanceMonitor.recordDataProcessed(deviceId);
                    CountDownLatch latch = latches.get(deviceId);
                    if (latch != null) {
                        latch.countDown();
                    }
                }
            }
        }

        private CountDownLatch latchFor(String deviceId, int count) {
            CountDownLatch latch = new CountDownLatch(count);
            latches.put(deviceId, latch);
            return latch;
        }

        private long processedPoints(String deviceId) {
            LongAdder adder = processedByDevice.get(deviceId);
            return adder == null ? 0L : adder.sum();
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}

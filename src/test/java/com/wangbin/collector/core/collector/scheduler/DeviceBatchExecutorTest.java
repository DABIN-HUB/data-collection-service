package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @BeforeEach
    void setUp() {
        collectionManager = mock(CollectionManager.class);
        configManager = mock(ConfigManager.class);
        collectedDataProcessor = mock(CollectedDataProcessor.class);
        collectionTaskGuard = new CollectionTaskGuard();
        runtimeState = new SchedulerRuntimeState();
        runtimeState.initializeTimeSlices(1, 1000);
        batchDispatcher = fixedPool("batch-test");
        asyncCollectorExecutor = fixedPool("collect-test");
        dataProcessorExecutor = fixedPool("process-test");
        testExecutor = Executors.newSingleThreadExecutor();
        CollectorProperties collectorProperties = new CollectorProperties();
        collectorProperties.getScheduler().setCollectTimeoutMs(100);
        batchExecutor = new DeviceBatchExecutor(
                collectionManager,
                configManager,
                mock(CollectionStatistics.class),
                collectorProperties,
                collectedDataProcessor,
                collectionTaskGuard,
                runtimeState,
                new PerformanceMonitor(),
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

    private long markRunning(String deviceId) {
        long generation = collectionTaskGuard.activateNextGeneration(deviceId);
        runtimeState.markRunning(deviceId, generation);
        return generation;
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
        return new ThreadPoolExecutor(
                1,
                1,
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
}

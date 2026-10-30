package com.wangbin.collector.core.collector.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.config.sqlite.runtime.WangbinSqliteLocalConfigurationPersistence;
import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import com.wangbin.collector.core.cache.aspect.CollectorDataCacheAspect;
import com.wangbin.collector.core.cache.aspect.CollectorDataPostProcessor;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessPipeline;
import com.wangbin.collector.core.collector.factory.CollectorFactory;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.protocol.modbus.Plc4xModbusTcpCollector;
import com.wangbin.collector.core.collector.protocol.modbus.support.FakeModbusTcpServer;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.loader.FileConfigLoader;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.persistence.WangbinLocalConfigurationPersistence;
import com.wangbin.collector.core.config.store.LocalDeviceConfigStore;
import com.wangbin.collector.core.connection.adapter.Plc4xModbusTcpConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionFactory;
import com.wangbin.collector.core.connection.manager.ConnectionManager;
import com.wangbin.collector.core.port.CollectionHealthReporter;
import com.wangbin.collector.core.processor.DataQualityProcessorTestSupport;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import com.wangbin.collector.core.report.validator.FieldUniquenessValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 SQLite、生命周期、调度执行、PLC4X 网络读取；仅隔离下游遥测落库/外部服务。 */
class WangbinPhase4ModbusSqliteChainTest {
    @TempDir Path directory;

    @Test
    void manualStartScheduledPointReadStopAndNewGenerationReadChangedValue() throws Exception {
        final String deviceA = "sqlite-modbus-a";
        final String deviceB = "sqlite-modbus-b";
        try (FakeModbusTcpServer server = new FakeModbusTcpServer();
             SqliteConfigurationStore store = SqliteConfigurationStore.createNew(
                     directory.resolve("isolated/config.db"), 1000)) {
            server.setHoldingRegister(4001, 101);
            server.setHoldingRegister(4002, 202);
            server.setHoldingRegister(4003, 303);
            DeviceContext initialA = configuration(deviceA, "holding-4001", "44001", server.port());
            DeviceContext initialB = configuration(deviceB, "holding-4003", "44003", server.port());
            CollectorProperties properties = new CollectorProperties();
            properties.getAdaptiveCollection().setEnabled(false);
            properties.getScheduler().setDeviceStartTimeoutMs(5000);
            properties.getScheduler().setCollectTimeoutMs(5000);
            Path remote = directory.resolve("empty-remote.json");
            Files.writeString(remote, "[]");
            properties.getConfig().getFile().setDevices(remote.toString());
            properties.getConfig().setLocalDeviceSnapshotFile(directory.resolve("unused.json").toString());
            var persistence = new WangbinSqliteLocalConfigurationPersistence(store);
            var provider = new StaticListableBeanFactory(Map.of("sqlite", persistence))
                    .getBeanProvider(WangbinLocalConfigurationPersistence.class);
            var restartCoordinatorReference = new AtomicReference<ConfigRestartCoordinator>();
            var events = new CopyOnWriteArrayList<ConfigUpdateEvent>();
            var config = new ConfigManager(new ConfigSyncService(new FileConfigLoader(properties), Runnable::run, properties),
                    event -> {
                        if (event instanceof ConfigUpdateEvent update) {
                            events.add(update);
                            ConfigRestartCoordinator restart = restartCoordinatorReference.get();
                            if (restart != null) restart.handleConfigUpdate(update);
                        }
                    }, new FieldUniquenessValidator(), new LocalDeviceConfigStore(new ObjectMapper(), properties),
                    null, null, provider);
            config.init();
            // 阶段 05 保存通过正式本地配置入口完成；启动仍只按 ID 读取，不恢复 SQLite 全库。
            assertEquals(1L, config.saveLocalDeviceConfigWithResult(initialA.getDeviceInfo(),
                    initialA.copyConnectionConfig(), initialA.copyDataPoints(), false).persistedRevision());
            assertEquals(2L, config.saveLocalDeviceConfigWithResult(initialB.getDeviceInfo(),
                    initialB.copyConnectionConfig(), initialB.copyDataPoints(), false).persistedRevision());
            assertNull(config.getDeviceContext(deviceA));
            assertNull(config.getDeviceContext(deviceB));
            var guard = new CollectionTaskGuard();
            var connectionFactory = mock(ConnectionFactory.class);
            when(connectionFactory.createConnection(any(DeviceInfo.class), any(DeviceConnection.class)))
                    .thenAnswer(call -> new Plc4xModbusTcpConnectionAdapter(call.getArgument(0), call.getArgument(1)));
            var connections = new ConnectionManager(connectionFactory, config, null, null);
            connections.setCollectionTaskGuard(guard);
            var telemetry = mock(TelemetryPostProcessPipeline.class);
            when(telemetry.processCore(any())).thenReturn(CompletableFuture.completedFuture(true));
            var post = new CollectorDataPostProcessor(Runnable::run, telemetry, guard);
            var collectorFactory = mock(CollectorFactory.class);
            var currentCollectors = new ConcurrentHashMap<String, AtomicReference<Plc4xModbusTcpCollector>>();
            when(collectorFactory.createCollector(any(DeviceInfo.class))).thenAnswer(call -> {
                var collector = new Plc4xModbusTcpCollector();
                collector.setBaseCollectorDependencies(properties, connections, config,
                        DataQualityProcessorTestSupport.create(),
                        new StaticListableBeanFactory().getBeanProvider(com.wangbin.collector.core.port.ExceptionReporter.class),
                        new StaticListableBeanFactory().getBeanProvider(com.wangbin.collector.core.collector.ingress.TelemetryIngressService.class));
                collector.setRequestTaskGuard(guard);
                DeviceInfo device = call.getArgument(0);
                collector.init(device);
                currentCollectors.computeIfAbsent(device.getDeviceId(), ignored -> new AtomicReference<>()).set(collector);
                var proxy = new AspectJProxyFactory(collector);
                proxy.addAspect(new CollectorDataCacheAspect(post));
                return proxy.getProxy();
            });
            var collectors = new CollectionManager(collectorFactory, connections);
            collectors.setCollectionTaskGuard(guard);
            var statistics = mock(CollectionStatistics.class);
            var health = mock(CollectionHealthReporter.class);
            var reconnect = mock(ReconnectCoordinator.class);
            var state = new SchedulerRuntimeState();
            state.initializeTimeSlices(1, 1000);
            var points = new PointRuntimeStateService();
            var planner = mock(DeviceBatchPlanner.class);
            var tasks = new ConcurrentHashMap<String, DeviceBatchTask>();
            var firstGeneration = new AtomicLong();
            var secondGeneration = new AtomicLong();
            var restarted = new CountDownLatch(1);
            when(planner.plan(anyString(), anyList(), anyInt(), anyLong(), anyLong())).thenAnswer(call -> {
                String id = call.getArgument(0);
                var planned = new DeviceBatchTask(id, call.getArgument(1), 0,
                        call.getArgument(3), call.getArgument(4));
                tasks.put(id, planned);
                if (deviceA.equals(id) && firstGeneration.get() > 0
                        && planned.generation > firstGeneration.get()) {
                    secondGeneration.set(planned.generation);
                    restarted.countDown();
                }
                return List.of(planned);
            });
            var strategy = mock(ProtocolBatchStrategy.class);
            when(strategy.defaultBatchSize(anyString())).thenReturn(10);
            when(strategy.maxBatchSize(anyString())).thenReturn(100);
            var monitor = new PerformanceMonitor();
            var preparer = new DeviceStartPreparer(config, properties, guard, points, state, reconnect);
            ThreadPoolExecutor starts = executor();
            ThreadPoolExecutor collects = executor();
            ThreadPoolExecutor processing = executor();
            ExecutorService dispatch = Executors.newSingleThreadExecutor();
            var downstream = mock(CollectedDataProcessor.class);
            var batches = new DeviceBatchExecutor(collectors, config, statistics, properties, downstream,
                    guard, state, monitor, reconnect, dispatch, collects, processing);
            var cleanup = new DeviceLifecycleCleanup(collectors, statistics, health, points, state,
                    batches, reconnect, guard);
            var lifecycle = new DeviceLifecycleCoordinator(collectors, statistics, health, planner, strategy,
                    guard, state, monitor, preparer, cleanup, starts);
            ScheduledExecutorService restartExecutor = new ScheduledThreadPoolExecutor(1);
            var configRestart = new ConfigRestartCoordinator(lifecycle, mock(TimeSliceConfigCoordinator.class),
                    restartExecutor, config);
            restartCoordinatorReference.set(configRestart);
            try {
                assertTrue(lifecycle.startDevice(deviceA));
                firstGeneration.set(state.getScheduleInfo(deviceA).getGeneration());
                assertTrue(lifecycle.startDevice(deviceB));
                long bGeneration = state.getScheduleInfo(deviceB).getGeneration();
                assertEquals(2, collectors.getAllDeviceIds().size());
                assertTrue(collectors.isDeviceConnected(deviceA));
                assertTrue(collectors.isDeviceConnected(deviceB));
                assertEquals("44001", config.getDataPoints(deviceA).get(0).getAddress());
                assertEquals("44003", config.getDataPoints(deviceB).get(0).getAddress());
                DeviceBatchTask firstTask = tasks.get(deviceA);
                DeviceBatchTask bTask = tasks.get(deviceB);
                assertNotNull(firstTask);
                assertNotNull(bTask);
                var firstCollector = currentCollectors.get(deviceA).get();
                var bCollector = currentCollectors.get(deviceB).get();
                var firstDispatch = batches.submit(firstTask);
                assertNotNull(firstDispatch);
                firstDispatch.get(8, TimeUnit.SECONDS);
                ProcessResult first = firstCollector.getLatestProcessResult("holding-4001");
                assertSample(first, 101);
                var bDispatch = batches.submit(bTask);
                assertNotNull(bDispatch);
                bDispatch.get(8, TimeUnit.SECONDS);
                ProcessResult bFirst = bCollector.getLatestProcessResult("holding-4003");
                assertSample(bFirst, 303);
                assertTrue(server.readRequestCount() > 0);
                long firstSampleAt = ((Number) first.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue();
                long bSampleAt = ((Number) bFirst.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue();
                long revisionBeforeEdit = store.findDevice(deviceA).orElseThrow().revision();
                long globalRevisionBeforeEdit = store.currentRevision();
                List<DataPoint> editedPoints = config.getConfigurationDeviceSnapshot(deviceA).context().copyDataPoints();
                editedPoints.get(0).setAddress("44002");
                assertTrue(config.updateDataPoints(deviceA, editedPoints, revisionBeforeEdit));
                assertEquals(globalRevisionBeforeEdit + 1, store.findDevice(deviceA).orElseThrow().revision());
                assertTrue(events.stream().anyMatch(event -> deviceA.equals(event.getDeviceId())
                        && "points".equals(event.getConfigType())));
                assertTrue(restarted.await(8, TimeUnit.SECONDS), "配置变更事件必须完成 A 的受控重启");
                long actualSecondGeneration = secondGeneration.get();
                assertTrue(actualSecondGeneration > firstGeneration.get());
                DeviceBatchTask secondTask = tasks.get(deviceA);
                assertNotNull(secondTask);
                assertEquals(actualSecondGeneration, secondTask.generation);
                assertEquals(2, collectors.getAllDeviceIds().size());
                assertEquals("44002", config.getDataPoints(deviceA).get(0).getAddress());
                assertNotSame(firstCollector, currentCollectors.get(deviceA).get());
                assertSame(bCollector, currentCollectors.get(deviceB).get());
                assertEquals(bGeneration, bTask.generation);
                assertTrue(collectors.isDeviceConnected(deviceB));
                assertNull(batches.submit(firstTask), "旧 generation 任务不得继续提交旧数据");
                var secondDispatch = batches.submit(secondTask);
                assertNotNull(secondDispatch);
                secondDispatch.get(8, TimeUnit.SECONDS);
                var second = currentCollectors.get(deviceA).get();
                ProcessResult freshResult = second.getLatestProcessResult("holding-4001");
                assertSample(freshResult, 202);
                assertTrue(((Number) freshResult.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue()
                        > firstSampleAt);
                var bAfterDispatch = batches.submit(tasks.get(deviceB));
                assertNotNull(bAfterDispatch);
                bAfterDispatch.get(8, TimeUnit.SECONDS);
                ProcessResult bAfter = bCollector.getLatestProcessResult("holding-4003");
                assertSample(bAfter, 303);
                assertTrue(((Number) bAfter.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue() > bSampleAt);
                assertEquals(2, collectors.getAllDeviceIds().size());
                long editedRevision = store.findDevice(deviceA).orElseThrow().revision();
                assertEquals(config.getConfigurationDeviceSnapshot(deviceA).persistedRevision(), editedRevision);
                System.out.printf("PHASE5_RUNNING_MODBUS firstGeneration=%d firstValue=%s firstQuality=%d firstAt=%d "
                                + "secondGeneration=%d secondValue=%s secondQuality=%d secondAt=%d "
                                + "bGeneration=%d bValue=%s bQuality=%d bAt=%d totalReads=%d revision=%d%n",
                        firstGeneration.get(), first.getProcessedValue(), first.getQuality(), firstSampleAt,
                        actualSecondGeneration, freshResult.getProcessedValue(), freshResult.getQuality(),
                        ((Number) freshResult.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue(),
                        bGeneration, bAfter.getProcessedValue(), bAfter.getQuality(),
                        ((Number) bAfter.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue(),
                        server.readRequestCount(), editedRevision);
                assertTrue(lifecycle.stopDevice(deviceA));
                assertNull(config.getDeviceContext(deviceA));
                assertTrue(store.findDevice(deviceA).isPresent());
                assertEquals(1, collectors.getAllDeviceIds().size());
                assertTrue(collectors.getAllDeviceIds().contains(deviceB));
                assertNotNull(config.getDeviceContext(deviceB));
                assertEquals(bGeneration, bTask.generation);
                assertNull(batches.submit(secondTask), "STOP 后 A 的新 generation 任务不得继续提交");
                assertTrue(lifecycle.stopDevice(deviceB));
                assertEquals(0, collectors.getAllDeviceIds().size());
            } finally {
                configRestart.cancelAll();
                lifecycle.beginShutdown();
                lifecycle.stopAllDevices();
                collectors.destroy();
                connections.destroy();
                dispatch.shutdownNow();
                starts.shutdownNow();
                collects.shutdownNow();
                processing.shutdownNow();
                restartExecutor.shutdownNow();
                assertTrue(dispatch.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(starts.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(collects.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(processing.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(restartExecutor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static DeviceContext configuration(String deviceId, String pointId, String address, int port) {
        var device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName("隔离 Modbus 设备 " + deviceId);
        device.setProtocolType("MODBUS_TCP");
        device.setConnectionType("MODBUS_TCP");
        device.setConfigSource("local");
        device.setTemporaryConfig(true);
        device.setCollectionInterval(1000);
        var connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(port);
        connection.setReadTimeout(3000);
        connection.setTimeout(3000);
        connection.setExtJson(Map.of("slaveId", 1, "byteOrder", "BIG_ENDIAN"));
        var point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setPointName("保持寄存器 " + address);
        point.setDeviceId(deviceId);
        point.setDeviceName(device.getDeviceName());
        point.setAddress(address);
        point.setDataType("UINT16");
        point.setUnitId(1);
        point.setStatus(1);
        point.setReadWrite("R");
        return DeviceContext.of(device, connection, List.of(point));
    }

    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(16));
    }

    private static void assertSample(ProcessResult result, int expected) {
        assertNotNull(result, "必须取得点位处理结果，连接成功不等于采集成功");
        assertTrue(result.isSuccess());
        assertEquals(expected, ((Number) result.getProcessedValue()).intValue());
        assertEquals(100, result.getQuality());
        assertTrue(((Number) result.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue() > 0);
    }
}

package com.wangbin.collector.core.collector.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
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
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.persistence.SqliteConfigService;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 SQLite、生命周期、调度执行、PLC4X 网络读取；仅隔离下游遥测落库/外部服务。 */
class WangbinPhase4ModbusSqliteChainTest {
    @TempDir Path directory;

    @Test
    void manualStartScheduledPointReadStopAndNewGenerationReadChangedValue() throws Exception {
        try (FakeModbusTcpServer server = new FakeModbusTcpServer();
             SqliteConfigurationStore store = SqliteConfigurationStore.createNew(
                     directory.resolve("isolated/config.db"), 1000)) {
            server.setHoldingRegister(4001, 101);
            SqliteConfigService sqlite = store;
            var device = new DeviceInfo();
            device.setDeviceId("sqlite-modbus-a");
            device.setDeviceName("隔离 Modbus 设备");
            device.setProtocolType("MODBUS_TCP");
            device.setConnectionType("MODBUS_TCP");
            device.setConfigSource("local");
            device.setTemporaryConfig(true);
            device.setCollectionInterval(1000);
            var connection = new DeviceConnection();
            connection.setDeviceId(device.getDeviceId());
            connection.setConnectionType("MODBUS_TCP");
            connection.setHost("127.0.0.1");
            connection.setPort(server.port());
            connection.setReadTimeout(3000);
            connection.setTimeout(3000);
            connection.setExtJson(Map.of("slaveId", 1, "byteOrder", "BIG_ENDIAN"));
            var point = new DataPoint();
            point.setPointId("holding-4001");
            point.setPointCode("holding-4001");
            point.setPointName("保持寄存器 4001");
            point.setDeviceId(device.getDeviceId());
            point.setDeviceName(device.getDeviceName());
            point.setAddress("44001");
            point.setDataType("UINT16");
            point.setUnitId(1);
            point.setStatus(1);
            point.setReadWrite("R");
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
            var config = new ConfigManager(new ConfigSyncService(new FileConfigLoader(properties), Runnable::run, properties),
                    event -> { }, new FieldUniquenessValidator(), new LocalDeviceConfigStore(new ObjectMapper(), properties),
                    null, null, provider);
            config.init();
            // 现有 init() 会全量恢复：隔离新设备在初始化之后按正式写入口保存，再走手动 START。
            sqlite.transaction(writer -> writer.saveDevice(new ConfigurationJsonCodec().fromRuntime(
                    DeviceContext.of(device, connection, List.of(point))), 0));
            assertNull(config.getDeviceContext(device.getDeviceId()));
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
            var currentCollector = new AtomicReference<Plc4xModbusTcpCollector>();
            when(collectorFactory.createCollector(any(DeviceInfo.class))).thenAnswer(call -> {
                var collector = new Plc4xModbusTcpCollector();
                collector.setBaseCollectorDependencies(properties, connections, config,
                        DataQualityProcessorTestSupport.create(),
                        new StaticListableBeanFactory().getBeanProvider(com.wangbin.collector.core.port.ExceptionReporter.class),
                        new StaticListableBeanFactory().getBeanProvider(com.wangbin.collector.core.collector.ingress.TelemetryIngressService.class));
                collector.setRequestTaskGuard(guard);
                collector.init(call.getArgument(0));
                currentCollector.set(collector);
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
            var task = new AtomicReference<DeviceBatchTask>();
            when(planner.plan(anyString(), anyList(), anyInt(), anyLong(), anyLong())).thenAnswer(call -> {
                var planned = new DeviceBatchTask(call.getArgument(0), call.getArgument(1), 0,
                        call.getArgument(3), call.getArgument(4));
                task.set(planned);
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
            try {
                assertTrue(lifecycle.startDevice(device.getDeviceId()));
                long firstGeneration = state.getScheduleInfo(device.getDeviceId()).getGeneration();
                assertTrue(collectors.isDeviceConnected(device.getDeviceId()));
                assertEquals("44001", config.getDataPoints(device.getDeviceId()).get(0).getAddress());
                assertNotNull(task.get());
                var firstDispatch = batches.submit(task.get());
                assertNotNull(firstDispatch);
                firstDispatch.get(8, TimeUnit.SECONDS);
                ProcessResult first = currentCollector.get().getLatestProcessResult(point.getPointId());
                assertSample(first, 101);
                assertTrue(server.readRequestCount() > 0);
                long firstSampleAt = ((Number) first.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue();
                assertTrue(lifecycle.stopDevice(device.getDeviceId()));
                assertNull(config.getDeviceContext(device.getDeviceId()));
                int stoppedReads = server.readRequestCount();
                assertNull(batches.submit(task.get()));
                assertEquals(stoppedReads, server.readRequestCount());
                server.setHoldingRegister(4001, 202);
                assertTrue(lifecycle.startDevice(device.getDeviceId()));
                long secondGeneration = state.getScheduleInfo(device.getDeviceId()).getGeneration();
                assertTrue(secondGeneration > firstGeneration);
                var secondDispatch = batches.submit(task.get());
                assertNotNull(secondDispatch);
                secondDispatch.get(8, TimeUnit.SECONDS);
                var second = currentCollector.get();
                assertNotNull(second);
                var freshResult = second.getLatestProcessResult(point.getPointId());
                assertSample(freshResult, 202);
                assertTrue(((Number) freshResult.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue()
                        >= firstSampleAt);
                assertTrue(server.readRequestCount() > stoppedReads);
                System.out.printf("PHASE4_MODBUS_SAMPLE firstGeneration=%d firstValue=%s firstQuality=%d firstAt=%d "
                                + "stoppedReads=%d secondGeneration=%d secondValue=%s secondQuality=%d secondAt=%d totalReads=%d%n",
                        firstGeneration, first.getProcessedValue(), first.getQuality(), firstSampleAt,
                        stoppedReads, secondGeneration, freshResult.getProcessedValue(), freshResult.getQuality(),
                        ((Number) freshResult.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue(),
                        server.readRequestCount());
                assertTrue(lifecycle.stopDevice(device.getDeviceId()));
                assertTrue(store.findDevice(device.getDeviceId()).isPresent());
            } finally {
                lifecycle.beginShutdown();
                lifecycle.stopAllDevices();
                collectors.destroy();
                connections.destroy();
                dispatch.shutdownNow();
                starts.shutdownNow();
                collects.shutdownNow();
                processing.shutdownNow();
                assertTrue(dispatch.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(starts.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(collects.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(processing.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(16));
    }

    private static void assertSample(ProcessResult result, int expected) {
        assertNotNull(result, "必须取得点位处理结果，连接成功不等于采集成功");
        assertTrue(result.isSuccess());
        assertEquals(expected, ((Number) result.getProcessedValue()).intValue());
        assertTrue(result.getQuality() > 0);
        assertTrue(((Number) result.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME)).longValue() > 0);
    }
}

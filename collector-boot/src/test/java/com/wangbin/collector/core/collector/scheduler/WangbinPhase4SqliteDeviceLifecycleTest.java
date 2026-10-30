package com.wangbin.collector.core.collector.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.config.sqlite.runtime.WangbinSqliteLocalConfigurationPersistence;
import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import com.wangbin.collector.core.collector.manager.CollectionManager;
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
import com.wangbin.collector.core.port.CollectionHealthReporter;
import com.wangbin.collector.core.report.validator.FieldUniquenessValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 隔离 SQLite 权威配置与真实设备生命周期编排；协议连接由可观察夹具替代，不代表 Modbus 实采。 */
class WangbinPhase4SqliteDeviceLifecycleTest {
    @TempDir Path directory;
    private SqliteConfigurationStore store;
    private SqliteConfigService sqlite;
    private WangbinLocalConfigurationPersistence persistence;
    private ConfigManager config;
    private CollectionManager collectors;
    private DeviceBatchPlanner planner;
    private SchedulerRuntimeState state;
    private CollectionTaskGuard guard;
    private DeviceLifecycleCoordinator lifecycle;
    private ThreadPoolExecutor starts;
    private ExecutorService calls;
    private final ConfigurationJsonCodec codec = new ConfigurationJsonCodec();

    @BeforeEach
    void setUp() throws Exception {
        store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 1000);
        sqlite = store;
        persistence = spy(new WangbinSqliteLocalConfigurationPersistence(store));
        CollectorProperties properties = new CollectorProperties();
        properties.getAdaptiveCollection().setEnabled(false);
        properties.getScheduler().setDeviceStartTimeoutMs(3000);
        Path remote = directory.resolve("remote.json");
        Files.writeString(remote, "[]");
        properties.getConfig().getFile().setDevices(remote.toString());
        properties.getConfig().setLocalDeviceSnapshotFile(directory.resolve("unused.json").toString());
        var beans = new StaticListableBeanFactory(Map.of("sqlite", persistence));
        config = new ConfigManager(new ConfigSyncService(new FileConfigLoader(properties), Runnable::run, properties),
                event -> { }, new FieldUniquenessValidator(),
                new LocalDeviceConfigStore(new ObjectMapper(), properties), null, null,
                beans.getBeanProvider(WangbinLocalConfigurationPersistence.class));
        config.init();
        collectors = mock(CollectionManager.class);
        planner = mock(DeviceBatchPlanner.class);
        state = new SchedulerRuntimeState();
        state.initializeTimeSlices(1, 1000);
        guard = new CollectionTaskGuard();
        var strategy = mock(ProtocolBatchStrategy.class);
        when(strategy.defaultBatchSize(anyString())).thenReturn(10);
        when(strategy.maxBatchSize(anyString())).thenReturn(100);
        when(planner.plan(anyString(), anyList(), anyInt(), anyLong(), anyLong())).thenAnswer(call -> List.of(
                new DeviceBatchTask(call.getArgument(0), call.getArgument(1), 0,
                        call.getArgument(3), call.getArgument(4))));
        starts = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(16));
        calls = Executors.newFixedThreadPool(2);
        var points = new PointRuntimeStateService();
        var reconnect = mock(ReconnectCoordinator.class);
        var statistics = mock(CollectionStatistics.class);
        var health = mock(CollectionHealthReporter.class);
        var preparer = new DeviceStartPreparer(config, properties, guard, points, state, reconnect);
        var cleanup = new DeviceLifecycleCleanup(collectors, statistics, health, points, state,
                mock(DeviceBatchExecutor.class), reconnect, guard);
        lifecycle = new DeviceLifecycleCoordinator(collectors, statistics, health, planner, strategy,
                guard, state, new PerformanceMonitor(), preparer, cleanup, starts);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (calls != null) {
            calls.shutdownNow();
            assertTrue(calls.awaitTermination(5, TimeUnit.SECONDS));
        }
        if (starts != null) {
            starts.shutdownNow();
            assertTrue(starts.awaitTermination(5, TimeUnit.SECONDS));
        }
        if (store != null) store.close();
    }

    @Test
    void startsOnlySelectedSqliteDeviceAndKeepsOtherGenerationAndPersistenceOnStop() {
        save("a");
        save("b");
        save("c");
        assertNull(config.getDeviceContext("a"));
        assertTrue(lifecycle.startDevice("b"));
        long bGeneration = state.getScheduleInfo("b").getGeneration();
        assertTrue(lifecycle.startDevice("a"));
        long first = state.getScheduleInfo("a").getGeneration();
        assertEquals("40001", config.getDataPoints("a").get(0).getAddress());
        verify(persistence, never()).findDevice("c");
        verify(persistence, times(1)).findDevice("b");
        verify(collectors, never()).connectDevice(eq("c"), anyLong());
        verify(collectors, times(1)).connectDevice(eq("b"), anyLong());
        verify(planner, never()).plan(eq("c"), anyList(), anyInt(), anyLong(), anyLong());
        verify(planner, times(1)).plan(eq("b"), anyList(), anyInt(), anyLong(), anyLong());
        assertTrue(lifecycle.startDevice("a"));
        assertEquals(first, state.getScheduleInfo("a").getGeneration());
        verify(collectors, times(1)).registerDevice(argThat(device -> "a".equals(device.getDeviceId())));
        assertTrue(lifecycle.stopDevice("a"));
        assertTrue(lifecycle.stopDevice("a"));
        assertNull(config.getDeviceContext("a"));
        assertNull(config.getConnectionConfig("a"));
        assertTrue(config.getDataPoints("a").isEmpty());
        assertTrue(lifecycle.isDeviceRunning("b"));
        assertEquals(bGeneration, state.getScheduleInfo("b").getGeneration());
        assertTrue(state.getSliceTasks(0).stream().anyMatch(task -> "b".equals(task.deviceId)
                && task.generation == bGeneration && !task.isCancelled()));
        verify(collectors, never()).cleanupDevice("b");
        assertEquals(1, store.findDevice("a").orElseThrow().configuration().points().size());
        assertNotNull(store.findDevice("a").orElseThrow().configuration().connection());
        var edited = configuration("a");
        var editedPoints = edited.copyDataPoints();
        editedPoints.get(0).setAddress("40002");
        long revision = store.findDevice("a").orElseThrow().revision();
        sqlite.transaction(writer -> writer.saveDevice(codec.fromRuntime(DeviceContext.of(
                edited.getDeviceInfo(), edited.copyConnectionConfig(), editedPoints)), revision));
        assertTrue(lifecycle.startDevice("a"));
        assertTrue(state.getScheduleInfo("a").getGeneration() > first);
        assertEquals("40002", config.getDataPoints("a").get(0).getAddress());
        assertEquals(bGeneration, state.getScheduleInfo("b").getGeneration());
    }

    @Test
    void initializedManagerDoesNotParseCorruptedOtherDeviceWhenStartingA() throws Exception {
        save("a");
        save("b");
        save("c");
        // 先按现有 init() 路径全量恢复合法的 A/B/C；再损坏 B，不把阶段 05 的启动恢复问题混入本用例。
        config.init();
        assertNotNull(config.getDeviceContext("b"));
        clearInvocations(persistence);
        try (var sql = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("private/collector.db"));
             var statement = sql.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.execute("UPDATE dc_device_point SET point_json='{broken' WHERE device_id='b'");
        }
        assertTrue(lifecycle.startDevice("a"));
        verify(persistence, never()).findDevice("b");
        verify(collectors, never()).registerDevice(argThat(device -> "b".equals(device.getDeviceId())));
        assertTrue(lifecycle.stopDevice("a"));
    }

    @Test
    void missingOrInvalidSqliteAggregateCannotClaimRunning() {
        assertFalse(lifecycle.startDevice("missing"));
        assertFalse(lifecycle.isDeviceRunning("missing"));
        save("a");
        long revision = store.findDevice("a").orElseThrow().revision();
        var withoutPoints = configuration("a");
        sqlite.transaction(writer -> writer.saveDevice(codec.fromRuntime(DeviceContext.of(
                withoutPoints.getDeviceInfo(), withoutPoints.copyConnectionConfig(), List.of())), revision));
        assertFalse(lifecycle.startDevice("a"));
        assertFalse(lifecycle.isDeviceRunning("a"));
        assertNull(config.getDeviceContext("a"));
        var invalid = configuration("a");
        var connection = invalid.copyConnectionConfig();
        connection.setPort(0);
        long nextRevision = store.findDevice("a").orElseThrow().revision();
        sqlite.transaction(writer -> writer.saveDevice(codec.fromRuntime(DeviceContext.of(
                invalid.getDeviceInfo(), connection, invalid.copyDataPoints())), nextRevision));
        assertFalse(lifecycle.startDevice("a"));
        assertFalse(lifecycle.isDeviceRunning("a"));
        assertNull(config.getDeviceContext("a"));
    }

    @Test
    void stopDuringSqliteReadRejectsLateSnapshotAndNewStartGetsNewGeneration() throws Exception {
        save("a");
        save("b");
        assertTrue(lifecycle.startDevice("b"));
        long bGeneration = state.getScheduleInfo("b").getGeneration();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(call -> {
            var result = store.findDevice("a");
            reading.countDown();
            assertTrue(resume.await(5, TimeUnit.SECONDS));
            return result;
        }).when(persistence).findDevice("a");
        Future<Boolean> firstStart = calls.submit(() -> lifecycle.startDevice("a"));
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            long first = state.getStartingGeneration("a");
            Future<Boolean> stop = calls.submit(() -> lifecycle.stopDevice("a"));
            assertTrue(stop.get(3, TimeUnit.SECONDS));
            assertFalse(guard.isCurrent("a", first));
            assertNull(config.getDeviceContext("a"));
            verify(collectors, never()).connectDevice(eq("a"), anyLong());
            assertTrue(lifecycle.isDeviceRunning("b"));
            assertEquals(bGeneration, state.getScheduleInfo("b").getGeneration());
        } finally {
            resume.countDown();
        }
        assertFalse(firstStart.get(5, TimeUnit.SECONDS));
        assertNull(config.getDeviceContext("a"));
        assertTrue(lifecycle.startDevice("a"));
        assertTrue(state.getScheduleInfo("a").getGeneration() > 0);
        verify(collectors, times(1)).connectDevice(eq("a"), anyLong());
    }

    @Test
    void lateFirstConnectionCannotEvictSecondGeneration() throws Exception {
        save("a");
        CountDownLatch oldConnect = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch oldExited = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 1) {
                oldConnect.countDown();
                boolean interrupted = false;
                try {
                    while (releaseOld.getCount() > 0) {
                        try {
                            assertTrue(releaseOld.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException cancelled) {
                            interrupted = true;
                        }
                    }
                } finally {
                    oldExited.countDown();
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            return null;
        }).when(collectors).connectDevice(eq("a"), anyLong());
        Future<Boolean> firstStart = calls.submit(() -> lifecycle.startDevice("a"));
        try {
            assertTrue(oldConnect.await(5, TimeUnit.SECONDS));
            long firstGeneration = state.getStartingGeneration("a");
            assertTrue(lifecycle.stopDevice("a"));
            assertFalse(firstStart.get(5, TimeUnit.SECONDS));
            assertTrue(lifecycle.startDevice("a"));
            long secondGeneration = state.getScheduleInfo("a").getGeneration();
            assertTrue(secondGeneration > firstGeneration);
            assertTrue(guard.isCurrent("a", secondGeneration));
            releaseOld.countDown();
            assertTrue(oldExited.await(5, TimeUnit.SECONDS));
            assertTrue(lifecycle.isDeviceRunning("a"));
            assertEquals(secondGeneration, state.getScheduleInfo("a").getGeneration());
            assertNotNull(config.getDeviceContext("a"));
        } finally {
            releaseOld.countDown();
        }
    }

    @Test
    void bCanStillCollectWhileAIsCleaningUp() throws Exception {
        save("a");
        save("b");
        assertTrue(lifecycle.startDevice("a"));
        assertTrue(lifecycle.startDevice("b"));
        long bGeneration = state.getScheduleInfo("b").getGeneration();
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch finishCleaning = new CountDownLatch(1);
        doAnswer(call -> {
            cleaning.countDown();
            assertTrue(finishCleaning.await(5, TimeUnit.SECONDS));
            return null;
        }).when(collectors).cleanupDevice("a");
        when(collectors.readPoints(eq("b"), anyList())).thenReturn(Map.of("p-b", 17));
        Future<Boolean> stopping = calls.submit(() -> lifecycle.stopDevice("a"));
        try {
            assertTrue(cleaning.await(5, TimeUnit.SECONDS));
            Future<Map<String, Object>> bCollection = calls.submit(() -> collectors.readPoints(
                    "b", config.getDataPoints("b")));
            assertEquals(17, bCollection.get(3, TimeUnit.SECONDS).get("p-b"));
            assertTrue(lifecycle.isDeviceRunning("b"));
            assertEquals(bGeneration, state.getScheduleInfo("b").getGeneration());
            assertTrue(state.getSliceTasks(0).stream().anyMatch(task -> "b".equals(task.deviceId)
                    && task.generation == bGeneration && !task.isCancelled()));
        } finally {
            finishCleaning.countDown();
        }
        assertTrue(stopping.get(5, TimeUnit.SECONDS));
        assertTrue(lifecycle.isDeviceRunning("b"));
        verify(collectors, never()).cleanupDevice("b");
    }

    @Test
    void failedCollectorCleanupDoesNotEvictSqliteRuntimeSnapshotOrClaimSuccess() {
        save("a");
        assertTrue(lifecycle.startDevice("a"));
        doThrow(new IllegalStateException("测试注入的释放故障")).when(collectors).cleanupDevice("a");
        assertFalse(lifecycle.stopDevice("a"));
        assertFalse(lifecycle.isDeviceRunning("a"));
        assertNotNull(config.getDeviceContext("a"));
        assertTrue(store.findDevice("a").isPresent());
        doNothing().when(collectors).cleanupDevice("a");
        assertTrue(lifecycle.stopDevice("a"));
        assertNull(config.getDeviceContext("a"));
    }

    @Test
    void cancelledConfigRestartAndShutdownCannotInstallOldOrNewResources() throws Exception {
        save("a");
        assertTrue(lifecycle.startDevice("a"));
        long revision = lifecycle.getIntentRevision("a");
        var reservation = lifecycle.reserveStartForConfigRestart("a", revision, true, false);
        assertNotNull(reservation);
        assertTrue(state.isStarting("a"));
        lifecycle.discardReservedStart(reservation);
        assertFalse(state.isStarting("a"));
        assertNull(config.getDeviceContext("a"));
        lifecycle.beginShutdown();
        assertFalse(lifecycle.startDevice("a"));
        assertNull(config.getDeviceContext("a"));
    }

    private void save(String id) {
        var context = configuration(id);
        sqlite.transaction(writer -> writer.saveDevice(codec.fromRuntime(context), 0));
    }

    private DeviceContext configuration(String id) {
        var device = new DeviceInfo();
        device.setDeviceId(id);
        device.setDeviceName("隔离设备-" + id);
        device.setProtocolType("MODBUS_TCP");
        device.setConnectionType("MODBUS_TCP");
        device.setConfigSource("local");
        device.setTemporaryConfig(true);
        device.setCollectionInterval(1000);
        var connection = new DeviceConnection();
        connection.setDeviceId(id);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(1502);
        var point = new DataPoint();
        point.setPointId("p-" + id);
        point.setPointCode("p-" + id);
        point.setDeviceId(id);
        point.setAddress("40001");
        point.setDataType("FLOAT");
        point.setStatus(1);
        return DeviceContext.of(device, connection, List.of(point));
    }
}

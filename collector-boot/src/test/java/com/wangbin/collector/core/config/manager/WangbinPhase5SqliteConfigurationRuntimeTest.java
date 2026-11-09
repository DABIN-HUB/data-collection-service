package com.wangbin.collector.core.config.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.config.sqlite.runtime.WangbinSqliteLocalConfigurationPersistence;
import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.runtime.AcquisitionRuntimeTracker;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimePhase;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeState;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.runtime.RuntimeStateCoordinator;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.collector.scheduler.ConfigRestartCoordinator;
import com.wangbin.collector.core.collector.scheduler.DeviceBatchExecutor;
import com.wangbin.collector.core.collector.scheduler.DeviceLifecycleCoordinator;
import com.wangbin.collector.core.collector.scheduler.PerformanceMonitor;
import com.wangbin.collector.core.collector.scheduler.ReconnectCoordinator;
import com.wangbin.collector.core.collector.scheduler.SchedulerMaintenanceCoordinator;
import com.wangbin.collector.core.collector.scheduler.SchedulerRuntimeState;
import com.wangbin.collector.core.collector.scheduler.TimeSliceConfigCoordinator;
import com.wangbin.collector.core.collector.scheduler.TimeSliceExecutionCoordinator;
import com.wangbin.collector.core.collector.scheduler.TimeSliceSchedulingCoordinator;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.loader.FileConfigLoader;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.store.LocalDeviceConfigStore;
import com.wangbin.collector.core.report.validator.FieldUniquenessValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 阶段 05：验证 SQLite 配置事实与本地运行快照的边界。 */
class WangbinPhase5SqliteConfigurationRuntimeTest {
    @TempDir
    Path directory;

    private SqliteConfigurationStore store;

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void TEST_05_01_restartKeepsConfigurationQueryableWithoutRestoringRuntimeCache() {
        Path database = directory.resolve("private/collector.db");
        store = SqliteConfigurationStore.createNew(database, 1000);
        save("a");
        save("b");
        save("c");
        long aRevision = revision("a");

        ConfigManager first = newManager(store);
        first.init();
        assertNoRuntimeCache(first, List.of("a", "b", "c"));
        assertConfigurationQueries(first, List.of("a", "b", "c"), aRevision);

        store.close();
        store = SqliteConfigurationStore.openExisting(database, 1000);
        ConfigManager restarted = newManager(store);
        restarted.init();
        assertNoRuntimeCache(restarted, List.of("a", "b", "c"));
        assertConfigurationQueries(restarted, List.of("a", "b", "c"), aRevision);
    }

    @Test
    void TEST_05_02_manualStartInstallsOnlyTargetAndStopReleasesRuntimeSnapshot() {
        store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 1000);
        save("a");
        save("b");
        save("c");
        ConfigManager config = newManager(store);
        config.init();

        ConfigManager.LocalStartConfiguration candidate = config.loadLocalStartConfiguration("a");
        assertNotNull(candidate);
        assertTrue(config.installLocalStartConfiguration("a", 1L, candidate));
        assertNotNull(config.getDeviceContext("a"));
        assertNull(config.getDeviceContext("b"));
        assertNull(config.getDeviceContext("c"));

        config.releaseLocalRuntimeConfiguration("a", 1L);
        assertNull(config.getDeviceContext("a"));
        assertTrue(store.findDevice("a").isPresent());
        assertEquals(1, store.findDevice("a").orElseThrow().configuration().points().size());
    }

    @Test
    void TEST_05_03_fullReloadDoesNotReinstallStoppedSqliteDevice() throws Exception {
        store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 1000);
        save("a");
        ConfigManager config = newManager(store);
        config.init();
        ConfigManager.LocalStartConfiguration candidate = config.loadLocalStartConfiguration("a");
        assertTrue(config.installLocalStartConfiguration("a", 7L, candidate));
        config.releaseLocalRuntimeConfiguration("a", 7L);
        assertNull(config.getDeviceContext("a"));

        Method fullReload = ConfigManager.class.getDeclaredMethod("loadAllConfig");
        fullReload.setAccessible(true);
        fullReload.invoke(config);

        assertNull(config.getDeviceContext("a"));
        assertTrue(store.findDevice("a").isPresent());
    }

    @Test
    void TEST_05_04_invalidDeviceDoesNotBlockIndependentDeviceQueryOrStartRead() throws Exception {
        store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 1000);
        save("a");
        save("b");
        ConfigManager config = newManager(store);
        config.init();
        corruptPoints("b");

        assertNotNull(config.getConfigurationDevice("a"));
        assertEquals("40001", config.loadLocalStartConfiguration("a").context().getDataPoints().get(0).getAddress());
        assertThrows(IllegalStateException.class, () -> config.loadLocalStartConfiguration("b"));
        assertNull(config.getDeviceContext("b"));
    }

    @Test
    void TEST_05_05_runtimeProjectionDistinguishesConfiguredStoppedDevice() {
        store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 1000);
        save("a");
        ConfigManager config = newManager(store);
        config.init();

        CollectionScheduler scheduler = mock(CollectionScheduler.class);
        when(scheduler.getDeviceRuntimeSnapshot("a")).thenReturn(
                new DeviceRuntimeSnapshot("a", DeviceRuntimePhase.STOPPED, false, false, false,
                        false, 0L, 0L, 0L, 0L, 0, 0L, null, System.currentTimeMillis(),
                        false, 0L, 0, null, 0L));
        when(scheduler.getAppliedConfigVersion("a")).thenReturn(0L);
        when(scheduler.getDesiredState("a")).thenReturn(SchedulerRuntimeState.DesiredState.STOPPED);

        RuntimeStateCoordinator runtime = new RuntimeStateCoordinator(scheduler, config,
                new AcquisitionRuntimeTracker(new CollectionTaskGuard()), new PointRuntimeStateService());
        DeviceRuntimeState state = runtime.snapshots().stream()
                .filter(item -> "a".equals(item.deviceId()))
                .findFirst().orElseThrow();

        assertEquals(DeviceRuntimeState.AcquisitionStatus.STOPPED, state.acquisition());
        assertEquals(DeviceRuntimeState.DeviceHealth.OFFLINE, state.health());
        assertEquals(DeviceRuntimeState.ProtocolStatus.STOPPED, state.protocol());
        assertEquals(1, state.configuredPointCount());
    }

    @Test
    void TEST_05_07_formalCrudSavePersistsConfigurationWithoutInstallingRuntimeCache() {
        store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 1000);
        ConfigManager config = newManager(store);
        DeviceContext input = configuration("crud-a");

        ConfigManager.DeviceConfigCommitResult result = config.saveLocalDeviceConfigWithResult(
                input.getDeviceInfo(), input.copyConnectionConfig(), input.copyDataPoints(), false);
        var stored = store.findDevice("crud-a").orElseThrow();

        assertNotNull(result);
        assertNotNull(config.getConfigurationDeviceSnapshot("crud-a").context());
        assertEquals(stored.revision(), config.getConfigurationDeviceSnapshot("crud-a").persistedRevision());
        assertNull(config.getDeviceContext("crud-a"));
        assertFalse(config.getAllDeviceIds().contains("crud-a"));
        assertEquals(0L, config.getDeviceConfigVersion("crud-a"));
        assertEquals(0, ((Number) config.getCacheStats().get("contextCount")).intValue());
    }

    @Test
    void TEST_05_08_stopThenEditKeepsRuntimeCacheEmptyUntilExplicitStart() {
        store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 1000);
        ConfigManager config = newManager(store);
        DeviceContext input = configuration("edit-a");
        DeviceContext other = configuration("edit-b");
        config.saveLocalDeviceConfigWithResult(input.getDeviceInfo(), input.copyConnectionConfig(), input.copyDataPoints(), false);
        config.saveLocalDeviceConfigWithResult(other.getDeviceInfo(), other.copyConnectionConfig(), other.copyDataPoints(), false);

        assertTrue(config.installLocalStartConfiguration("edit-a", 1L,
                config.loadLocalStartConfiguration("edit-a")));
        assertTrue(config.installLocalStartConfiguration("edit-b", 2L,
                config.loadLocalStartConfiguration("edit-b")));
        assertNotNull(config.getDeviceContext("edit-a"));
        config.releaseLocalRuntimeConfiguration("edit-a", 1L);
        assertNull(config.getDeviceContext("edit-a"));
        assertNotNull(config.getDeviceContext("edit-b"));

        ConfigManager.DeviceConfigurationSnapshot before = config.getConfigurationDeviceSnapshot("edit-a");
        List<DataPoint> editedPoints = before.context().copyDataPoints();
        editedPoints.get(0).setAddress("40002");
        assertTrue(config.updateDataPoints("edit-a", editedPoints, before.persistedRevision()));

        assertNull(config.getDeviceContext("edit-a"));
        assertEquals("40002", config.getConfigurationPoints("edit-a").getDataPoints().get(0).getAddress());
        assertTrue(config.getConfigurationDeviceSnapshot("edit-a").persistedRevision() > before.persistedRevision());
        assertNotNull(config.getDeviceContext("edit-b"));
        assertEquals("40001", config.getDataPoints("edit-b").get(0).getAddress());

        assertTrue(config.installLocalStartConfiguration("edit-a", 2L,
                config.loadLocalStartConfiguration("edit-a")));
        assertEquals("40002", config.getDataPoints("edit-a").get(0).getAddress());
        config.releaseLocalRuntimeConfiguration("edit-a", 2L);
        config.releaseLocalRuntimeConfiguration("edit-b", 2L);
    }

    @Test
    void TEST_05_09_conflictAndCommitUnknownReconciliationNeverInstallsStoppedDevice() {
        store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 1000);
        ConfigManager config = newManager(store);
        DeviceContext input = configuration("failure-a");
        config.saveLocalDeviceConfigWithResult(input.getDeviceInfo(), input.copyConnectionConfig(), input.copyDataPoints(), false);
        ConfigManager.DeviceConfigurationSnapshot before = config.getConfigurationDeviceSnapshot("failure-a");

        DeviceContext external = configuration("failure-a");
        external.getDeviceInfo().setDeviceName("external-winner");
        store.transaction(writer -> writer.saveDevice(
                new ConfigurationJsonCodec().fromRuntime(external), before.persistedRevision()));
        List<DataPoint> stalePoints = before.context().copyDataPoints();
        stalePoints.get(0).setAddress("40002");
        assertThrows(ConfigVersionConflictException.class,
                () -> config.updateDataPoints("failure-a", stalePoints, before.persistedRevision()));
        assertNull(config.getDeviceContext("failure-a"));

        store.close();
        store = SqliteConfigurationStore.openExisting(directory.resolve("private/collector.db"), 1000);
        var uncertainPersistence = spy(new WangbinSqliteLocalConfigurationPersistence(store));
        doThrow(new com.wangbin.collector.core.config.persistence.ConfigurationCommitUncertainException())
                .when(uncertainPersistence).transaction(any());
        ConfigManager uncertain = newManager(uncertainPersistence);
        ConfigManager.DeviceConfigurationSnapshot winner = uncertain.getConfigurationDeviceSnapshot("failure-a");
        assertThrows(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.class,
                () -> uncertain.updateDeviceConfig(winner.context().getDeviceInfo(), winner.persistedRevision()));
        assertEquals(0, ((Number) uncertain.getCacheStats().get("contextCount")).intValue());
        assertTrue(store.findDevice("failure-a").isPresent());
    }

    @Test
    void TEST_05_10_refreshAndFullReloadDoNotReinstallStoppedSqliteDevice() throws Exception {
        store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 1000);
        ConfigManager config = newManager(store);
        DeviceContext input = configuration("refresh-a");
        config.saveLocalDeviceConfigWithResult(input.getDeviceInfo(), input.copyConnectionConfig(), input.copyDataPoints(), false);

        assertTrue(config.refreshDeviceConfig("refresh-a"));
        assertNull(config.getDeviceContext("refresh-a"));
        Method fullReload = ConfigManager.class.getDeclaredMethod("loadAllConfig");
        fullReload.setAccessible(true);
        fullReload.invoke(config);
        assertNull(config.getDeviceContext("refresh-a"));
        assertNotNull(config.getConfigurationDeviceSnapshot("refresh-a").context());

        CollectionScheduler scheduler = newReloadScheduler(config);
        scheduler.reloadAllDevices();
        assertNull(config.getDeviceContext("refresh-a"));
        assertNotNull(config.getConfigurationDeviceSnapshot("refresh-a").context());
    }

    @Test
    void TEST_05_11_runningGenerationOwnsConfigEditAndStopReleasesIt() {
        store = SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 1000);
        ConfigManager config = newManager(store);
        DeviceContext a = configuration("running-a");
        DeviceContext b = configuration("running-b");
        config.saveLocalDeviceConfigWithResult(a.getDeviceInfo(), a.copyConnectionConfig(), a.copyDataPoints(), false);
        config.saveLocalDeviceConfigWithResult(b.getDeviceInfo(), b.copyConnectionConfig(), b.copyDataPoints(), false);
        assertTrue(config.installLocalStartConfiguration("running-a", 1L,
                config.loadLocalStartConfiguration("running-a")));
        assertTrue(config.installLocalStartConfiguration("running-b", 2L,
                config.loadLocalStartConfiguration("running-b")));
        long oldVersion = config.getDeviceConfigVersion("running-a");

        ConfigManager.DeviceConfigurationSnapshot before = config.getConfigurationDeviceSnapshot("running-a");
        List<DataPoint> editedPoints = before.context().copyDataPoints();
        editedPoints.get(0).setAddress("40003");
        assertTrue(config.updateDataPoints("running-a", editedPoints, before.persistedRevision()));
        assertTrue(config.getDeviceConfigVersion("running-a") > oldVersion);
        assertEquals("40003", config.getDataPoints("running-a").get(0).getAddress());
        assertNotNull(config.getDeviceContext("running-b"));
        assertEquals("40001", config.getDataPoints("running-b").get(0).getAddress());

        config.releaseLocalRuntimeConfiguration("running-a", 1L);
        assertNotNull(config.getDeviceContext("running-b"));
        config.releaseLocalRuntimeConfiguration("running-b", 2L);
        assertNull(config.getDeviceContext("running-a"));
        assertEquals("40003", config.getConfigurationPoints("running-a").getDataPoints().get(0).getAddress());
    }

    private ConfigManager newManager(SqliteConfigurationStore sqlite) {
        return newManager(new WangbinSqliteLocalConfigurationPersistence(sqlite), "remote.json", "unused.json");
    }

    private ConfigManager newManager(WangbinSqliteLocalConfigurationPersistence persistence) {
        return newManager(persistence, "remote-uncertain.json", "unused-uncertain.json");
    }

    private ConfigManager newManager(WangbinSqliteLocalConfigurationPersistence persistence,
                                     String remoteName, String snapshotName) {
        CollectorProperties properties = new CollectorProperties();
        properties.getAdaptiveCollection().setEnabled(false);
        Path remote = directory.resolve(remoteName);
        try {
            Files.writeString(remote, "[]");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        properties.getConfig().getFile().setDevices(remote.toString());
        properties.getConfig().setLocalDeviceSnapshotFile(directory.resolve(snapshotName).toString());
        var beans = new StaticListableBeanFactory(Map.of("sqlite", persistence));
        return new ConfigManager(new ConfigSyncService(new FileConfigLoader(properties), Runnable::run, properties),
                event -> { }, new FieldUniquenessValidator(),
                new LocalDeviceConfigStore(new ObjectMapper(), properties), null, null,
                beans.getBeanProvider(com.wangbin.collector.core.config.persistence.WangbinLocalConfigurationPersistence.class));
    }

    private CollectionScheduler newReloadScheduler(ConfigManager config) {
        CollectionScheduler scheduler = new CollectionScheduler(
                mock(CollectionManager.class), mock(CollectionStatistics.class), new SchedulerRuntimeState(),
                mock(PerformanceMonitor.class), mock(DeviceLifecycleCoordinator.class),
                mock(DeviceBatchExecutor.class), mock(ReconnectCoordinator.class),
                mock(TimeSliceSchedulingCoordinator.class), mock(TimeSliceExecutionCoordinator.class),
                mock(TimeSliceConfigCoordinator.class), mock(SchedulerMaintenanceCoordinator.class),
                mock(ConfigRestartCoordinator.class), mock(AcquisitionRuntimeTracker.class));
        scheduler.setConfigManager(config);
        return scheduler;
    }

    private void save(String id) {
        ConfigurationJsonCodec codec = new ConfigurationJsonCodec();
        store.transaction(writer -> writer.saveDevice(codec.fromRuntime(configuration(id)), 0L));
    }

    private long revision(String id) {
        return store.findDevice(id).orElseThrow().revision();
    }

    private void assertNoRuntimeCache(ConfigManager config, List<String> ids) {
        for (String id : ids) assertNull(config.getDeviceContext(id), id);
    }

    private void assertConfigurationQueries(ConfigManager config, List<String> ids, long expectedRevision) {
        assertEquals(ids.stream().sorted().toList(), config.getConfigurationDevices().stream()
                .map(DeviceInfo::getDeviceId).sorted().toList());
        ConfigManager.DeviceConfigurationSnapshot snapshot = config.getConfigurationDeviceSnapshot("a");
        assertNotNull(snapshot.context());
        assertEquals(expectedRevision, snapshot.persistedRevision());
        assertEquals("127.0.0.1", config.getConfigurationConnection("a").getConnectionConfig().getHost());
        assertEquals("40001", config.getConfigurationPoints("a").getDataPoints().get(0).getAddress());
    }

    private void corruptPoints(String id) throws Exception {
        try (var sql = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("private/collector.db"));
             var statement = sql.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.execute("UPDATE dc_device_point SET point_json='{broken' WHERE device_id='" + id + "'");
        }
    }

    private DeviceContext configuration(String id) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(id);
        device.setDeviceName("阶段05设备-" + id);
        device.setProtocolType("MODBUS_TCP");
        device.setConnectionType("MODBUS_TCP");
        device.setConfigSource("local");
        device.setTemporaryConfig(true);
        device.setCollectionInterval(1000);
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(id);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(1502);
        DataPoint point = new DataPoint();
        point.setPointId("p-" + id);
        point.setPointCode("p-" + id);
        point.setDeviceId(id);
        point.setAddress("40001");
        point.setDataType("FLOAT");
        point.setStatus(1);
        return DeviceContext.of(device, connection, List.of(point));
    }
}

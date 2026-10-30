package com.wangbin.collector.core.config.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.config.sqlite.runtime.WangbinSqliteLocalConfigurationPersistence;
import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import com.wangbin.collector.core.collector.runtime.AcquisitionRuntimeTracker;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimePhase;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeState;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.runtime.RuntimeStateCoordinator;
import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.collector.scheduler.SchedulerRuntimeState;
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

    private ConfigManager newManager(SqliteConfigurationStore sqlite) {
        CollectorProperties properties = new CollectorProperties();
        properties.getAdaptiveCollection().setEnabled(false);
        Path remote = directory.resolve("remote.json");
        try {
            Files.writeString(remote, "[]");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        properties.getConfig().getFile().setDevices(remote.toString());
        properties.getConfig().setLocalDeviceSnapshotFile(directory.resolve("unused.json").toString());
        WangbinSqliteLocalConfigurationPersistence persistence =
                new WangbinSqliteLocalConfigurationPersistence(sqlite);
        var beans = new StaticListableBeanFactory(Map.of("sqlite", persistence));
        return new ConfigManager(new ConfigSyncService(new FileConfigLoader(properties), Runnable::run, properties),
                event -> { }, new FieldUniquenessValidator(),
                new LocalDeviceConfigStore(new ObjectMapper(), properties), null, null,
                beans.getBeanProvider(com.wangbin.collector.core.config.persistence.WangbinLocalConfigurationPersistence.class));
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

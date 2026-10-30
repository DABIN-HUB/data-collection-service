package com.wangbin.collector.config.sqlite.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;

import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.loader.FileConfigLoader;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.manager.ConfigVersionConflictException;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.config.model.ConfigUpdateType;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.persistence.WangbinLocalConfigurationPersistence;
import com.wangbin.collector.core.config.store.LocalDeviceConfigStore;
import com.wangbin.collector.core.report.validator.FieldUniquenessValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 真实仓储及管理器提交测试，不创建任何采集器或网络连接。 */
class WangbinSqliteConfigManagerCrudTest {
    @TempDir Path directory;
    final List<Object> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    final ConfigurationJsonCodec codec = new ConfigurationJsonCodec();

    @Test
    void createCommitsAggregateAndRestartRestoresIt() throws Exception {
        try (var store = database()) {
            ConfigManager manager = manager(store);
            DeviceContext input = configuration("device-a", "point-a");
            var result = manager.saveLocalDeviceConfigWithResult(input.getDeviceInfo(), input.copyConnectionConfig(), input.copyDataPoints(), false);
            assertNotNull(result);
            assertEquals(1, store.listDevices().size());
            assertTrue(codec.equivalent(manager.getDeviceContext("device-a"), codec.toRuntime(store.findDevice("device-a").orElseThrow().configuration())));
            assertEquals(result.configVersion(), manager.getDeviceConfigVersion("device-a"));
            assertNotEquals(store.findDevice("device-a").orElseThrow().revision(), result.configVersion());
            assertEquals(1, events.size());
            assertEquals(result.configVersion(), ((ConfigUpdateEvent) events.get(0)).getConfigVersion());
            events.clear();
            ConfigManager restarted = manager(store);
            // 阶段 05 新契约：重启不恢复运行缓存；配置事实仍通过 SQLite 定向查询可读。
            assertNull(restarted.getDeviceContext("device-a"));
            assertTrue(codec.equivalent(manager.getDeviceContext("device-a"),
                    restarted.getConfigurationDeviceSnapshot("device-a").context()));
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void partialCrudBundleBatchAndCacheRefreshRemainDurable() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            save(manager, configuration("b", "p-b"));
            events.clear();
            var device = manager.getDeviceConfigurationSnapshot("a").context().getDeviceInfo();
            device.setDeviceName("renamed");
            assertTrue(manager.updateDeviceConfig(device));
            assertState(store, manager, "a");
            var connection = manager.getDeviceConfigurationSnapshot("a").context().copyConnectionConfig();
            connection.setPort(2502);
            assertTrue(manager.updateConnectionConfig("a", connection));
            assertState(store, manager, "a");
            var points = new ArrayList<>(manager.getDeviceConfigurationSnapshot("a").context().copyDataPoints());
            points.get(0).setAddress("40002");
            var second = configuration("a", "p-a2").copyDataPoints().get(0);
            points.add(second);
            assertTrue(manager.updateDataPoints("a", points));
            assertState(store, manager, "a");
            java.util.Collections.reverse(points);
            long version = manager.getDeviceConfigVersion("a");
            assertTrue(manager.updateDataPoints("a", points));
            assertTrue(manager.getDeviceConfigVersion("a") > version);
            assertEquals(List.of("p-a2", "p-a"), store.findDevice("a").orElseThrow().configuration().points().stream().map(p -> p.pointId()).toList());
            assertTrue(((ConfigUpdateEvent) events.get(events.size() - 1)).getRetiredPointIds().isEmpty());
            var snapshot = manager.getDeviceConfigurationSnapshot("a");
            var candidate = snapshot.context();
            candidate.getDeviceInfo().setDeviceName("bundle");
            var result = manager.replaceDeviceContextAtomically("a", candidate.getDeviceInfo(), candidate.copyConnectionConfig(),
                    candidate.copyDataPoints(), snapshot.configVersion());
            assertEquals(result.configVersion(), manager.getDeviceConfigVersion("a"));
            assertState(store, manager, "a");
            assertTrue(manager.replaceDeviceContextsAtomically(List.of(configuration("c", "p-c"), configuration("d", "p-d"))));
            assertEquals(4, store.listDevices().size());
            assertState(store, manager, "c");
            assertTrue(manager.updateDataPoints("a", List.of()));
            assertTrue(store.findDevice("a").orElseThrow().configuration().points().isEmpty());
            assertTrue(manager.deleteLocalDeviceConfig("d"));
            assertTrue(store.findDevice("d").isEmpty());
            assertNull(manager.getDeviceContext("d"));
            manager.clearDeviceConfig("b");
            assertTrue(manager.isLocalTemporaryDevice("b"));
            assertTrue(manager.refreshDeviceConfig("b"));
            assertState(store, manager, "b");
            manager.clearAllCache();
            assertEquals(3, store.listDevices().size());
            assertTrue(manager.refreshDeviceConfig("c"));
            assertState(store, manager, "c");
            // clearAllCache 后新实例不再从 SQLite 全量安装运行缓存，配置列表仍保持完整。
            ConfigManager restarted = manager(store);
            assertEquals(3, restarted.getConfigurationDevices().size());
            assertTrue(restarted.getAllDeviceIds().isEmpty());
            assertFalse(Files.exists(directory.resolve("never-write.json")));
        }
    }

    void save(ConfigManager manager, DeviceContext input) {
        manager.saveLocalDeviceConfigWithResult(input.getDeviceInfo(), input.copyConnectionConfig(), input.copyDataPoints(), false);
    }

    @Test
    void configurationReadsUseFreshSqliteWithoutReplacingRuntimeCache() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            assertTrue(manager.getConfigurationDevices().isEmpty());
            save(manager, configuration("a", "p-a"));
            assertEquals(1, manager.getConfigurationDevices().size());
            save(manager, configuration("b", "p-b"));
            var persisted = store.findDevice("a").orElseThrow();
            var changed = configuration("a", "p-a");
            changed.getDeviceInfo().setDeviceName("database-name");
            var connection = changed.copyConnectionConfig();
            connection.setPort(2503);
            var points = changed.copyDataPoints();
            points.get(0).setAddress("40003");
            store.transaction(w -> w.saveDevice(codec.fromRuntime(DeviceContext.of(changed.getDeviceInfo(), connection, points)), persisted.revision()));

            assertEquals("a", manager.getDevice("a").getDeviceName());
            assertEquals("database-name", manager.getConfigurationDevice("a").getDeviceName());
            assertEquals("database-name", manager.getConfigurationDevices().stream()
                    .filter(device -> device.getDeviceId().equals("a")).findFirst().orElseThrow().getDeviceName());
            assertEquals(2, manager.getConfigurationDevices().size());
            assertEquals(2503, manager.getConfigurationConnection("a").copyConnectionConfig().getPort());
            assertEquals("40003", manager.getConfigurationPoints("a").copyDataPoints().get(0).getAddress());
            long revision = store.currentRevision();
            try (var blocker = sqlConnection(); var sql = blocker.createStatement()) {
                sql.execute("BEGIN IMMEDIATE");
                assertEquals(2503, manager.getConfigurationConnection("a").copyConnectionConfig().getPort());
                assertEquals("40003", manager.getConfigurationPoints("a").copyDataPoints().get(0).getAddress());
                sql.execute("ROLLBACK");
            }
            assertEquals(revision, store.currentRevision());
            assertEquals("40003", manager.getConfigurationDeviceSnapshot("a").context().copyDataPoints().get(0).getAddress());
            assertEquals("40001", manager.getDataPoints("a").get(0).getAddress());
            assertNull(manager.getConfigurationDevice("unknown"));
            assertNull(manager.getConfigurationDeviceSnapshot("unknown").context());
            assertNull(manager.getConfigurationPoints("unknown"));
            assertNull(manager.getConfigurationConnection("unknown"));
            assertTrue(events.size() >= 2);
        }
    }

    @Test
    void listingAndSavingSingleDeviceNeverDeserializeOtherDevicesPoints() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            save(manager, configuration("b", "p-b"));
            corruptJson("UPDATE dc_device_point SET point_json = '{broken' WHERE device_id = 'b'");
            corruptJson("UPDATE dc_device_connection SET connection_json = '{broken' WHERE device_id = 'b'");
            assertEquals(2, manager.getConfigurationDevices().size());
            save(manager, configuration("c", "p-c"));
            assertEquals(3, manager.getConfigurationDevices().size());
            assertEquals("c", store.findDevice("c").orElseThrow().configuration().deviceId());
            assertThrows(RuntimeException.class, store::listDevices);
            corruptJson("UPDATE dc_device SET device_json = '{broken' WHERE device_id = 'b'");
            assertThrows(RuntimeException.class, manager::getConfigurationDevices);
            assertThrows(RuntimeException.class, () -> manager.getConfigurationDevice("b"));
            assertThrows(RuntimeException.class, () -> manager.getConfigurationDeviceSnapshot("b"));
            assertThrows(RuntimeException.class, () -> manager.getConfigurationPoints("b"));
            executeSql("DROP TABLE dc_device");
            assertThrows(RuntimeException.class, manager::getConfigurationDevices);
        }
    }

    @Test
    void sqliteListKeepsRemoteDevicesAndLocalIdWinsCollision() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("local", "p-local"));
            Files.writeString(directory.resolve("remote.json"),
                    "[{\"deviceId\":\"remote\",\"deviceName\":\"remote-name\","
                            + "\"configSource\":\"remote\",\"protocolType\":\"MODBUS_TCP\"},"
                            + "{\"deviceId\":\"local\",\"deviceName\":\"wrong-owner\","
                            + "\"configSource\":\"remote\",\"protocolType\":\"MODBUS_TCP\"}]");
            var reload = ConfigManager.class.getDeclaredMethod("loadAllConfig");
            reload.setAccessible(true);
            reload.invoke(manager);
            assertEquals(List.of("local", "remote"), manager.getConfigurationDevices().stream()
                    .map(DeviceInfo::getDeviceId).sorted().toList());
            assertEquals("local", manager.getConfigurationDevice("local").getDeviceName());
            assertEquals("remote-name", manager.getConfigurationDevice("remote").getDeviceName());
            assertEquals("remote", manager.getConfigurationDeviceSnapshot("remote").context()
                    .getDeviceInfo().getDeviceId());
            assertEquals("remote", manager.getConfigurationDevice("remote").getConfigSource());
        }
    }

    void assertState(SqliteConfigurationStore store, ConfigManager manager, String id) {
        var stored = store.findDevice(id).orElseThrow();
        assertTrue(codec.equivalent(codec.toRuntime(stored.configuration()), manager.getDeviceConfigurationSnapshot(id).context()));
        assertTrue(manager.getDeviceConfigVersion(id) > 0);
        assertNotEquals(stored.revision(), manager.getDeviceConfigVersion(id));
    }

    @Test
    void noOpKeepsSqlJvmAndEventsAndStaleJvmCasStillConflicts() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            var snapshot = manager.getDeviceConfigurationSnapshot("a");
            long sql = store.currentRevision();
            int count = events.size();
            var context = snapshot.context();
            assertTrue(manager.updateDeviceConfig(context.getDeviceInfo()));
            assertTrue(manager.updateConnectionConfig("a", context.copyConnectionConfig()));
            assertTrue(manager.updateDataPoints("a", context.copyDataPoints()));
            assertTrue(manager.replaceDeviceContextsAtomically(List.of(context)));
            manager.replaceDeviceContextAtomically("a", context.getDeviceInfo(), context.copyConnectionConfig(), context.copyDataPoints(), snapshot.configVersion());
            manager.saveLocalDeviceConfigWithResult(context.getDeviceInfo(), context.copyConnectionConfig(), context.copyDataPoints(), true);
            assertEquals(sql, store.currentRevision());
            assertEquals(snapshot.configVersion(), manager.getDeviceConfigVersion("a"));
            assertEquals(count, events.size());
            assertThrows(com.wangbin.collector.core.config.manager.ConfigVersionConflictException.class,
                    () -> manager.replaceDeviceContextAtomically("a", context.getDeviceInfo(), context.copyConnectionConfig(), context.copyDataPoints(), snapshot.configVersion() - 1));
            assertEquals(sql, store.currentRevision());
            assertState(store, manager, "a");
        }
    }

    @Test
    void rejectsGlobalPointCollisionIncludingClearedAndSameBatchAndMissingIdentity() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            manager.clearDeviceConfig("a");
            long sql = store.currentRevision();
            int count = events.size();
            assertThrows(IllegalArgumentException.class, () -> save(manager, configuration("b", "p-a")));
            assertThrows(IllegalArgumentException.class, () -> manager.replaceDeviceContextsAtomically(
                    List.of(configuration("b", "p-x"), configuration("c", "p-x"))));
            var missing = configuration("b", "p-new");
            missing.getDataPoints().get(0).setPointId(null);
            assertThrows(IllegalArgumentException.class, () -> save(manager, missing));
            assertEquals(sql, store.currentRevision());
            assertEquals(count, events.size());
            assertEquals(1, store.listDevices().size());
            assertNull(manager.getDevice("b"));
            assertTrue(manager.refreshDeviceConfig("a"));
            assertState(store, manager, "a");
        }
    }

    @Test
    void fourProtocolNestedConfigurationRoundTripsAndJsonKeyOrderIsNoOp() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            for (String protocol : List.of("MODBUS_TCP", "OPC_UA", "IEC104", "MQTT")) {
                String id = "device-" + protocol;
                var candidate = configuration(id, "point-" + protocol);
                candidate.getDeviceInfo().setProtocolType(protocol);
                candidate.getDeviceInfo().setConnectionType(protocol);
                var connection = candidate.copyConnectionConfig();
                connection.setConnectionType(protocol);
                connection.setUrl(protocol.equals("OPC_UA") ? "opc.tcp://127.0.0.1:4840" : null);
                var nested = new java.util.LinkedHashMap<String, Object>();
                nested.put("values", java.util.Arrays.asList(new java.math.BigDecimal("12345678901234567890.123456789"), null, true));
                nested.put("flag", false);
                connection.setExtJson(Map.of("nested", nested));
                connection.setAuthParams(Map.of("privateExtension", new ObjectMapper().writeValueAsString(nested)));
                var point = candidate.copyDataPoints().get(0);
                point.setStatus(0);
                point.setAlarmRule("[{\"type\":\"threshold\",\"unknown\":{\"precise\":12345678901234567890.123456789,\"values\":[true,null]}}]");
                point.setAdditionalConfig(Map.of("historyEnabled", false, "nested", nested));
                var result = manager.saveLocalDeviceConfigWithResult(candidate.getDeviceInfo(), connection, List.of(point), false);
                assertState(store, manager, id);
                var snapshot = manager.getDeviceConfigurationSnapshot(id).context();
                var raw = DataPoint.class.getDeclaredField("alarmRule");
                raw.setAccessible(true);
                assertEquals(pointAlarm(point), raw.get(snapshot.getDataPoints().get(0)));
                long sql = store.currentRevision();
                var reordered = new java.util.LinkedHashMap<String, Object>();
                reordered.put("flag", false);
                reordered.put("values", nested.get("values"));
                var edited = snapshot.copyConnectionConfig();
                var ext = new java.util.LinkedHashMap<>(edited.getExtJson());
                ext.put("nested", reordered);
                edited.setExtJson(ext);
                assertTrue(manager.updateConnectionConfig(id, edited));
                assertEquals(sql, store.currentRevision());
                assertEquals(result.configVersion(), manager.getDeviceConfigVersion(id));
            }
            var restarted = manager(store);
            for (String id : restarted.getAllDeviceIds()) assertState(store, restarted, id);
            assertEquals(4, store.listDevices().size());
        }
    }

    String pointAlarm(DataPoint point) throws Exception {
        var field = DataPoint.class.getDeclaredField("alarmRule");
        field.setAccessible(true);
        return (String) field.get(point);
    }

    @Test
    void writeLockAndSqlTriggerFailuresPreserveAllPreCommitState() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            var before = manager.getDeviceConfigurationSnapshot("a");
            long sql = store.currentRevision();
            var changed = manager.getDeviceConfigurationSnapshot("a").context().getDeviceInfo();
            changed.setDeviceName("not committed");
            int count = events.size();
            try (var blocker = sqlConnection(); var statement = blocker.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                var error = assertThrows(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.class,
                        () -> manager.updateDeviceConfig(changed));
                assertEquals(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.Outcome.WRITE_FAILED, error.getOutcome());
                assertFalse(error.isCommitted());
                assertNull(error.getCause());
                statement.execute("ROLLBACK");
            }
            assertUnchanged(store, manager, before, sql, count);
            executeSql("CREATE TRIGGER fail_write BEFORE UPDATE ON dc_device BEGIN SELECT RAISE(ABORT, 'private-test-failure'); END");
            assertThrows(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.class,
                    () -> manager.updateDeviceConfig(changed));
            assertUnchanged(store, manager, before, sql, count);
        }
    }

    @Test
    void secondBatchDeviceFailureRollsBackFirstAndKeepsOutsideDevice() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("outside", "p-outside"));
            long sql = store.currentRevision();
            long version = manager.getDeviceConfigVersion("outside");
            int count = events.size();
            executeSql("CREATE TRIGGER fail_second BEFORE INSERT ON dc_device WHEN NEW.device_id='b' BEGIN SELECT RAISE(ABORT, 'batch-failure'); END");
            var error = assertThrows(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.class,
                    () -> manager.replaceDeviceContextsAtomically(List.of(configuration("a", "p-a"), configuration("b", "p-b"))));
            assertFalse(error.isCommitted());
            assertEquals(List.of("a", "b"), error.getDeviceIds());
            assertEquals(sql, store.currentRevision());
            assertEquals(1, store.listDevices().size());
            assertNull(manager.getDevice("a"));
            assertNull(manager.getDevice("b"));
            assertEquals(0, manager.getDeviceConfigVersion("a"));
            assertEquals(version, manager.getDeviceConfigVersion("outside"));
            assertEquals(count, events.size());
            assertState(store, manager, "outside");
        }
    }

    @Test
    void externalCasWinnerIsReconciledWithoutRetryOrSqlVersionLeak() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            var stale = manager.getDeviceConfigurationSnapshot("a");
            var winner = manager.getDeviceConfigurationSnapshot("a").context();
            winner.getDeviceInfo().setDeviceName("external-winner");
            var original = store.findDevice("a").orElseThrow();
            try (var other = SqliteConfigurationStore.openExisting(directory.resolve("private/collector.db"), 100)) {
                other.transaction(writer -> writer.saveDevice(codec.fromRuntime(winner), original.revision()));
            }
            long sql = store.currentRevision();
            stale.context().getDeviceInfo().setDeviceName("stale-loser");
            var error = assertThrows(ConfigVersionConflictException.class,
                    () -> manager.updateDeviceConfig(stale.context().getDeviceInfo()));
            assertTrue(error.isPersistedRevision());
            assertEquals(original.revision(), error.getExpectedVersion());
            assertEquals(sql, error.getCurrentVersion());
            assertEquals(sql, store.currentRevision());
            assertEquals("external-winner", manager.getDevice("a").getDeviceName());
            assertTrue(manager.getDeviceConfigVersion("a") > stale.configVersion());
            assertState(store, manager, "a");
            assertEquals(2, events.size());
            assertEquals(manager.getDeviceConfigVersion("a"), ((ConfigUpdateEvent) events.get(1)).getConfigVersion());
            var next = manager.getDeviceConfigurationSnapshot("a").context().getDeviceInfo();
            next.setDeviceName("explicit-next");
            assertTrue(manager.updateDeviceConfig(next));
            assertState(store, manager, "a");
        }
    }

    @Test
    void twoManagersWithSamePersistedRevisionAllowOnlyOneCommit() throws Exception {
        try (var firstStore = database();
             var secondStore = SqliteConfigurationStore.openExisting(directory.resolve("private/collector.db"), 1000)) {
            var first = manager(firstStore);
            save(first, configuration("a", "p-a"));
            var second = manager(secondStore);
            var firstSnapshot = first.getConfigurationDeviceSnapshot("a");
            var secondSnapshot = second.getConfigurationDeviceSnapshot("a");
            assertEquals(firstSnapshot.persistedRevision(), secondSnapshot.persistedRevision());

            var winner = firstSnapshot.context();
            winner.getDeviceInfo().setDeviceName("first-winner");
            var committed = first.replaceDeviceContextAtomically("a", winner.getDeviceInfo(),
                    winner.copyConnectionConfig(), winner.copyDataPoints(), firstSnapshot.configVersion(),
                    firstSnapshot.persistedRevision());
            assertEquals(firstSnapshot.persistedRevision() + 1, committed.persistedRevision());

            var loser = secondSnapshot.context();
            loser.getDeviceInfo().setDeviceName("second-loser");
            var conflict = assertThrows(ConfigVersionConflictException.class,
                    () -> second.replaceDeviceContextAtomically("a", loser.getDeviceInfo(),
                            loser.copyConnectionConfig(), loser.copyDataPoints(), secondSnapshot.configVersion(),
                            secondSnapshot.persistedRevision()));
            assertTrue(conflict.isPersistedRevision());
            assertEquals(firstSnapshot.persistedRevision(), conflict.getExpectedVersion());
            assertEquals(committed.persistedRevision(), conflict.getCurrentVersion());
            assertEquals("first-winner", second.getConfigurationDevice("a").getDeviceName());
            assertEquals(committed.persistedRevision(), second.getConfigurationDeviceSnapshot("a").persistedRevision());
            assertEquals(committed.persistedRevision(), secondStore.currentRevision());
        }
    }

    @Test
    void rawExternalSqliteUpdateInvalidatesStaleManagerEdit() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            var old = manager.getConfigurationDeviceSnapshot("a");
            long newRevision;
            try (var external = sqlConnection()) {
                external.setAutoCommit(false);
                try (var audit = external.prepareStatement("INSERT INTO dc_config_revision(entity_type,entity_id,operation,created_at) VALUES ('DEVICE','a','UPDATE',?)")) {
                    audit.setString(1, "2026-10-29T00:00:00Z");
                    audit.executeUpdate();
                }
                try (var statement = external.createStatement(); var row = statement.executeQuery("SELECT last_insert_rowid()")) {
                    assertTrue(row.next());
                    newRevision = row.getLong(1);
                }
                try (var update = external.prepareStatement("UPDATE dc_device SET device_json=json_set(device_json,'$.deviceName',?),revision=? WHERE device_id='a'")) {
                    update.setString(1, "external-sql-winner");
                    update.setLong(2, newRevision);
                    assertEquals(1, update.executeUpdate());
                }
                external.commit();
            }
            var stale = old.context().getDeviceInfo();
            stale.setDeviceName("stale-client");
            var conflict = assertThrows(ConfigVersionConflictException.class,
                    () -> manager.updateDeviceConfig(stale, old.persistedRevision()));
            assertTrue(conflict.isPersistedRevision());
            assertEquals(old.persistedRevision(), conflict.getExpectedVersion());
            assertEquals(newRevision, conflict.getCurrentVersion());
            assertEquals(newRevision, store.currentRevision());
            assertEquals("external-sql-winner", manager.getConfigurationDevice("a").getDeviceName());
            assertEquals(newRevision, manager.getConfigurationDeviceSnapshot("a").persistedRevision());
        }
    }

    @Test
    void unchangedRequestWithStaleRevisionConflictsWithoutNewAudit() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            var old = manager.getConfigurationDeviceSnapshot("a");
            var current = old.context();
            current.getDeviceInfo().setDeviceName("changed");
            var committed = manager.replaceDeviceContextAtomically("a", current.getDeviceInfo(),
                    current.copyConnectionConfig(), current.copyDataPoints(), old.configVersion(), old.persistedRevision());
            var unchanged = manager.getConfigurationDeviceSnapshot("a");
            int auditCount = store.listRevisions(0, 100).size();
            var conflict = assertThrows(ConfigVersionConflictException.class,
                    () -> manager.replaceDeviceContextAtomically("a", unchanged.context().getDeviceInfo(),
                            unchanged.context().copyConnectionConfig(), unchanged.context().copyDataPoints(),
                            unchanged.configVersion(), old.persistedRevision()));
            assertTrue(conflict.isPersistedRevision());
            assertEquals(committed.persistedRevision(), conflict.getCurrentVersion());
            assertEquals(auditCount, store.listRevisions(0, 100).size());
            assertEquals(unchanged.configVersion(), manager.getDeviceConfigVersion("a"));
            assertEquals(committed.persistedRevision(), manager.getConfigurationDeviceSnapshot("a").persistedRevision());
        }
    }

    @Test
    void batchRevisionConflictRollsBackEarlierDeviceAndPreservesOutsideWinner() throws Exception {
        try (var store = database();
             var external = SqliteConfigurationStore.openExisting(directory.resolve("private/collector.db"), 1000)) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            save(manager, configuration("b", "p-b"));
            var oldA = manager.getConfigurationDeviceSnapshot("a");
            var oldB = manager.getConfigurationDeviceSnapshot("b");
            var winner = oldB.context();
            winner.getDeviceInfo().setDeviceName("external-b");
            long winningRevision = external.transaction(writer -> writer.saveDevice(codec.fromRuntime(winner), oldB.persistedRevision())).revision();
            var changedA = oldA.context();
            changedA.getDeviceInfo().setDeviceName("rolled-back-a");
            var changedB = oldB.context();
            changedB.getDeviceInfo().setDeviceName("stale-b");
            long auditBefore = store.currentRevision();
            var conflict = assertThrows(ConfigVersionConflictException.class,
                    () -> manager.replaceDeviceContextsAtomically(List.of(changedA, changedB),
                            Map.of("a", oldA.persistedRevision(), "b", oldB.persistedRevision())));
            assertTrue(conflict.isPersistedRevision());
            assertEquals("b", conflict.getDeviceId());
            assertEquals(winningRevision, conflict.getCurrentVersion());
            assertEquals(auditBefore, store.currentRevision());
            assertEquals("a", codec.toRuntime(store.findDevice("a").orElseThrow().configuration()).getDeviceInfo().getDeviceName());
            assertEquals("external-b", codec.toRuntime(store.findDevice("b").orElseThrow().configuration()).getDeviceInfo().getDeviceName());
            assertEquals("a", manager.getConfigurationDevice("a").getDeviceName());
            assertEquals("external-b", manager.getConfigurationDevice("b").getDeviceName());
        }
    }

    @Test
    void mixedBatchRetainsUnchangedDeviceVersionPointsAndRevision() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            save(manager, configuration("b", "p-b"));
            var beforeA = manager.getConfigurationDeviceSnapshot("a");
            var beforeB = manager.getConfigurationDeviceSnapshot("b");
            var changedA = beforeA.context();
            changedA.getDeviceInfo().setDeviceName("changed-a");
            assertTrue(manager.replaceDeviceContextsAtomically(List.of(changedA, beforeB.context()),
                    Map.of("a", beforeA.persistedRevision(), "b", beforeB.persistedRevision())));
            var afterA = manager.getConfigurationDeviceSnapshot("a");
            var afterB = manager.getConfigurationDeviceSnapshot("b");
            assertTrue(afterA.persistedRevision() > beforeA.persistedRevision());
            assertEquals(beforeB.persistedRevision(), afterB.persistedRevision());
            assertEquals(beforeB.configVersion(), afterB.configVersion());
            assertEquals(1, afterB.context().copyDataPoints().size());

            var changedB = afterB.context();
            changedB.getDeviceInfo().setDeviceName("changed-b");
            assertTrue(manager.replaceDeviceContextsAtomically(List.of(afterA.context(), changedB),
                    Map.of("a", afterA.persistedRevision(), "b", afterB.persistedRevision())));
            var unchangedA = manager.getConfigurationDeviceSnapshot("a");
            var updatedB = manager.getConfigurationDeviceSnapshot("b");
            assertEquals(afterA.persistedRevision(), unchangedA.persistedRevision());
            assertEquals(afterA.configVersion(), unchangedA.configVersion());
            assertTrue(updatedB.persistedRevision() > afterB.persistedRevision());
            long audit = store.currentRevision();
            assertTrue(manager.replaceDeviceContextsAtomically(List.of(unchangedA.context(), updatedB.context()),
                    Map.of("a", unchangedA.persistedRevision(), "b", updatedB.persistedRevision())));
            assertEquals(audit, store.currentRevision());
            assertEquals(1, manager.getConfigurationDeviceSnapshot("a").context().copyDataPoints().size());
            var stale = assertThrows(ConfigVersionConflictException.class,
                    () -> manager.replaceDeviceContextsAtomically(List.of(unchangedA.context(), updatedB.context()),
                            Map.of("a", beforeA.persistedRevision(), "b", updatedB.persistedRevision())));
            assertEquals("a", stale.getDeviceId());
            assertEquals(audit, store.currentRevision());
        }
    }

    @Test
    void mixedBatchWithEvictedRuntimeVersionKeepsPersistentUnchangedDevice() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            save(manager, configuration("b", "p-b"));
            var beforeA = manager.getConfigurationDeviceSnapshot("a");
            var beforeB = manager.getConfigurationDeviceSnapshot("b");
            manager.clearAllCache();
            assertEquals(0L, manager.getDeviceConfigVersion("b"));
            var changedA = beforeA.context();
            changedA.getDeviceInfo().setDeviceName("changed-a");
            assertTrue(manager.replaceDeviceContextsAtomically(List.of(changedA, beforeB.context()),
                    Map.of("a", beforeA.persistedRevision(), "b", beforeB.persistedRevision())));
            assertEquals(beforeB.persistedRevision(), store.findDevice("b").orElseThrow().revision());
            assertEquals(1, manager.getConfigurationDeviceSnapshot("b").context().copyDataPoints().size());
        }
    }

    @Test
    void mixedDeleteAndUnchangedAggregateRetainsOnlyUnchangedRevision() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            save(manager, configuration("b", "p-b"));
            var beforeA = manager.getConfigurationDeviceSnapshot("a");
            var beforeB = manager.getConfigurationDeviceSnapshot("b");
            var requested = new java.util.LinkedHashMap<String, DeviceContext>();
            requested.put("a", null);
            requested.put("b", beforeB.context());
            // 公共删除入口只接收单设备；直接验证现有批量提交器的删除与未变更分支。
            var commit = ConfigManager.class.getDeclaredMethod("commitLocalAggregates",
                    java.util.function.Supplier.class, ConfigUpdateType.class, String.class, Map.class);
            commit.setAccessible(true);
            Map<?, ?> results = (Map<?, ?>) commit.invoke(manager,
                    (java.util.function.Supplier<Map<String, DeviceContext>>) () -> requested,
                    ConfigUpdateType.LOCAL_DELETE, "mixed-delete",
                    Map.of("a", beforeA.persistedRevision(), "b", beforeB.persistedRevision()));
            var deleted = (ConfigManager.DeviceConfigCommitResult) results.get("a");
            var unchanged = (ConfigManager.DeviceConfigCommitResult) results.get("b");
            assertEquals(0, deleted.pointCount());
            assertEquals(0L, deleted.persistedRevision());
            assertEquals(1, unchanged.pointCount());
            assertEquals(beforeB.configVersion(), unchanged.configVersion());
            assertEquals(beforeB.persistedRevision(), unchanged.persistedRevision());
            assertTrue(store.findDevice("a").isEmpty());
            assertEquals(beforeB.persistedRevision(), store.findDevice("b").orElseThrow().revision());
        }
    }

    @Test
    void partialCrudUsesFreshRevisionAndReopenedManagerKeepsLastRevision() throws Exception {
        long lastRevision;
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            var initial = manager.getConfigurationDeviceSnapshot("a");
            var renamed = initial.context().getDeviceInfo();
            renamed.setDeviceName("renamed");
            assertTrue(manager.updateDeviceConfig(renamed, initial.persistedRevision()));
            var afterDevice = manager.getConfigurationDeviceSnapshot("a");
            assertEquals(initial.persistedRevision() + 1, afterDevice.persistedRevision());
            var connection = afterDevice.context().copyConnectionConfig();
            connection.setPort(2502);
            assertTrue(manager.updateConnectionConfig("a", connection, afterDevice.persistedRevision()));
            var afterConnection = manager.getConfigurationDeviceSnapshot("a");
            assertEquals(afterDevice.persistedRevision() + 1, afterConnection.persistedRevision());
            var points = afterConnection.context().copyDataPoints();
            points.get(0).setAddress("40002");
            assertTrue(manager.updateDataPoints("a", points, afterConnection.persistedRevision()));
            var afterPoints = manager.getConfigurationDeviceSnapshot("a");
            assertEquals(afterConnection.persistedRevision() + 1, afterPoints.persistedRevision());
            assertEquals("renamed", afterPoints.context().getDeviceInfo().getDeviceName());
            assertEquals(2502, afterPoints.context().copyConnectionConfig().getPort());
            assertEquals("40002", afterPoints.context().copyDataPoints().get(0).getAddress());
            var stale = assertThrows(ConfigVersionConflictException.class,
                    () -> manager.updateDataPoints("a", points, afterConnection.persistedRevision()));
            assertTrue(stale.isPersistedRevision());
            assertEquals(afterPoints.persistedRevision(), stale.getCurrentVersion());
            lastRevision = afterPoints.persistedRevision();
            assertEquals(lastRevision, store.currentRevision());
        }
        try (var reopened = SqliteConfigurationStore.openExisting(directory.resolve("private/collector.db"), 1000)) {
            var restarted = manager(reopened);
            var restored = restarted.getConfigurationDeviceSnapshot("a");
            assertEquals(lastRevision, restored.persistedRevision());
            assertEquals("renamed", restored.context().getDeviceInfo().getDeviceName());
            assertEquals(2502, restored.context().copyConnectionConfig().getPort());
            assertEquals("40002", restored.context().copyDataPoints().get(0).getAddress());
        }
    }

    @Test
    void eventFailureDoesNotUndoCommittedStateAndDeleteTombstoneCannotTargetRecreatedDevice() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            long originalVersion = manager.getDeviceConfigVersion("a");
            long originalRevision = store.currentRevision();
            var field = ConfigManager.class.getDeclaredField("eventPublisher");
            field.setAccessible(true);
            final Runnable[] release = new Runnable[1];
            final long[] deleted = new long[1];
            field.set(manager, (org.springframework.context.ApplicationEventPublisher) event -> {
                ConfigUpdateEvent update = (ConfigUpdateEvent) event;
                events.add(event);
                if (update.getConfigType().equals("local-delete")) {
                    deleted[0] = update.getConfigVersion();
                    release[0] = manager.retainDeletedConfigurationVersion("a", deleted[0]);
                    throw new IllegalStateException("listener-failure");
                }
            });
            var failure = assertThrows(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.class,
                    () -> manager.deleteLocalDeviceConfig("a"));
            assertTrue(failure.isCommitted());
            assertFalse(failure.isReconciled());
            assertEquals(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.Outcome.COMMITTED_APPLY_FAILED,
                    failure.getOutcome());
            assertEquals(List.of("a"), failure.getDeviceIds());
            assertTrue(store.findDevice("a").isEmpty());
            assertEquals(originalRevision + 1, store.currentRevision());
            var contextsField = ConfigManager.class.getDeclaredField("deviceContextCache");
            contextsField.setAccessible(true);
            assertFalse(((Map<?, ?>) contextsField.get(manager)).containsKey("a"));
            var unavailable = assertThrows(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.class,
                    () -> manager.getDeviceContext("a"));
            assertEquals(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.Outcome.CONFIGURATION_UNAVAILABLE,
                    unavailable.getOutcome());
            assertFalse(manager.runIfConfigurationCurrent("a", originalVersion, () -> fail("已删除设备的旧任务不得执行")));
            assertNotNull(release[0]);
            assertFalse(manager.runIfConfigurationCurrent("a", deleted[0], () -> fail("事件失败后封闭设备不得执行副作用")));
            field.set(manager, (org.springframework.context.ApplicationEventPublisher) events::add);
            assertFalse(manager.refreshDeviceConfig("a"));
            assertNull(manager.getDeviceContext("a"));
            assertEquals(originalRevision + 1, store.currentRevision());
            save(manager, configuration("a", "p-new"));
            assertFalse(manager.runIfConfigurationCurrent("a", deleted[0], () -> fail("旧异步START/删除回调不得执行")));
            assertNull(manager.retainDeletedConfigurationVersion("a", deleted[0]));
            release[0].run();
            assertState(store, manager, "a");
        }
    }

    @Test
    void cachePublicationFailureReconcilesCommittedAggregateAndReportsTruth() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            var before = manager.getDeviceConfigurationSnapshot("a");
            var field = ConfigManager.class.getDeclaredField("deviceContextCache");
            field.setAccessible(true);
            var failing = new FailingContextMap(1);
            failing.putAll((Map<String, DeviceContext>) field.get(manager));
            field.set(manager, failing);
            var device = before.context().getDeviceInfo();
            device.setDeviceName("committed-after-cache-failure");
            var error = assertThrows(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.class,
                    () -> manager.updateDeviceConfig(device));
            assertTrue(error.isCommitted());
            assertTrue(error.isReconciled());
            assertEquals(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.Outcome.COMMITTED_RECONCILED, error.getOutcome());
            assertTrue(manager.getDeviceConfigVersion("a") > before.configVersion());
            assertEquals("committed-after-cache-failure", manager.getDevice("a").getDeviceName());
            assertState(store, manager, "a");
            assertEquals(2, events.size());
        }
    }

    @Test
    void repeatedCacheFailureFailsClosedOnlyTargetUntilExplicitRefresh() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            save(manager, configuration("b", "p-b"));
            save(manager, configuration("c", "p-c"));
            var before = manager.getDeviceConfigurationSnapshot("a");
            var field = ConfigManager.class.getDeclaredField("deviceContextCache");
            field.setAccessible(true);
            var failing = new FailingContextMap(2);
            failing.putAll((Map<String, DeviceContext>) field.get(manager));
            field.set(manager, failing);
            var device = before.context().getDeviceInfo();
            device.setDeviceName("committed-unavailable");
            var error = assertThrows(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.class,
                    () -> manager.updateDeviceConfig(device));
            assertTrue(error.isCommitted());
            assertFalse(error.isReconciled());
            assertEquals(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.Outcome.COMMITTED_RECOVERY_REQUIRED, error.getOutcome());
            assertEquals("committed-unavailable", codec.toRuntime(store.findDevice("a").orElseThrow().configuration()).getDeviceInfo().getDeviceName());
            assertEquals(List.of("b", "c"), manager.getConfigurationDevices().stream()
                    .map(DeviceInfo::getDeviceId).sorted().toList());
            assertThrows(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.class,
                    () -> manager.getConfigurationDevice("a"));
            assertThrows(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.class,
                    () -> manager.getConfigurationDeviceSnapshot("a"));
            assertThrows(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.class, () -> manager.getDeviceConfigurationSnapshot("a"));
            assertThrows(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.class, () -> manager.getDevice("a"));
            assertThrows(com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.class, () -> manager.updateDeviceConfig(device));
            assertFalse(manager.runIfConfigurationCurrent("a", before.configVersion(), () -> fail("不一致配置不可用于START")));
            assertState(store, manager, "b");
            assertTrue(manager.refreshDeviceConfig("a"));
            assertState(store, manager, "a");
            assertTrue(manager.getDeviceConfigVersion("a") > before.configVersion());
        }
    }

    @Test
    void concurrentBundlesWithOneBaseVersionHaveExactlyOneWinner() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            long base = manager.getDeviceConfigVersion("a");
            long revision = store.currentRevision();
            events.clear();
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
                for (String name : List.of("first", "second")) {
                    tasks.add(pool.submit(() -> {
                        var candidate = manager.getDeviceConfigurationSnapshot("a").context();
                        candidate.getDeviceInfo().setDeviceName(name);
                        ready.countDown();
                        assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        try {
                            manager.replaceDeviceContextAtomically("a", candidate.getDeviceInfo(),
                                    candidate.copyConnectionConfig(), candidate.copyDataPoints(), base);
                            return true;
                        } catch (ConfigVersionConflictException conflict) {
                            assertEquals(base, conflict.getExpectedVersion());
                            return false;
                        }
                    }));
                }
                assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
                start.countDown();
                int winners = 0;
                for (var task : tasks) if (task.get(10, java.util.concurrent.TimeUnit.SECONDS)) winners++;
                assertEquals(1, winners);
                assertEquals(revision + 1, store.currentRevision());
                assertEquals(1, events.size());
                assertEquals(manager.getDeviceConfigVersion("a"), ((ConfigUpdateEvent) events.get(0)).getConfigVersion());
                assertState(store, manager, "a");
            } finally {
                start.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void concurrentDifferentDevicesKeepIndependentAggregatesAndVersions() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            save(manager, configuration("b", "p-b"));
            long revision = store.currentRevision();
            events.clear();
            var start = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
                for (String id : List.of("a", "b")) {
                    tasks.add(pool.submit(() -> {
                        var snapshot = manager.getDeviceConfigurationSnapshot(id);
                        snapshot.context().getDeviceInfo().setDeviceName(id + "-changed");
                        assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        manager.replaceDeviceContextAtomically(id, snapshot.context().getDeviceInfo(),
                                snapshot.context().copyConnectionConfig(), snapshot.context().copyDataPoints(), snapshot.configVersion());
                        return true;
                    }));
                }
                start.countDown();
                for (var task : tasks) assertTrue(task.get(10, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(revision + 2, store.currentRevision());
                assertEquals(2, events.size());
                for (String id : List.of("a", "b")) {
                    assertEquals(id + "-changed", manager.getDevice(id).getDeviceName());
                    assertEquals("p-" + id, manager.getDataPoints(id).get(0).getPointId());
                    assertState(store, manager, id);
                }
            } finally {
                start.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void deleteRacingUpdateCannotResurrectTheAggregate() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            var candidate = manager.getDeviceConfigurationSnapshot("a").context().getDeviceInfo();
            candidate.setDeviceName("racing-update");
            long revision = store.currentRevision();
            events.clear();
            var start = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var update = pool.submit(() -> {
                    assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    try {
                        return manager.updateDeviceConfig(candidate);
                    } catch (IllegalArgumentException missingAfterDelete) {
                        return false;
                    }
                });
                var delete = pool.submit(() -> {
                    assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    return manager.deleteLocalDeviceConfig("a");
                });
                start.countDown();
                boolean updated = update.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(delete.get(10, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(revision + (updated ? 2 : 1), store.currentRevision());
                assertEquals(updated ? 2 : 1, events.size());
                assertTrue(store.findDevice("a").isEmpty());
                assertNull(manager.getDeviceContext("a"));
                assertEquals(0, manager.getDeviceConfigVersion("a"));
                assertNull(manager(store).getDeviceContext("a"));
            } finally {
                start.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void pendingAsynchronousStartIsRejectedAfterCommittedDelete() throws Exception {
        try (var store = database()) {
            var manager = manager(store);
            save(manager, configuration("a", "p-a"));
            long capturedVersion = manager.getDeviceConfigVersion("a");
            long revision = store.currentRevision();
            events.clear();
            var captured = new java.util.concurrent.CountDownLatch(1);
            var continueStart = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var pending = pool.submit(() -> {
                    captured.countDown();
                    assertTrue(continueStart.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    return manager.runIfConfigurationCurrent("a", capturedVersion,
                            () -> fail("已删除配置的旧异步START不得执行"));
                });
                assertTrue(captured.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(manager.deleteLocalDeviceConfig("a"));
                continueStart.countDown();
                assertFalse(pending.get(10, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(revision + 1, store.currentRevision());
                assertTrue(store.findDevice("a").isEmpty());
                assertNull(manager.getDeviceContext("a"));
                assertEquals(1, events.size());
                assertEquals("local-delete", ((ConfigUpdateEvent) events.get(0)).getConfigType());
            } finally {
                continueStart.countDown();
                pool.shutdownNow();
            }
        }
    }

    static class FailingContextMap extends java.util.concurrent.ConcurrentHashMap<String, DeviceContext> {
        int remaining;
        FailingContextMap(int remaining) { this.remaining = remaining; }
        @Override public DeviceContext put(String key, DeviceContext value) {
            if (key.equals("a") && remaining-- > 0) throw new IllegalStateException("cache-install-failure");
            return super.put(key, value);
        }
    }

    java.sql.Connection sqlConnection() throws Exception {
        return java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("private/collector.db"));
    }

    void executeSql(String sql) throws Exception {
        try (var connection = sqlConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    void corruptJson(String sql) throws Exception {
        try (var connection = sqlConnection(); var statement = connection.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.execute(sql);
        }
    }

    void assertUnchanged(SqliteConfigurationStore store, ConfigManager manager,
                         ConfigManager.DeviceConfigurationSnapshot before, long sql, int eventCount) {
        assertEquals(sql, store.currentRevision());
        assertEquals(before.configVersion(), manager.getDeviceConfigVersion("a"));
        assertTrue(codec.equivalent(before.context(), manager.getDeviceConfigurationSnapshot("a").context()));
        assertEquals(eventCount, events.size());
        assertState(store, manager, "a");
    }

    SqliteConfigurationStore database() throws Exception {
        return SqliteConfigurationStore.createNew(directory.resolve("private/collector.db"), 100);
    }

    ConfigManager manager(SqliteConfigurationStore store) throws Exception {
        Path remote = directory.resolve("remote.json");
        Files.writeString(remote, "[]");
        var properties = new CollectorProperties();
        properties.getConfig().getFile().setDevices(remote.toString());
        properties.getConfig().setLocalDeviceSnapshotFile(directory.resolve("never-write.json").toString());
        var sync = new ConfigSyncService(new FileConfigLoader(properties), Runnable::run, properties);
        var bridge = new WangbinSqliteLocalConfigurationPersistence(store);
        var beans = new StaticListableBeanFactory(Map.of("persistence", bridge));
        var manager = new ConfigManager(sync, events::add, new FieldUniquenessValidator(),
                new LocalDeviceConfigStore(new ObjectMapper(), properties), null, null,
                beans.getBeanProvider(WangbinLocalConfigurationPersistence.class));
        manager.init();
        return manager;
    }

    DeviceContext configuration(String deviceId, String pointId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(deviceId);
        device.setProtocolType("MODBUS_TCP");
        device.setConnectionType("MODBUS_TCP");
        device.setConfigSource("local");
        device.setTemporaryConfig(true);
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(1502);
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setAddress("40001");
        point.setDataType("FLOAT");
        point.setStatus(1);
        return DeviceContext.of(device, connection, List.of(point));
    }
}

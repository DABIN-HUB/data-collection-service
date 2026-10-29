package com.wangbin.collector.config.sqlite.store;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.core.config.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 SQLite 仓储测试，不使用 Spring 或数据源替身。 */
class SqliteConfigurationStoreTest {
    @TempDir Path temp;
    private final ConfigurationJsonCodec codec = new ConfigurationJsonCodec();
    private Path path() { return temp.resolve("private-data/collector.db"); }
    private SqliteConfigurationStore create() { return SqliteConfigurationStore.createNew(path(), 100); }
    private DeviceConfiguration device(String id, String point) {
        return codec.decodeLocalDevice("""
                {"device":{"deviceId":"%s","configSource":"local","temporaryConfig":true},
                 "connection":{"deviceId":"%s","extJson":{"unknown":[null,12345678901234567890],"fraction":0.123456789012345678901},"password":"PLACEHOLDER_ONLY"},
                 "points":[{"deviceId":"%s","pointId":"%s","pointCode":"same","status":1,"alarmRule":{"unknown":true},"additionalConfig":{"historyEnabled":true}}]}
                """.formatted(id, id, id, point));
    }
    private MigrationRecord record() { return new MigrationRecord("m", "placeholder.json", "a".repeat(64), 1, 1, 1, Instant.parse("2026-10-22T00:00:00Z")); }

    @Test void summariesReadOnlyDeviceColumnsAndDetailsRemainTargeted() throws Exception {
        try (var store = create()) {
            assertTrue(store.listDeviceSummaries().isEmpty());
            assertTrue(store.findDeviceSummary("missing").isEmpty());
            store.transaction(w -> { w.saveDevice(device("a", "p-a"), 0); w.saveDevice(device("b", "p-b"), 0); return null; });
            try (var sql = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = sql.createStatement()) {
                statement.execute("PRAGMA ignore_check_constraints=ON");
                statement.executeUpdate("UPDATE dc_device_connection SET connection_json = '{broken' WHERE device_id = 'b'");
                statement.executeUpdate("UPDATE dc_device_point SET point_json = '{broken' WHERE device_id = 'b'");
            }
            assertEquals(List.of("a", "b"), store.listDeviceSummaries().stream().map(StoredDeviceSummary::deviceId).toList());
            assertEquals("a", store.findDeviceSummary("a").orElseThrow().deviceId());
            assertEquals(1, store.listDevicePoints("a").size());
            assertEquals("p-a", store.listDevicePoints("a").get(0).pointId());
            assertTrue(store.findDeviceConnection("a").isPresent());
            assertTrue(store.findDeviceConnection("missing").isEmpty());
            assertTrue(store.listDevicePoints("missing").isEmpty());
            assertThrows(RuntimeException.class, store::listDevices);
        }
    }

    @Test void pointOwnerLookupIsScopedBatchedAndSharesWriterTransaction() {
        try (var store = create()) {
            store.transaction(w -> w.saveDevice(device("a", "p-a"), 0));
            List<String> candidates = new ArrayList<>();
            for (int i = 0; i < 600; i++) candidates.add("new-" + i);
            candidates.add("p-a");
            assertEquals(Map.of("p-a", "a"), store.transaction(w -> w.findPointOwners(candidates)));
            assertEquals(Map.of(), store.transaction(w -> w.findPointOwners(List.of())));
            assertThrows(IllegalArgumentException.class, () -> store.transaction(w -> {
                w.saveDevice(device("b", "p-b"), 0);
                assertEquals("b", w.findPointOwners(List.of("p-b")).get("p-b"));
                throw new IllegalArgumentException("rollback");
            }));
            assertTrue(store.findDevice("b").isEmpty());
            assertEquals(1, store.currentRevision());
        }
    }

    @Test void createsAndReopensRealDatabase() {
        var input = device("d", "p");
        try (var store = create()) {
            assertEquals(0, store.currentRevision());
            assertEquals(1, store.transaction(w -> w.saveDevice(input, 0)).revision());
            assertEquals(input, store.findDevice("d").orElseThrow().configuration());
        }
        try (var store = SqliteConfigurationStore.openExisting(path(), 100)) {
            assertEquals(1, store.currentRevision()); assertEquals(input, store.findDevice("d").orElseThrow().configuration());
        }
    }
    @Test void deviceCrudAndCasAreAtomic() {
        try (var store = create()) {
            store.transaction(w -> w.saveDevice(device("d", "p"), 0));
            assertThrows(ConfigurationConflictException.class, () -> store.transaction(w -> w.saveDevice(device("d", "p"), 0)));
            assertThrows(ConfigurationConflictException.class, () -> store.transaction(w -> w.saveDevice(device("absent", "p2"), 1)));
            assertThrows(ConfigurationConflictException.class, () -> store.transaction(w -> w.saveDevice(device("d", "p"), 99)));
            assertEquals(2, store.transaction(w -> w.saveDevice(device("d", "next"), 1)).revision());
            assertEquals("next", store.findDevice("d").orElseThrow().configuration().points().get(0).pointId());
            assertThrows(IllegalArgumentException.class, () -> store.transaction(w -> { w.deleteDevice("d", 0); return null; }));
            assertThrows(ConfigurationConflictException.class, () -> store.transaction(w -> { w.deleteDevice("d", 1); return null; }));
            store.transaction(w -> { w.deleteDevice("d", 2); return null; });
            assertTrue(store.listDevices().isEmpty());
            assertEquals(List.of("CREATE", "UPDATE", "DELETE"), store.listRevisions(0, 10).stream().map(ConfigurationRevision::operation).toList());
            assertEquals(1, store.listRevisions(1, 1).size());
            assertThrows(ConfigurationConflictException.class, () -> store.transaction(w -> { w.deleteDevice("d", 2); return null; }));
        }
    }
    @Test void callbackFailureRollsBackDataAuditAndMigration() {
        try (var store = create()) {
            assertThrows(IllegalArgumentException.class, () -> store.transaction(w -> {
                w.saveDevice(device("d", "p"), 0); w.recordMigration(record()); throw new IllegalArgumentException("受控失败");
            }));
            assertEquals(0, store.currentRevision()); assertTrue(store.listDevices().isEmpty()); assertTrue(store.listMigrations().isEmpty());
            assertEquals(1, store.transaction(w -> w.saveGatewaySetting("k", "null", 0)).revision());
        }
    }
    @Test void duplicateGlobalPointRollsBackEvenWhenCaught() {
        try (var store = create()) {
            store.transaction(w -> w.saveDevice(device("d", "global"), 0));
            assertThrows(IllegalStateException.class, () -> store.transaction(w -> {
                assertThrows(IllegalStateException.class, () -> w.saveDevice(device("d2", "global"), 0));
                assertThrows(IllegalStateException.class, w::listDevices);
                assertThrows(IllegalStateException.class, w::currentRevision);
                return null;
            }));
            assertEquals(1, store.currentRevision()); assertTrue(store.findDevice("d2").isEmpty());
            store.transaction(w -> w.saveDevice(device("d2", "other"), 0)); assertEquals(2, store.listDevices().size());
        }
    }
    @Test void writerSeesOwnWritesAndExternalConnectionDoesNot() {
        try (var store = create(); var other = SqliteConfigurationStore.openExisting(path(), 100)) {
            store.transaction(w -> {
                w.saveDevice(device("d", "p"), 0); assertEquals(1, w.listDevices().size()); assertEquals(1, w.currentRevision());
                assertTrue(w.findDevice("d").isPresent()); assertTrue(other.listDevices().isEmpty()); return null;
            }); assertEquals(1, other.listDevices().size());
        }
    }
    private List<Consumer<ConfigurationStore.Writer>> operations() {
        return List.of(w -> w.findDevice("d"), ConfigurationReader::listDevices,
                w -> w.findProtocolProfile("p"), ConfigurationReader::listProtocolProfiles,
                w -> w.findGatewaySetting("k"), ConfigurationReader::listGatewaySettings,
                w -> w.findRemoteSnapshot("cloud", "s", "v"), w -> w.listRemoteSnapshots("cloud"),
                ConfigurationReader::currentRevision, w -> w.listRevisions(0, 1), w -> w.findMigration("m"), ConfigurationReader::listMigrations,
                w -> w.saveDevice(device("d", "p"), 0), w -> w.deleteDevice("d", 1),
                w -> w.saveProtocolProfile("p", "TCP", "{}", 0), w -> w.deleteProtocolProfile("p", 1),
                w -> w.saveGatewaySetting("k", "null", 0), w -> w.deleteGatewaySetting("k", 1),
                w -> w.saveRemoteSnapshot("cloud", "s", "v", "{}", 0), w -> w.deleteRemoteSnapshot("cloud", "s", "v", 1),
                w -> w.recordMigration(record()));
    }
    @Test void everyWriterMethodRejectsCrossThreadAndEscapedCalls() {
        try (var store = create()) {
            AtomicReference<ConfigurationStore.Writer> captured = new AtomicReference<>();
            store.transaction(w -> {
                captured.set(w); var executor = Executors.newSingleThreadExecutor();
                try { executor.submit(() -> operations().forEach(call -> assertThrows(IllegalStateException.class, () -> call.accept(w)))).get(5, TimeUnit.SECONDS); }
                catch (Exception exception) { throw new AssertionError(exception); }
                finally { executor.shutdownNow(); } return null;
            }); operations().forEach(call -> assertThrows(IllegalStateException.class, () -> call.accept(captured.get())));
        }
    }
    @Test void nestedTransactionsAndClosedStoreAreRejected() {
        var store = create();
        assertThrows(IllegalStateException.class, () -> store.transaction(w -> store.transaction(inner -> null)));
        assertEquals(0, store.currentRevision()); store.close(); store.close();
        assertThrows(IllegalStateException.class, store::listDevices); assertThrows(IllegalStateException.class, () -> store.transaction(w -> null)); assertThrows(IllegalStateException.class, store::currentRevision);
    }
    @Test void closeDuringCallbackRollsBack() {
        var store = create();
        assertThrows(IllegalStateException.class, () -> store.transaction(w -> { w.saveGatewaySetting("k", "{}", 0); store.close(); return null; }));
        try (var reopened = SqliteConfigurationStore.openExisting(path(), 100)) { assertEquals(0, reopened.currentRevision()); }
    }
    @Test void profileSettingAndSnapshotCrudPreserveExactJson() {
        String json = "{\"precise\":0.123456789012345678901234567890,\"unknown\":[null,123456789012345678901234567890]}";
        try (var store = create()) {
            store.transaction(w -> {
                w.saveProtocolProfile("p", "CUSTOM", json, 0); w.saveGatewaySetting("k", "[null,true,12345678901234567890]", 0);
                w.saveRemoteSnapshot("cloud", "d", "v1", json, 0); w.saveRemoteSnapshot("cloud", "d", "v2", "false", 0); return null;
            });
            assertEquals(json, store.findProtocolProfile("p").orElseThrow().configJson()); assertEquals(json, store.findRemoteSnapshot("cloud", "d", "v1").orElseThrow().configJson());
            assertEquals(2, store.listRemoteSnapshots("cloud").size()); assertTrue(store.listDevices().isEmpty());
            store.transaction(w -> {
                w.saveProtocolProfile("p", "NEXT", "{}", w.findProtocolProfile("p").orElseThrow().revision());
                w.saveGatewaySetting("k", "null", w.findGatewaySetting("k").orElseThrow().revision());
                w.saveRemoteSnapshot("cloud", "d", "v1", "{}", w.findRemoteSnapshot("cloud", "d", "v1").orElseThrow().revision()); return null;
            });
            assertEquals("NEXT", store.listProtocolProfiles().get(0).protocolType()); assertEquals("null", store.listGatewaySettings().get(0).configJson());
            store.transaction(w -> {
                w.deleteProtocolProfile("p", w.findProtocolProfile("p").orElseThrow().revision()); w.deleteGatewaySetting("k", w.findGatewaySetting("k").orElseThrow().revision());
                w.deleteRemoteSnapshot("cloud", "d", "v1", w.findRemoteSnapshot("cloud", "d", "v1").orElseThrow().revision()); return null;
            });
            assertTrue(store.listProtocolProfiles().isEmpty()); assertTrue(store.listGatewaySettings().isEmpty()); assertEquals("v2", store.listRemoteSnapshots("cloud").get(0).sourceVersion());
        }
    }
    @Test void allOtherEntitiesRejectCasConflicts() {
        try (var store = create()) {
            store.transaction(w -> { w.saveProtocolProfile("p", "TCP", "{}", 0); w.saveGatewaySetting("k", "{}", 0); w.saveRemoteSnapshot("cloud", "s", "v", "{}", 0); return null; });
            List<Consumer<ConfigurationStore.Writer>> conflicts = List.of(
                    w -> w.saveProtocolProfile("p", "TCP", "{}", 0), w -> w.saveProtocolProfile("absent", "TCP", "{}", 9), w -> w.deleteProtocolProfile("p", 99), w -> w.deleteProtocolProfile("absent", 99),
                    w -> w.saveGatewaySetting("k", "{}", 0), w -> w.saveGatewaySetting("absent", "{}", 9), w -> w.deleteGatewaySetting("k", 99), w -> w.deleteGatewaySetting("absent", 99),
                    w -> w.saveRemoteSnapshot("cloud", "s", "v", "{}", 0), w -> w.saveRemoteSnapshot("cloud", "s", "absent", "{}", 9), w -> w.deleteRemoteSnapshot("cloud", "s", "v", 99), w -> w.deleteRemoteSnapshot("cloud", "s", "absent", 99));
            for (var conflict : conflicts) assertThrows(ConfigurationConflictException.class, () -> store.transaction(w -> { conflict.accept(w); return null; })); assertEquals(3, store.currentRevision());
        }
    }
    @Test void migrationIsAtomicAndDuplicateRejected() {
        try (var store = create()) {
            store.transaction(w -> { w.saveDevice(device("d", "p"), 0); w.recordMigration(record()); assertEquals(record(), w.findMigration("m").orElseThrow()); return null; });
            assertEquals(List.of(record()), store.listMigrations());
            assertEquals(2, store.currentRevision());
            assertEquals("JSON_MIGRATION", store.listRevisions(1, 1).get(0).entityType());
            long revision = store.currentRevision();
            assertThrows(ConfigurationConflictException.class, () -> store.transaction(w -> { w.saveDevice(device("d2", "p2"), 0); w.recordMigration(record()); return null; }));
            assertEquals(revision, store.currentRevision()); assertTrue(store.findDevice("d2").isEmpty());
        }
    }
    @Test void writerBoundaryRejectsPollutedModelsAndJson() {
        try (var store = create()) {
            var good = device("d", "p"); var point = good.points().get(0);
            for (String field : List.of("status", "configSource", "temporaryConfig")) {
                ObjectNode node = (ObjectNode) codec.readJson(good.deviceJson()); node.put(field, "WRONG");
                var bad = new DeviceConfiguration("d", node.toString(), good.connection(), good.points());
                assertThrows(IllegalArgumentException.class, () -> store.transaction(w -> w.saveDevice(bad, 0)));
            }
            var wrongStatus = new DeviceConfiguration("d", good.deviceJson(), good.connection(), List.of(new PointConfiguration("d", "p", 0, 0, point.configJson())));
            assertThrows(IllegalArgumentException.class, () -> store.transaction(w -> w.saveDevice(wrongStatus, 0)));
            for (String json : List.of("bad", "{} {}", "{\"a\":1,\"a\":2}")) {
                assertThrows(IllegalArgumentException.class, () -> store.transaction(w -> w.saveGatewaySetting("k", json, 0)));
                assertThrows(IllegalArgumentException.class, () -> store.transaction(w -> w.saveRemoteSnapshot("cloud", "s", "v", json, 0)));
            }
            for (String json : List.of("null", "true", "[]", "1", "\"text\"")) assertThrows(IllegalArgumentException.class, () -> store.transaction(w -> w.saveProtocolProfile("p", "TCP", json, 0)));
            assertThrows(IllegalArgumentException.class, () -> store.transaction(w -> w.saveRemoteSnapshot("local", "d", "v", "{}", 0))); assertEquals(0, store.currentRevision());
        }
    }
    @Test void nullableStatusAndWhitespaceIdentitySurviveWithoutConnection() {
        try (var store = create()) {
            var config = codec.decodeLocalDevice("{\"device\":{\"deviceId\":\" d \",\"configSource\":\"local\",\"temporaryConfig\":true},\"points\":[{\"deviceId\":\" d \",\"pointId\":\" p \",\"status\":null}]}");
            store.transaction(w -> w.saveDevice(config, 0)); assertEquals(config, store.findDevice(" d ").orElseThrow().configuration()); assertTrue(store.findDevice("d").isEmpty());
        }
    }
    @Test void lockFailureIsSanitizedAndRecovers() throws Exception {
        try (var store = create(); Connection blocker = DriverManager.getConnection("jdbc:sqlite:" + path().toUri().toASCIIString() + "?mode=rw"); Statement sql = blocker.createStatement()) {
            sql.execute("BEGIN IMMEDIATE");
            var failure = assertThrows(IllegalStateException.class, () -> store.transaction(w -> w.saveGatewaySetting("PLACEHOLDER_SECRET", "\"PLACEHOLDER_SECRET\"", 0)));
            assertNull(failure.getCause()); assertFalse(failure.toString().contains("PLACEHOLDER_SECRET")); assertTrue(failure.getMessage().contains("占用"));
            sql.execute("ROLLBACK"); assertEquals(1, store.transaction(w -> w.saveGatewaySetting("ok", "{}", 0)).revision());
        }
    }
    @Test void concurrentWritersAllocateUniqueRevisions() throws Exception {
        try (var first = SqliteConfigurationStore.createNew(path(), 3000); var second = SqliteConfigurationStore.openExisting(path(), 3000)) {
            var executor = Executors.newFixedThreadPool(2);
            try {
                List<Future<Long>> futures = new ArrayList<>();
                for (int i = 0; i < 16; i++) { int id = i; var store = i % 2 == 0 ? first : second; futures.add(executor.submit(() -> store.transaction(w -> w.saveGatewaySetting("k" + id, "null", 0)).revision())); }
                Set<Long> revisions = new HashSet<>(); for (var future : futures) revisions.add(future.get(10, TimeUnit.SECONDS));
                assertEquals(16, revisions.size()); assertEquals(16, first.currentRevision()); assertEquals(16, first.listGatewaySettings().size());
            } finally { executor.shutdownNow(); }
        }
    }
    @Test void aggregateListsStayConsistentDuringConcurrentAtomicUpdates() throws Exception {
        try (var reader = SqliteConfigurationStore.createNew(path(), 3000); var writer = SqliteConfigurationStore.openExisting(path(), 3000)) {
            writer.transaction(w -> { w.saveDevice(device("d1", "v0-1"), 0); w.saveDevice(device("d2", "v0-2"), 0); return null; });
            var executor = Executors.newSingleThreadExecutor();
            try {
                var future = executor.submit(() -> {
                    for (int i = 1; i <= 12; i++) {
                        int version = i;
                        writer.transaction(w -> {
                            w.saveDevice(device("d1", "v" + version + "-1"), w.findDevice("d1").orElseThrow().revision());
                            w.saveDevice(device("d2", "v" + version + "-2"), w.findDevice("d2").orElseThrow().revision()); return null;
                        });
                    }
                });
                for (int i = 0; i < 25; i++) {
                    var devices = reader.listDevices(); assertEquals(2, devices.size());
                    String first = devices.get(0).configuration().points().get(0).pointId().split("-")[0];
                    String second = devices.get(1).configuration().points().get(0).pointId().split("-")[0]; assertEquals(first, second);
                }
                future.get(15, TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
        }
    }
    @Test void malformedStoredMetadataNeverLeaksPayloadThroughParserCause() throws Exception {
        try (var store = create()) {
            store.transaction(w -> { w.recordMigration(record()); return null; });
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path().toUri().toASCIIString() + "?mode=rw"); var sql = connection.createStatement()) {
                sql.executeUpdate("UPDATE dc_json_migration SET completed_at='PLACEHOLDER_SECRET'");
            }
            var failure = assertThrows(IllegalStateException.class, () -> store.findMigration("m"));
            assertNull(failure.getCause()); assertFalse(failure.toString().contains("PLACEHOLDER_SECRET"));
        }
    }
    @Test void immutableCollectionsAndPayloadFreeAuditAndCursorValidation() {
        try (var store = create()) {
            store.transaction(w -> w.saveDevice(device("d", "p"), 0));
            assertThrows(UnsupportedOperationException.class, () -> store.listDevices().clear()); assertThrows(UnsupportedOperationException.class, () -> store.listRevisions(0, 10).clear());
            var audit = store.listRevisions(0, 10).get(0); assertEquals("DEVICE", audit.entityType()); assertEquals("d", audit.entityId()); assertFalse(audit.toString().contains("PLACEHOLDER_ONLY"));
            assertThrows(IllegalArgumentException.class, () -> store.listRevisions(-1, 1)); assertThrows(IllegalArgumentException.class, () -> store.listRevisions(0, 0));
        }
    }
}

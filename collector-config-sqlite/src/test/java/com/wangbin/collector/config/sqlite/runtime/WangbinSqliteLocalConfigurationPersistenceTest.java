package com.wangbin.collector.config.sqlite.runtime;

import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.config.sqlite.migration.LegacyJsonMigration;
import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import com.wangbin.collector.core.config.persistence.DeviceConfiguration;
import com.wangbin.collector.core.config.persistence.StoredDeviceConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 仅操作 scratch 下本测试创建的新来源与新库，不接触既有用户配置。 */
class WangbinSqliteLocalConfigurationPersistenceTest {
    private final ConfigurationJsonCodec codec = new ConfigurationJsonCodec();
    private Path directory;
    private Path source;
    private Path database;
    private SqliteConfigurationStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path scratch = Path.of(System.getProperty("user.home"), "AppData", "Local", "hermes", "cache", "scratch");
        Files.createDirectories(scratch);
        directory = Files.createTempDirectory(scratch, "wangbin-phase2-mapping-");
        source = directory.resolve("source.json");
        database = directory.resolve("configuration.db");
        Files.writeString(source, "[" + document("initial") + "]");
        store = SqliteConfigurationStore.createNew(database, 1000);
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (store != null) store.close();
        if (directory != null) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void freshDatabaseRestoresWithoutAnyLegacyFileOrMigrationMarker() throws Exception {
        Files.delete(source);
        var port = new WangbinSqliteLocalConfigurationPersistence(store);
        assertTrue(port.restore().isEmpty());
        assertTrue(store.listMigrations().isEmpty());
        assertEquals(0, store.currentRevision());
        assertFalse(Files.exists(source));
    }

    @Test
    void databaseOnlyRestoresNewWritesAfterReopenAndNeverReadsLegacyJson() throws Exception {
        Files.writeString(source, "not-valid-json");
        var port = new WangbinSqliteLocalConfigurationPersistence(store);
        var first = port.transaction(writer -> writer.saveDevice(config("new-device"), 0));
        port.transaction(writer -> writer.saveDevice(codec.decodeLocalDevice(
                document("new-device").replace("original-name", "persisted-name")), first.revision()));
        long revision = store.currentRevision();
        store.close();
        store = SqliteConfigurationStore.openExisting(database, 1000);
        port = new WangbinSqliteLocalConfigurationPersistence(store);
        var restored = port.restore();
        assertEquals(1, restored.size());
        assertEquals("persisted-name", port.toRuntime(restored.get(0).configuration()).getDeviceInfo().getDeviceName());
        assertEquals(revision, restored.get(0).revision());
        assertEquals(revision, store.currentRevision());
        assertTrue(store.listMigrations().isEmpty());
        assertEquals("not-valid-json", Files.readString(source));
        long expected = restored.get(0).revision();
        port.transaction(writer -> { writer.deleteDevice("new-device", expected); return null; });
        assertTrue(new WangbinSqliteLocalConfigurationPersistence(store).restore().isEmpty());
    }

    @Test
    void databaseOnlyStillRejectsUntrackedDeviceRemoval() throws Exception {
        var port = new WangbinSqliteLocalConfigurationPersistence(store);
        port.transaction(writer -> writer.saveDevice(config("new-device"), 0));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM dc_device WHERE device_id='new-device'");
        }
        assertThrows(IllegalStateException.class, port::restore);
    }

    @Test
    void restoresOnlyAfterExplicitMigrationWithoutWritingRevisions() {
        migrate();
        long before = store.currentRevision();
        var port = port();
        List<StoredDeviceConfiguration> restored = port.restore();
        assertEquals(1, restored.size());
        assertEquals("initial", restored.get(0).configuration().deviceId());
        assertEquals(before, store.currentRevision());
        assertEquals("initial", port.toRuntime(restored.get(0).configuration()).getDeviceId());
        assertTrue(port.equivalent(port.toRuntime(restored.get(0).configuration()),
                port.toRuntime(port.fromRuntime(port.toRuntime(restored.get(0).configuration())))));
    }

    @Test
    void rejectsMissingMarkerEvenForEmptySourceAndEmptyStore() throws Exception {
        Files.writeString(source, "[]");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> port().restore());
        assertTrue(error.getMessage().contains("迁移"));
        assertNull(error.getCause());
        assertTrue(store.listDevices().isEmpty());
        assertEquals(0, store.currentRevision());
        migrate();
        assertTrue(port().restore().isEmpty());
    }

    @Test
    void sourceChangesAreRejectedButFindDeviceReadsCurrentStore() throws Exception {
        migrate();
        long before = store.currentRevision();
        Files.writeString(source, "[]");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> port().restore());
        assertTrue(error.getMessage().contains("SHA"));
        assertNull(error.getCause());
        assertTrue(port().findDevice("initial").isPresent());
        assertEquals(before, store.currentRevision());
    }

    @Test
    void rejectsDifferentCanonicalSourceAndMissingSourceWithoutCause() throws Exception {
        migrate();
        Path other = directory.resolve("other.json");
        Files.copy(source, other);
        assertThrows(IllegalStateException.class, () -> new WangbinSqliteLocalConfigurationPersistence(store, other).restore());
        assertEquals(1, new WangbinSqliteLocalConfigurationPersistence(store,
                directory.resolve("child/../source.json")).restore().size());
        Files.delete(source);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> port().restore());
        assertNull(error.getCause());
    }

    @Test
    void acceptsAuditedCreateUpdateDeleteAndRecreationWithoutOriginalCountRestriction() {
        migrate();
        var port = port();
        StoredDeviceConfiguration original = port.findDevice("initial").orElseThrow();
        port.transaction(writer -> {
            writer.saveDevice(config("added"), 0);
            writer.saveDevice(codec.decodeLocalDevice(document("initial").replace("original-name", "updated-name")), original.revision());
            return null;
        });
        assertEquals(2, port.restore().size());
        long current = port.findDevice("initial").orElseThrow().revision();
        port.transaction(writer -> { writer.deleteDevice("initial", current); return null; });
        assertEquals(List.of("added"), port.restore().stream().map(item -> item.configuration().deviceId()).toList());
        port.transaction(writer -> { writer.saveDevice(config("initial"), 0); return null; });
        assertEquals(2, port.restore().size());
        long added = port.findDevice("added").orElseThrow().revision();
        long initial = port.findDevice("initial").orElseThrow().revision();
        port.transaction(writer -> { writer.deleteDevice("added", added); writer.deleteDevice("initial", initial); return null; });
        assertTrue(port.restore().isEmpty());
    }

    @Test
    void auditPaginationDoesNotTruncateValidLaterDeviceChanges() {
        migrate();
        var port = port();
        port.transaction(writer -> {
            for (int index = 0; index < 270; index++) writer.saveGatewaySetting("setting-" + index, "{}", 0);
            writer.saveDevice(config("after-page"), 0);
            return null;
        });
        assertEquals(2, port.restore().size());
    }

    @Test
    void rejectsUnauditedChangesToUntouchedConfigurationAndIdentity() throws Exception {
        migrate();
        long before = store.currentRevision();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (var statement = connection.prepareStatement("UPDATE dc_device SET device_json=? WHERE device_id=?")) {
                statement.setString(1, config("initial").deviceJson().replace("original-name", "untracked-name"));
                statement.setString(2, "initial");
                statement.executeUpdate();
            }
        }
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> port().restore());
        assertNull(error.getCause());
        assertEquals(before, store.currentRevision());
    }

    @Test
    void rejectsUnauditedMissingDeviceRatherThanAcceptingLowerCount() throws Exception {
        migrate();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM dc_device WHERE device_id='initial'");
        }
        assertThrows(IllegalStateException.class, () -> port().restore());
    }

    @Test
    void malformedOrNonUtf8SourcesCannotPassWithForgedSuccessRecord() throws Exception {
        List<byte[]> variants = List.of(
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "[] []".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "[{\"password\":\"[REDACTED]\", broken".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new byte[] { (byte) 0xC3, (byte) 0x28 });
        for (int index = 0; index < variants.size(); index++) {
            Path testDirectory = Files.createDirectory(directory.resolve("malformed-" + index));
            Path testSource = testDirectory.resolve("source.json");
            byte[] bytes = variants.get(index);
            Files.write(testSource, bytes);
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            try (SqliteConfigurationStore testStore = SqliteConfigurationStore.createNew(testDirectory.resolve("test.db"), 1000)) {
                testStore.transaction(writer -> {
                    writer.recordMigration(new com.wangbin.collector.core.config.persistence.MigrationRecord(
                            LegacyJsonMigration.MIGRATION_ID, testSource.toAbsolutePath().normalize().toString(),
                            digest, 0, 0, 0, java.time.Instant.now()));
                    return null;
                });
                var port = new WangbinSqliteLocalConfigurationPersistence(testStore, testSource);
                IllegalStateException error = assertThrows(IllegalStateException.class, port::restore);
                assertNull(error.getCause());
                assertFalse(error.toString().contains("password"));
                assertEquals(1, testStore.currentRevision());
            }
        }
    }

    @Test
    void rejectsMarkerCountDriftEvenThoughSourceHashStillMatches() throws Exception {
        migrate();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE dc_json_migration SET point_count=999");
        }
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> port().restore());
        assertTrue(error.getMessage().contains("数量"));
        assertNull(error.getCause());
    }

    @Test
    void rejectsIllegalAuditedTransitionInsteadOfTreatingItAsPermissionToSkipComparison() throws Exception {
        migrate();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO dc_config_revision(entity_type,entity_id,operation,created_at) "
                    + "VALUES('DEVICE','initial','CREATE','2026-01-01T00:00:00Z')");
        }
        assertThrows(IllegalStateException.class, () -> port().restore());
    }

    @Test
    void transactionDelegatesCasAndNeverOwnsOrClosesSharedStore() {
        migrate();
        var port = port();
        StoredDeviceConfiguration created = port.transaction(writer -> writer.saveDevice(config("port-created"), 0));
        assertEquals(created, port.findDevice("port-created").orElseThrow());
        assertThrows(com.wangbin.collector.core.config.persistence.ConfigurationConflictException.class,
                () -> port.transaction(writer -> writer.saveDevice(config("port-created"), 0)));
        assertEquals(2, store.listDevices().size());
    }

    private void migrate() { new LegacyJsonMigration(store, codec).migrate(source); }
    private WangbinSqliteLocalConfigurationPersistence port() { return new WangbinSqliteLocalConfigurationPersistence(store, source); }
    private DeviceConfiguration config(String id) { return codec.decodeLocalDevice(document(id)); }
    private String document(String id) {
        return "{\"device\":{\"deviceId\":\"" + id + "\",\"deviceName\":\"original-name\",\"configSource\":\"local\",\"temporaryConfig\":true},"
                + "\"connection\":{\"deviceId\":\"" + id + "\",\"charset\":\"UTF-8\"},"
                + "\"points\":[{\"deviceId\":\"" + id + "\",\"pointId\":\"point-" + id + "\",\"status\":0,\"additionalConfig\":{\"historyEnabled\":false}}]}";
    }
}

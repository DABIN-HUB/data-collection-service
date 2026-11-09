package com.wangbin.collector.config.sqlite.database;

import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 私有 JDBC、版本和文件安全的真实集成验证。 */
class SqliteDatabaseTest {
    @TempDir Path temp;
    private Path path() { return temp.resolve("private-data/collector.db"); }
    @Test void migrationScriptChecksumIgnoresOnlyCrossPlatformLineEndings() throws Exception {
        assertEquals("first\nsecond\nthird\n", SqliteDatabase.canonicalScript("first\r\nsecond\rthird\n"));
        assertNotEquals(SqliteDatabase.canonicalScript("SELECT 1;\r\n"), SqliteDatabase.canonicalScript("SELECT 2;\n"));
        var database = SqliteDatabase.createNew(path(), 100);
        try (var stream = SqliteDatabase.class.getResourceAsStream("/db/migration/V001__wangbin_configuration.sql");
             var connection = database.openConnection(); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT checksum FROM dc_schema_version")) {
            assertNotNull(stream);
            String script = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
            String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertTrue(rows.next()); assertEquals(expected, rows.getString(1));
        }
    }
    private long pragma(Connection connection, String name) throws Exception {
        try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery("PRAGMA " + name)) { assertTrue(rows.next()); return rows.getLong(1); }
    }
    @Test void concurrentCreateNeverOverwritesWinner() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> attempt = () -> {
                try { SqliteDatabase.createNew(path(), 500); return true; }
                catch (IllegalStateException exception) { return false; }
            };
            var first = executor.submit(attempt); var second = executor.submit(attempt);
            assertNotEquals(first.get(5, java.util.concurrent.TimeUnit.SECONDS), second.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNotNull(SqliteDatabase.openExisting(path(), 100));
        } finally { executor.shutdownNow(); }
    }
    @Test void accessDeniedFileIsRejectedBeforeAclCanBeRewritten() throws Exception {
        SqliteDatabase.createNew(path(), 100);
        var posix = Files.getFileAttributeView(path(), PosixFileAttributeView.class);
        if (posix != null) {
            var original = posix.readAttributes().permissions(); posix.setPermissions(Set.of());
            try { assertThrows(IllegalStateException.class, () -> SqliteDatabase.openExisting(path(), 100)); assertEquals(Set.of(), posix.readAttributes().permissions()); }
            finally { posix.setPermissions(original); }
        } else {
            var acl = Files.getFileAttributeView(path(), AclFileAttributeView.class); var original = acl.getAcl();
            var deny = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(acl.getOwner())
                    .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA).build();
            var restricted = new ArrayList<AclEntry>(); restricted.add(deny); restricted.addAll(original); acl.setAcl(restricted);
            try { assertThrows(IllegalStateException.class, () -> SqliteDatabase.openExisting(path(), 100)); assertEquals(restricted, acl.getAcl()); }
            finally { acl.setAcl(original); }
        }
    }
    @Test void refusesDatabaseWhenParentIsAnOrdinaryFile() throws Exception {
        Path file = temp.resolve("plain-file"); Files.writeString(file, "placeholder");
        assertThrows(IllegalStateException.class, () -> SqliteDatabase.createNew(file.resolve("db.sqlite"), 100));
        assertEquals("placeholder", Files.readString(file));
    }
    @Test void everyRealConnectionUsesRequiredPragmasAndWal() throws Exception {
        var database = SqliteDatabase.createNew(path(), 137);
        try (var first = database.openConnection(); var second = database.openConnection()) {
            assertNotSame(first, second);
            for (var connection : List.of(first, second)) {
                assertEquals(1, pragma(connection, "foreign_keys")); assertEquals(2, pragma(connection, "synchronous")); assertEquals(137, pragma(connection, "busy_timeout"));
                try (var sql = connection.createStatement(); var rows = sql.executeQuery("PRAGMA journal_mode")) { assertTrue(rows.next()); assertEquals("wal", rows.getString(1)); }
            }
        }
    }
    @Test void schemaEnforcesForeignKeysUniquePointAndConnectionAndStatus() throws Exception {
        var database = SqliteDatabase.createNew(path(), 100);
        try (var connection = database.openConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO dc_config_revision VALUES(1,'DEVICE','d','CREATE','2026-10-22T00:00:00Z')");
            sql.executeUpdate("INSERT INTO dc_device VALUES('d','{\"deviceId\":\"d\",\"configSource\":\"local\",\"temporaryConfig\":true}',1)");
            sql.executeUpdate("INSERT INTO dc_device VALUES('d2','{\"deviceId\":\"d2\",\"configSource\":\"local\",\"temporaryConfig\":true}',1)");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO dc_device_connection VALUES('absent','{\"deviceId\":\"absent\"}')"));
            sql.executeUpdate("INSERT INTO dc_device_connection VALUES('d','{\"deviceId\":\"d\"}')");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO dc_device_connection VALUES('d','{\"deviceId\":\"d\"}')"));
            sql.executeUpdate("INSERT INTO dc_device_point VALUES('p','d',0,1,'same','{\"deviceId\":\"d\",\"pointId\":\"p\",\"status\":1}')");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO dc_device_point VALUES('p','d2',0,1,'same','{\"deviceId\":\"d2\",\"pointId\":\"p\",\"status\":1}')"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO dc_device_point VALUES('other','d',1,0,'same','{\"deviceId\":\"d\",\"pointId\":\"other\",\"status\":1}')"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO dc_device_point VALUES('','d',1,1,'same','{\"deviceId\":\"d\",\"pointId\":\"\",\"status\":1}')"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO dc_protocol_profile VALUES('p','TCP','[]',1)"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO dc_device VALUES('bad','{\"deviceId\":\"bad\"}',1)"));
            sql.executeUpdate("INSERT INTO dc_device_point VALUES('other','d2',0,1,'same','{\"deviceId\":\"d2\",\"pointId\":\"other\",\"status\":1}')");
            sql.executeUpdate("DELETE FROM dc_device WHERE device_id='d'");
            try (var rows = sql.executeQuery("SELECT count(*) FROM dc_device_connection")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
            try (var rows = sql.executeQuery("SELECT count(*) FROM dc_device_point")) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
        }
    }
    @Test void refusesExistingEmptyCorruptAndMissingFilesWithoutReplacingThem() throws Exception {
        Files.createDirectories(path().getParent());
        assertThrows(IllegalStateException.class, () -> SqliteDatabase.openExisting(path(), 100)); assertFalse(Files.exists(path()));
        Files.createFile(path());
        assertThrows(IllegalStateException.class, () -> SqliteDatabase.openExisting(path(), 100));
        assertThrows(IllegalStateException.class, () -> SqliteDatabase.createNew(path(), 100)); assertEquals(0, Files.size(path()));
        byte[] bad = "not a database PLACEHOLDER_SECRET".getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(path(), bad);
        var failure = assertThrows(IllegalStateException.class, () -> SqliteDatabase.openExisting(path(), 100));
        assertNull(failure.getCause()); assertFalse(failure.toString().contains("PLACEHOLDER_SECRET")); assertArrayEquals(bad, Files.readAllBytes(path()));
    }
    @Test void zeroTimeoutIsRejectedBeforeAnyDatabaseFileMutation() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SqliteDatabase.createNew(path(), 0));
        assertThrows(IllegalArgumentException.class, () -> SqliteDatabase.openExisting(path(), 0));
        assertFalse(Files.exists(path().getParent()));
        SqliteDatabase.createNew(path(), 100);
        byte[] original = Files.readAllBytes(path());
        assertThrows(IllegalArgumentException.class, () -> SqliteDatabase.openExisting(path(), 0));
        assertArrayEquals(original, Files.readAllBytes(path()));
    }
    @Test void rejectsUnknownFutureVersionApplicationIdentityAndChecksum() throws Exception {
        for (String change : List.of("PRAGMA user_version=999", "PRAGMA application_id=0", "UPDATE dc_schema_version SET checksum='wrong'", "UPDATE dc_schema_version SET script_name='wrong'", "CREATE TABLE unexpected(x TEXT)", "DROP INDEX idx_dc_point_code")) {
            Path file = temp.resolve("case-" + UUID.randomUUID() + "/db.sqlite"); var database = SqliteDatabase.createNew(file, 100);
            try (var connection = database.openConnection(); var sql = connection.createStatement()) { sql.execute(change); }
            assertThrows(IllegalStateException.class, () -> SqliteDatabase.openExisting(file, 100)); assertTrue(Files.size(file) > 0);
        }
        Path unknown = temp.resolve("unknown/db.sqlite"); Files.createDirectories(unknown.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + unknown.toUri().toASCIIString()); var sql = connection.createStatement()) { sql.execute("CREATE TABLE other(x TEXT)"); }
        assertThrows(IllegalStateException.class, () -> SqliteDatabase.openExisting(unknown, 100));
    }
    @Test void missingFileAfterOpenNeverGetsSilentlyRecreated() throws Exception {
        var database = SqliteDatabase.createNew(path(), 100); Files.delete(path());
        assertThrows(IllegalStateException.class, database::openConnection); assertFalse(Files.exists(path()));
    }
    @Test void handlesUnicodeSpaceQuoteAndUriReservedCharacters() {
        Path file = temp.resolve("私有 数据 ' # % ? 目录".replace('?', '_')).resolve("配置 ' # % 数据.db");
        try (var store = SqliteConfigurationStore.createNew(file, 100)) { store.transaction(w -> w.saveGatewaySetting("k", "null", 0)); }
        try (var store = SqliteConfigurationStore.openExisting(file, 100)) { assertTrue(store.findGatewaySetting("k").isPresent()); }
        String uri = file.toUri().toASCIIString(); assertTrue(uri.contains("%23")); assertTrue(uri.contains("%25")); assertFalse(uri.contains(" "));
    }
    @Test void readOnlyFileIsRejectedWithoutChangingItsReadOnlyAttribute() throws Exception {
        SqliteDatabase.createNew(path(), 100);
        var dos = Files.getFileAttributeView(path(), DosFileAttributeView.class);
        var posix = Files.getFileAttributeView(path(), PosixFileAttributeView.class);
        if (posix != null) {
            var original = posix.readAttributes().permissions(); posix.setPermissions(PosixFilePermissions.fromString("r--------"));
            try { assertThrows(IllegalStateException.class, () -> SqliteDatabase.openExisting(path(), 100)); assertEquals(PosixFilePermissions.fromString("r--------"), posix.readAttributes().permissions()); }
            finally { posix.setPermissions(original); }
        } else {
            assertNotNull(dos); dos.setReadOnly(true);
            try { assertThrows(IllegalStateException.class, () -> SqliteDatabase.openExisting(path(), 100)); assertTrue(dos.readAttributes().isReadOnly()); }
            finally { dos.setReadOnly(false); }
        }
    }
    @Test void privateDirectoryDatabaseAndLiveSidecarsDoNotChangeSharedAncestor() throws Exception {
        var acl = Files.getFileAttributeView(temp, AclFileAttributeView.class);
        var posix = Files.getFileAttributeView(temp, PosixFileAttributeView.class);
        Object before = posix != null ? posix.readAttributes().permissions() : acl.getAcl();
        var database = SqliteDatabase.createNew(path(), 100);
        try (var connection = database.openConnection(); var sql = connection.createStatement()) {
            sql.execute("BEGIN IMMEDIATE"); sql.executeUpdate("INSERT INTO dc_config_revision(entity_type,entity_id,operation,created_at) VALUES('DEVICE','d','CREATE','2026-10-22T00:00:00Z')");
            database.secureFiles();
            assertTrue(Files.exists(Path.of(path() + "-wal"))); assertTrue(Files.exists(Path.of(path() + "-shm")));
            assertPrivate(path().getParent(), true); assertPrivate(path(), false); assertPrivate(Path.of(path() + "-wal"), false); assertPrivate(Path.of(path() + "-shm"), false);
            sql.execute("ROLLBACK");
        }
        Object after = posix != null ? posix.readAttributes().permissions() : acl.getAcl(); assertEquals(before, after);
    }
    private void assertPrivate(Path file, boolean directory) throws Exception {
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) assertEquals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"), posix.readAttributes().permissions());
        else {
            var acl = Files.getFileAttributeView(file, AclFileAttributeView.class); assertNotNull(acl); assertFalse(acl.getAcl().isEmpty());
            var owner = acl.getOwner();
            assertTrue(acl.getAcl().stream().allMatch(entry -> entry.type() != AclEntryType.ALLOW || entry.principal().equals(owner)));
        }
    }
    @Test void rejectsSharedParentAndDirectoryAsDatabaseAndInvalidTimeout() {
        assertThrows(IllegalStateException.class, () -> SqliteDatabase.createNew(Path.of(System.getProperty("user.home"), "never-create.db"), 100));
        assertThrows(IllegalStateException.class, () -> SqliteDatabase.createNew(Path.of(System.getProperty("user.dir"), "never-create.db"), 100));
        assertThrows(IllegalStateException.class, () -> SqliteDatabase.createNew(path().toAbsolutePath().getRoot().resolve("never-create.db"), 100));
        assertThrows(IllegalStateException.class, () -> SqliteDatabase.openExisting(temp, 100));
        assertThrows(IllegalArgumentException.class, () -> SqliteDatabase.createNew(path(), -1));
    }
}

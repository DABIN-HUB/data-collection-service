package com.wangbin.collector.config.sqlite.diagnostic;

import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 使用独立临时库核对诊断快照、故障清理及缺失库拒绝，不接触现役数据库。 */
class SqliteRevisionSnapshotTest {
    @TempDir
    Path directory;

    @Test
    void readsOnlyTargetRevisionsAndReleasesConnectionAfterClose() {
        Path database = directory.resolve("private/collector.db");
        var codec = new ConfigurationJsonCodec();
        var device = codec.decodeLocalDevice("""
                {"device":{"deviceId":"isolated-d","configSource":"local","temporaryConfig":true},
                 "points":[{"deviceId":"isolated-d","pointId":"isolated-a","status":1},
                           {"deviceId":"isolated-d","pointId":"isolated-b","status":1}]}
                """);
        try (var store = SqliteConfigurationStore.createNew(database, 250)) {
            store.transaction(writer -> writer.saveDevice(device, 0));
            AtomicReference<Connection> active = new AtomicReference<>();
            var snapshot = SqliteRevisionSnapshot.capture(database, "isolated-d", active, () -> {
                assertNotNull(active.get());
                assertTrue(Files.isRegularFile(database));
            });
            assertEquals(1, snapshot.currentRevision());
            assertEquals(1, snapshot.deviceRevision());
            assertEquals(List.of("isolated-a", "isolated-b"), snapshot.pointIds());
            assertEquals("CREATE", snapshot.recentDeviceRevisions().get(0).operation());
            assertNull(active.get());
            assertEquals(1, store.currentRevision());
            store.transaction(writer -> writer.saveDevice(device, 1));
            assertEquals(2, store.currentRevision());
        }
    }

    @Test
    void failedSidecarInspectionReleasesConnection() {
        Path database = directory.resolve("private/collector.db");
        try (var store = SqliteConfigurationStore.createNew(database, 250)) {
            var codec = new ConfigurationJsonCodec();
            var device = codec.decodeLocalDevice("""
                    {"device":{"deviceId":"isolated-d","configSource":"local","temporaryConfig":true},
                     "points":[{"deviceId":"isolated-d","pointId":"isolated-a","status":1},
                               {"deviceId":"isolated-d","pointId":"isolated-b","status":1}]}
                    """);
            store.transaction(writer -> writer.saveDevice(device, 0));
            AtomicReference<Connection> active = new AtomicReference<>();
            assertThrows(IllegalStateException.class, () -> SqliteRevisionSnapshot.capture(
                    database, "isolated-d", active, () -> { throw new IllegalStateException("合成权限异常"); }));
            assertNull(active.get());
            assertEquals(1, store.currentRevision());
        }
    }

    @Test
    void missingDatabaseIsNeverCreated() {
        Path missing = directory.resolve("absent/collector.db");
        assertThrows(IllegalStateException.class, () -> SqliteRevisionSnapshot.capture(
                missing, "isolated-d", new AtomicReference<>(), () -> { }));
        assertFalse(Files.exists(missing.getParent()));
    }
}

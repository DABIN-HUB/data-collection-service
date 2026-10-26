package com.wangbin.collector.config.sqlite.database;

import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipal;

import static org.junit.jupiter.api.Assertions.*;

/** wangbin 权限边界必须信任服务身份，而不是文件当前 owner 或可伪造的 user.name。 */
class SqliteOwnershipTest {
    @TempDir
    Path directory;

    @Test
    void unexpectedOwnerIsRejectedWithoutChangingPermissionsOrContent() throws Exception {
        Path file = directory.resolve("data.txt");
        Files.writeString(file, "wangbin-owner-boundary");
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        Object permissions = posix != null ? posix.readAttributes().permissions() : acl.getAcl();
        UserPrincipal anotherService = () -> "wangbin-another-service";
        assertThrows(IllegalStateException.class, () -> SqliteFileSecurity.requireOwner(file, anotherService));
        assertEquals(permissions, posix != null ? posix.readAttributes().permissions() : acl.getAcl());
        assertEquals("wangbin-owner-boundary", Files.readString(file));
    }

    @Test
    void operatingSystemServiceIdentityIsNotOverriddenByUserNameProperty() throws Exception {
        Path database = directory.resolve("private/collector.db");
        String original = System.getProperty("user.name");
        try {
            System.setProperty("user.name", "wangbin-not-the-service-identity");
            try (var store = SqliteConfigurationStore.createNew(database, 100)) {
                store.transaction(writer -> writer.saveGatewaySetting("owner-check", "true", 0));
            }
            try (var store = SqliteConfigurationStore.openExisting(database, 100)) {
                assertTrue(store.findGatewaySetting("owner-check").isPresent());
            }
        } finally {
            if (original == null) System.clearProperty("user.name");
            else System.setProperty("user.name", original);
        }
    }

    @Test
    void leftoverWalCannotBeMistakenForFreshInstallation() throws Exception {
        Path database = directory.resolve("private/collector.db");
        Files.createDirectories(database.getParent());
        Path wal = Path.of(database + "-wal");
        Files.writeString(wal, "wangbin-existing-wal-must-remain");
        assertThrows(IllegalStateException.class, () -> SqliteConfigurationStore.createNew(database, 100));
        assertFalse(Files.exists(database));
        assertEquals("wangbin-existing-wal-must-remain", Files.readString(wal));
    }
}

package com.wangbin.collector.config.sqlite.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 全部文件仅位于临时目录；已安装或不确定状态不得被自动空库掩盖。 */
class WangbinInstallationBootstrapTest {
    @TempDir
    Path directory;

    @Test
    void freshDirectoryInitializesOnceAndReopensWithoutMigration() throws Exception {
        Path database = directory.resolve("private/collector.db");
        try (var store = WangbinInstallationBootstrap.openOrInitializeInstallation(database, 500)) {
            assertTrue(store.listDevices().isEmpty());
            assertTrue(store.listMigrations().isEmpty());
        }
        assertTrue(Files.isDirectory(directory.resolve("private.wangbin-installation")));
        byte[] original = Files.readAllBytes(database);
        try (var store = WangbinInstallationBootstrap.openOrInitializeInstallation(database, 500)) {
            assertEquals(0, store.currentRevision());
        }
        assertArrayEquals(original, Files.readAllBytes(database));
    }

    @Test
    void existingEmptyDirectoryIsNotProofOfFreshInstallation() throws Exception {
        Path database = directory.resolve("private/collector.db");
        Files.createDirectory(database.getParent());
        assertThrows(IllegalStateException.class,
                () -> WangbinInstallationBootstrap.openOrInitializeInstallation(database, 500));
        assertFalse(Files.exists(database));
        assertFalse(Files.exists(directory.resolve("private.wangbin-installation")));
    }

    @Test
    void installedDatabaseLossDoesNotCreateEmptyReplacement() throws Exception {
        Path database = directory.resolve("private/collector.db");
        try (var ignored = WangbinInstallationBootstrap.openOrInitializeInstallation(database, 500)) { }
        Files.delete(database);
        assertThrows(IllegalStateException.class,
                () -> WangbinInstallationBootstrap.openOrInitializeInstallation(database, 500));
        assertFalse(Files.exists(database));
        Files.delete(database.getParent());
        assertThrows(IllegalStateException.class,
                () -> WangbinInstallationBootstrap.openOrInitializeInstallation(database, 500));
        assertFalse(Files.exists(database.getParent()));
    }

    @Test
    void interruptedInstallationGuardPreventsRetryAsFresh() throws Exception {
        Path database = directory.resolve("private/collector.db");
        Files.createDirectory(directory.resolve("private.wangbin-installation"));
        assertThrows(IllegalStateException.class,
                () -> WangbinInstallationBootstrap.openOrInitializeInstallation(database, 500));
        assertFalse(Files.exists(database.getParent()));
    }

    @Test
    void corruptExistingDatabaseIsPreserved() throws Exception {
        Path database = directory.resolve("private/collector.db");
        Files.createDirectory(database.getParent());
        byte[] original = "wangbin-invalid-sqlite".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(database, original);
        assertThrows(IllegalStateException.class,
                () -> WangbinInstallationBootstrap.openOrInitializeInstallation(database, 500));
        assertArrayEquals(original, Files.readAllBytes(database));
    }

    @Test
    void invalidTimeoutDoesNotLeaveInstallationArtifacts() {
        Path database = directory.resolve("private/collector.db");
        assertThrows(IllegalArgumentException.class,
                () -> WangbinInstallationBootstrap.openOrInitializeInstallation(database, 0));
        assertFalse(Files.exists(database.getParent()));
        assertFalse(Files.exists(directory.resolve("private.wangbin-installation")));
    }

    @Test
    void competingInitializersCannotOverwriteOrPartiallyInitialize() throws Exception {
        Path database = directory.resolve("private/collector.db");
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try {
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                try (var store = WangbinInstallationBootstrap.openOrInitializeInstallation(database, 500)) {
                    assertTrue(store.listDevices().isEmpty());
                    return true;
                } catch (IllegalStateException exception) {
                    return false;
                }
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            boolean a = first.get(15, TimeUnit.SECONDS);
            boolean b = second.get(15, TimeUnit.SECONDS);
            assertTrue(a || b);
            try (var store = WangbinInstallationBootstrap.openOrInitializeInstallation(database, 500)) {
                assertEquals(0, store.currentRevision());
                assertTrue(store.listDevices().isEmpty());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void explicitInstallationAlsoLeavesIndependentGuard() throws Exception {
        Path database = directory.resolve("explicit/collector.db");
        try (var ignored = WangbinInstallationBootstrap.initializeNewInstallation(database, 500, true)) { }
        assertTrue(Files.isDirectory(directory.resolve("explicit.wangbin-installation")));
        Files.delete(database);
        Files.delete(database.getParent());
        assertThrows(IllegalStateException.class,
                () -> WangbinInstallationBootstrap.openOrInitializeInstallation(database, 500));
        assertFalse(Files.exists(database));
    }

    @Test
    void validatedOlderInstallationGetsGuardBeforeItCanBeLost() throws Exception {
        Path database = directory.resolve("older/collector.db");
        try (var ignored = com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore.createNew(database, 500)) { }
        assertFalse(Files.exists(directory.resolve("older.wangbin-installation")));
        try (var ignored = WangbinInstallationBootstrap.openOrInitializeInstallation(database, 500)) { }
        assertTrue(Files.isDirectory(directory.resolve("older.wangbin-installation")));
        Files.delete(database);
        Files.delete(database.getParent());
        assertThrows(IllegalStateException.class,
                () -> WangbinInstallationBootstrap.openOrInitializeInstallation(database, 500));
        assertFalse(Files.exists(database));
    }
}

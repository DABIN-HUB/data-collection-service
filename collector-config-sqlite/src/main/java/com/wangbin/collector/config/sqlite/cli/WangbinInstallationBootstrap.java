package com.wangbin.collector.config.sqlite.cli;

import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import com.wangbin.collector.config.sqlite.database.SqliteDatabase;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * wangbin 安装入口：已有库只打开，自动首次安装需要原子取得全新专用目录。
 * 保护目录独立于数据目录保留；安装失败不自动清理证据或重建。
 */
public final class WangbinInstallationBootstrap {
    private WangbinInstallationBootstrap() {
    }

    public static SqliteConfigurationStore openOrInitializeInstallation(Path path, int busyTimeoutMs) {
        if (path == null) throw new IllegalArgumentException("数据库路径不能为空");
        Path target = path.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return protectInstallation(SqliteConfigurationStore.openExisting(target, busyTimeoutMs), target);
        }
        if (!Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("无法确认数据库状态，禁止自动初始化");
        }
        SqliteDatabase.claimNewInstallationDirectory(target, busyTimeoutMs);
        return initializeNewInstallation(target, busyTimeoutMs, true);
    }

    public static SqliteConfigurationStore initializeNewInstallation(Path path, int busyTimeoutMs,
                                                                     boolean freshInstallConfirmed) {
        if (!freshInstallConfirmed) throw new IllegalArgumentException("未明确声明 wangbin 全新安装，禁止初始化");
        return protectInstallation(SqliteConfigurationStore.createNew(path, busyTimeoutMs), path);
    }

    private static SqliteConfigurationStore protectInstallation(SqliteConfigurationStore store, Path path) {
        try {
            SqliteDatabase.ensureInstallationGuard(path);
            return store;
        } catch (RuntimeException exception) {
            store.close();
            throw exception;
        }
    }
}

package com.wangbin.collector.core.config.persistence;

import java.time.Instant;
import java.util.Objects;

/** 迁移成功记录，与数据同事务写入；不保存原始文件或配置负载。 */
public record MigrationRecord(String migrationId, String sourcePath, String sourceSha256,
        int deviceCount, int connectionCount, int pointCount, Instant completedAt) {
    public MigrationRecord {
        ConfigurationModelChecks.identity(migrationId);
        ConfigurationModelChecks.identity(sourcePath);
        if (sourceSha256 == null || !sourceSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("迁移文件 SHA-256 格式不合法");
        }
        if (deviceCount < 0 || connectionCount < 0 || pointCount < 0 || connectionCount > deviceCount) {
            throw new IllegalArgumentException("迁移统计计数不合法");
        }
        Objects.requireNonNull(completedAt, "迁移完成时间不能为空");
    }
    @Override public String toString() { return "MigrationRecord[已隐藏来源路径]"; }
}

package com.wangbin.collector.config.sqlite.migration;

import com.wangbin.collector.core.config.persistence.MigrationRecord;

/** wangbin 迁移结果；成功与已迁移分开表达，不携带设备配置。 */
public record MigrationResult(Status status, MigrationRecord record, boolean identityAndConfigurationVerified) {
    public enum Status {
        IMPORTED,
        ALREADY_MIGRATED
    }
}

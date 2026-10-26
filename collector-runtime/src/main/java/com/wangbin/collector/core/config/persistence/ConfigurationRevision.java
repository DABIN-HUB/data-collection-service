package com.wangbin.collector.core.config.persistence;

import java.time.Instant;
import java.util.Objects;

/** 不含配置负载的持久化审计，仅允许身份、操作与提交时间。 */
public record ConfigurationRevision(long revision, String entityType, String entityId, String operation, Instant createdAt) {
    public ConfigurationRevision {
        ConfigurationModelChecks.revision(revision);
        ConfigurationModelChecks.identity(entityType);
        ConfigurationModelChecks.identity(entityId);
        ConfigurationModelChecks.identity(operation);
        Objects.requireNonNull(createdAt, "审计时间不能为空");
    }
    @Override public String toString() { return "ConfigurationRevision[已隐藏审计标识]"; }
}

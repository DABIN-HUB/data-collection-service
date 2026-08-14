package com.wangbin.collector.monitor.metrics;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 历史存储组件的实时健康快照。
 */
@Data
@Builder
public class StorageMetricsSnapshot {

    private final boolean enabled;
    private final Status status;
    private final String message;
    private final long responseTimeMs;

    @Builder.Default
    private final long generatedAt = Instant.now().toEpochMilli();

    /**
     * 定义当前模块的枚举值。
     */
    public enum Status {
        OK,
        ERROR,
        DISABLED,
        UNKNOWN
    }
}

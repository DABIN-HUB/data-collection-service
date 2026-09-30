package com.wangbin.collector.monitor.metrics;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 定义当前模块的业务组件。
 */
@Data
@Builder
public class ExceptionStatsSnapshot {

    private final long totalExceptions;

    @Builder.Default
    private final Map<String, Long> byCategory = Collections.emptyMap();

    @Builder.Default
    private final Map<String, Long> byDevice = Collections.emptyMap();

    @Builder.Default
    private final List<ExceptionSummary> recent = Collections.emptyList();

    @Builder.Default
    private final int trackedCategoryCount = 0;

    @Builder.Default
    private final int categoryCapacity = 0;

    @Builder.Default
    private final long otherCategoryExceptions = 0L;

    @Builder.Default
    private final int trackedDeviceCount = 0;

    @Builder.Default
    private final int deviceCapacity = 0;

    @Builder.Default
    private final long otherDeviceExceptions = 0L;

    @Builder.Default
    private final long generatedAt = Instant.now().toEpochMilli();
}

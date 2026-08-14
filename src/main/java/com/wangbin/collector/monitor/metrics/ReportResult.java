package com.wangbin.collector.monitor.metrics;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 承载当前模块的数据传输内容。
 */
@Data
@Builder
public class ReportResult {
    private final String taskId;
    private final String taskName;

    @Builder.Default
    private final Map<String, Object> data = Collections.emptyMap();

    @Builder.Default
    private final long generatedAt = Instant.now().toEpochMilli();
}

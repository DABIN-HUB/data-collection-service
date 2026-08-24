package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.core.collector.scheduler.PerformanceStatsSnapshot;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 控制台运行状态统一快照。
 *
 * <p>该模型作为控制台概览、系统诊断和上报链路的统一事实源，避免前端重复推断状态。</p>
 */
@Data
@Builder
public class ConsoleRuntimeStatusSnapshot {

    /**
     * 整体健康级别。
     */
    private final RuntimeHealthLevel level;

    /**
     * 整体状态说明。
     */
    private final String message;

    /**
     * 组件状态列表。
     */
    @Builder.Default
    private final List<RuntimeComponentStatus> components = Collections.emptyList();

    /**
     * 聚合风险说明。
     */
    @Builder.Default
    private final List<String> risks = Collections.emptyList();

    /**
     * 缓存指标原始快照。
     */
    private final CacheMetricsSnapshot cache;

    /**
     * 设备连接指标原始快照。
     */
    private final DeviceStatusSnapshot devices;

    /**
     * 系统资源指标原始快照。
     */
    private final SystemResourceSnapshot system;

    /**
     * 异常指标原始快照。
     */
    private final ExceptionStatsSnapshot exceptions;

    /**
     * 调度性能原始快照。
     */
    private final PerformanceStatsSnapshot performance;

    /**
     * 云端上报原始指标。
     */
    @Builder.Default
    private final Map<String, Object> report = Collections.emptyMap();

    /**
     * 历史存储原始快照。
     */
    private final StorageMetricsSnapshot storage;

    /**
     * 快照生成时间。
     */
    @Builder.Default
    private final long generatedAt = Instant.now().toEpochMilli();
}

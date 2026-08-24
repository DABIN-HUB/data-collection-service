package com.wangbin.collector.monitor.metrics;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * JVM 与系统资源快照。
 */
@Data
@Builder
public class SystemResourceSnapshot {

    private final long heapUsed;
    private final long heapCommitted;
    private final long heapMax;
    private final long nonHeapUsed;
    private final long nonHeapCommitted;
    private final long totalPhysicalMemorySize;
    private final long freePhysicalMemorySize;

    private final double processCpuLoad;
    private final double systemCpuLoad;
    private final int threadCount;
    private final int daemonThreadCount;
    private final long outboxPendingCount;
    private final long outboxIsolatedCount;
    private final long outboxOldestMessageAgeMillis;

    @Builder.Default
    private final Map<String, ThreadPoolSnapshot> threadPools = Collections.emptyMap();

    @Builder.Default
    private final long generatedAt = Instant.now().toEpochMilli();

    /**
     * 定义当前模块的业务组件。
     */
    @Data
    @Builder
    public static class ThreadPoolSnapshot {
        private final int corePoolSize;
        private final int maxPoolSize;
        private final int activeCount;
        private final int queueSize;
        private final long completedTaskCount;
        private final long rejectedCount;
    }
}

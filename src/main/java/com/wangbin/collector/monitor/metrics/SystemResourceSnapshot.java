package com.wangbin.collector.monitor.metrics;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * JVM/system resource snapshot.
 */
@Data
@Builder
public class SystemResourceSnapshot {

    private final long heapUsed;
    private final long heapCommitted;
    private final long heapMax;
    private final long nonHeapUsed;
    private final long nonHeapCommitted;

    private final double processCpuLoad;
    private final double systemCpuLoad;
    private final int threadCount;
    private final int daemonThreadCount;

    @Builder.Default
    private final Map<String, ThreadPoolSnapshot> threadPools = Collections.emptyMap();

    @Builder.Default
    private final long generatedAt = Instant.now().toEpochMilli();

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

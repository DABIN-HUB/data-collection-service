package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.core.cache.config.TelemetryExecutorNames;

import java.util.List;

/**
 * 系统资源监控支持的线程池名称。
 */
public final class MonitorThreadPoolNames {

    public static final List<String> ALL = List.of(
            "batchDispatcherExecutor",
            "asyncCollectorExecutor",
            "dataProcessorExecutor",
            "cacheAsyncExecutor",
            "reportExecutor",
            TelemetryExecutorNames.CACHE_STAGE,
            TelemetryExecutorNames.STREAM_STAGE,
            TelemetryExecutorNames.HISTORY_STAGE,
            TelemetryExecutorNames.REPORT_STAGE,
            "timeSliceScheduler",
            "monitorExecutor",
            "taskScheduler",
            "ioIntensiveExecutor",
            "cpuIntensiveExecutor");

    private MonitorThreadPoolNames() {
    }
}

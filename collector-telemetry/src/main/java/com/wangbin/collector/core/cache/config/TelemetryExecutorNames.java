package com.wangbin.collector.core.cache.config;

/**
 * 遥测后处理线程池名称
 */
public final class TelemetryExecutorNames {

    public static final String CACHE_STAGE = "telemetryCacheStageExecutor";
    public static final String STREAM_STAGE = "telemetryStreamStageExecutor";
    public static final String STREAM_WRITE = "telemetryStreamWriteExecutor";
    public static final String HISTORY_STAGE = "telemetryHistoryStageExecutor";
    public static final String REPORT_STAGE = "telemetryReportStageExecutor";

    /**
     * 创建当前组件实例。
     */
    private TelemetryExecutorNames() {
    }
}

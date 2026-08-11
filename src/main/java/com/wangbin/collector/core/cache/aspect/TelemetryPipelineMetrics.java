package com.wangbin.collector.core.cache.aspect;

/**
 * 遥测后处理流水线热路径内部观测快照。
 */
public record TelemetryPipelineMetrics(long processedItems,
                                       long stageSubmissions,
                                       long stageRejectedEvents,
                                       long stageRejectedCompensatedEvents,
                                       long stageRejectedUncompensatedEvents,
                                       long stageRejectedShutdownEvents,
                                       double processLatencyP50Ms,
                                       double processLatencyP95Ms,
                                       double processLatencyP99Ms,
                                       double stageSubmissionLatencyP50Ms,
                                       double stageSubmissionLatencyP95Ms,
                                       double stageSubmissionLatencyP99Ms,
                                       long logRateLimitedEvents,
                                       long logSuppressedEvents) {

    /**
     * 返回空快照。
     */
    public static TelemetryPipelineMetrics empty() {
        return new TelemetryPipelineMetrics(
                0L, 0L, 0L, 0L, 0L, 0L,
                0D, 0D, 0D, 0D, 0D, 0D,
                0L, 0L);
    }
}

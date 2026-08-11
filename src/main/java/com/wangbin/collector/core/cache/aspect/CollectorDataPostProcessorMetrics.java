package com.wangbin.collector.core.cache.aspect;

/**
 * 遥测入口执行器任务热路径内部观测快照。
 */
public record CollectorDataPostProcessorMetrics(long batchTaskCount,
                                                long batchTaskItems,
                                                int batchSizeP50,
                                                int batchSizeP95,
                                                int batchSizeMax,
                                                int batchSizeSampleCount,
                                                long batchSizeTotalRecorded,
                                                long batchSizeOverwrittenSamples,
                                                double batchTaskLatencyP50Ms,
                                                double batchTaskLatencyP95Ms,
                                                double batchTaskLatencyP99Ms,
                                                int batchTaskLatencySampleCount,
                                                long batchTaskLatencyTotalRecorded,
                                                long batchTaskLatencyOverwrittenSamples,
                                                long metricsInternalErrors,
                                                long entryLogRateLimitedEvents,
                                                long entryLogSuppressedEvents) {

    /**
     * 返回空快照。
     */
    public static CollectorDataPostProcessorMetrics empty() {
        return new CollectorDataPostProcessorMetrics(
                0L, 0L, 0, 0, 0, 0, 0L, 0L,
                0D, 0D, 0D, 0, 0L, 0L,
                0L, 0L, 0L);
    }
}

package com.wangbin.collector.core.cache.service;

/**
 * Redis Stream 写入路径的内部观测快照。
 */
public record TelemetryStreamMetrics(long appendAttempts,
                                     long skippedAppends,
                                     long serializationFailures,
                                     long xaddSuccess,
                                     long xaddFailure,
                                     double appendLatencyP50Ms,
                                     double appendLatencyP95Ms,
                                     double appendLatencyP99Ms,
                                     double xaddLatencyP50Ms,
                                     double xaddLatencyP95Ms,
                                     double xaddLatencyP99Ms) {

    /**
     * 返回空指标，供未启用或测试替身使用。
     */
    public static TelemetryStreamMetrics empty() {
        return new TelemetryStreamMetrics(0L, 0L, 0L, 0L, 0L,
                0D, 0D, 0D, 0D, 0D, 0D);
    }
}

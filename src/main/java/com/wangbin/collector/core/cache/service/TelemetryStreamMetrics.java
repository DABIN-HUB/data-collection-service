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
                                     double xaddLatencyP99Ms,
                                     long admissionAccepted,
                                     long admissionRejected,
                                     long admissionDropped,
                                     int bufferSize,
                                     int bufferPeak,
                                     int bufferCapacity,
                                     long writerBatchCount,
                                     long writerRows,
                                     int writerBatchSizeP50,
                                     int writerBatchSizeP95,
                                     int writerBatchSizeP99,
                                     long redisPipelineCalls,
                                     long redisXaddRows,
                                     long redisXaddFailures,
                                     double admissionLatencyP50Ms,
                                     double admissionLatencyP95Ms,
                                     double admissionLatencyP99Ms,
                                     double redisBatchLatencyP50Ms,
                                     double redisBatchLatencyP95Ms,
                                     double redisBatchLatencyP99Ms,
                                     long shutdownDroppedRows,
                                     long writerLoopFailures) {

    /**
     * 返回空指标，供未启用或测试替身使用。
     */
    public static TelemetryStreamMetrics empty() {
        return new TelemetryStreamMetrics(0L, 0L, 0L, 0L, 0L,
                0D, 0D, 0D, 0D, 0D, 0D,
                0L, 0L, 0L, 0, 0, 0,
                0L, 0L, 0, 0, 0,
                0L, 0L, 0L, 0D, 0D, 0D, 0D, 0D, 0D,
                0L, 0L);
    }
}

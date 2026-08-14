package com.wangbin.collector.core.cache.service;

/**
 * Redis Stream 写缓冲和 pipeline writer 的内部观测快照。
 */
public record StreamWriteBufferMetrics(long admissionAccepted,
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
                                       double redisBatchLatencyP50Ms,
                                       double redisBatchLatencyP95Ms,
                                       double redisBatchLatencyP99Ms,
                                       long shutdownDroppedRows,
                                       long writerLoopFailures) {

    /**
     * 返回空快照，用于禁用或测试替身。
     */
    public static StreamWriteBufferMetrics empty() {
        return new StreamWriteBufferMetrics(0L, 0L, 0L, 0, 0, 0,
                0L, 0L, 0, 0, 0, 0L, 0L, 0L,
                0D, 0D, 0D, 0L, 0L);
    }
}

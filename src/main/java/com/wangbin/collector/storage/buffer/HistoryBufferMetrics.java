package com.wangbin.collector.storage.buffer;

/**
 * 历史数据缓冲队列指标。
 */
public record HistoryBufferMetrics(long redisPending,
                                   long redisProcessing,
                                   long redisDeadLetter,
                                   int localPending,
                                   int localCapacity,
                                   long writeFailureRedisBuffered,
                                   long rejectedRedisBuffered,
                                   long writeFailureLocalBuffered,
                                   long rejectedLocalBuffered,
                                   long writeFailureDropped,
                                   long rejectedDropped,
                                   long writeFailureDisabled,
                                   long rejectedDisabled,
                                   long replayClaimedRows,
                                   long replaySuccessfulRows,
                                   long replayFailedRows,
                                   long replayBatchCount,
                                   double replayAverageBatchSize,
                                   int replayBatchSizeP95,
                                   int replayBatchSizeMax,
                                   double replayRowsPerSecond,
                                   double replayBatchWriteP50Ms,
                                   double replayBatchWriteP95Ms,
                                   double replayBatchWriteP99Ms,
                                   long replayPausedForLivePressureCount,
                                   int replayProcessingRows,
                                   long batchFallbackRedisRows,
                                   long batchFallbackRedisOps,
                                   long batchFallbackLocalRows,
                                   long batchFallbackDroppedRows,
                                   double batchFallbackLatencyP50Ms,
                                   double batchFallbackLatencyP95Ms,
                                   double batchFallbackLatencyP99Ms,
                                   double liveFlushQueueUtilization) {

    public HistoryBufferMetrics(long redisPending,
                                long redisProcessing,
                                long redisDeadLetter,
                                int localPending,
                                int localCapacity) {
        this(redisPending, redisProcessing, redisDeadLetter, localPending, localCapacity,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0D, 0, 0, 0D,
                0D, 0D, 0D, 0L, 0, 0L, 0L, 0L, 0L,
                0D, 0D, 0D, 0D);
    }
}

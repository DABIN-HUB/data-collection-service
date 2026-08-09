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
                                   long rejectedDisabled) {

    public HistoryBufferMetrics(long redisPending,
                                long redisProcessing,
                                long redisDeadLetter,
                                int localPending,
                                int localCapacity) {
        this(redisPending, redisProcessing, redisDeadLetter, localPending, localCapacity,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }
}

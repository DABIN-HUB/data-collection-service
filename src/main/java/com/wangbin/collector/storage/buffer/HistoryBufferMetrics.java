package com.wangbin.collector.storage.buffer;

/**
 * 历史数据缓冲队列指标。
 */
public record HistoryBufferMetrics(long redisPending,
                                   long redisProcessing,
                                   long redisDeadLetter,
                                   int localPending,
                                   int localCapacity) {
}

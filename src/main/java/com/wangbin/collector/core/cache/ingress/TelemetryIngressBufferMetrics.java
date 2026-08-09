package com.wangbin.collector.core.cache.ingress;

/**
 * 遥测入口过载缓冲指标快照。
 */
public record TelemetryIngressBufferMetrics(long redisPending,
                                            long redisProcessing,
                                            long redisDeadLetter,
                                            int localPending,
                                            int localCapacity,
                                            long rejectedTasks,
                                            long rejectedItems,
                                            long redisBufferedItems,
                                            long localBufferedItems,
                                            long droppedItems,
                                            long replayCompletedItems,
                                            long pendingRemoveFailures,
                                            long poisonDeadLetterItems,
                                            long staleSameRuntimeDroppedItems,
                                            long crossRuntimeRecoveredItems,
                                            long legacyEnvelopeRecoveredItems) {

    public static TelemetryIngressBufferMetrics empty() {
        return new TelemetryIngressBufferMetrics(
                0L, 0L, 0L, 0, 0,
                0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L,
                0L);
    }
}

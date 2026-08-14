package com.wangbin.collector.core.cloud.aggregation;

/**
 * 云端聚合策略。
 */
public record CloudAggregationPolicy(CloudFlushMode flushMode,
                                     long windowMs,
                                     CloudConflictPolicy conflictPolicy) {

    /**
     * 执行当前业务逻辑。
     */
    public static CloudAggregationPolicy defaults() {
        return new CloudAggregationPolicy(CloudFlushMode.WINDOW, 1000L, CloudConflictPolicy.LATEST_WINS);
    }
}

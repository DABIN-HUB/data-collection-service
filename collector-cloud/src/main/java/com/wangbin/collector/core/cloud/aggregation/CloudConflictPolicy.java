package com.wangbin.collector.core.cloud.aggregation;

/**
 * 云端聚合字段冲突策略。
 */
public enum CloudConflictPolicy {
    LATEST_WINS,
    SOURCE_PRIORITY,
    REJECT_CONFLICT
}

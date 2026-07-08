package com.wangbin.collector.core.cloud.aggregation;

/**
 * 云端聚合 flush 策略。
 */
public enum CloudFlushMode {
    IMMEDIATE,
    WINDOW,
    PERIODIC
}

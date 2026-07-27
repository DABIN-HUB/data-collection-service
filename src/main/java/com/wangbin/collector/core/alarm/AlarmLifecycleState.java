package com.wangbin.collector.core.alarm;

/**
 * 告警生命周期状态
 */
public enum AlarmLifecycleState {
    NORMAL,
    PENDING,
    ACTIVE,
    ACKED,
    RECOVERED
}

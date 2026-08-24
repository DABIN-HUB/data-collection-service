package com.wangbin.collector.core.collector.runtime;

/**
 * 设备运行阶段。
 */
public enum DeviceRuntimePhase {
    STOPPED,
    STARTING,
    RUNNING,
    RECONNECTING,
    ONLINE,
    DEGRADED,
    FAILED
}

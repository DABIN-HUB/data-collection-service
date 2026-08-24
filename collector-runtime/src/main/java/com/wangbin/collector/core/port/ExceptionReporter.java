package com.wangbin.collector.core.port;

/**
 * 核心链路异常上报端口，避免 core 直接依赖监控实现。
 */
public interface ExceptionReporter {

    /**
     * 记录带设备和点位上下文的异常。
     */
    void record(Throwable throwable, String deviceId, String pointId);
}

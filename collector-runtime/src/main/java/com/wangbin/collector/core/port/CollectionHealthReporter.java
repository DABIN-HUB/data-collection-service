package com.wangbin.collector.core.port;

/**
 * 采集服务健康状态上报端口。
 */
public interface CollectionHealthReporter {

    /**
     * 标记设备已进入采集运行态。
     */
    void markDeviceStarted(String deviceId);

    /**
     * 标记设备已退出采集运行态。
     */
    void markDeviceStopped(String deviceId);
}

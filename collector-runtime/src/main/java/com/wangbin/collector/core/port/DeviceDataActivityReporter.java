package com.wangbin.collector.core.port;

/** 公共采集数据活动上报端口，供推送链路更新当前设备就绪窗口。 */
public interface DeviceDataActivityReporter {
    void recordSuccessfulData(String deviceId, long collectTime);
}

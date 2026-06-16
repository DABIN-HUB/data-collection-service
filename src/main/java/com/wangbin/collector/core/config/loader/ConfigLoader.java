package com.wangbin.collector.core.config.loader;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;

import java.util.List;

public interface ConfigLoader {

    List<DeviceInfo> loadAllDevices();

    DeviceInfo loadDevice(String deviceId);

    List<DataPoint> loadDataPoints(String deviceId);

    DeviceConnection loadConnectionConfig(String deviceId);
}

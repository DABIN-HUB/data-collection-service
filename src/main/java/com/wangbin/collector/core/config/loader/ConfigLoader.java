package com.wangbin.collector.core.config.loader;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.model.ConfigSnapshot;
import com.wangbin.collector.core.config.model.ConfigLoadResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定义当前模块的业务契约。
 */
public interface ConfigLoader {

    /**
     * 查询并返回业务数据。
     */
    List<DeviceInfo> loadAllDevices();

    /**
     * 查询并返回业务数据。
     */
    DeviceInfo loadDevice(String deviceId);

    /**
     * 查询并返回业务数据。
     */
    List<DataPoint> loadDataPoints(String deviceId);

    /**
     * 查询并返回业务数据。
     */
    DeviceConnection loadConnectionConfig(String deviceId);

    /**
     * 加载完整配置快照，并明确区分成功与失败。
     *
     * @return 配置加载结果
     */
    default ConfigLoadResult loadSnapshotResult() {
        try {
            return ConfigLoadResult.success(loadSnapshot());
        } catch (RuntimeException exception) {
            return ConfigLoadResult.failed(exception.getMessage());
        }
    }

    /**
     * 查询并返回业务数据。
     */
    default ConfigSnapshot loadSnapshot() {
        List<DeviceInfo> devices = loadAllDevices();
        Map<String, DeviceInfo> deviceConfigs = new LinkedHashMap<>();
        Map<String, List<DataPoint>> pointConfigs = new LinkedHashMap<>();
        Map<String, DeviceConnection> connectionConfigs = new LinkedHashMap<>();

        if (devices != null) {
            for (DeviceInfo device : devices) {
                if (device == null || device.getDeviceId() == null || device.getDeviceId().isBlank()) {
                    continue;
                }
                String deviceId = device.getDeviceId();
                deviceConfigs.put(deviceId, device);
                List<DataPoint> points = loadDataPoints(deviceId);
                pointConfigs.put(deviceId, points != null ? points : List.of());
                DeviceConnection connection = loadConnectionConfig(deviceId);
                if (connection != null) {
                    connectionConfigs.put(deviceId, connection);
                }
            }
        }

        return new ConfigSnapshot(deviceConfigs, pointConfigs, connectionConfigs);
    }
}

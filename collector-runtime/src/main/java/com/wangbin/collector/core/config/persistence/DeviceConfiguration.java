package com.wangbin.collector.core.config.persistence;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;

/** 本地设备配置聚合，不包含运行态实体；连接可为空，点位按原始顺序保存。 */
public record DeviceConfiguration(String deviceId, String deviceJson,
        ConnectionConfiguration connection, List<PointConfiguration> points) {
    public DeviceConfiguration {
        ConfigurationModelChecks.identity(deviceId);
        ConfigurationModelChecks.json(deviceJson);
        if (connection != null && !deviceId.equals(connection.deviceId())) {
            throw new IllegalArgumentException("连接归属与设备身份不一致");
        }
        points = List.copyOf(Objects.requireNonNull(points, "点位集合不能为空"));
        var identities = new HashSet<String>();
        for (int i = 0; i < points.size(); i++) {
            PointConfiguration point = points.get(i);
            if (!deviceId.equals(point.deviceId()) || point.sortOrder() != i || !identities.add(point.pointId())) {
                throw new IllegalArgumentException("点位归属、排序或稳定身份不合法");
            }
        }
    }
    @Override public String toString() { return "DeviceConfiguration[已隐藏配置]"; }
}

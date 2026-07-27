package com.wangbin.collector.core.config.model;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.cloud.model.CloudTargetConfig;
import lombok.Getter;
import lombok.ToString;
import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 聚合设备级缓存的不可变配置快照。
 */
@Getter
@ToString
public class DeviceContext {

    private final String deviceId;
    private final DeviceInfo deviceInfo;
    private final DeviceConnection connectionConfig;
    private final List<DataPoint> dataPoints;

    private DeviceContext(String deviceId,
                          DeviceInfo deviceInfo,
                          DeviceConnection connectionConfig,
                          List<DataPoint> dataPoints) {
        this.deviceId = deviceId;
        this.deviceInfo = deviceInfo;
        this.connectionConfig = connectionConfig;
        this.dataPoints = dataPoints;
    }

    public static DeviceContext of(DeviceInfo deviceInfo,
                                   DeviceConnection connectionConfig,
                                   List<DataPoint> dataPoints) {
        String deviceId = deviceInfo != null ? deviceInfo.getDeviceId() : null;
        DeviceInfo deviceSnapshot = copyDevice(deviceInfo);
        DeviceConnection connectionSnapshot = copyConnection(connectionConfig);
        List<DataPoint> pointSnapshot = snapshotPoints(dataPoints);
        return new DeviceContext(deviceId, deviceSnapshot, connectionSnapshot, pointSnapshot);
    }

    public DeviceConnection copyConnectionConfig() {
        return copyConnection(this.connectionConfig);
    }

    public List<DataPoint> copyDataPoints() {
        return snapshotPoints(dataPoints);
    }

    private static DeviceInfo copyDevice(DeviceInfo source) {
        if (source == null) {
            return null;
        }
        DeviceInfo target = new DeviceInfo();
        BeanUtils.copyProperties(source, target);
        target.setAuthConfig(source.getAuthConfig() == null ? null : new java.util.LinkedHashMap<>(source.getAuthConfig()));
        if (source.getCloudTarget() != null) {
            CloudTargetConfig cloudTarget = new CloudTargetConfig();
            BeanUtils.copyProperties(source.getCloudTarget(), cloudTarget);
            target.setCloudTarget(cloudTarget);
        }
        return target;
    }

    private static DeviceConnection copyConnection(DeviceConnection source) {
        if (source == null) {
            return null;
        }
        DeviceConnection target = new DeviceConnection();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private static List<DataPoint> snapshotPoints(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }
        List<DataPoint> snapshot = new ArrayList<>(points.size());
        for (DataPoint point : points) {
            if (point != null) {
                DataPoint pointCopy = new DataPoint();
                BeanUtils.copyProperties(point, pointCopy);
                pointCopy.setAdditionalConfig(point.getAdditionalConfig() == null
                        ? new java.util.LinkedHashMap<>()
                        : new java.util.LinkedHashMap<>(point.getAdditionalConfig()));
                snapshot.add(pointCopy);
            }
        }
        return Collections.unmodifiableList(snapshot);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceContext that)) return false;
        return Objects.equals(deviceId, that.deviceId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(deviceId);
    }
}

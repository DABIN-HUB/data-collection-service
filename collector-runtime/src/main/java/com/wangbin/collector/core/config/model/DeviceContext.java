package com.wangbin.collector.core.config.model;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.cloud.CloudTargetConfig;
import lombok.Getter;
import lombok.ToString;
import org.springframework.beans.BeanUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
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

    /**
     * 创建当前组件实例。
     */
    private DeviceContext(String deviceId,
                          DeviceInfo deviceInfo,
                          DeviceConnection connectionConfig,
                          List<DataPoint> dataPoints) {
        this.deviceId = deviceId;
        this.deviceInfo = deviceInfo;
        this.connectionConfig = connectionConfig;
        this.dataPoints = dataPoints;
    }

    /**
     * 创建并返回业务对象。
     */
    public static DeviceContext of(DeviceInfo deviceInfo,
                                   DeviceConnection connectionConfig,
                                   List<DataPoint> dataPoints) {
        String deviceId = deviceInfo != null ? deviceInfo.getDeviceId() : null;
        DeviceInfo deviceSnapshot = copyDevice(deviceInfo);
        DeviceConnection connectionSnapshot = copyConnection(connectionConfig);
        List<DataPoint> pointSnapshot = snapshotPoints(dataPoints);
        return new DeviceContext(deviceId, deviceSnapshot, connectionSnapshot, pointSnapshot);
    }

    /**
     * 执行当前业务逻辑。
     */
    public DeviceConnection copyConnectionConfig() {
        return copyConnection(this.connectionConfig);
    }

    /**
     * 执行当前业务逻辑。
     */
    public List<DataPoint> copyDataPoints() {
        return snapshotPoints(dataPoints);
    }

    /**
     * 执行当前业务逻辑。
     */
    private static DeviceInfo copyDevice(DeviceInfo source) {
        if (source == null) {
            return null;
        }
        DeviceInfo target = new DeviceInfo();
        BeanUtils.copyProperties(source, target);
        target.setAuthConfig(WangbinConfigurationCopy.map(source.getAuthConfig()));
        copyDates(source, target, DeviceInfo.class);
        if (source.getCloudTarget() != null) {
            CloudTargetConfig cloudTarget = new CloudTargetConfig();
            BeanUtils.copyProperties(source.getCloudTarget(), cloudTarget);
            target.setCloudTarget(cloudTarget);
        }
        return target;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static DeviceConnection copyConnection(DeviceConnection source) {
        if (source == null) {
            return null;
        }
        DeviceConnection target = new DeviceConnection();
        BeanUtils.copyProperties(source, target);
        target.setExtJson(WangbinConfigurationCopy.map(source.getExtJson()));
        target.setAuthParams(WangbinConfigurationCopy.map(source.getAuthParams()));
        copyDates(source, target, DeviceConnection.class);
        return target;
    }

    /**
     * 查询并返回业务数据。
     */
    private static List<DataPoint> snapshotPoints(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }
        List<DataPoint> snapshot = new ArrayList<>(points.size());
        for (DataPoint point : points) {
            if (point != null) {
                snapshot.add(copyPointConfiguration(point));
            }
        }
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * 无损复制单点配置，供快照调用方复用；不能通过 typed 告警 getter 重建 JSON。
     * additionalConfig 同样读取原始字段，避免 getter 将 null 隐式替换为空对象。
     */
    public static DataPoint copyPointConfiguration(DataPoint source) {
        if (source == null) return null;
        DataPoint target = new DataPoint();
        BeanUtils.copyProperties(source, target, "alarmRule", "additionalConfig");
        target.setAlarmRule((String) pointField(source, "alarmRule"));
        Map<String, Object> additional = WangbinConfigurationCopy.map(pointAdditionalConfig(source));
        target.setAdditionalConfig(additional);
        if (additional == null) {
            // 公共 setter 会把 null 改为空对象；配置快照保留持久化原始空值。
            try {
                Field field = DataPoint.class.getDeclaredField("additionalConfig");
                if (!field.trySetAccessible()) throw new IllegalStateException("无法复制点位配置字段");
                field.set(target, null);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("无法复制点位配置字段");
            }
        }
        copyDates(source, target, DataPoint.class);
        return target;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pointAdditionalConfig(DataPoint point) {
        // 底层字段声明为 Map<String,Object>，此处仅恢复声明类型，不做 JSON 转换。
        return (Map<String, Object>) pointField(point, "additionalConfig");
    }

    private static Object pointField(DataPoint point, String name) {
        try {
            Field field = DataPoint.class.getDeclaredField(name);
            if (!field.trySetAccessible()) throw new IllegalStateException("无法复制点位配置字段");
            return field.get(point);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("无法复制点位配置字段");
        }
    }

    /** 时间字段可变，复制时不共享 Date 引用；不输出字段值或原始反射异常。 */
    private static void copyDates(Object source, Object target, Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != Date.class) continue;
            try {
                if (!field.trySetAccessible()) throw new IllegalStateException("无法复制配置时间字段");
                Date date = (Date) field.get(source);
                field.set(target, date == null ? null : new Date(date.getTime()));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("无法复制配置时间字段");
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceContext that)) return false;
        return Objects.equals(deviceId, that.deviceId);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(deviceId);
    }
}

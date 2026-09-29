package com.wangbin.collector.core.config.validator;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.mqtt.MqttPointOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MQTT 新配置在保存前沿用运行期 topic、payload 与发布模板校验。
 */
@Component
public class MqttProtocolPointValidator implements ProtocolPointValidator {
    @Override
    public boolean supports(DeviceInfo device) {
        if (device == null || device.getProtocolType() == null) {
            return false;
        }
        return Set.of("MQTT", "MQTT_SSL").contains(device.getProtocolType().trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public void validate(List<DataPoint> points) {
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            try {
                String mode = point.getCollectionMode();
                if (mode == null || !"SUBSCRIBE".equalsIgnoreCase(mode.trim())) {
                    throw new IllegalArgumentException("MQTT collectionMode requires SUBSCRIBE");
                }
                // 设备 ID 由配置管理器赋值；占位 ID 仅用于校验模板结构，不会保存。
                String deviceId = point.getDeviceId() != null && !point.getDeviceId().isBlank()
                        ? point.getDeviceId() : "device";
                MqttPointOptions.from(point, 1, deviceId);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("MQTT 点位配置无效, pointId=" + point.getPointId()
                        + ", address=" + point.getAddress() + ": " + exception.getMessage(), exception);
            }
        }
    }
}

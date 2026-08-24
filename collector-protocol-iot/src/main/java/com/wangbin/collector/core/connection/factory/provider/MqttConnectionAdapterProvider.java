package com.wangbin.collector.core.connection.factory.provider;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.MqttConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionAdapterProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Set;

/**
 * MQTT 连接适配器 Provider。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqttConnectionAdapterProvider implements ConnectionAdapterProvider {

    private final CollectorProperties collectorProperties;

    @Override
    public Set<String> supportedConnectionTypes() {
        return Set.of("MQTT");
    }

    @Override
    public ConnectionAdapter<?> create(String connectionType, DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        try {
            applyMqttConnectionDefaults(connectionConfig);
            return new MqttConnectionAdapter(deviceInfo, connectionConfig);
        } catch (Exception exception) {
            log.error("创建 MQTT 连接失败: 设备={}", deviceInfo.getDeviceId(), exception);
            throw new CollectorException("创建 MQTT 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private void applyMqttConnectionDefaults(DeviceConnection connectionConfig) {
        if (connectionConfig == null || collectorProperties.getMqtt() == null) {
            return;
        }
        if (connectionConfig.getExtJson() == null) {
            connectionConfig.setExtJson(new LinkedHashMap<>());
        }
        // 平台不支持并发建连时，该值应保持为 1。
        connectionConfig.getExtJson().putIfAbsent(
                "maxConcurrentConnects",
                Math.max(1, collectorProperties.getMqtt().getMaxConcurrentConnects()));
    }
}

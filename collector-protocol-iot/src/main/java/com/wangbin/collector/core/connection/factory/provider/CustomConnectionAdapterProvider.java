package com.wangbin.collector.core.connection.factory.provider;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.CustomTcpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.CustomUdpConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionAdapterProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 自定义 TCP/UDP 连接适配器 Provider。
 */
@Slf4j
@Component
public class CustomConnectionAdapterProvider implements ConnectionAdapterProvider {

    @Override
    public Set<String> supportedConnectionTypes() {
        return Set.of("CUSTOM_TCP", "CUSTOM_UDP");
    }

    @Override
    public ConnectionAdapter<?> create(String connectionType, DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        try {
            return switch (connectionType) {
                case "CUSTOM_TCP" -> new CustomTcpConnectionAdapter(deviceInfo, connectionConfig);
                case "CUSTOM_UDP" -> new CustomUdpConnectionAdapter(deviceInfo, connectionConfig);
                default -> throw new IllegalArgumentException("Custom Provider 不支持连接类型: " + connectionType);
            };
        } catch (Exception exception) {
            log.error("创建 {} 连接失败: {}", connectionType, deviceInfo.getDeviceId(), exception);
            throw switch (connectionType) {
                case "CUSTOM_TCP" -> new CollectorException("创建自定义TCP连接失败",
                        deviceInfo.getDeviceId(), null);
                case "CUSTOM_UDP" -> new CollectorException("创建自定义UDP连接失败",
                        deviceInfo.getDeviceId(), null);
                default -> new CollectorException("不支持的连接类型: " + connectionType,
                        deviceInfo.getDeviceId(), null);
            };
        }
    }
}

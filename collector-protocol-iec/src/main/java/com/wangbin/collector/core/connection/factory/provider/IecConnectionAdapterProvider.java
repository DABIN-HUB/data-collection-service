package com.wangbin.collector.core.connection.factory.provider;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Iec104ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Iec61850ConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionAdapterProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * TCP 型 IEC 协议连接适配器 Provider。
 */
@Slf4j
@Component
public class IecConnectionAdapterProvider implements ConnectionAdapterProvider {

    @Override
    public Set<String> supportedConnectionTypes() {
        return Set.of("IEC104", "IEC61850");
    }

    @Override
    public ConnectionAdapter<?> create(String connectionType, DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        try {
            return switch (connectionType) {
                case "IEC104" -> new Iec104ConnectionAdapter(deviceInfo, connectionConfig);
                case "IEC61850" -> new Iec61850ConnectionAdapter(deviceInfo, connectionConfig);
                default -> throw new IllegalArgumentException("IEC Provider 不支持连接类型: " + connectionType);
            };
        } catch (Exception exception) {
            log.error("创建 {} 连接失败: 设备={}", connectionType, deviceInfo.getDeviceId(), exception);
            throw switch (connectionType) {
                case "IEC104" -> new CollectorException("创建 IEC104 连接失败",
                        deviceInfo.getDeviceId(), null);
                case "IEC61850" -> new CollectorException("创建 IEC61850 连接失败",
                        deviceInfo.getDeviceId(), null);
                default -> new CollectorException("不支持的连接类型: " + connectionType,
                        deviceInfo.getDeviceId(), null);
            };
        }
    }
}

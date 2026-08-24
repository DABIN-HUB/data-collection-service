package com.wangbin.collector.core.connection.factory.provider;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.OpcUaConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Plc4xOpcUaConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionAdapterProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * OPC UA 连接适配器 Provider。
 */
@Slf4j
@Component
public class OpcUaConnectionAdapterProvider implements ConnectionAdapterProvider {

    @Override
    public Set<String> supportedConnectionTypes() {
        return Set.of("OPC_UA", "OPC_UA_PLC4X", "OPC_UA_MILO");
    }

    @Override
    public ConnectionAdapter<?> create(String connectionType, DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        try {
            return switch (connectionType) {
                case "OPC_UA", "OPC_UA_PLC4X" -> new Plc4xOpcUaConnectionAdapter(deviceInfo, connectionConfig);
                case "OPC_UA_MILO" -> new OpcUaConnectionAdapter(deviceInfo, connectionConfig);
                default -> throw new IllegalArgumentException("OPC UA Provider 不支持连接类型: " + connectionType);
            };
        } catch (Exception exception) {
            log.error("创建 {} 连接失败: 设备={}", connectionType, deviceInfo.getDeviceId(), exception);
            throw switch (connectionType) {
                case "OPC_UA", "OPC_UA_PLC4X" -> new CollectorException(
                        "Create PLC4X OPC UA connection failed", deviceInfo.getDeviceId(), null);
                case "OPC_UA_MILO" -> new CollectorException("创建 OPC UA 连接失败",
                        deviceInfo.getDeviceId(), null);
                default -> new CollectorException("不支持的连接类型: " + connectionType,
                        deviceInfo.getDeviceId(), null);
            };
        }
    }
}

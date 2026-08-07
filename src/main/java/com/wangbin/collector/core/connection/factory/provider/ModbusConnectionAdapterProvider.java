package com.wangbin.collector.core.connection.factory.provider;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Plc4xModbusRtuConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Plc4xModbusTcpConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionAdapterProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Modbus 连接适配器 Provider。
 */
@Slf4j
@Component
public class ModbusConnectionAdapterProvider implements ConnectionAdapterProvider {

    @Override
    public Set<String> supportedConnectionTypes() {
        return Set.of("MODBUS_TCP", "MODBUS_RTU");
    }

    @Override
    public ConnectionAdapter<?> create(String connectionType, DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        try {
            return switch (connectionType) {
                case "MODBUS_TCP" -> new Plc4xModbusTcpConnectionAdapter(deviceInfo, connectionConfig);
                case "MODBUS_RTU" -> new Plc4xModbusRtuConnectionAdapter(deviceInfo, connectionConfig);
                default -> throw new IllegalArgumentException("Modbus Provider 不支持连接类型: " + connectionType);
            };
        } catch (Exception exception) {
            log.error("创建 {} 连接失败: 设备={}", connectionType, deviceInfo.getDeviceId(), exception);
            throw switch (connectionType) {
                case "MODBUS_TCP" -> new CollectorException("创建 Modbus TCP 连接失败",
                        deviceInfo.getDeviceId(), null);
                case "MODBUS_RTU" -> new CollectorException("创建 Modbus RTU 连接失败",
                        deviceInfo.getDeviceId(), null);
                default -> new CollectorException("不支持的连接类型: " + connectionType,
                        deviceInfo.getDeviceId(), null);
            };
        }
    }
}

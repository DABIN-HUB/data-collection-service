package com.wangbin.collector.core.connection.factory;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorRegistry;
import com.wangbin.collector.core.config.validator.ProtocolConnectionValidator;
import com.wangbin.collector.core.connection.adapter.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 连接工厂
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionFactory {

    @Autowired(required = false)
    @Qualifier("ioIntensiveExecutor")
    private Executor ioExecutor;

    @Autowired(required = false)
    @Qualifier("timeSliceScheduler")
    private ScheduledExecutorService protocolScheduler;

    @Autowired(required = false)
    private ProtocolConnectionValidator protocolConnectionValidator = new ProtocolConnectionValidator();

    private final ProtocolDescriptorRegistry protocolDescriptorRegistry;

    public ConnectionAdapter<?> createConnection(DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        if (deviceInfo == null || deviceInfo.getDeviceId() == null || deviceInfo.getDeviceId().isBlank()) {
            throw new IllegalArgumentException("设备信息无效");
        }
        DeviceConnection cfg = connectionConfig != null ? connectionConfig : new DeviceConnection();
        String connectionType = canonicalizeConnectionType(resolveConnectionType(deviceInfo, cfg), cfg);
        protocolConnectionValidator.validate(deviceInfo, cfg);
        return switch (connectionType) {
            case "TCP" -> createTcpConnection(deviceInfo, cfg);
            case "HTTP" -> createHttpConnection(deviceInfo, cfg);
            case "MQTT" -> createMqttConnection(deviceInfo, cfg);
            case "WEBSOCKET" -> createWebSocketConnection(deviceInfo, cfg);
            case "COAP" -> createCoapConnection(deviceInfo, cfg);
            case "SIEMENS_S7" -> createS7Connection(deviceInfo, cfg);
            case "ETHERNET_IP" -> createEtherNetIpConnection(deviceInfo, cfg);
            case "ADS" -> createAdsConnection(deviceInfo, cfg);
            case "MODBUS_TCP" -> createModbusTcpConnection(deviceInfo, cfg);
            case "MODBUS_RTU" -> createModbusRtuConnection(deviceInfo, cfg);
            case "SNMP" -> createSnmpConnection(deviceInfo, cfg);
            case "OPC_UA", "OPC_UA_PLC4X" -> createPlc4xOpcUaConnection(deviceInfo, cfg);
            case "IEC104" -> createIec104Connection(deviceInfo, cfg);
            case "IEC61850" -> createIec61850Connection(deviceInfo, cfg);
            default -> throw new CollectorException(
                    String.format("不支持的连接类型: %s", connectionType),
                    deviceInfo.getDeviceId(), null
            );
        };
    }

    private String resolveConnectionType(DeviceInfo deviceInfo, DeviceConnection cfg) {
        if (deviceInfo.getConnectionType() != null && !deviceInfo.getConnectionType().isBlank()) {
            return normalize(deviceInfo.getConnectionType());
        }
        if (deviceInfo.getProtocolType() != null && !deviceInfo.getProtocolType().isBlank()) {
            return normalize(deviceInfo.getProtocolType());
        }
        if (cfg != null && cfg.getConnectionType() != null && !cfg.getConnectionType().isBlank()) {
            return normalize(cfg.getConnectionType());
        }
        return "TCP";
    }

    private String canonicalizeConnectionType(String type, DeviceConnection cfg) {
        return protocolDescriptorRegistry.applyConnectionDefaults(type, cfg);
    }

    private String normalize(String type) {
        return type.toUpperCase().replace("-", "_");
    }

    private ConnectionAdapter<?> createTcpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new TcpConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建TCP连接失败: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建TCP连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createHttpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new HttpConnectionAdapter(deviceInfo, cfg, ioExecutor);
        } catch (Exception e) {
            log.error("创建HTTP连接失败: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建HTTP连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createMqttConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new MqttConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建MQTT连接失败: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建MQTT连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createWebSocketConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new WebSocketConnectionAdapter(deviceInfo, cfg, ioExecutor, protocolScheduler);
        } catch (Exception e) {
            log.error("创建WebSocket连接失败: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建WebSocket连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createCoapConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new CoapConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建CoAP连接失败: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建CoAP连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createS7Connection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new S7ConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建S7连接失败: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建S7连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createEtherNetIpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new EtherNetIpConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("Create EtherNet/IP connection failed: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("Create EtherNet/IP connection failed", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createAdsConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new AdsConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("Create ADS connection failed: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("Create ADS connection failed", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createModbusTcpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new Plc4xModbusTcpConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建Modbus TCP连接失败: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建Modbus TCP连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createModbusRtuConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new Plc4xModbusRtuConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建Modbus RTU连接失败: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建Modbus RTU连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createSnmpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new SnmpConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建SNMP连接失败: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建SNMP连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createOpcUaConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new OpcUaConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建OPC UA连接失败: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建OPC UA连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createPlc4xOpcUaConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new Plc4xOpcUaConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("Create PLC4X OPC UA connection failed: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("Create PLC4X OPC UA connection failed", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createIec104Connection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new Iec104ConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建IEC104连接失败: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建IEC104连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createIec61850Connection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new Iec61850ConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建IEC61850连接失败: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建IEC61850连接失败", deviceInfo.getDeviceId(), null);
        }
    }
}

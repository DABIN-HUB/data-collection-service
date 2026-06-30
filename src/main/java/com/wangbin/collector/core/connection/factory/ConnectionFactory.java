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
 * 杩炴帴宸ュ巶
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
            throw new IllegalArgumentException("璁惧淇℃伅鏃犳晥");
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
            case "BACNET_IP" -> createBacnetIpConnection(deviceInfo, cfg);
            case "MITSUBISHI_MC" -> createMitsubishiMcConnection(deviceInfo, cfg);
            case "ETHERNET_IP" -> createEtherNetIpConnection(deviceInfo, cfg);
            case "ADS" -> createAdsConnection(deviceInfo, cfg);
            case "KNXNET_IP" -> createKnxNetIpConnection(deviceInfo, cfg);
            case "MODBUS_TCP" -> createModbusTcpConnection(deviceInfo, cfg);
            case "MODBUS_RTU" -> createModbusRtuConnection(deviceInfo, cfg);
            case "SNMP" -> createSnmpConnection(deviceInfo, cfg);
            case "OPC_UA", "OPC_UA_PLC4X" -> createPlc4xOpcUaConnection(deviceInfo, cfg);
            case "IEC104" -> createIec104Connection(deviceInfo, cfg);
            case "IEC61850" -> createIec61850Connection(deviceInfo, cfg);
            default -> throw new CollectorException(
                    String.format("涓嶆敮鎸佺殑杩炴帴绫诲瀷: %s", connectionType),
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
            log.error("鍒涘缓TCP杩炴帴澶辫触: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("鍒涘缓TCP杩炴帴澶辫触", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createHttpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new HttpConnectionAdapter(deviceInfo, cfg, ioExecutor);
        } catch (Exception e) {
            log.error("鍒涘缓HTTP杩炴帴澶辫触: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("鍒涘缓HTTP杩炴帴澶辫触", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createMqttConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new MqttConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("鍒涘缓MQTT杩炴帴澶辫触: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("鍒涘缓MQTT杩炴帴澶辫触", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createWebSocketConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new WebSocketConnectionAdapter(deviceInfo, cfg, ioExecutor, protocolScheduler);
        } catch (Exception e) {
            log.error("鍒涘缓WebSocket杩炴帴澶辫触: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("鍒涘缓WebSocket杩炴帴澶辫触", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createCoapConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new CoapConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("鍒涘缓CoAP杩炴帴澶辫触: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("鍒涘缓CoAP杩炴帴澶辫触", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createS7Connection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new S7ConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("鍒涘缓S7杩炴帴澶辫触: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("鍒涘缓S7杩炴帴澶辫触", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createBacnetIpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new BacnetIpConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("Create BACnet/IP connection failed: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("Create BACnet/IP connection failed", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createMitsubishiMcConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new MitsubishiMcConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("Create Mitsubishi MC connection failed: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("Create Mitsubishi MC connection failed", deviceInfo.getDeviceId(), null);
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

    private ConnectionAdapter<?> createKnxNetIpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new KnxNetIpConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("Create KNXnet/IP connection failed: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("Create KNXnet/IP connection failed", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createModbusTcpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new Plc4xModbusTcpConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("鍒涘缓Modbus TCP杩炴帴澶辫触: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("鍒涘缓Modbus TCP杩炴帴澶辫触", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createModbusRtuConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new Plc4xModbusRtuConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("鍒涘缓Modbus RTU杩炴帴澶辫触: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("鍒涘缓Modbus RTU杩炴帴澶辫触", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createSnmpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new SnmpConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("鍒涘缓SNMP杩炴帴澶辫触: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("鍒涘缓SNMP杩炴帴澶辫触", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createOpcUaConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new OpcUaConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("鍒涘缓OPC UA杩炴帴澶辫触: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("鍒涘缓OPC UA杩炴帴澶辫触", deviceInfo.getDeviceId(), null);
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
            log.error("鍒涘缓IEC104杩炴帴澶辫触: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("鍒涘缓IEC104杩炴帴澶辫触", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createIec61850Connection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new Iec61850ConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("鍒涘缓IEC61850杩炴帴澶辫触: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("鍒涘缓IEC61850杩炴帴澶辫触", deviceInfo.getDeviceId(), null);
        }
    }
}


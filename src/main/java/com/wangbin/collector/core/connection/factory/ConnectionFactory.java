package com.wangbin.collector.core.connection.factory;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorRegistry;
import com.wangbin.collector.core.config.validator.ProtocolConnectionValidator;
import com.wangbin.collector.core.connection.adapter.*;
import com.wangbin.collector.core.connection.serial.SharedSerialChannelManager;
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
 * 连接工厂，负责根据设备协议创建对应的连接适配器。
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

    @Autowired(required = false)
    private CollectorProperties collectorProperties;

    @Autowired(required = false)
    private SharedSerialChannelManager sharedSerialChannelManager = new SharedSerialChannelManager();

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
            case "BACNET_IP" -> createBacnetIpConnection(deviceInfo, cfg);
            case "BACNET_MSTP" -> createBacnetMstpConnection(deviceInfo, cfg);
            case "BACNET_SC" -> createBacnetScConnection(deviceInfo, cfg);
            case "MITSUBISHI_MC" -> createMitsubishiMcConnection(deviceInfo, cfg);
            case "OMRON_FINS" -> createOmronFinsConnection(deviceInfo, cfg);
            case "ETHERNET_IP" -> createEtherNetIpConnection(deviceInfo, cfg);
            case "ADS" -> createAdsConnection(deviceInfo, cfg);
            case "KNXNET_IP" -> createKnxNetIpConnection(deviceInfo, cfg);
            case "MODBUS_TCP" -> createModbusTcpConnection(deviceInfo, cfg);
            case "MODBUS_RTU" -> createModbusRtuConnection(deviceInfo, cfg);
            case "SNMP" -> createSnmpConnection(deviceInfo, cfg);
            case "OPC_UA", "OPC_UA_PLC4X" -> createPlc4xOpcUaConnection(deviceInfo, cfg);
            case "OPC_UA_MILO" -> createOpcUaConnection(deviceInfo, cfg);
            case "IEC104" -> createIec104Connection(deviceInfo, cfg);
            case "DLT645_2007" -> createDlt645Connection(deviceInfo, cfg);
            case "IEC101" -> createIec101Connection(deviceInfo, cfg);
            case "IEC61850" -> createIec61850Connection(deviceInfo, cfg);
            case "CUSTOM_TCP" -> createCustomTcpConnection(deviceInfo, cfg);
            case "CUSTOM_UDP" -> createCustomUdpConnection(deviceInfo, cfg);
            default -> throw new CollectorException(
                    String.format("不支持的连接类型: %s", connectionType),
                    deviceInfo.getDeviceId(), null
            );
        };
    }

    private String resolveConnectionType(DeviceInfo deviceInfo, DeviceConnection cfg) {
        String protocolType = null;
        if (deviceInfo.getProtocolType() != null && !deviceInfo.getProtocolType().isBlank()) {
            protocolType = normalize(deviceInfo.getProtocolType());
            if (protocolDescriptorRegistry.resolve(protocolType) != null) {
                return protocolType;
            }
        }
        if (deviceInfo.getConnectionType() != null && !deviceInfo.getConnectionType().isBlank()) {
            return normalize(deviceInfo.getConnectionType());
        }
        if (cfg != null && cfg.getConnectionType() != null && !cfg.getConnectionType().isBlank()) {
            return normalize(cfg.getConnectionType());
        }
        if (protocolType != null) {
            return protocolType;
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
            log.error("创建 TCP 连接失败: deviceId={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建 TCP 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createHttpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new HttpConnectionAdapter(deviceInfo, cfg, ioExecutor);
        } catch (Exception e) {
            log.error("创建 HTTP 连接失败: deviceId={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建 HTTP 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createMqttConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            applyMqttConnectionDefaults(cfg);
            return new MqttConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建 MQTT 连接失败: deviceId={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建 MQTT 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private void applyMqttConnectionDefaults(DeviceConnection cfg) {
        if (cfg == null || collectorProperties == null || collectorProperties.getMqtt() == null) {
            return;
        }
        if (cfg.getExtJson() == null) {
            cfg.setExtJson(new LinkedHashMap<>());
        }
        // 平台不支持并发建连时，该值应保持为 1。
        cfg.getExtJson().putIfAbsent(
                "maxConcurrentConnects",
                Math.max(1, collectorProperties.getMqtt().getMaxConcurrentConnects()));
    }

    private ConnectionAdapter<?> createWebSocketConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new WebSocketConnectionAdapter(deviceInfo, cfg, ioExecutor, protocolScheduler);
        } catch (Exception e) {
            log.error("创建 WebSocket 连接失败: deviceId={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建 WebSocket 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createCoapConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new CoapConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建 CoAP 连接失败: deviceId={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建 CoAP 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createS7Connection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new S7ConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建 S7 连接失败: deviceId={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建 S7 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createBacnetIpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new BacnetIpConnectionAdapter(deviceInfo, cfg, protocolScheduler);
        } catch (Exception e) {
            log.error("Create BACnet/IP connection failed: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("Create BACnet/IP connection failed", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createBacnetMstpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new BacnetMstpConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("Create BACnet MS/TP connection failed: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("Create BACnet MS/TP connection failed", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createBacnetScConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new BacnetScConnectionAdapter(deviceInfo, cfg, ioExecutor, protocolScheduler);
        } catch (Exception e) {
            log.error("Create BACnet/SC connection failed: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("Create BACnet/SC connection failed", deviceInfo.getDeviceId(), null);
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

    private ConnectionAdapter<?> createOmronFinsConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new OmronFinsUdpConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("Create OMRON FINS connection failed: {}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("Create OMRON FINS connection failed", deviceInfo.getDeviceId(), null);
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
            log.error("创建 Modbus TCP 连接失败: deviceId={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建 Modbus TCP 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createModbusRtuConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new Plc4xModbusRtuConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建 Modbus RTU 连接失败: deviceId={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建 Modbus RTU 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createSnmpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new SnmpConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建 SNMP 连接失败: deviceId={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建 SNMP 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createOpcUaConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new OpcUaConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建 OPC UA 连接失败: deviceId={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建 OPC UA 连接失败", deviceInfo.getDeviceId(), null);
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
            log.error("创建 IEC104 连接失败: deviceId={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建 IEC104 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createDlt645Connection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new Dlt645ConnectionAdapter(deviceInfo, cfg, sharedSerialChannelManager);
        } catch (Exception exception) {
            log.error("创建 DL/T 645 连接失败: {}", deviceInfo.getDeviceId(), exception);
            throw new CollectorException("创建 DL/T 645 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createIec101Connection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new Iec101ConnectionAdapter(deviceInfo, cfg, sharedSerialChannelManager);
        } catch (Exception exception) {
            log.error("创建 IEC101 连接失败: {}", deviceInfo.getDeviceId(), exception);
            throw new CollectorException("创建 IEC101 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createIec61850Connection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new Iec61850ConnectionAdapter(deviceInfo, cfg);
        } catch (Exception e) {
            log.error("创建 IEC61850 连接失败: deviceId={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("创建 IEC61850 连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createCustomTcpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new CustomTcpConnectionAdapter(deviceInfo, cfg);
        } catch (Exception exception) {
            log.error("创建自定义TCP连接失败: {}", deviceInfo.getDeviceId(), exception);
            throw new CollectorException("创建自定义TCP连接失败", deviceInfo.getDeviceId(), null);
        }
    }

    private ConnectionAdapter<?> createCustomUdpConnection(DeviceInfo deviceInfo, DeviceConnection cfg) {
        try {
            return new CustomUdpConnectionAdapter(deviceInfo, cfg);
        } catch (Exception exception) {
            log.error("创建自定义UDP连接失败: {}", deviceInfo.getDeviceId(), exception);
            throw new CollectorException("创建自定义UDP连接失败", deviceInfo.getDeviceId(), null);
        }
    }
}


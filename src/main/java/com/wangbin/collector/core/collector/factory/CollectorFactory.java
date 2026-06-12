package com.wangbin.collector.core.collector.factory;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;
import com.wangbin.collector.core.collector.protocol.coap.CoapCollector;
import com.wangbin.collector.core.collector.protocol.custom.CustomProtocolCollector;
import com.wangbin.collector.core.collector.protocol.ethernetip.EtherNetIpCollector;
import com.wangbin.collector.core.collector.protocol.http.HttpCollector;
import com.wangbin.collector.core.collector.protocol.iec.Iec104Collector;
import com.wangbin.collector.core.collector.protocol.iec.Iec61850Collector;
import com.wangbin.collector.core.collector.protocol.modbus.Plc4xModbusRtuCollector;
import com.wangbin.collector.core.collector.protocol.modbus.Plc4xModbusTcpCollector;
import com.wangbin.collector.core.collector.protocol.mqtt.MqttCollector;
import com.wangbin.collector.core.collector.protocol.opc.OpcDaCollector;
import com.wangbin.collector.core.collector.protocol.opc.OpcUaCollector;
import com.wangbin.collector.core.collector.protocol.s7.S7Collector;
import com.wangbin.collector.core.collector.protocol.snmp.SnmpCollector;
import com.wangbin.collector.core.collector.protocol.websocket.WebSocketCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 采集器工厂：根据协议类型创建对应的采集器实现。
 */
@Slf4j
@Component
public class CollectorFactory {

    @Autowired
    private AutowireCapableBeanFactory beanFactory;

    private final Map<String, CollectorCreator> collectorCreators = new HashMap<>();

    public CollectorFactory() {
        registerCollectorCreators();
    }

    /**
     * 创建采集器并完成初始化。
     */
    public ProtocolCollector createCollector(DeviceInfo deviceInfo) throws CollectorException {
        String protocolType = deviceInfo.getProtocolType();
        if (protocolType == null || protocolType.isEmpty()) {
            throw new IllegalArgumentException("Protocol type cannot be empty");
        }

        CollectorCreator creator = collectorCreators.get(protocolType.toUpperCase());
        if (creator == null) {
            throw new CollectorException(
                    String.format("Unsupported protocol type: %s", protocolType),
                    deviceInfo.getDeviceId(),
                    null
            );
        }

        try {
            ProtocolCollector collector = creator.create(deviceInfo);
            collector.init(deviceInfo);
            log.info("Collector created successfully, deviceId={}, protocolType={}",
                    deviceInfo.getDeviceId(), protocolType);
            return collector;
        } catch (Exception e) {
            log.error("Collector creation failed, deviceId={}, protocolType={}",
                    deviceInfo.getDeviceId(), protocolType, e);
            throw new CollectorException("Collector creation failed", deviceInfo.getDeviceId(), null, e);
        }
    }

    /**
     * 注册协议与采集器创建器映射。
     */
    public void registerCollector(String protocolType, CollectorCreator creator) {
        collectorCreators.put(protocolType.toUpperCase(), creator);
        log.info("Collector registered, protocolType={}", protocolType);
    }

    /**
     * 获取当前支持的所有协议类型。
     */
    public String[] getSupportedProtocols() {
        return collectorCreators.keySet().toArray(new String[0]);
    }

    /**
     * 判断是否支持某个协议。
     */
    public boolean supportsProtocol(String protocolType) {
        return collectorCreators.containsKey(protocolType.toUpperCase());
    }

    /**
     * 注册内置采集器。
     */
    private void registerCollectorCreators() {
        // Modbus
        registerCollector("MODBUS_TCP", Plc4xModbusTcpCollector.class);
        registerCollector("MODBUS_RTU", Plc4xModbusRtuCollector.class);
        registerCollector("MODBUS_ASCII", Plc4xModbusRtuCollector.class);
        registerCollector("SIEMENS_S7", S7Collector.class);
        registerCollector("S7", S7Collector.class);
        registerCollector("ETHERNET_IP", EtherNetIpCollector.class);
        registerCollector("EIP", EtherNetIpCollector.class);
        registerCollector("LOGIX", EtherNetIpCollector.class);
        registerCollector("AB_ETH", EtherNetIpCollector.class);

        // OPC
        registerCollector("OPC_DA", OpcDaCollector.class);
        registerCollector("OPC_UA", OpcUaCollector.class);

        // SNMP / CoAP / MQTT
        registerCollector("SNMP", SnmpCollector.class);
        registerCollector("SNMP_V1", SnmpCollector.class);
        registerCollector("SNMP_V2C", SnmpCollector.class);
        registerCollector("SNMP_V3", SnmpCollector.class);
        registerCollector("COAP", CoapCollector.class);
        registerCollector("COAP_SSL", CoapCollector.class);
        registerCollector("MQTT", MqttCollector.class);
        registerCollector("MQTT_SSL", MqttCollector.class);

        // IEC
        registerCollector("IEC104", Iec104Collector.class);
        registerCollector("IEC61850", Iec61850Collector.class);

        // Custom protocol
        registerCollector("CUSTOM_TCP", CustomProtocolCollector.class);
        registerCollector("CUSTOM_UDP", CustomProtocolCollector.class);

        // HTTP / WebSocket
        registerCollector("HTTP", HttpCollector.class);
        registerCollector("HTTPS", HttpCollector.class);
        registerCollector("WEBSOCKET", WebSocketCollector.class);
        registerCollector("WEBSOCKET_SSL", WebSocketCollector.class);

        log.info("CollectorFactory initialized, supported protocol count={}", collectorCreators.size());
    }

    /**
     * 使用 Spring BeanFactory 创建实例，保证 AOP 生效。
     */
    public void registerCollector(String protocolType, Class<? extends ProtocolCollector> collectorClass) {
        registerCollector(protocolType, deviceInfo -> instantiateCollector(protocolType, collectorClass));
    }

    private ProtocolCollector instantiateCollector(String protocolType,
                                                   Class<? extends ProtocolCollector> collectorClass) {
        try {
            if (beanFactory != null) {
                return beanFactory.createBean(collectorClass);
            }
            return collectorClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format("Failed to instantiate collector, protocolType=%s, class=%s",
                            protocolType, collectorClass.getName()),
                    e
            );
        }
    }

    @FunctionalInterface
    public interface CollectorCreator {
        ProtocolCollector create(DeviceInfo deviceInfo) throws Exception;
    }
}

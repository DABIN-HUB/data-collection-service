package com.wangbin.collector.core.collector.factory;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;
import com.wangbin.collector.core.collector.protocol.coap.CoapCollector;
import com.wangbin.collector.core.collector.protocol.custom.CustomProtocolCollector;
import com.wangbin.collector.core.collector.protocol.http.HttpCollector;
import com.wangbin.collector.core.collector.protocol.iec.Iec104Collector;
import com.wangbin.collector.core.collector.protocol.iec.Iec61850Collector;
import com.wangbin.collector.core.collector.protocol.modbus.ModbusRtuCollector;
import com.wangbin.collector.core.collector.protocol.modbus.ModbusTcpCollector;
import com.wangbin.collector.core.collector.protocol.mqtt.MqttCollector;
import com.wangbin.collector.core.collector.protocol.opc.OpcDaCollector;
import com.wangbin.collector.core.collector.protocol.opc.OpcUaCollector;
import com.wangbin.collector.core.collector.protocol.snmp.SnmpCollector;
import com.wangbin.collector.core.collector.protocol.websocket.WebSocketCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 采集器工厂
 */
@Slf4j
@Component
public class CollectorFactory {

    @Autowired
    private AutowireCapableBeanFactory beanFactory;

    private final Map<String, CollectorCreator> collectorCreators = new HashMap<>();

    public CollectorFactory() {
        // 注册所有采集器创建器
        registerCollectorCreators();
    }

    /**
     * 创建采集器
     */
    public ProtocolCollector createCollector(DeviceInfo deviceInfo) throws CollectorException {
        String protocolType = deviceInfo.getProtocolType();

        if (protocolType == null || protocolType.isEmpty()) {
            throw new IllegalArgumentException("协议类型不能为空");
        }

        CollectorCreator creator = collectorCreators.get(protocolType.toUpperCase());
        if (creator == null) {
            throw new CollectorException(
                    String.format("不支持的协议类型: %s", protocolType),
                    deviceInfo.getDeviceId(), null);
        }

        try {
            ProtocolCollector collector = creator.create(deviceInfo);
            collector.init(deviceInfo);

            log.info("采集器创建成功: {} [{}]",
                    deviceInfo.getDeviceId(), protocolType);

            return collector;
        } catch (Exception e) {
            log.error("采集器创建失败: {} [{}]",
                    deviceInfo.getDeviceId(), protocolType, e);
            throw new CollectorException("采集器创建失败",
                    deviceInfo.getDeviceId(), null, e);
        }
    }

    /**
     * 注册采集器
     */
    public void registerCollector(String protocolType, CollectorCreator creator) {
        collectorCreators.put(protocolType.toUpperCase(), creator);
        log.info("注册采集器: {}", protocolType);
    }

    /**
     * 获取支持的协议类型
     */
    public String[] getSupportedProtocols() {
        return collectorCreators.keySet().toArray(new String[0]);
    }

    /**
     * 是否支持协议类型
     */
    public boolean supportsProtocol(String protocolType) {
        return collectorCreators.containsKey(protocolType.toUpperCase());
    }

    /**
     * 注册所有采集器创建器
     */
    private void registerCollectorCreators() {
        // Modbus 协议
        registerCollector("MODBUS_TCP", ModbusTcpCollector.class);
        registerCollector("MODBUS_RTU", ModbusRtuCollector.class);

        // OPC 协议
        registerCollector("OPC_DA", OpcDaCollector.class);
        registerCollector("OPC_UA", OpcUaCollector.class);

        // SNMP/COAP/MQTT 协议
        registerCollector("SNMP", SnmpCollector.class);
        registerCollector("COAP", CoapCollector.class);
        registerCollector("MQTT", MqttCollector.class);

        // IEC 协议
        registerCollector("IEC104", Iec104Collector.class);
        registerCollector("IEC61850", Iec61850Collector.class);

        // 自定义协议
        registerCollector("CUSTOM_TCP", CustomProtocolCollector.class);

        // HTTP / WebSocket 协议
        registerCollector("HTTP", HttpCollector.class);
        registerCollector("WEBSOCKET", WebSocketCollector.class);

        log.info("采集器工厂初始化完成，支持 {} 种协议", collectorCreators.size());
    }

    /**
     * 使用Spring BeanFactory创建采集器实例，确保AOP等BeanPostProcessor生效
     */
    public void registerCollector(String protocolType, Class<? extends ProtocolCollector> collectorClass) {
        registerCollector(protocolType, deviceInfo -> instantiateCollector(protocolType, collectorClass));
    }

    private ProtocolCollector instantiateCollector(String protocolType, Class<? extends ProtocolCollector> collectorClass) {
        try {
            if (beanFactory != null) {
                return beanFactory.createBean(collectorClass);
            }
            return collectorClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(String.format("%s采集器加载失败: %s", protocolType, collectorClass.getName()), e);
        }
    }

    /**
     * 采集器创建器接口
     */
    @FunctionalInterface
    public interface CollectorCreator {
        ProtocolCollector create(DeviceInfo deviceInfo) throws Exception;
    }
}


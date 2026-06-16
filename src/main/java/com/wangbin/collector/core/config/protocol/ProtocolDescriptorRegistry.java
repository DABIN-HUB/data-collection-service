package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.ads.AdsCollector;
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
import com.wangbin.collector.core.collector.protocol.opc.Plc4xOpcUaCollector;
import com.wangbin.collector.core.collector.protocol.s7.S7Collector;
import com.wangbin.collector.core.collector.protocol.snmp.SnmpCollector;
import com.wangbin.collector.core.collector.protocol.websocket.WebSocketCollector;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class ProtocolDescriptorRegistry {

    private final Map<String, ProtocolDescriptor> descriptors = new LinkedHashMap<>();
    private final Map<String, AliasDescriptor> aliases = new LinkedHashMap<>();

    public ProtocolDescriptorRegistry() {
        registerPrimary(new ProtocolDescriptor("MODBUS_TCP", List.of(), Plc4xModbusTcpCollector.class,
                "MODBUS_TCP", 502, ProtocolAddressingMode.NUMERIC));
        registerPrimary(new ProtocolDescriptor("MODBUS_RTU", List.of("MODBUS_ASCII"), Plc4xModbusRtuCollector.class,
                "MODBUS_RTU", null, ProtocolAddressingMode.NUMERIC));
        registerPrimary(new ProtocolDescriptor("SIEMENS_S7", List.of("S7"), S7Collector.class,
                "SIEMENS_S7", 102, ProtocolAddressingMode.MIXED));
        registerPrimary(new ProtocolDescriptor("ETHERNET_IP", List.of("EIP", "LOGIX", "AB_ETH"),
                EtherNetIpCollector.class, "ETHERNET_IP", 44818, ProtocolAddressingMode.SYMBOLIC));
        registerPrimary(new ProtocolDescriptor("ADS", List.of("AMS"), AdsCollector.class,
                "ADS", 48898, ProtocolAddressingMode.SYMBOLIC));
        registerPrimary(new ProtocolDescriptor("OPC_DA", List.of(), OpcDaCollector.class,
                "OPC_DA", null, ProtocolAddressingMode.SYMBOLIC));
        registerPrimary(new ProtocolDescriptor("OPC_UA", List.of("OPCUA"), Plc4xOpcUaCollector.class,
                "OPC_UA", 4840, ProtocolAddressingMode.SYMBOLIC));
        registerPrimary(new ProtocolDescriptor("OPC_UA_PLC4X", List.of("OPCUA_PLC4X"), Plc4xOpcUaCollector.class,
                "OPC_UA_PLC4X", 4840, ProtocolAddressingMode.SYMBOLIC));
        registerPrimary(new ProtocolDescriptor("SNMP", List.of("SNMP_V1", "SNMP_V2C", "SNMP_V3"), SnmpCollector.class,
                "SNMP", 161, ProtocolAddressingMode.NUMERIC));
        registerPrimary(new ProtocolDescriptor("COAP", List.of("COAP_SSL"), CoapCollector.class,
                "COAP", 5683, ProtocolAddressingMode.SYMBOLIC));
        registerPrimary(new ProtocolDescriptor("MQTT", List.of("MQTT_SSL"), MqttCollector.class,
                "MQTT", 1883, ProtocolAddressingMode.SYMBOLIC));
        registerPrimary(new ProtocolDescriptor("IEC104", List.of("IEC_104"), Iec104Collector.class,
                "IEC104", 2404, ProtocolAddressingMode.NUMERIC));
        registerPrimary(new ProtocolDescriptor("IEC61850", List.of("IEC_61850"), Iec61850Collector.class,
                "IEC61850", 102, ProtocolAddressingMode.SYMBOLIC));
        registerPrimary(new ProtocolDescriptor("HTTP", List.of("HTTPS"), HttpCollector.class,
                "HTTP", 80, ProtocolAddressingMode.SYMBOLIC));
        registerPrimary(new ProtocolDescriptor("WEBSOCKET", List.of("WEBSOCKET_SSL"), WebSocketCollector.class,
                "WEBSOCKET", 80, ProtocolAddressingMode.SYMBOLIC));
        registerPrimary(new ProtocolDescriptor("CUSTOM_TCP", List.of("CUSTOM_UDP"), CustomProtocolCollector.class,
                "TCP", null, ProtocolAddressingMode.SYMBOLIC));

        registerAlias("HTTPS", "HTTP", cfg -> {
            cfg.setSslEnabled(true);
            applyDefaultPort(cfg, 443);
        });
        registerAlias("WEBSOCKET_SSL", "WEBSOCKET", cfg -> {
            cfg.setSslEnabled(true);
            applyDefaultPort(cfg, 443);
        });
        registerAlias("MQTT_SSL", "MQTT", cfg -> {
            cfg.setSslEnabled(true);
            applyDefaultPort(cfg, 8883);
        });
        registerAlias("COAP_SSL", "COAP", cfg -> {
            cfg.setSslEnabled(true);
            applyDefaultPort(cfg, 5684);
            putExtIfAbsent(cfg, "scheme", "coaps");
        });
        registerAlias("SNMP_V1", "SNMP", cfg -> putExtIfAbsent(cfg, "snmpVersion", "1"));
        registerAlias("SNMP_V2C", "SNMP", cfg -> putExtIfAbsent(cfg, "snmpVersion", "2c"));
        registerAlias("SNMP_V3", "SNMP", cfg -> putExtIfAbsent(cfg, "snmpVersion", "3"));
    }

    public boolean supports(String protocol) {
        return canonicalProtocol(protocol) != null;
    }

    public String canonicalProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return null;
        }
        String normalized = normalize(protocol);
        if (descriptors.containsKey(normalized)) {
            return normalized;
        }
        AliasDescriptor alias = aliases.get(normalized);
        return alias != null ? alias.primaryCode() : null;
    }

    public ProtocolDescriptor resolve(String protocol) {
        String canonical = canonicalProtocol(protocol);
        return canonical == null ? null : descriptors.get(canonical);
    }

    public Collection<ProtocolDescriptor> primaryDescriptors() {
        return Collections.unmodifiableCollection(descriptors.values());
    }

    public Collection<String> allSupportedCodes() {
        java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>(descriptors.keySet());
        codes.addAll(aliases.keySet());
        return Collections.unmodifiableSet(codes);
    }

    public String applyConnectionDefaults(String protocol, DeviceConnection cfg) {
        ProtocolDescriptor descriptor = resolve(protocol);
        String canonical = descriptor != null ? descriptor.connectionType() : normalize(protocol);
        AliasDescriptor alias = aliases.get(normalize(protocol));
        if (alias != null && alias.customizer() != null) {
            alias.customizer().accept(cfg);
        }
        if (descriptor != null) {
            applyDefaultPort(cfg, descriptor.defaultPort());
        }
        return canonical;
    }

    private void registerPrimary(ProtocolDescriptor descriptor) {
        descriptors.put(descriptor.code(), descriptor);
        for (String alias : descriptor.aliases()) {
            registerAlias(alias, descriptor.code(), null);
        }
    }

    private void registerAlias(String alias, String primaryCode, Consumer<DeviceConnection> customizer) {
        aliases.put(normalize(alias), new AliasDescriptor(primaryCode, customizer));
    }

    private static void applyDefaultPort(DeviceConnection cfg, Integer defaultPort) {
        if (cfg != null && cfg.getPort() == null && defaultPort != null && defaultPort > 0) {
            cfg.setPort(defaultPort);
        }
    }

    private static void putExtIfAbsent(DeviceConnection cfg, String key, Object value) {
        if (cfg.getExtJson() == null) {
            cfg.setExtJson(new LinkedHashMap<>());
        }
        cfg.getExtJson().putIfAbsent(key, value);
    }

    private String normalize(String protocol) {
        return protocol.trim().toUpperCase(Locale.ROOT).replace("-", "_");
    }

    private record AliasDescriptor(String primaryCode, Consumer<DeviceConnection> customizer) {
    }
}

package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.snmp.SnmpCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SNMP 协议元数据提供者。
 */
@Component
@Order(110)
public class SnmpProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("SNMP", "SNMP", "SNMP polling protocol.",
                List.of("SNMP_V1", "SNMP_V2C", "SNMP_V3"), SnmpCollector.class, "SNMP", 161,
                ProtocolAddressingMode.NUMERIC,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.SUPPORTED,
                List.of("1.3.6.1.2.1.1.3.0", "1.3.6.1.4.1.2021.10.1.3.1"),
                registry.fields(
                        registry.field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        registry.field("port", "number", "Port", true, "161", null, "connection"),
                        registry.field("community", "string", "Community", true, "public", null, "security"),
                        registry.field("snmpVersion", "select", "SNMP version", true, "2c",
                                List.of("1", "2c", "3"), "protocol"),
                        registry.conditional("snmpSecurityName", "string", "SNMPv3 security name", false, "", null,
                                "security", "snmpVersion=3"),
                        registry.conditional("snmpSecurityLevel", "select", "SNMPv3 security level", false, "authPriv",
                                List.of("noAuthNoPriv", "authNoPriv", "authPriv"), "security", "snmpVersion=3"),
                        registry.conditional("snmpAuthProtocol", "select", "SNMPv3 auth protocol", false, "SHA",
                                List.of("MD5", "SHA", "SHA224", "SHA256", "SHA384", "SHA512", "NONE"),
                                "security", "snmpSecurityLevel=authNoPriv/authPriv"),
                        registry.conditional("snmpAuthPassword", "password", "SNMPv3 auth password", false, "", null,
                                "security", "snmpSecurityLevel=authNoPriv/authPriv"),
                        registry.conditional("snmpPrivProtocol", "select", "SNMPv3 privacy protocol", false, "AES128",
                                List.of("DES", "AES128", "AES192", "AES256", "NONE"),
                                "security", "snmpSecurityLevel=authPriv"),
                        registry.conditional("snmpPrivPassword", "password", "SNMPv3 privacy password", false, "", null,
                                "security", "snmpSecurityLevel=authPriv"),
                        registry.conditional("snmpContextName", "string", "SNMPv3 context name", false, "", null,
                                "security", "snmpVersion=3"),
                        registry.conditional("snmpContextEngineId", "string", "SNMPv3 context engine ID", false, "", null,
                                "security", "snmpVersion=3"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("snmpRetries", "number", "Retry count", false, "1", null, "advanced")))
                .withDriverPrimarySchema("SNMP value type", driverDataTypes(), List.of()));

        registry.registerAlias("SNMP_V1", "SNMP", cfg -> ProtocolDescriptorRegistry.putExtIfAbsent(cfg, "snmpVersion", "1"));
        registry.registerAlias("SNMP_V2C", "SNMP", cfg -> ProtocolDescriptorRegistry.putExtIfAbsent(cfg, "snmpVersion", "2c"));
        registry.registerAlias("SNMP_V3", "SNMP", cfg -> ProtocolDescriptorRegistry.putExtIfAbsent(cfg, "snmpVersion", "3"));
    }

    private List<String> driverDataTypes() {
        return List.of(
                "AUTO", "INTEGER", "COUNTER32", "COUNTER64", "GAUGE32", "TIMETICKS",
                "OCTET_STRING", "STRING", "IP_ADDRESS", "OID", "NULL");
    }
}

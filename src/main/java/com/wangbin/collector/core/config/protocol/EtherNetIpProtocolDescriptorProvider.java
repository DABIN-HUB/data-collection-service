package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.ethernetip.EtherNetIpCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * EtherNet/IP 协议元数据提供者。
 */
@Component
@Order(70)
public class EtherNetIpProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("ETHERNET_IP", "EtherNet/IP",
                "PLC4X-backed EtherNet/IP / Logix tag collector.",
                List.of("EIP", "LOGIX", "AB_ETH"), EtherNetIpCollector.class, "ETHERNET_IP", 44818,
                ProtocolAddressingMode.SYMBOLIC,
                true, true, false,
                List.of("MainProgram.Tag1", "Program:MainProgram.Tag2", "%Tag[0]:1:DINT"),
                registry.fields(
                        registry.field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        registry.field("port", "number", "Port", false, "44818", null, "connection"),
                        registry.field("communicationPath", "string", "Communication path", false, "[1,0]", null, "protocol"),
                        registry.field("backplane", "number", "Backplane", false, "1", null, "protocol"),
                        registry.field("slot", "number", "Slot", false, "0", null, "protocol"),
                        registry.field("maxFieldsPerRequest", "number", "Max fields per request", false, "64", null, "advanced"),
                        registry.field("bigEndian", "boolean", "Big-endian mode", false, "true",
                                List.of("true", "false"), "advanced"),
                        registry.field("forceUnconnectedOperation", "boolean", "Force unconnected operation", false, "false",
                                List.of("true", "false"), "advanced"),
                        registry.field("tcpKeepAlive", "boolean", "TCP keep-alive", false, "true",
                                List.of("true", "false"), "advanced"),
                        registry.field("tcpNoDelay", "boolean", "TCP no-delay", false, "true",
                                List.of("true", "false"), "advanced"),
                        registry.field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced")))
                .withDriverPrimarySchema("EIP driver type", driverDataTypes(), pointFields(registry)));
    }

    private List<String> driverDataTypes() {
        return List.of(
                "BOOL", "BYTE", "SINT", "USINT", "INT", "UINT", "WORD",
                "DINT", "UDINT", "DWORD", "LINT", "ULINT", "LWORD", "REAL", "LREAL", "STRING");
    }

    private List<ProtocolFieldConfig> pointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.arraySize", "number", "Array size", false, "",
                        Collections.emptyList(), "Element count for array tags when the address or symbol refers to an array.", null)
        );
    }
}

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
                List.of("MainProgram.Tag1", "MainProgram.Tag2", "Tag[0]:DINT:1", "%Tag[0]:DINT:1"),
                registry.fields(
                        registry.conditional("host", "string", "设备地址", false, "127.0.0.1", null,
                                "connection", "plc4xConnectionString empty"),
                        registry.field("port", "number", "端口（显式连接串存在时忽略）", false, "44818", null, "connection"),
                        registry.field("communicationPath", "string", "路由（优先于 backplane/slot；PLC4X 0.13.0 格式为端口和地址成对的逗号序列）", false, "1,0", null, "protocol"),
                        registry.field("backplane", "number", "背板端口（仅自动路由）", false, "1", null, "protocol"),
                        registry.field("slot", "number", "槽位（仅自动路由）", false, "0", null, "protocol"),
                        registry.field("maxFieldsPerRequest", "number", "Max fields per request", false, "64", null, "advanced"),
                        registry.field("bigEndian", "boolean", "Big-endian mode", false, "true",
                                List.of("true", "false"), "advanced"),
                        registry.field("forceUnconnectedOperation", "boolean", "Force unconnected operation", false, "false",
                                List.of("true", "false"), "advanced"),
                        registry.field("tcpKeepAlive", "boolean", "TCP keep-alive", false, "false",
                                List.of("true", "false"), "advanced"),
                        registry.field("tcpNoDelay", "boolean", "TCP no-delay", false, "true",
                                List.of("true", "false"), "advanced"),
                        registry.field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        registry.field("readTimeout", "number", "读取超时（毫秒）", false, "30000", null, "advanced"),
                        registry.field("timeout", "number", "协议超时（毫秒）", false, "30000", null, "advanced")))
                .withDriverPrimarySchema("EIP driver type", driverDataTypes(), pointFields(registry)));
    }

    private List<String> driverDataTypes() {
        // PLC4X 0.13.0 EipTag/CIPDataTypeCode 与当前编解码器共同覆盖这些标量类型。
        return List.of("BOOL", "BYTE", "SINT", "USINT", "INT", "UINT", "WORD", "DINT",
                "UDINT", "DWORD", "LINT", "ULINT", "LWORD", "REAL", "LREAL", "STRING");
    }

    private List<ProtocolFieldConfig> pointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.arraySize", "number", "Array size", false, "",
                        Collections.emptyList(), "数组读取/写入严格匹配配置长度；数组点不支持缩放、偏移、精度、上下限和告警处理。", null)
        );
    }
}

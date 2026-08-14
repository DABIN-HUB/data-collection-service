package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.ads.AdsCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Beckhoff ADS 协议元数据提供者。
 */
@Component
@Order(80)
public class AdsProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("ADS", "Beckhoff ADS",
                "PLC4X-backed Beckhoff ADS / AMS collector.",
                List.of("AMS"), AdsCollector.class, "ADS", 48898,
                ProtocolAddressingMode.SYMBOLIC,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.UNSUPPORTED,
                List.of("MAIN.temperature", "0x4020/0x0:REAL", "16416/32:STRING(80)"),
                registry.fields(
                        registry.field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        registry.field("port", "number", "TCP port", false, "48898", null, "connection"),
                        registry.field("targetAmsNetId", "string", "Target AMS Net ID", true, "", null, "protocol"),
                        registry.field("targetAmsPort", "number", "Target AMS port", true, "851", null, "protocol"),
                        registry.field("sourceAmsNetId", "string", "Source AMS Net ID", true, "", null, "protocol"),
                        registry.field("sourceAmsPort", "number", "Source AMS port", true, "", null, "protocol"),
                        registry.field("loadSymbolAndDataTypeTables", "boolean", "Load symbol/data type tables", false, "true",
                                List.of("true", "false"), "advanced"),
                        registry.field("timeoutRequest", "number", "ADS request timeout (ms)", false, "4000", null, "advanced"),
                        registry.field("maxFieldsPerRequest", "number", "Max fields per request", false, "64", null, "advanced"),
                        registry.field("plc4xConnectionString", "string", "PLC4X connection string", false, "", null, "advanced"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("timeout", "number", "Protocol timeout (ms)", false, "5000", null, "advanced")))
                .withDriverPrimarySchema("ADS driver type", driverDataTypes(), pointFields(registry)));
    }

    private List<String> driverDataTypes() {
        return List.of(
                "BOOL", "BYTE", "SINT", "USINT", "INT", "UINT", "DINT", "UDINT",
                "LINT", "ULINT", "REAL", "LREAL", "STRING", "WSTRING");
    }

    private List<ProtocolFieldConfig> pointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.stringLength", "number", "String length", false, "",
                        Collections.emptyList(), "Used when driverDataType=STRING or WSTRING to declare the ADS string length.", "driverDataType=STRING/WSTRING"),
                registry.pointField("additionalConfig.arraySize", "number", "Array size", false, "",
                        Collections.emptyList(), "Element count for ADS array symbols or direct array addresses.", null)
        );
    }
}

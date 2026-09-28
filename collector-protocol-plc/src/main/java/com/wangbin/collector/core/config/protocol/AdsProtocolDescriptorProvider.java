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
                ProtocolAddressingMode.MIXED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.UNSUPPORTED,
                List.of("MAIN.temperature", "0x4020/0x0:REAL", "16416/32:STRING(80)"),
                registry.fields(
                        registry.conditional("host", "string", "ADS AUTO 设备地址", false, "127.0.0.1", null,
                                "connection", "plc4xConnectionString empty"),
                        registry.field("port", "number", "ADS AUTO TCP 端口（显式连接串忽略）", false, "48898", null, "connection"),
                        registry.conditional("targetAmsNetId", "string", "目标 AMS Net ID（六段）", false, "", null,
                                "protocol", "plc4xConnectionString empty"),
                        registry.field("targetAmsPort", "number", "目标 AMS 端口（TwinCAT Runtime 1 默认 851）", false, "851", null, "protocol"),
                        registry.conditional("sourceAmsNetId", "string", "本机源 AMS Net ID（六段）", false, "", null,
                                "protocol", "plc4xConnectionString empty"),
                        registry.conditional("sourceAmsPort", "number", "本机源 AMS 端口", false, "", null,
                                "protocol", "plc4xConnectionString empty"),
                        registry.field("loadSymbolAndDataTypeTables", "boolean", "预加载符号/类型表（关闭时按需解析 symbolic）", false, "true",
                                List.of("true", "false"), "advanced"),
                        registry.field("timeoutRequest", "number", "PLC4X ADS 请求超时（毫秒，须小于采集等待超时）", false, "4000", null, "advanced"),
                        registry.field("maxFieldsPerRequest", "number", "单批请求最大字段数（正整数）", false, "64", null, "advanced"),
                        registry.field("plc4xConnectionString", "string", "PLC4X ADS 完整覆盖连接串（不与 AUTO 参数混用）", false, "", null, "advanced"),
                        registry.field("readTimeout", "number", "Collector Future 等待超时（毫秒）", false, "30000", null, "advanced"),
                        registry.field("subscriptionEnabled", "boolean", "订阅开关：缺省跟随驱动能力，false 强制关闭", false, "",
                                List.of("true", "false"), "advanced"),
                        registry.field("subscriptionInterval", "number", "循环订阅默认周期（毫秒，正整数）", false, "2000", null, "advanced"),
                        registry.field("timeout", "number", "备用协议超时（毫秒）", false, "30000", null, "advanced")))
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

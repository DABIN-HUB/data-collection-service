package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.dlt645.Dlt645Collector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * DL/T 645 协议元数据提供者。
 */
@Component
@Order(150)
public class Dlt645ProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("DLT645_2007", "DL/T 645-2007",
                "电能表串行通信数据采集。",
                List.of("DLT645", "DL_T_645", "DLT_645_2007"), Dlt645Collector.class, "DLT645_2007", null,
                ProtocolAddressingMode.SYMBOLIC,
                ProtocolCapabilityState.EXPERIMENTAL,
                ProtocolCapabilityState.EXPERIMENTAL,
                ProtocolCapabilityState.UNSUPPORTED,
                ProtocolCapabilityState.UNSUPPORTED,
                List.of("00010000", "02010100"),
                registry.fields(
                        registry.field("serialPort", "string", "串口名称", true, "COM1", null, "connection"),
                        registry.field("baudRate", "number", "波特率", true, "2400", null, "connection"),
                        registry.field("dataBits", "number", "数据位", true, "8", null, "connection"),
                        registry.field("stopBits", "number", "停止位", true, "1", null, "connection"),
                        registry.field("parity", "select", "校验位", true, "EVEN", List.of("NONE", "EVEN", "ODD"), "connection"),
                        registry.field("meterAddress", "string", "电表通信地址", true, "000000000001", null, "protocol"),
                        registry.field("readTimeout", "number", "读取超时（毫秒）", false, "3000", null, "advanced"),
                        registry.field("writeTimeout", "number", "写入超时（毫秒）", false, "3000", null, "advanced"),
                        registry.field("retryCount", "number", "重试次数", false, "2", null, "advanced"),
                        registry.field("wakeupByteCount", "number", "唤醒字节数", false, "4", null, "advanced"),
                        registry.field("interFrameDelayMs", "number", "帧间隔（毫秒）", false, "20", null, "advanced"),
                        registry.field("writeEnabled", "boolean", "允许远程写入", false, "false", List.of("true", "false"), "security"),
                        registry.field("writePasswordHex", "password", "写入密码（十六进制）", false, "", null, "security"),
                        registry.field("operatorCodeHex", "password", "操作者代码（十六进制）", false, "", null, "security")))
                .withPointFields(pointFields(registry)));
    }

    private List<ProtocolFieldConfig> pointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.valueType", "select", "原始值类型", false, "BCD",
                        List.of("BCD", "DECIMAL", "UINT_LE", "INT_LE", "FLOAT_LE", "ASCII", "DATETIME", "HEX"),
                        "数据标识对应的数据区解析类型。", null),
                registry.pointField("additionalConfig.dataFormat", "string", "BCD 数据格式", false, "",
                        Collections.emptyList(), "例如 XXXXXX.XX，用于确定小数位。", null),
                registry.pointField("additionalConfig.valueIndex", "number", "值序号", false, "0",
                        Collections.emptyList(), "响应包含多个值时，从零开始选择目标值。", null)
        );
    }
}

package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.iec101.Iec101Collector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * IEC 60870-5-101 协议元数据提供者。
 */
@Component
@Order(160)
public class Iec101ProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("IEC101", "IEC 60870-5-101",
                "非平衡式主站串行遥测与遥控。",
                List.of("IEC_101", "IEC60870_5_101"), Iec101Collector.class, "IEC101", null,
                ProtocolAddressingMode.MIXED,
                ProtocolCapabilityState.EXPERIMENTAL,
                ProtocolCapabilityState.EXPERIMENTAL,
                ProtocolCapabilityState.EXPERIMENTAL,
                ProtocolCapabilityState.UNSUPPORTED,
                List.of("M_SP_NA_1:1", "M_ME_NC_1:100"),
                registry.fields(
                        registry.field("serialPort", "string", "串口名称", true, "COM1", null, "connection"),
                        registry.field("baudRate", "number", "波特率", true, "9600", null, "connection"),
                        registry.field("dataBits", "number", "数据位", true, "8", null, "connection"),
                        registry.field("stopBits", "number", "停止位", true, "1", null, "connection"),
                        registry.field("parity", "select", "校验位", true, "EVEN", List.of("NONE", "EVEN", "ODD"), "connection"),
                        registry.field("linkMode", "select", "链路模式", true, "UNBALANCED", List.of("UNBALANCED"), "protocol"),
                        registry.field("linkAddress", "number", "链路地址", true, "1", null, "protocol"),
                        registry.field("commonAddress", "number", "公共地址", true, "1", null, "protocol"),
                        registry.field("linkAddressSize", "select", "链路地址长度", true, "1", List.of("1", "2"), "protocol"),
                        registry.field("causeOfTransmissionSize", "select", "传送原因长度", true, "2", List.of("1", "2"), "protocol"),
                        registry.field("commonAddressSize", "select", "公共地址长度", true, "2", List.of("1", "2"), "protocol"),
                        registry.field("informationObjectAddressSize", "select", "信息体地址长度", true, "3", List.of("1", "2", "3"), "protocol"),
                        registry.field("readTimeout", "number", "读取超时（毫秒）", false, "3000", null, "advanced"),
                        registry.field("retryCount", "number", "重试次数", false, "2", null, "advanced"),
                        registry.field("interFrameDelayMs", "number", "帧间隔（毫秒）", false, "20", null, "advanced"),
                        registry.field("class1PollIntervalMs", "number", "一级数据轮询周期（毫秒）", false, "1000", null, "subscription"),
                        registry.field("class2PollIntervalMs", "number", "二级数据轮询周期（毫秒）", false, "5000", null, "subscription"),
                        registry.field("generalInterrogationOnConnect", "boolean", "连接后总召唤", false, "true", List.of("true", "false"), "subscription"),
                        registry.field("clockSyncOnConnect", "boolean", "连接后时钟同步", false, "false", List.of("true", "false"), "subscription")))
                .withPointFields(pointFields(registry)));
    }

    private List<ProtocolFieldConfig> pointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.typeId", "number", "类型标识", false, "",
                        Collections.emptyList(), "IEC101 信息体类型标识。", null),
                registry.pointField("additionalConfig.writeAddress", "string", "写入地址", false, "",
                        Collections.emptyList(), "遥控或设点命令使用的信息体地址。", null),
                registry.pointField("additionalConfig.writeSelect", "boolean", "预置后执行", false, "false",
                        List.of("true", "false"), "启用后先发送选择命令，再发送执行命令。", null),
                registry.pointField("additionalConfig.writeQualifier", "number", "命令限定词", false, "0",
                        Collections.emptyList(), "遥控或设点命令使用的限定词。", null)
        );
    }
}

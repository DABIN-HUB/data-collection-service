package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.iec.Iec104Collector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * IEC 规约族元数据提供者。
 */
@Component
@Order(140)
public class IecProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("IEC104", "IEC 60870-5-104",
                "IEC104 常用遥信、遥测、电度采集与遥控/设点；完整标准高级能力有边界。",
                List.of("IEC_104"), Iec104Collector.class, "IEC104", 2404,
                ProtocolAddressingMode.MIXED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.RUNTIME_DEPENDENT,
                ProtocolCapabilityState.UNSUPPORTED,
                List.of("M_SP_NA_1:1", "M_ME_NC_1:100"),
                registry.fields(
                        registry.field("host", "string", "Device host", true, "127.0.0.1", null, "connection"),
                        registry.field("port", "number", "Port", true, "2404", null, "connection"),
                        registry.field("slaveId", "number", "Common address", true, "1", null, "protocol"),
                        registry.field("timeout", "number", "Protocol timeout (ms)", true, "5000", null, "advanced"),
                        registry.field("generalInterrogationMode", "select", "读取策略", false, "AUTO",
                                List.of("AUTO", "READ_COMMAND", "GENERAL_INTERROGATION", "SPONTANEOUS"), "protocol",
                                "AUTO 保持历史行为；仅支持总召的设备使用 GENERAL_INTERROGATION。"),
                        registry.field("generalInterrogationOnConnect", "boolean", "连接后总召唤", false, "true",
                                List.of("true", "false"), "subscription"),
                        registry.field("generalInterrogationInterval", "number", "总召唤周期（毫秒）", false, "",
                                Collections.emptyList(), "subscription"),
                        registry.field("generalInterrogationQualifier", "number", "总召唤限定词", false, "20",
                                Collections.emptyList(), "subscription"),
                        registry.field("singleInterrogationOnReadMiss", "boolean", "缓存失效时单组总召唤", false, "true",
                                List.of("true", "false"), "subscription"),
                        registry.field("cacheTtl", "number", "协议样本缓存有效期（毫秒）", false, "",
                                Collections.emptyList(), "subscription"),
                        registry.field("cotFieldLength", "select", "COT 字段长度", false, "2", List.of("1", "2"), "advanced"),
                        registry.field("commonAddressFieldLength", "select", "公共地址字段长度", false, "2", List.of("1", "2"), "advanced"),
                        registry.field("ioaFieldLength", "select", "IOA 字段长度", false, "3", List.of("1", "2", "3"), "advanced"),
                        registry.field("ioaEncodingMode", "select", "IOA 编码模式", false, "STANDARD",
                                List.of("STANDARD", "SHIFT8_COMPAT"), "advanced", "标准设备使用 STANDARD；仅兼容特定模拟器或厂家设备时使用 SHIFT8_COMPAT。")))
                .withPointFields(iec104PointFields(registry)));

    }

    private List<ProtocolFieldConfig> iec104PointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.typeId", "number", "Type ID", false, "",
                        Collections.emptyList(), "IEC104 information object type identifier.", null),
                registry.pointField("additionalConfig.iecTypeId", "number", "IEC type alias", false, "",
                        Collections.emptyList(), "Compatibility alias for existing IEC104 type-id configuration.", null),
                registry.pointField("additionalConfig.writeAddress", "string", "Write address", false, "",
                        Collections.emptyList(), "Target write address used for control commands.", null),
                registry.pointField("additionalConfig.writeCommonAddress", "number", "Write common address", false, "",
                        Collections.emptyList(), "Common address used for control commands.", null),
                registry.pointField("additionalConfig.writeQl", "number", "Write quality", false, "",
                        Collections.emptyList(), "Quality descriptor used for control commands.", null),
                registry.pointField("additionalConfig.writeSelect", "boolean", "Select before execute (unsupported)", false, "false",
                        List.of("false"), "Confirmed select-before-execute is not implemented; true is rejected before sending.", null)
        );
    }

}

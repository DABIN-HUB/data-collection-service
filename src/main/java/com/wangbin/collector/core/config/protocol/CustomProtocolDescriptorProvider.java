package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.custom.CustomProtocolCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自定义 TCP/UDP 协议元数据提供者。
 */
@Component
@Order(200)
public class CustomProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("CUSTOM_TCP", "Custom TCP",
                "受控模板和帧编解码驱动的自定义TCP请求响应协议。",
                List.of(), CustomProtocolCollector.class, "CUSTOM_TCP", null,
                ProtocolAddressingMode.MIXED,
                ProtocolCapabilityState.EXPERIMENTAL,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.UNSUPPORTED,
                ProtocolCapabilityState.UNSUPPORTED,
                List.of("BYTE:0:2", "BIT:2:3", "JSON:$.data.value"),
                customConnectionFields(registry, false))
                .withPointFields(customPointFields(registry)));
        registry.registerPrimary(registry.descriptor("CUSTOM_UDP", "Custom UDP",
                "以单个数据报为完整帧的自定义UDP请求响应协议。",
                List.of(), CustomProtocolCollector.class, "CUSTOM_UDP", null,
                ProtocolAddressingMode.MIXED,
                ProtocolCapabilityState.EXPERIMENTAL,
                ProtocolCapabilityState.SUPPORTED,
                ProtocolCapabilityState.UNSUPPORTED,
                ProtocolCapabilityState.UNSUPPORTED,
                List.of("BYTE:0:4", "BIT:4:0", "JSON:$.value"),
                customConnectionFields(registry, true))
                .withPointFields(customPointFields(registry)));
    }

    private List<ProtocolFieldConfig> customConnectionFields(ProtocolDescriptorRegistry registry, boolean udp) {
        List<ProtocolFieldConfig> configured = new ArrayList<>();
        configured.add(registry.field("host", "string", "设备主机", true, "127.0.0.1", null, "connection"));
        configured.add(registry.field("port", "number", "设备端口", true, "", null, "connection"));
        configured.add(registry.field("readRequestTemplate", "textarea", "读取请求模板", true, "", null, "request"));
        configured.add(registry.field("writeRequestTemplate", "textarea", "写入请求模板", false, "", null, "request"));
        configured.add(registry.field("requestEncoding", "select", "请求编码", true, "HEX",
                List.of("HEX", "TEXT", "BASE64"), "request"));
        configured.add(registry.field("writeRequestEncoding", "select", "写入请求编码", false, "HEX",
                List.of("HEX", "TEXT", "BASE64"), "request"));
        configured.add(registry.field("writeExpectResponse", "boolean", "写入等待响应", false, "true",
                List.of("true", "false"), "request"));
        configured.add(registry.field("writeSuccessHex", "string", "写入成功响应前缀", false, "", null, "request"));
        configured.add(registry.field("charset", "string", "字符集", false, "UTF-8", null, "request"));
        configured.add(registry.field("readTimeout", "number", "读取超时（毫秒）", false, "5000", null, "advanced"));
        configured.add(registry.field("bufferSize", "number", "接收缓冲区大小", false, "8192", null, "advanced"));
        if (!udp) {
            configured.add(registry.field("frameMode", "select", "帧边界模式", true, "LENGTH_FIELD",
                    List.of("LENGTH_FIELD", "FIXED_LENGTH", "DELIMITER"), "protocol"));
            configured.add(registry.field("fixedFrameLength", "number", "固定帧长度", false, "", null, "protocol"));
            configured.add(registry.field("delimiterHex", "string", "分隔符十六进制", false, "0A", null, "protocol"));
            configured.add(registry.field("lengthFieldOffset", "number", "长度字段偏移", false, "0", null, "protocol"));
            configured.add(registry.field("lengthFieldLength", "select", "长度字段字节数", false, "4",
                    List.of("1", "2", "4", "8"), "protocol"));
            configured.add(registry.field("lengthAdjustment", "number", "长度修正值", false, "0", null, "protocol"));
            configured.add(registry.field("initialBytesToStrip", "number", "响应剥离字节数", false, "4", null, "protocol"));
            configured.add(registry.field("prependLengthField", "boolean", "请求添加长度字段", false, "true",
                    List.of("true", "false"), "protocol"));
        }
        return List.copyOf(configured);
    }

    private List<ProtocolFieldConfig> customPointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.requestTemplate", "textarea", "读取请求模板", false, "",
                        Collections.emptyList(), "可覆盖连接级读取模板，仅支持固定占位符，不执行脚本。", null),
                registry.pointField("additionalConfig.writeRequestTemplate", "textarea", "写入请求模板", false, "",
                        Collections.emptyList(), "写入模板可使用value和valueHex等受控占位符。", null),
                registry.pointField("additionalConfig.requestAddress", "string", "协议请求地址", false, "",
                        Collections.emptyList(), "与响应解析地址分离的设备原始地址。", null),
                registry.pointField("additionalConfig.addressHex", "string", "请求地址十六进制", false, "",
                        Collections.emptyList(), "直接写入模板addressHex占位符的十六进制内容。", null),
                registry.pointField("additionalConfig.byteOrder", "select", "字节序", false, "BIG_ENDIAN",
                        List.of("BIG_ENDIAN", "LITTLE_ENDIAN"), "数值解析和valueHex编码使用的字节序。", null),
                registry.pointField("additionalConfig.length", "number", "解析长度", false, "",
                        Collections.emptyList(), "字符串或变长字段的解析字节数。", null),
                registry.pointField("additionalConfig.charset", "string", "字符集", false, "UTF-8",
                        Collections.emptyList(), "文本模板、字符串值和JSON响应使用的字符集。", null),
                registry.pointField("additionalConfig.writeExpectResponse", "boolean", "写入等待响应", false, "true",
                        List.of("true", "false"), "关闭后写入仅发送请求，不等待响应。", null),
                registry.pointField("additionalConfig.writeSuccessHex", "string", "写入成功响应前缀", false, "",
                        Collections.emptyList(), "配置后仅响应十六进制以前缀开头时判定成功。", null)
        );
    }
}

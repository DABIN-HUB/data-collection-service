package com.wangbin.collector.core.collector.protocol.mqtt;

import java.util.Map;

/** MQTT 主题模板解析器，仅支持协议约定的稳定占位符。 */
public final class ProtocolTemplateResolver {
    private ProtocolTemplateResolver() {}

    public static String resolve(String template, Map<String, ?> values) {
        if (template == null || values == null) return template;
        String result = template;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().toString();
            result = result.replace("${" + entry.getKey() + "}", value)
                    .replace("{" + entry.getKey() + "}", value);
        }
        return result;
    }
}

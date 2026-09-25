package com.wangbin.collector.core.collector.protocol.mqtt;

import com.wangbin.collector.core.config.validator.MqttConfigurationContract;

import java.util.Map;

/** MQTT 主题模板解析器，仅支持协议约定的稳定占位符。 */
public final class ProtocolTemplateResolver {
    private ProtocolTemplateResolver() {}

    public static String resolve(String template, Map<String, ?> values) {
        return MqttConfigurationContract.resolveTopic(template, values);
    }
}

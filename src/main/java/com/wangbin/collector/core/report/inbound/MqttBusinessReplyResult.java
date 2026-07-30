package com.wangbin.collector.core.report.inbound;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MQTT 业务回执处理结果，不会再向平台发送二次响应。
 */
public record MqttBusinessReplyResult(
        String method,
        int code,
        String message,
        Map<String, Object> data) {

    public MqttBusinessReplyResult {
        data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
    }

    public static MqttBusinessReplyResult success(String method, Map<String, Object> data) {
        return new MqttBusinessReplyResult(method, 0, "success", data);
    }

    public static MqttBusinessReplyResult ignored(String method) {
        return new MqttBusinessReplyResult(method, 0, "ignored", Map.of());
    }

    public static MqttBusinessReplyResult failure(String method, String message) {
        return new MqttBusinessReplyResult(method, 500, message, Map.of());
    }
}

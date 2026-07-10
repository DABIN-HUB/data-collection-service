package com.wangbin.collector.core.report.inbound;

import java.util.Arrays;

/**
 * MQTT 入站消息快照，避免业务层直接依赖 Paho 消息对象。
 */
public record MqttInboundMessage(
        String topic,
        byte[] payload,
        int qos,
        String cloudProvider) {

    public MqttInboundMessage {
        payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}

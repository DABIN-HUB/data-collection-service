package com.wangbin.collector.core.report.inbound;

/**
 * 平台业务 ACK 解析结果。
 */
public record MqttAckReply(
        String messageId,
        int code,
        String message) {
}

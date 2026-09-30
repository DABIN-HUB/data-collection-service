package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.core.report.outbox.CloudOutboxMessage;
import com.wangbin.collector.core.report.outbox.CloudOutboxStatus;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CloudOutboxItemResponse {
    String messageId;
    String localDeviceId;
    String cloudDeviceId;
    String gatewayDeviceId;
    CloudOutboxStatus status;
    int retryCount;
    long createdAt;
    long nextAttemptAt;
    String lastError;

    public static CloudOutboxItemResponse from(CloudOutboxMessage message) {
        return builder().messageId(message.getMessageId()).localDeviceId(message.getLocalDeviceId())
                .cloudDeviceId(message.getDeviceName()).gatewayDeviceId(message.getGatewayDeviceId())
                .status(message.getStatus()).retryCount(message.getAttempts()).createdAt(message.getCreatedAt())
                .nextAttemptAt(message.getNextAttemptAt()).lastError(message.getLastError()).build();
    }
}

package com.wangbin.collector.common.domain.alert;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * 告警通知数据，供质量处理、告警监控、历史存储和云端上报共享。
 */
@Value
@Builder
@Jacksonized
public class AlertNotification {
    String deviceId;
    String deviceName;
    String pointId;
    String pointCode;
    String ruleId;
    String ruleName;
    String level;
    String message;
    String eventType;
    String eventId;
    String relatedEventId;
    long startedAt;
    long durationMillis;
    Object value;
    String unit;
    long timestamp;
}

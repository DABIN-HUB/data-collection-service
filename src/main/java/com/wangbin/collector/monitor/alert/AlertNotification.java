package com.wangbin.collector.monitor.alert;

import lombok.Builder;
import lombok.Value;

/**
 * 定义当前模块的业务组件。
 */
@Value
@Builder
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

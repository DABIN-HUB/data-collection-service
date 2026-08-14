package com.wangbin.collector.monitor.alert;

/**
 * 告警确认记录。
 */
public record AlarmAcknowledgement(String alarmId,
                                   String operator,
                                   long acknowledgedAt,
                                   String note,
                                   String idempotencyKey) {
}
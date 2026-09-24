package com.wangbin.collector.api.controller.dto;

import java.util.List;

/** 告警触发记录及其恢复、确认叠加结果。 */
public record AlarmLifecycleResponse(String status, List<AlarmItem> items, int count) {
    /** 同一告警事件的完整生命周期，确认与恢复为独立事实。 */
    public record AlarmItem(String alarmId, String deviceId, String deviceName, String pointId,
                            String pointCode, String ruleId, String ruleName, String level,
                            String message, Object value, String unit, String lifecycleState,
                            boolean acknowledged, Long acknowledgedAt, String acknowledgedBy,
                            String acknowledgementNote, long startedAt, long occurredAt,
                            long lastOccurredAt, Long recoveredAt, Long durationMillis) {
    }
}

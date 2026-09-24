package com.wangbin.collector.core.alarm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 可持久化的告警生命周期快照。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlarmStateSnapshot {

    private String stateKey;
    private AlarmLifecycleState lifecycleState;
    private long pendingSince;
    private long activeSince;
    private String alarmId;
    private long updatedAt;
    private long lastOccurredAt;
    private String ackedBy;
    private long ackedAt;
    private String ackNote;
    private String ackIdempotencyKey;
    private String deviceId;
    private String pointId;
    private String ruleId;
    private String ruleName;
    private long recoveredAt;

    public AlarmStateSnapshot(String stateKey, AlarmLifecycleState lifecycleState,
                              long pendingSince, long activeSince, String alarmId, long updatedAt) {
        this.stateKey = stateKey;
        this.lifecycleState = lifecycleState;
        this.pendingSince = pendingSince;
        this.activeSince = activeSince;
        this.alarmId = alarmId;
        this.updatedAt = updatedAt;
    }
}

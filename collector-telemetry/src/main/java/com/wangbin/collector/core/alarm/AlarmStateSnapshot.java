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
}

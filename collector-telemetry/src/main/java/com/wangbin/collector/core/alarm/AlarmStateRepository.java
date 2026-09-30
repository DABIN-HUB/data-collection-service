package com.wangbin.collector.core.alarm;

import java.util.Optional;

/**
 * 告警生命周期状态仓库。
 */
public interface AlarmStateRepository {

    /**
     * 查询并返回业务数据。
     */
    Optional<AlarmStateSnapshot> find(String stateKey);

    /** 按告警标识查询当前规则的快照；旧事件不得匹配到新事件。 */
    Optional<AlarmStateSnapshot> findByAlarmId(String alarmId);

    /**
     * 写入或持久化业务数据。
     */
    void save(AlarmStateSnapshot snapshot);
}

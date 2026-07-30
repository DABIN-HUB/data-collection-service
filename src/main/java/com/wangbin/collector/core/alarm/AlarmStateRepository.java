package com.wangbin.collector.core.alarm;

import java.util.Optional;

/**
 * 告警生命周期状态仓库。
 */
public interface AlarmStateRepository {

    Optional<AlarmStateSnapshot> find(String stateKey);

    void save(AlarmStateSnapshot snapshot);
}

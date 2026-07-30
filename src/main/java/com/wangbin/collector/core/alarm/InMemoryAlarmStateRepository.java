package com.wangbin.collector.core.alarm;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用于独立运行和单元测试的内存告警状态仓库。
 */
public class InMemoryAlarmStateRepository implements AlarmStateRepository {

    private final ConcurrentMap<String, AlarmStateSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public Optional<AlarmStateSnapshot> find(String stateKey) {
        return Optional.ofNullable(snapshots.get(stateKey));
    }

    @Override
    public void save(AlarmStateSnapshot snapshot) {
        if (snapshot != null && snapshot.getStateKey() != null) {
            snapshots.put(snapshot.getStateKey(), snapshot);
        }
    }
}

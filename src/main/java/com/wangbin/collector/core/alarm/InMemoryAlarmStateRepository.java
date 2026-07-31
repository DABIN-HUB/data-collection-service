package com.wangbin.collector.core.alarm;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用于独立运行和单元测试的内存告警状态仓库。
 */
public class InMemoryAlarmStateRepository implements AlarmStateRepository {

    private final ConcurrentMap<String, AlarmStateSnapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * 查询并返回业务数据。
     */
    @Override
    public Optional<AlarmStateSnapshot> find(String stateKey) {
        return Optional.ofNullable(snapshots.get(stateKey));
    }

    /**
     * 写入或持久化业务数据。
     */
    @Override
    public void save(AlarmStateSnapshot snapshot) {
        if (snapshot != null && snapshot.getStateKey() != null) {
            snapshots.put(snapshot.getStateKey(), snapshot);
        }
    }
}

package com.wangbin.collector.core.alarm;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用于独立运行和单元测试的内存告警状态仓库。
 */
public class InMemoryAlarmStateRepository implements AlarmStateRepository {

    private final ConcurrentMap<String, AlarmStateSnapshot> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> alarmKeys = new ConcurrentHashMap<>();

    /**
     * 查询并返回业务数据。
     */
    @Override
    public Optional<AlarmStateSnapshot> find(String stateKey) {
        return Optional.ofNullable(snapshots.get(stateKey));
    }

    @Override
    public Optional<AlarmStateSnapshot> findByAlarmId(String alarmId) {
        if (alarmId == null) {
            return Optional.empty();
        }
        String stateKey = alarmKeys.get(alarmId);
        return stateKey == null ? Optional.empty()
                : find(stateKey).filter(snapshot -> alarmId.equals(snapshot.getAlarmId()));
    }

    /**
     * 写入或持久化业务数据。
     */
    @Override
    public void save(AlarmStateSnapshot snapshot) {
        if (snapshot != null && snapshot.getStateKey() != null) {
            AlarmStateSnapshot previous = snapshots.put(snapshot.getStateKey(), snapshot);
            if (previous != null && previous.getAlarmId() != null
                    && !previous.getAlarmId().equals(snapshot.getAlarmId())) {
                alarmKeys.remove(previous.getAlarmId(), snapshot.getStateKey());
            }
            if (snapshot.getAlarmId() != null) {
                alarmKeys.put(snapshot.getAlarmId(), snapshot.getStateKey());
            }
        }
    }
}

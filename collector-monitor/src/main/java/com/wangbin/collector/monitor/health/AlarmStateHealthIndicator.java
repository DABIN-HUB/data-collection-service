package com.wangbin.collector.monitor.health;

import com.wangbin.collector.core.alarm.AlarmStateProperties;
import com.wangbin.collector.core.alarm.RedisAlarmStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 告警状态持久化健康指标。
 */
@Component("alarmState")
@RequiredArgsConstructor
public class AlarmStateHealthIndicator implements HealthIndicator {

    private static final int BACKLOG_BATCH_MULTIPLIER = 10;
    private final RedisAlarmStateRepository alarmStateRepository;
    private final AlarmStateProperties properties;

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public Health health() {
        int pending = alarmStateRepository.getPendingWriteCount();
        int threshold = Math.max(1, properties.getRetryBatchSize()) * BACKLOG_BATCH_MULTIPLIER;
        return (pending >= threshold ? Health.down() : Health.up())
                .withDetail("是否启用", properties.isEnabled())
                .withDetail("待持久化数量", pending)
                .withDetail("积压告警阈值", threshold)
                .build();
    }
}

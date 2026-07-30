package com.wangbin.collector.monitor.health;

import com.wangbin.collector.core.config.manager.ConfigSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 配置同步健康指标。
 */
@Component("configSync")
@RequiredArgsConstructor
public class ConfigSyncHealthIndicator implements HealthIndicator {

    private static final int FAILURE_THRESHOLD = 3;

    private final ConfigSyncService configSyncService;

    @Override
    public Health health() {
        int failures = configSyncService.getConsecutiveFailures();
        Health.Builder builder = failures >= FAILURE_THRESHOLD ? Health.down() : Health.up();
        return builder
                .withDetail("连续失败次数", failures)
                .withDetail("最后成功时间", configSyncService.getLastSyncTime())
                .withDetail("最后失败时间", configSyncService.getLastFailureTime())
                .withDetail("配置版本", String.valueOf(configSyncService.getSourceVersion()))
                .withDetail("设备数量", configSyncService.getSnapshotDeviceCount())
                .build();
    }
}

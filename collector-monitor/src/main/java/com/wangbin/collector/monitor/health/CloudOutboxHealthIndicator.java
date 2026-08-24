package com.wangbin.collector.monitor.health;

import com.wangbin.collector.core.report.outbox.CloudOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 云端上报持久化发件箱健康指标。
 */
@Component("cloudOutbox")
@RequiredArgsConstructor
public class CloudOutboxHealthIndicator implements HealthIndicator {

    private final CloudOutboxService cloudOutboxService;

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public Health health() {
        long pendingCount = cloudOutboxService.getPendingCount();
        long isolatedCount = cloudOutboxService.getIsolatedCount();
        long oldestAge = cloudOutboxService.getOldestMessageAgeMillis();
        Health.Builder builder;
        if (pendingCount < 0L || isolatedCount < 0L || oldestAge < 0L) {
            builder = Health.unknown();
        } else {
            builder = isolatedCount > 0L ? Health.down() : Health.up();
        }
        return builder
                .withDetail("是否启用", cloudOutboxService.isEnabled())
                .withDetail("待发送数量", pendingCount)
                .withDetail("隔离数量", isolatedCount)
                .withDetail("最老消息等待毫秒", oldestAge)
                .build();
    }
}

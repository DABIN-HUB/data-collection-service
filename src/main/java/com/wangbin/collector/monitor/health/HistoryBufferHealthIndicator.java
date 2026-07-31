package com.wangbin.collector.monitor.health;

import com.wangbin.collector.storage.buffer.HistoryBufferMetrics;
import com.wangbin.collector.storage.buffer.HistoryWriteBuffer;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * 历史数据失败缓冲健康指标。
 */
@Component("historyBuffer")
@ConditionalOnBean(HistoryWriteBuffer.class)
@RequiredArgsConstructor
public class HistoryBufferHealthIndicator implements HealthIndicator {

    private static final double LOCAL_QUEUE_WARNING_RATIO = 0.9D;
    private final HistoryWriteBuffer historyWriteBuffer;

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public Health health() {
        HistoryBufferMetrics metrics = historyWriteBuffer.metrics();
        double localUsage = metrics.localCapacity() <= 0
                ? 0D : (double) metrics.localPending() / metrics.localCapacity();
        Health.Builder builder;
        if (metrics.redisPending() < 0L) {
            builder = localUsage >= LOCAL_QUEUE_WARNING_RATIO ? Health.down() : Health.unknown();
        } else if (metrics.redisDeadLetter() > 0L || localUsage >= LOCAL_QUEUE_WARNING_RATIO) {
            builder = Health.down();
        } else {
            builder = Health.up();
        }
        return builder
                .withDetail("Redis待写数量", metrics.redisPending())
                .withDetail("Redis处理中数量", metrics.redisProcessing())
                .withDetail("Redis隔离数量", metrics.redisDeadLetter())
                .withDetail("本地待写数量", metrics.localPending())
                .withDetail("本地队列容量", metrics.localCapacity())
                .build();
    }
}

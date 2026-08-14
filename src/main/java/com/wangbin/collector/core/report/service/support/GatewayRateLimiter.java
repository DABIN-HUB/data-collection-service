
package com.wangbin.collector.core.report.service.support;

import com.google.common.util.concurrent.RateLimiter;
import com.wangbin.collector.core.report.config.ReportProperties;
import org.springframework.stereotype.Component;

/**
 * 限制网关上报流量，避免压垮消息代理。
 */
@Component
public class GatewayRateLimiter {

    private final RateLimiter rateLimiter;

    /**
     * 创建当前组件实例。
     */
    public GatewayRateLimiter(ReportProperties reportProperties) {
        int maxPerSecond = reportProperties.getMaxGatewayMessagesPerSecond();
        this.rateLimiter = maxPerSecond > 0 ? RateLimiter.create(maxPerSecond) : null;
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean tryAcquire(boolean highPriority) {
        if (highPriority || rateLimiter == null) {
            return true;
        }
        return rateLimiter.tryAcquire();
    }
}

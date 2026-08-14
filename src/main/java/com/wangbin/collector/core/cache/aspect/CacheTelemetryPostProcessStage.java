package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 定义当前模块的业务组件。
 */
@Component
@Order(10)
@RequiredArgsConstructor
class CacheTelemetryPostProcessStage implements TelemetryPostProcessStage {

    private final MultiLevelCacheManager multiLevelCacheManager;

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public TelemetryStageType type() {
        return TelemetryStageType.CACHE;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public String name() {
        return "cache";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public boolean enabled(TelemetryPostProcessContext context) {
        return context.cacheValue() != null && context.point() != null && context.point().needCache();
    }

    /**
     * 处理当前业务流程。
     */
    @Override
    public void process(TelemetryPostProcessContext context) {
        DataPoint point = context.point();
        CacheKey cacheKey = CacheKey.dataKey(context.deviceId(), point.getPointId());
        multiLevelCacheManager.put(cacheKey, context.cacheValue(), getCacheExpireTime(point));
    }

    private long getCacheExpireTime(DataPoint point) {
        if (point.getCacheDuration() != null && point.getCacheDuration() > 0) {
            return point.getCacheDuration() * 1000L;
        }
        if (point.getPriority() != null) {
            if (point.getPriority() <= 3) {
                return 7_200_000L;
            }
            if (point.getPriority() <= 7) {
                return 3_600_000L;
            }
        }
        return 1_800_000L;
    }
}

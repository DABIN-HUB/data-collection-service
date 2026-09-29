package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.cache.realtime.RealtimeChangeTracker;
import com.wangbin.collector.core.collector.runtime.AcquisitionRuntimeTracker;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 定义当前模块的业务组件。
 */
@Component
@Order(10)
@Slf4j
class CacheTelemetryPostProcessStage implements TelemetryPostProcessStage {

    private final MultiLevelCacheManager multiLevelCacheManager;
    private final RealtimeChangeTracker realtimeChangeTracker;
    private final AcquisitionRuntimeTracker acquisitionRuntimeTracker;

    @Autowired
    CacheTelemetryPostProcessStage(MultiLevelCacheManager multiLevelCacheManager,
                                   RealtimeChangeTracker realtimeChangeTracker,
                                   AcquisitionRuntimeTracker acquisitionRuntimeTracker) {
        this.multiLevelCacheManager = multiLevelCacheManager;
        this.realtimeChangeTracker = realtimeChangeTracker;
        this.acquisitionRuntimeTracker = acquisitionRuntimeTracker;
    }

    CacheTelemetryPostProcessStage(MultiLevelCacheManager multiLevelCacheManager,
                                   RealtimeChangeTracker realtimeChangeTracker) {
        this(multiLevelCacheManager, realtimeChangeTracker, null);
    }

    /**
     * 测试兼容构造器，用于不关注实时增量跟踪的历史管线单测。
     */
    CacheTelemetryPostProcessStage(MultiLevelCacheManager multiLevelCacheManager) {
        this(multiLevelCacheManager, new RealtimeChangeTracker());
    }

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
        boolean success = multiLevelCacheManager.put(cacheKey, context.cacheValue(), getCacheExpireTime(point));
        if (!success) {
            return;
        }
        ProcessResult result = context.processResult();
        if (acquisitionRuntimeTracker != null && result != null) {
            acquisitionRuntimeTracker.recordCachedPoint(context.deviceId(), point, context.generation(),
                    result.isSuccess() && result.isQualityAcceptable() && result.getFinalValue() != null,
                    result.getQuality(), System.currentTimeMillis());
        }
        try {
            realtimeChangeTracker.record(context.deviceId(), point.getPointId(), context.cacheValue());
        } catch (Exception exception) {
            log.warn("实时变更跟踪失败，设备={}，点位={}", context.deviceId(), point.getPointId(), exception);
            try {
                realtimeChangeTracker.invalidateSnapshot();
            } catch (Exception invalidateException) {
                log.warn("实时变更跟踪降级失败，设备={}，点位={}", context.deviceId(), point.getPointId(), invalidateException);
            }
        }
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

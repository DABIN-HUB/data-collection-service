package com.wangbin.collector.api.application;

import com.wangbin.collector.core.cache.realtime.RealtimeChangeTracker;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 实时紧凑快照配置变更监听器。
 *
 * <p>配置变化可能影响点位静态字段、点位新增删除和设备删除，因此统一提升配置纪元并要求客户端完整同步。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeChangeTrackerConfigListener {

    private final RealtimeChangeTracker realtimeChangeTracker;
    private final MultiLevelCacheManager cacheManager;
    private final CollectionTaskGuard taskGuard;

    /**
     * 处理配置更新事件。
     *
     * @param event 配置更新事件
     */
    @EventListener
    public void handleConfigUpdate(ConfigUpdateEvent event) {
        if (event == null) {
            return;
        }
        try {
            invalidateRetiredPoints(event);
        } finally {
            // 即使某个缓存层暂不可用，也不能继续接受旧客户端的配置游标。
            realtimeChangeTracker.invalidateConfiguration();
        }
        log.debug("实时紧凑快照配置纪元已更新，类型={}，设备={}", event.getConfigType(), event.getDeviceId());
    }

    private void invalidateRetiredPoints(ConfigUpdateEvent event) {
        String deviceId = event.getDeviceId();
        Long version = event.getConfigVersion();
        if (deviceId == null || deviceId.isBlank() || version == null || version <= 0L
                || event.getRetiredPointIds().isEmpty()) return;
        taskGuard.runDeviceScoped(deviceId, () -> {
            for (String pointId : event.getRetiredPointIds()) {
                if (pointId == null || pointId.isBlank()) continue;
                boolean deleted = cacheManager.deleteIf(CacheKey.dataKey(deviceId, pointId),
                        value -> !belongsToNewConfiguration(value, version));
                if (!deleted) {
                    log.warn("废弃点位实时缓存失效未全部完成，设备={}，点位={}，配置版本={}",
                            deviceId, pointId, version);
                }
            }
        });
    }

    /** 旧事件可以清理旧值，但不能删除同一设备重建后已经提交的新值。 */
    private boolean belongsToNewConfiguration(Object value, long eventVersion) {
        if (!(value instanceof ProcessResult result)) return false;
        Object sourceVersion = result.getMetadata(ProcessResultMetadataKeys.CONFIG_VERSION);
        return taskGuard.runtimeId().equals(result.getMetadata(ProcessResultMetadataKeys.RUNTIME_ID))
                && sourceVersion instanceof Number version && version.longValue() >= eventVersion;
    }
}

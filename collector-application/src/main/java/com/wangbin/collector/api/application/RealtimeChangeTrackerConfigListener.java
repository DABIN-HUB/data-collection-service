package com.wangbin.collector.api.application;

import com.wangbin.collector.core.cache.realtime.RealtimeChangeTracker;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
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
        realtimeChangeTracker.invalidateConfiguration();
        log.debug("实时紧凑快照配置纪元已更新，类型={}，设备={}", event.getConfigType(), event.getDeviceId());
    }
}

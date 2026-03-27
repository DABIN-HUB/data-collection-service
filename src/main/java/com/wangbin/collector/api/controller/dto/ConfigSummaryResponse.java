package com.wangbin.collector.api.controller.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 配置治理概览响应
 */
@Data
@Builder
public class ConfigSummaryResponse {

    /**
     * ConfigManager 缓存统计
     */
    private Map<String, Object> cacheStats;

    /**
     * 最近一次触发同步的时间戳（毫秒）
     */
    private Long lastSyncTime;

    /**
     * 预计下一次自动同步时间戳（毫秒）
     */
    private Long nextSyncTime;

    /**
     * ConfigSyncService 配置的同步间隔（毫秒）
     */
    private Long syncInterval;

    /**
     * 当前采集实例 serviceId
     */
    private String serviceId;

    /**
     * 已注册的配置变更监听器数量
     */
    private Integer listenerCount;
}

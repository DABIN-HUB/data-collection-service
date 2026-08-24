package com.wangbin.collector.api.controller.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 配置同步状态响应数据。
 */
@Data
@Builder
public class ConfigSyncStatusResponse {

    /**
     * 当前服务实例标识。
     */
    private String serviceId;

    /**
     * 最近一次同步时间戳。
     */
    private Long lastSyncTime;

    /**
     * 同步间隔，单位毫秒。
     */
    private Long syncInterval;

    /**
     * 配置变更监听器数量。
     */
    private Integer listenerCount;

    /**
     * 连续失败次数。
     */
    private Integer consecutiveFailures;

    /**
     * 最近一次失败时间戳。
     */
    private Long lastFailureTime;

    /**
     * 配置源版本。
     */
    private String sourceVersion;

    /**
     * 快照中的设备数量。
     */
    private Integer snapshotDeviceCount;
}

package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.Builder;
import lombok.Data;

/**
 * 单设备配置对比响应数据。
 */
@Data
@Builder
public class DeviceConfigDetailResponse {

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 本地运行配置。
     */
    private DeviceInfo local;

    /**
     * 远端配置快照。
     */
    private DeviceInfo remote;

    /**
     * 本地和远端是否一致。
     */
    private Boolean inSync;
}

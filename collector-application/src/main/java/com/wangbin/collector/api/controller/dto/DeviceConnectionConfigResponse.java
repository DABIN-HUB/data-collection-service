package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import lombok.Builder;
import lombok.Data;

/**
 * 设备连接配置响应数据。
 */
@Data
@Builder
public class DeviceConnectionConfigResponse {

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 已脱敏的连接配置。
     */
    private DeviceConnection connection;
}

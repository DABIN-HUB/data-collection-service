package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * 设备标识类操作响应数据。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceIdResponse {

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 配置来源。
     */
    private String configSource;

    /**
     * 是否为本地临时配置。
     */
    private Boolean temporaryConfig;

    /**
     * 保存后是否启动。
     */
    private Boolean started;

    /**
     * 点位数量。
     */
    private Integer pointCount;

    /**
     * 兼容配置更新接口的数量字段。
     */
    private Integer count;
}
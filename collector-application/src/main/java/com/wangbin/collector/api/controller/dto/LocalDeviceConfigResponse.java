package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * 本地临时设备配置响应数据。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LocalDeviceConfigResponse {

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
     * 设备配置包。
     */
    private ConfigBundle bundle;

    /**
     * 保存后是否启动成功。
     */
    private Boolean started;

    /**
     * 点位数量。
     */
    private Integer pointCount;
}

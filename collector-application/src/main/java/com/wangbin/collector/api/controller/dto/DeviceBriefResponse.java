package com.wangbin.collector.api.controller.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 数据查询接口中的设备摘要。
 */
@Data
@Builder
public class DeviceBriefResponse {

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 设备下配置的点位数量。
     */
    private Integer pointCount;
}

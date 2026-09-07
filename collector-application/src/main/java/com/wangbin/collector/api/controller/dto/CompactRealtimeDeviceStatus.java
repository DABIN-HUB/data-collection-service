package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * 紧凑实时聚合响应中的轻量设备状态。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompactRealtimeDeviceStatus {

    /**
     * 设备在本次聚合中的业务状态。
     */
    private String status;

    /**
     * 设备级业务提示信息。
     */
    private String message;

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 当前设备贡献的点位行数。
     */
    private Integer dataCount;
}

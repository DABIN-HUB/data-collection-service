package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 设备点位配置列表响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DevicePointListResponse {

    /**
     * 业务状态。
     */
    private String status;

    /**
     * 业务提示信息。
     */
    private String message;

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 点位数量。
     */
    private Integer pointCount;

    /**
     * 点位配置和运行状态列表。
     */
    private List<PointRealtimePayload> points;

    /**
     * 响应生成时间戳，单位毫秒。
     */
    private Long timestamp;
}

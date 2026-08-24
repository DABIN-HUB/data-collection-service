package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 设备实时数据查询响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceRealtimeDataResponse {

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
     * 返回的点位数量。
     */
    private Integer dataCount;

    /**
     * 点位实时数据，键为稳定 pointId。
     */
    private Map<String, PointRealtimePayload> data;

    /**
     * 响应生成时间戳，单位毫秒。
     */
    private Long timestamp;
}

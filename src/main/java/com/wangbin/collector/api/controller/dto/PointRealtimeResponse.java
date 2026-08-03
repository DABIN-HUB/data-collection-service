package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * 单点实时数据查询响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PointRealtimeResponse {

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
     * 稳定点位唯一标识。
     */
    private String pointId;

    /**
     * 实时点位数据。
     */
    private PointRealtimePayload data;

    /**
     * 响应生成时间戳，单位毫秒。
     */
    private Long timestamp;
}

package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单设备实时表格紧凑快照响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompactDeviceRealtimeDataResponse {

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
     * 返回的点位行数。
     */
    private Integer dataCount;

    /**
     * 实时表格点位行。
     */
    private List<CompactRealtimePointPayload> rows;

    /**
     * 响应生成时间戳，单位毫秒。
     */
    private Long timestamp;
}

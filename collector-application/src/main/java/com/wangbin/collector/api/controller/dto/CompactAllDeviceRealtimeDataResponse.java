package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 全设备实时表格紧凑快照聚合响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompactAllDeviceRealtimeDataResponse {

    /**
     * 聚合查询整体状态。
     */
    private String status;

    /**
     * 聚合查询提示信息。
     */
    private String message;

    /**
     * 聚合涉及的设备数量。
     */
    private Integer deviceCount;

    /**
     * 聚合返回的点位行数。
     */
    private Integer dataCount;

    /**
     * 所有成功设备的扁平实时表格行。
     */
    private List<CompactRealtimePointPayload> rows;

    /**
     * 每台设备的轻量状态。
     */
    private List<CompactRealtimeDeviceStatus> devices;

    /**
     * 响应生成时间戳，单位毫秒。
     */
    private Long timestamp;
}

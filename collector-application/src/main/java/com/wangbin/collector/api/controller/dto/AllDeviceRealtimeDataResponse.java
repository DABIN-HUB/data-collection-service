package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 全部设备实时数据聚合查询响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AllDeviceRealtimeDataResponse {

    /**
     * 聚合查询整体状态。
     */
    private String status;

    /**
     * 聚合查询提示信息。
     */
    private String message;

    /**
     * 聚合返回的设备数量。
     */
    private Integer deviceCount;

    /**
     * 聚合返回的点位总数。
     */
    private Integer dataCount;

    /**
     * 每台设备的实时数据响应。
     */
    private List<DeviceRealtimeDataResponse> devices;

    /**
     * 响应生成时间戳，单位毫秒。
     */
    private Long timestamp;
}

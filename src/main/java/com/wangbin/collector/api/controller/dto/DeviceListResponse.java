package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 数据查询接口中的设备列表响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceListResponse {

    /**
     * 业务状态。
     */
    private String status;

    /**
     * 业务提示信息。
     */
    private String message;

    /**
     * 设备数量。
     */
    private Integer deviceCount;

    /**
     * 设备摘要列表。
     */
    private List<DeviceBriefResponse> devices;

    /**
     * 响应生成时间戳，单位毫秒。
     */
    private Long timestamp;
}

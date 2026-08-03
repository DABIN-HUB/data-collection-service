package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 点位历史数据查询响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HistoryDataResponse {

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
     * 返回记录数量。
     */
    private Integer count;

    /**
     * 历史数据行，字段由历史存储查询结果决定。
     */
    private List<Map<String, Object>> data;

    /**
     * 查询开始时间戳。
     */
    private Long startTs;

    /**
     * 查询结束时间戳。
     */
    private Long endTs;

    /**
     * 响应生成时间戳，单位毫秒。
     */
    private Long timestamp;
}

package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 告警历史查询响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AlarmHistoryDataResponse {

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
     * 点位业务编码。
     */
    private String pointCode;

    /**
     * 告警级别。
     */
    private String level;

    /**
     * 告警规则标识。
     */
    private String ruleId;

    /**
     * 当前返回记录数量。
     */
    private Integer count;

    /**
     * 符合条件的记录总数。
     */
    private Long total;

    /**
     * 告警历史行，字段由历史存储查询结果决定。
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

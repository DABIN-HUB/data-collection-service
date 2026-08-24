package com.wangbin.collector.api.controller.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 批量点位写入响应。
 */
@Data
@Builder
public class BatchPointWriteResponse {

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 提交字段到写入结果的映射。
     */
    private Map<String, BatchPointWriteFieldResponse> fields;

    /**
     * 提交字段总数。
     */
    private Integer total;

    /**
     * 成功映射到点位的字段数量。
     */
    private Long mapped;

    /**
     * 写入成功字段数量。
     */
    private Long success;
}

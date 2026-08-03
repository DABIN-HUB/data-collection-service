package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * 批量点位写入中单个提交字段的处理结果。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchPointWriteFieldResponse {

    /**
     * 提交字段是否映射到本地点位。
     */
    private Boolean mapped;

    /**
     * 写入是否成功。
     */
    private Boolean success;

    /**
     * 失败原因。
     */
    private String error;

    /**
     * 稳定点位唯一标识。
     */
    private String pointId;

    /**
     * 点位业务编码。
     */
    private String pointCode;

    /**
     * 写入值。
     */
    private Object value;
}

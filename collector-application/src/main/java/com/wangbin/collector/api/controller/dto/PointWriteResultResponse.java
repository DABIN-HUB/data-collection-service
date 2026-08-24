package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * 单点写入结果响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PointWriteResultResponse {

    /**
     * 稳定点位唯一标识。
     */
    private String pointId;

    /**
     * 点位业务编码。
     */
    private String pointCode;

    /**
     * 点位名称。
     */
    private String pointName;

    /**
     * 写入值。
     */
    private Object value;

    /**
     * 写入是否成功。
     */
    private Boolean success;

    /**
     * 写入失败原因。
     */
    private String error;
}

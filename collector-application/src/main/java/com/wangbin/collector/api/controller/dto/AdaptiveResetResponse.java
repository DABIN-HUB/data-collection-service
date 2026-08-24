package com.wangbin.collector.api.controller.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 自适应采集配置重置响应。
 */
@Data
@Builder
public class AdaptiveResetResponse {

    /**
     * 兼容前端的业务状态码。
     */
    private Integer code;

    /**
     * 业务提示信息。
     */
    private String message;
}

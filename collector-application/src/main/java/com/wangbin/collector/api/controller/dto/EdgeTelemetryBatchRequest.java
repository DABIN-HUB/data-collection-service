package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.core.collector.edge.EdgeProtocolType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 边缘进程批量遥测请求。
 */
public record EdgeTelemetryBatchRequest(
        @NotBlank(message = "网关标识不能为空") String gatewayId,
        @NotNull(message = "边缘协议类型不能为空") EdgeProtocolType protocol,
        @NotBlank(message = "配置版本不能为空") String configVersion,
        @Valid @NotEmpty(message = "遥测数据不能为空")
        @Size(max = 1000, message = "单批遥测数据不能超过1000条") List<EdgeTelemetryItem> items) {
}

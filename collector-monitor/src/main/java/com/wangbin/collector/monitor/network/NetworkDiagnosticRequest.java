package com.wangbin.collector.monitor.network;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 网络检测请求。
 */
public record NetworkDiagnosticRequest(
        @NotNull(message = "检测方式不能为空") NetworkDiagnosticType type,
        String deviceId,
        @NotBlank(message = "检测目标不能为空") String target,
        @Min(value = 1, message = "端口必须大于 0")
        @Max(value = 65_535, message = "端口不能超过 65535") Integer port,
        @Min(value = 100, message = "超时时间不能小于 100 毫秒")
        @Max(value = 10_000, message = "超时时间不能超过 10000 毫秒") Integer timeoutMs) {
}
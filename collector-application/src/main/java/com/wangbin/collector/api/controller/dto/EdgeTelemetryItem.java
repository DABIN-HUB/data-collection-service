package com.wangbin.collector.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 边缘进程上送的单点遥测数据。
 */
public record EdgeTelemetryItem(
        @NotBlank(message = "设备标识不能为空") String deviceId,
        @NotBlank(message = "点位引用不能为空") String pointRef,
        @NotNull(message = "点位值不能为空") Object value,
        Integer quality,
        Long timestamp,
        @Positive(message = "序号必须大于零") long sequence) {
}

package com.wangbin.collector.monitor.alert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 告警确认请求。
 */
public record AlarmAcknowledgementRequest(
        @Size(max = 500, message = "处理说明不能超过 500 个字符") String note,
        @NotBlank(message = "幂等键不能为空")
        @Size(max = 128, message = "幂等键不能超过 128 个字符") String idempotencyKey) {
}
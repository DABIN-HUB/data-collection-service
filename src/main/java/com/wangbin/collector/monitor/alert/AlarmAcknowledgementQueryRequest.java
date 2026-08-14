package com.wangbin.collector.monitor.alert;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 告警确认状态批量查询请求。
 */
public record AlarmAcknowledgementQueryRequest(
        @NotEmpty(message = "告警标识不能为空")
        @Size(max = 500, message = "单次最多查询 500 个告警")
        List<@Size(max = 256, message = "告警标识不能超过 256 个字符") String> alarmIds) {
}
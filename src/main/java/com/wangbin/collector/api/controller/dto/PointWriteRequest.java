package com.wangbin.collector.api.controller.dto;

import lombok.Data;
import jakarta.validation.constraints.AssertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 控制台手工点位写入请求。
 */
@Data
public class PointWriteRequest {
    private Object value;
    private Map<String, Object> values = new LinkedHashMap<>();

    @AssertTrue(message = "value和values不能同时为空")
    public boolean isWritePayloadPresent() {
        return value != null || (values != null && !values.isEmpty());
    }
}

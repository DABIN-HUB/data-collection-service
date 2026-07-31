package com.wangbin.collector.core.cache.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定义当前模块的业务组件。
 */
public final class TelemetryStreamRecordBuilder {

    /**
     * 创建当前组件实例。
     */
    private TelemetryStreamRecordBuilder() {
    }

    /**
     * 创建并返回业务对象。
     */
    public static Map<String, String> build(ObjectMapper objectMapper,
                                            String deviceId,
                                            DataPoint point,
                                            ProcessResult result,
                                            long eventTs) throws JsonProcessingException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventTs", String.valueOf(eventTs));
        fields.put("deviceId", safe(deviceId));

        if (point != null) {
            fields.put("pointId", safe(point.getPointId()));
            fields.put("pointCode", safe(point.getPointCode()));
            fields.put("pointName", safe(point.getPointName()));
        }

        // 保留完整 ProcessResult 载荷，便于下游扩展。
        fields.put("processResult", objectMapper.writeValueAsString(result));
        return fields;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static String safe(String value) {
        return value == null ? "" : value;
    }
}


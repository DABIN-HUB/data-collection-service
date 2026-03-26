package com.wangbin.collector.core.cache.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TelemetryStreamRecordBuilder {

    private TelemetryStreamRecordBuilder() {
    }

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

        // Keep full ProcessResult payload for downstream extensibility.
        fields.put("processResult", objectMapper.writeValueAsString(result));
        return fields;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}


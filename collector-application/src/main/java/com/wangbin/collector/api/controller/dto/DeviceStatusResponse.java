package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.common.constant.CommonMapKeys;
import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备调度状态响应。
 */
@Data
@Builder
public class DeviceStatusResponse {

    private String deviceId;
    private Boolean isRunning;
    private Boolean isStarting;
    private Boolean connected;
    private Boolean reconnecting;
    private Long reconnectNextRetryAt;
    private DeviceStatisticsResponse statistics;
    private DevicePerformanceResponse performance;

    public static DeviceStatusResponse from(Map<String, Object> source) {
        Map<String, Object> statistics = asMap(source == null ? null : source.get("statistics"));
        Map<String, Object> performance = asMap(source == null ? null : source.get("performance"));
        return DeviceStatusResponse.builder()
                .deviceId(asString(source == null ? null : source.get(CommonMapKeys.DEVICE_ID)))
                .isRunning(asBoolean(source == null ? null : source.get("isRunning")))
                .isStarting(asBoolean(source == null ? null : source.get("isStarting")))
                .connected(asBoolean(source == null ? null : source.get(CommonMapKeys.CONNECTED)))
                .reconnecting(asBoolean(source == null ? null : source.get("reconnecting")))
                .reconnectNextRetryAt(asLong(source == null ? null : source.get("reconnectNextRetryAt")))
                .statistics(DeviceStatisticsResponse.from(statistics))
                .performance(DevicePerformanceResponse.from(performance))
                .build();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean asBoolean(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, mapValue) -> {
            if (key != null) {
                result.put(String.valueOf(key), mapValue);
            }
        });
        return result;
    }
}

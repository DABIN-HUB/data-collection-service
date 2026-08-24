package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.core.report.shadow.ShadowDocumentKeys;
import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备影子差异响应，delta 和 metadata 的属性名保持动态。
 */
@Data
@Builder
public class DeviceShadowDeltaResponse {

    private String deviceId;
    private Long version;
    private Long timestamp;
    private Map<String, Object> delta;
    private Map<String, Object> metadata;

    public static DeviceShadowDeltaResponse from(Map<String, Object> source) {
        return DeviceShadowDeltaResponse.builder()
                .deviceId(asString(value(source, CommonMapKeys.DEVICE_ID)))
                .version(asLong(value(source, ShadowDocumentKeys.VERSION)))
                .timestamp(asLong(value(source, CommonMapKeys.TIMESTAMP)))
                .delta(asMap(value(source, ShadowDocumentKeys.DELTA)))
                .metadata(asMap(value(source, CommonMapKeys.METADATA)))
                .build();
    }

    private static Object value(Map<String, Object> source, String key) {
        return source == null ? null : source.get(key);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, nested) -> {
            if (key != null) {
                result.put(String.valueOf(key), nested);
            }
        });
        return result;
    }
}

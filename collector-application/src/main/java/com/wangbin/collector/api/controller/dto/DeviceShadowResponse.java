package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.core.report.shadow.ShadowDocumentKeys;
import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备影子文档响应，动态属性保留在 state 和 metadata 内部 Map。
 */
@Data
@Builder
public class DeviceShadowResponse {

    private String deviceId;
    private Long version;
    private Long timestamp;
    private Long createdAt;
    private Long lastReportAt;
    private Long lastWindowStart;
    private Long lastWindowEnd;
    private DeviceShadowStateResponse state;
    private DeviceShadowMetadataResponse metadata;

    public static DeviceShadowResponse from(Map<String, Object> source) {
        Map<String, Object> state = asMap(value(source, ShadowDocumentKeys.STATE));
        Map<String, Object> metadata = asMap(value(source, CommonMapKeys.METADATA));
        return DeviceShadowResponse.builder()
                .deviceId(asString(value(source, CommonMapKeys.DEVICE_ID)))
                .version(asLong(value(source, ShadowDocumentKeys.VERSION)))
                .timestamp(asLong(value(source, CommonMapKeys.TIMESTAMP)))
                .createdAt(asLong(value(source, ShadowDocumentKeys.CREATED_AT)))
                .lastReportAt(asLong(value(source, ShadowDocumentKeys.LAST_REPORT_AT)))
                .lastWindowStart(asLong(value(source, ShadowDocumentKeys.LAST_WINDOW_START)))
                .lastWindowEnd(asLong(value(source, ShadowDocumentKeys.LAST_WINDOW_END)))
                .state(new DeviceShadowStateResponse(
                        asMap(state.get(ShadowDocumentKeys.REPORTED)),
                        asMap(state.get(ShadowDocumentKeys.DESIRED)),
                        asMap(state.get(ShadowDocumentKeys.DELTA)),
                        asMap(state.get(ShadowDocumentKeys.LAST_REPORTED))))
                .metadata(new DeviceShadowMetadataResponse(
                        asMap(metadata.get(ShadowDocumentKeys.REPORTED)),
                        asMap(metadata.get(ShadowDocumentKeys.DESIRED))))
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

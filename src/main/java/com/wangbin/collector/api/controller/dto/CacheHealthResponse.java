package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.core.cache.constant.CacheMetricKeys;
import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多级缓存健康检查响应。
 */
@Data
@Builder
public class CacheHealthResponse {

    private Boolean enabled;
    private Integer totalLevels;
    private Integer maxLevel;
    private List<CacheLevelHealthResponse> levels;
    private String overallStatus;

    public static CacheHealthResponse from(Map<String, Object> source) {
        return CacheHealthResponse.builder()
                .enabled(asBoolean(value(source, CommonMapKeys.ENABLED)))
                .totalLevels(asInteger(value(source, CacheMetricKeys.TOTAL_LEVELS)))
                .maxLevel(asInteger(value(source, CacheMetricKeys.MAX_LEVEL)))
                .levels(asLevelHealthList(value(source, CacheMetricKeys.LEVELS)))
                .overallStatus(asString(value(source, CacheMetricKeys.OVERALL_STATUS)))
                .build();
    }

    private static Object value(Map<String, Object> source, String key) {
        return source == null ? null : source.get(key);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean asBoolean(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static List<CacheLevelHealthResponse> asLevelHealthList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(CacheHealthResponse::asStringObjectMap)
                .map(CacheLevelHealthResponse::from)
                .toList();
    }

    private static Map<String, Object> asStringObjectMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }
}

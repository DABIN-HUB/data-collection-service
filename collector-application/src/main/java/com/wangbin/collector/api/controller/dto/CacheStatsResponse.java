package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.core.cache.constant.CacheMetricKeys;
import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多级缓存统计响应。
 */
@Data
@Builder
public class CacheStatsResponse {

    private Boolean enabled;
    private Boolean writeThrough;
    private Boolean readThrough;
    private Boolean cacheAside;
    private Integer maxLevel;
    private Long totalReads;
    private Long totalWrites;
    private Long totalDeletes;
    private Long level1Hits;
    private Long level2Hits;
    private Long totalMisses;
    private String totalHitRate;
    private String level1HitRate;
    private String level2HitRate;
    private String missRate;
    private Long totalAccess;
    private Map<String, Map<String, Object>> levelStatistics;

    public static CacheStatsResponse from(Map<String, Object> source) {
        return CacheStatsResponse.builder()
                .enabled(asBoolean(value(source, CommonMapKeys.ENABLED)))
                .writeThrough(asBoolean(value(source, CacheMetricKeys.WRITE_THROUGH)))
                .readThrough(asBoolean(value(source, CacheMetricKeys.READ_THROUGH)))
                .cacheAside(asBoolean(value(source, CacheMetricKeys.CACHE_ASIDE)))
                .maxLevel(asInteger(value(source, CacheMetricKeys.MAX_LEVEL)))
                .totalReads(asLong(value(source, CacheMetricKeys.TOTAL_READS)))
                .totalWrites(asLong(value(source, CacheMetricKeys.TOTAL_WRITES)))
                .totalDeletes(asLong(value(source, CacheMetricKeys.TOTAL_DELETES)))
                .level1Hits(asLong(value(source, CacheMetricKeys.LEVEL1_HITS)))
                .level2Hits(asLong(value(source, CacheMetricKeys.LEVEL2_HITS)))
                .totalMisses(asLong(value(source, CacheMetricKeys.TOTAL_MISSES)))
                .totalHitRate(asString(value(source, CacheMetricKeys.TOTAL_HIT_RATE)))
                .level1HitRate(asString(value(source, CacheMetricKeys.LEVEL1_HIT_RATE)))
                .level2HitRate(asString(value(source, CacheMetricKeys.LEVEL2_HIT_RATE)))
                .missRate(asString(value(source, CacheMetricKeys.MISS_RATE)))
                .totalAccess(asLong(value(source, CacheMetricKeys.TOTAL_ACCESS)))
                .levelStatistics(asNestedMap(value(source, CacheMetricKeys.LEVEL_STATISTICS)))
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

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Map<String, Map<String, Object>> asNestedMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        map.forEach((key, nested) -> result.put(String.valueOf(key), asMap(nested)));
        return result;
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

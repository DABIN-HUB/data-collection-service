package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.core.cache.constant.CacheMetricKeys;

import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存指标采集服务。
 */
@Service
@RequiredArgsConstructor
public class CacheMonitorService {

    private final MultiLevelCacheManager multiLevelCacheManager;

    public CacheMetricsSnapshot getCacheMetrics() {
        Map<String, Object> stats = multiLevelCacheManager.getStatistics();
        return CacheMetricsSnapshot.builder()
                .totalReads(longValue(stats, CacheMetricKeys.TOTAL_READS))
                .totalWrites(longValue(stats, CacheMetricKeys.TOTAL_WRITES))
                .totalDeletes(longValue(stats, CacheMetricKeys.TOTAL_DELETES))
                .totalMisses(longValue(stats, CacheMetricKeys.TOTAL_MISSES))
                .totalAccess(longValue(stats, CacheMetricKeys.TOTAL_ACCESS))
                .totalHitRate(percentValue(stats, CacheMetricKeys.TOTAL_HIT_RATE))
                .level1HitRate(percentValue(stats, CacheMetricKeys.LEVEL1_HIT_RATE))
                .level2HitRate(percentValue(stats, CacheMetricKeys.LEVEL2_HIT_RATE))
                .missRate(percentValue(stats, CacheMetricKeys.MISS_RATE))
                .levelStatistics(levelStats(stats.get(CacheMetricKeys.LEVEL_STATISTICS)))
                .health(cacheHealth())
                .build();
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> cacheHealth() {
        try {
            return new LinkedHashMap<>(multiLevelCacheManager.getHealthStatus());
        } catch (RuntimeException exception) {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put(CacheMetricKeys.OVERALL_STATUS, "UNKNOWN");
            health.put(CacheMetricKeys.LEVELS, Collections.emptyList());
            return health;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private long longValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isEmpty()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    /**
     * 执行当前业务逻辑。
     */
    private double percentValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isEmpty()) {
            String normalized = text.replace("%", "").trim();
            try {
                return Double.parseDouble(normalized);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0;
    }

    /**
     * 执行当前业务逻辑。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> levelStats(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Collections.emptyMap();
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                continue;
            }
            Object val = entry.getValue();
            if (val instanceof Map<?, ?> inner) {
                Map<String, Object> safeMap = new LinkedHashMap<>();
                inner.forEach((innerKey, innerValue) -> {
                    if (innerKey != null) {
                        safeMap.put(innerKey.toString(), innerValue);
                    }
                });
                result.put(key, safeMap);
            }
        }
        return result;
    }
}

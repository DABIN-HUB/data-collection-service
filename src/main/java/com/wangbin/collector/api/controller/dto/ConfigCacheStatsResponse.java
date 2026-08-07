package com.wangbin.collector.api.controller.dto;

import com.wangbin.collector.common.constant.CommonMapKeys;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 配置缓存统计响应。
 */
@Data
@Builder
public class ConfigCacheStatsResponse {

    private Integer deviceCount;
    private Integer pointCount;
    private Integer connectionCount;
    private Integer contextCount;

    public static ConfigCacheStatsResponse from(Map<String, Object> source) {
        return ConfigCacheStatsResponse.builder()
                .deviceCount(asInteger(value(source, "deviceCount")))
                .pointCount(asInteger(value(source, CommonMapKeys.POINT_COUNT)))
                .connectionCount(asInteger(value(source, "connectionCount")))
                .contextCount(asInteger(value(source, "contextCount")))
                .build();
    }

    private static Object value(Map<String, Object> source, String key) {
        return source == null ? null : source.get(key);
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}

package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.core.cache.constant.CacheMetricKeys;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 单个缓存层健康状态响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CacheLevelHealthResponse {

    private String type;
    private Integer level;
    private Long size;
    private String status;
    private String error;

    public static CacheLevelHealthResponse from(Map<String, Object> source) {
        return CacheLevelHealthResponse.builder()
                .type(asString(source.get(CommonMapKeys.TYPE)))
                .level(asInteger(source.get(CacheMetricKeys.LEVEL)))
                .size(asLong(source.get(CacheMetricKeys.SIZE)))
                .status(asString(source.get(CommonMapKeys.STATUS)))
                .error(asString(source.get(CommonMapKeys.ERROR)))
                .build();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}

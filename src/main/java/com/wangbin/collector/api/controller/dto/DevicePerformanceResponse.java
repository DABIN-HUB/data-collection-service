package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wangbin.collector.common.constant.CommonMapKeys;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 单设备调度性能响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DevicePerformanceResponse {

    private String deviceId;
    private Integer totalPoints;
    private Integer successfulBatches;
    private Integer failedBatches;
    private Long averageBatchTime;
    private Integer currentBatchSize;
    private Integer maxBatchSize;
    private Double successRate;
    private Double healthScore;
    private String failureRisk;
    private Integer consecutiveFailures;
    private Long averageResponseTime;
    private List<Long> recentResponseTimes;

    public static DevicePerformanceResponse from(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return DevicePerformanceResponse.builder().build();
        }
        return DevicePerformanceResponse.builder()
                .deviceId(asString(source.get(CommonMapKeys.DEVICE_ID)))
                .totalPoints(asInteger(source.get("totalPoints")))
                .successfulBatches(asInteger(source.get("successfulBatches")))
                .failedBatches(asInteger(source.get("failedBatches")))
                .averageBatchTime(asLong(source.get("averageBatchTime")))
                .currentBatchSize(asInteger(source.get("currentBatchSize")))
                .maxBatchSize(asInteger(source.get("maxBatchSize")))
                .successRate(asDouble(source.get(CommonMapKeys.SUCCESS_RATE)))
                .healthScore(asDouble(source.get("healthScore")))
                .failureRisk(asString(source.get("failureRisk")))
                .consecutiveFailures(asInteger(source.get("consecutiveFailures")))
                .averageResponseTime(asLong(source.get("averageResponseTime")))
                .recentResponseTimes(asLongList(source.get("recentResponseTimes")))
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

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static List<Long> asLongList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        return list.stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .toList();
    }
}

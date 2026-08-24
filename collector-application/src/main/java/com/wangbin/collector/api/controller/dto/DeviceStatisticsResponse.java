package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wangbin.collector.common.constant.CommonMapKeys;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 单设备采集统计响应。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceStatisticsResponse {

    private String deviceId;
    private Boolean isRunning;
    private Long runningDuration;
    private Integer totalExecutions;
    private Integer successfulExecutions;
    private Integer failedExecutions;
    private Integer totalPoints;
    private Integer currentTaskPoints;
    private Long averageExecutionTime;
    private Double successRate;
    private Long lastExecutionTime;

    public static DeviceStatisticsResponse from(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return DeviceStatisticsResponse.builder().build();
        }
        return DeviceStatisticsResponse.builder()
                .deviceId(asString(source.get(CommonMapKeys.DEVICE_ID)))
                .isRunning(asBoolean(source.get("isRunning")))
                .runningDuration(asLong(source.get("runningDuration")))
                .totalExecutions(asInteger(source.get("totalExecutions")))
                .successfulExecutions(asInteger(source.get("successfulExecutions")))
                .failedExecutions(asInteger(source.get("failedExecutions")))
                .totalPoints(asInteger(source.get("totalPoints")))
                .currentTaskPoints(asInteger(source.get("currentTaskPoints")))
                .averageExecutionTime(asLong(source.get("averageExecutionTime")))
                .successRate(asDouble(source.get(CommonMapKeys.SUCCESS_RATE)))
                .lastExecutionTime(asLong(source.get("lastExecutionTime")))
                .build();
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

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}

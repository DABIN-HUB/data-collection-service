package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.monitor.metrics.CloudReportMetricKeys;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云上报链路监控响应。
 */
@Data
@Builder
public class CloudReportMetricsResponse {

    private Boolean enabled;
    private String status;
    private String statusText;
    private String mode;
    private String cloudProvider;
    private List<String> supportedProtocols;
    private Map<String, Map<String, Object>> handlersStatus;
    private Map<String, Map<String, Object>> handlersStatistics;
    private Configured configured;
    private Executor executor;
    private Batch batch;
    private Ack ack;
    private Outbox outbox;
    private Payload payload;
    private List<String> risks;
    private Long generatedAt;

    public static CloudReportMetricsResponse from(Map<String, Object> source) {
        return CloudReportMetricsResponse.builder()
                .enabled(asBoolean(value(source, CommonMapKeys.ENABLED)))
                .status(asString(value(source, CommonMapKeys.STATUS)))
                .statusText(asString(value(source, CloudReportMetricKeys.STATUS_TEXT)))
                .mode(asString(value(source, CommonMapKeys.MODE)))
                .cloudProvider(asString(value(source, CloudReportMetricKeys.CLOUD_PROVIDER)))
                .supportedProtocols(asStringList(value(source, CloudReportMetricKeys.SUPPORTED_PROTOCOLS)))
                .handlersStatus(asNestedMap(value(source, CloudReportMetricKeys.HANDLERS_STATUS)))
                .handlersStatistics(asNestedMap(value(source, CloudReportMetricKeys.HANDLERS_STATISTICS)))
                .configured(Configured.from(asMap(value(source, CloudReportMetricKeys.CONFIGURED))))
                .executor(Executor.from(asMap(value(source, CloudReportMetricKeys.EXECUTOR))))
                .batch(Batch.from(asMap(value(source, CloudReportMetricKeys.BATCH))))
                .ack(Ack.from(asMap(value(source, CloudReportMetricKeys.ACK))))
                .outbox(Outbox.from(asMap(value(source, CloudReportMetricKeys.OUTBOX))))
                .payload(Payload.from(asMap(value(source, CloudReportMetricKeys.PAYLOAD))))
                .risks(asStringList(value(source, CloudReportMetricKeys.RISKS)))
                .generatedAt(asLong(value(source, CloudReportMetricKeys.GENERATED_AT)))
                .build();
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Configured {
        private Boolean configSnapshotAvailable;
        private String configSnapshotError;
        private Integer deviceCount;
        private Integer pointCount;
        private Integer reportEnabledPointCount;
        private Integer eventEnabledPointCount;
        private Integer changeTriggerPointCount;
        private Integer reportFieldPointCount;
        private Integer reportablePointCount;
        private Integer cloudTargetDeviceCount;
        private Integer invalidCloudTargetDeviceCount;
        private Integer cloudTargetCount;
        private List<String> cloudTargetKeys;
        private Double cloudTargetCoverage;

        private static Configured from(Map<String, Object> source) {
            return Configured.builder()
                    .configSnapshotAvailable(asBoolean(value(source, CloudReportMetricKeys.CONFIG_SNAPSHOT_AVAILABLE)))
                    .configSnapshotError(asString(value(source, CloudReportMetricKeys.CONFIG_SNAPSHOT_ERROR)))
                    .deviceCount(asInteger(value(source, CloudReportMetricKeys.DEVICE_COUNT)))
                    .pointCount(asInteger(value(source, CommonMapKeys.POINT_COUNT)))
                    .reportEnabledPointCount(asInteger(value(source, CloudReportMetricKeys.REPORT_ENABLED_POINT_COUNT)))
                    .eventEnabledPointCount(asInteger(value(source, CloudReportMetricKeys.EVENT_ENABLED_POINT_COUNT)))
                    .changeTriggerPointCount(asInteger(value(source, CloudReportMetricKeys.CHANGE_TRIGGER_POINT_COUNT)))
                    .reportFieldPointCount(asInteger(value(source, CloudReportMetricKeys.REPORT_FIELD_POINT_COUNT)))
                    .reportablePointCount(asInteger(value(source, CloudReportMetricKeys.REPORTABLE_POINT_COUNT)))
                    .cloudTargetDeviceCount(asInteger(value(source, CloudReportMetricKeys.CLOUD_TARGET_DEVICE_COUNT)))
                    .invalidCloudTargetDeviceCount(asInteger(value(source, CloudReportMetricKeys.INVALID_CLOUD_TARGET_DEVICE_COUNT)))
                    .cloudTargetCount(asInteger(value(source, CloudReportMetricKeys.CLOUD_TARGET_COUNT)))
                    .cloudTargetKeys(asStringList(value(source, CloudReportMetricKeys.CLOUD_TARGET_KEYS)))
                    .cloudTargetCoverage(asDouble(value(source, CloudReportMetricKeys.CLOUD_TARGET_COVERAGE)))
                    .build();
        }
    }

    @Data
    @Builder
    public static class Executor {
        private String type;
        private Integer corePoolSize;
        private Integer maxPoolSize;
        private Integer poolSize;
        private Integer activeCount;
        private Integer queueSize;
        private Integer queueRemainingCapacity;
        private Integer queueCapacity;
        private Double queueUsage;
        private Long completedTaskCount;
        private Long taskCount;
        private Long rejectedCount;

        private static Executor from(Map<String, Object> source) {
            return Executor.builder()
                    .type(asString(value(source, CommonMapKeys.TYPE)))
                    .corePoolSize(asInteger(value(source, CloudReportMetricKeys.CORE_POOL_SIZE)))
                    .maxPoolSize(asInteger(value(source, CloudReportMetricKeys.MAX_POOL_SIZE)))
                    .poolSize(asInteger(value(source, CloudReportMetricKeys.POOL_SIZE)))
                    .activeCount(asInteger(value(source, CloudReportMetricKeys.ACTIVE_COUNT)))
                    .queueSize(asInteger(value(source, CloudReportMetricKeys.QUEUE_SIZE)))
                    .queueRemainingCapacity(asInteger(value(source, CloudReportMetricKeys.QUEUE_REMAINING_CAPACITY)))
                    .queueCapacity(asInteger(value(source, CloudReportMetricKeys.QUEUE_CAPACITY)))
                    .queueUsage(asDouble(value(source, CloudReportMetricKeys.QUEUE_USAGE)))
                    .completedTaskCount(asLong(value(source, CloudReportMetricKeys.COMPLETED_TASK_COUNT)))
                    .taskCount(asLong(value(source, CloudReportMetricKeys.TASK_COUNT)))
                    .rejectedCount(asLong(value(source, CloudReportMetricKeys.REJECTED_COUNT)))
                    .build();
        }
    }

    @Data
    @Builder
    public static class Batch {
        private Boolean enabled;
        private Integer maxDevicesPerPack;
        private Integer maxPropertiesPerPack;
        private Integer maxPayloadBytes;
        private Long maxDelayMs;
        private Boolean highPriorityBypass;

        private static Batch from(Map<String, Object> source) {
            return Batch.builder()
                    .enabled(asBoolean(value(source, CommonMapKeys.ENABLED)))
                    .maxDevicesPerPack(asInteger(value(source, CloudReportMetricKeys.MAX_DEVICES_PER_PACK)))
                    .maxPropertiesPerPack(asInteger(value(source, CloudReportMetricKeys.MAX_PROPERTIES_PER_PACK)))
                    .maxPayloadBytes(asInteger(value(source, CloudReportMetricKeys.MAX_PAYLOAD_BYTES)))
                    .maxDelayMs(asLong(value(source, CloudReportMetricKeys.MAX_DELAY_MS)))
                    .highPriorityBypass(asBoolean(value(source, CloudReportMetricKeys.HIGH_PRIORITY_BYPASS)))
                    .build();
        }
    }

    @Data
    @Builder
    public static class Ack {
        private String mode;
        private Long timeoutMs;
        private Integer maxPending;
        private Long timeoutScanMs;
        private String commitOn;

        private static Ack from(Map<String, Object> source) {
            return Ack.builder()
                    .mode(asString(value(source, CommonMapKeys.MODE)))
                    .timeoutMs(asLong(value(source, CloudReportMetricKeys.TIMEOUT_MS)))
                    .maxPending(asInteger(value(source, CloudReportMetricKeys.MAX_PENDING)))
                    .timeoutScanMs(asLong(value(source, CloudReportMetricKeys.TIMEOUT_SCAN_MS)))
                    .commitOn(asString(value(source, CloudReportMetricKeys.COMMIT_ON)))
                    .build();
        }
    }

    @Data
    @Builder
    public static class Outbox {
        private Boolean enabled;
        private Long pendingCount;
        private Long isolatedCount;
        private Long oldestMessageAgeMs;

        private static Outbox from(Map<String, Object> source) {
            return Outbox.builder()
                    .enabled(asBoolean(value(source, CommonMapKeys.ENABLED)))
                    .pendingCount(asLong(value(source, CloudReportMetricKeys.PENDING_COUNT)))
                    .isolatedCount(asLong(value(source, CloudReportMetricKeys.ISOLATED_COUNT)))
                    .oldestMessageAgeMs(asLong(value(source, CloudReportMetricKeys.OLDEST_MESSAGE_AGE_MS)))
                    .build();
        }
    }

    @Data
    @Builder
    public static class Payload {
        private String profile;
        private String includeQuality;
        private Boolean includePropertyTs;
        private Boolean includeMetadata;
        private Boolean includeMessageId;

        private static Payload from(Map<String, Object> source) {
            return Payload.builder()
                    .profile(asString(value(source, CloudReportMetricKeys.PROFILE)))
                    .includeQuality(asString(value(source, CloudReportMetricKeys.INCLUDE_QUALITY)))
                    .includePropertyTs(asBoolean(value(source, CloudReportMetricKeys.INCLUDE_PROPERTY_TS)))
                    .includeMetadata(asBoolean(value(source, CloudReportMetricKeys.INCLUDE_METADATA)))
                    .includeMessageId(asBoolean(value(source, CloudReportMetricKeys.INCLUDE_MESSAGE_ID)))
                    .build();
        }
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

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        list.forEach(item -> {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        });
        return result;
    }

    private static Map<String, Map<String, Object>> asNestedMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        map.forEach((key, nested) -> {
            if (key != null) {
                result.put(String.valueOf(key), asMap(nested));
            }
        });
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

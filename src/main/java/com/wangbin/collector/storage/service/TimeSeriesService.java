package com.wangbin.collector.storage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.enums.QualityEnum;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.constant.TelemetryStorageJsonKeys;
import com.wangbin.collector.storage.repository.DataRepository;
import com.wangbin.collector.storage.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 处理当前模块的业务服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class TimeSeriesService {

    private static final String TELEMETRY_UNIT_COLUMN = "unit";
    private static final Set<String> INTERNAL_METADATA_KEYS = Set.of(
            ProcessResultMetadataKeys.RAW_VALUE,
            ProcessResultMetadataKeys.PROCESSED_VALUE,
            ProcessResultMetadataKeys.RAW_BYTES,
            ProcessResultMetadataKeys.COLLECT_TIME,
            TelemetryStorageJsonKeys.RAW_JSON,
            TelemetryStorageJsonKeys.PROCESSED_JSON,
            TelemetryStorageJsonKeys.METADATA_JSON
    );

    private final DataRepository dataRepository;
    private final DeviceRepository deviceRepository;
    private final TdengineProperties properties;
    private final ObjectMapper objectMapper;
    private final PointRuntimeStateService pointRuntimeStateService;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);
    private final Map<String, Boolean> ensuredTables = new ConcurrentHashMap<>();

    /**
     * 写入或持久化业务数据。
     */
    public void append(String deviceId,
                       String protocolType,
                       DataPoint point,
                       ProcessResult processResult,
                       long eventTs) {
        ensureSchema();
        String database = sanitizeIdentifier(properties.getDatabase());
        String superTable = sanitizeIdentifier(properties.getSuperTable());
        String subTable = resolveSubTableName(deviceId);
        ensureSubTable(database, superTable, subTable, deviceId, protocolType);

        Object finalValue = processResult != null ? processResult.getFinalValue() : null;
        TelemetryPayload payload = buildPayload(deviceId, protocolType, point, processResult, finalValue, eventTs);

        dataRepository.insertTelemetry(
                database,
                subTable,
                eventTs,
                point != null ? point.getPointId() : null,
                point != null ? point.getPointCode() : null,
                point != null ? point.getPointName() : null,
                finalValue != null ? String.valueOf(finalValue) : null,
                point != null ? point.getUnit() : null,
                processResult != null ? processResult.getQuality() : null,
                processResult != null ? processResult.isSuccess() : null,
                processResult != null ? processResult.getMessage() : null,
                payload.rawJson(),
                payload.processedJson(),
                payload.metadataJson()
        );
    }

    /**
     * 查询并返回业务数据。
     */
    public List<Map<String, Object>> query(String deviceId,
                                           String pointId,
                                           Long startTs,
                                           Long endTs,
                                           Integer limit) {
        ensureSchema();
        int resolvedLimit = limit == null || limit <= 0 ? properties.getQueryDefaultLimit() : limit;
        int guardedLimit = Math.max(1, Math.min(resolvedLimit, properties.getQueryMaxLimit()));
        return dataRepository.queryPointHistory(
                sanitizeIdentifier(properties.getDatabase()),
                resolveSubTableName(deviceId),
                pointId,
                startTs,
                endTs,
                guardedLimit
        );
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureSchema() {
        if (schemaReady.get() || !properties.isAutoCreate()) {
            return;
        }
        synchronized (schemaReady) {
            if (schemaReady.get()) {
                return;
            }
            String database = sanitizeIdentifier(properties.getDatabase());
            String superTable = sanitizeIdentifier(properties.getSuperTable());
            dataRepository.createDatabase(database, properties.getKeepDays());
            dataRepository.createStable(database, superTable);
            ensureTelemetryUnitColumn(database, superTable);
            schemaReady.set(true);
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureTelemetryUnitColumn(String database, String superTable) {
        Long count = dataRepository.countColumn(database, superTable, TELEMETRY_UNIT_COLUMN);
        if (count != null && count > 0) {
            return;
        }
        dataRepository.addTelemetryUnitColumn(database, superTable);
        log.info("TDengine 遥测 超级表 已升级 with 字段 {}:{}.{}",
                TELEMETRY_UNIT_COLUMN, database, superTable);
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureSubTable(String database,
                                String superTable,
                                String subTable,
                                String deviceTag,
                                String protocolTag) {
        if (Boolean.TRUE.equals(ensuredTables.get(subTable))) {
            return;
        }
        synchronized (ensuredTables) {
            if (Boolean.TRUE.equals(ensuredTables.get(subTable))) {
                return;
            }
            deviceRepository.createChildTable(
                    database,
                    subTable,
                    superTable,
                    escapeTag(deviceTag),
                    escapeTag(protocolTag != null ? protocolTag : "UNKNOWN")
            );
            ensuredTables.put(subTable, true);
            log.info("TDengine 子表 ready:{}", subTable);
        }
    }

    /**
     * 创建并返回业务对象。
     */
    private TelemetryPayload buildPayload(String deviceId,
                                          String protocolType,
                                          DataPoint point,
                                          ProcessResult processResult,
                                          Object finalValue,
                                          long eventTs) {
        Map<String, Object> metadata = metadataOf(processResult);
        return new TelemetryPayload(
                toJson(buildRawJson(protocolType, point, processResult, metadata, eventTs)),
                toJson(buildProcessedJson(point, processResult, finalValue, metadata, eventTs)),
                toJson(buildMetadataJson(deviceId, protocolType, point, processResult, metadata))
        );
    }

    /**
     * 创建并返回业务对象。
     */
    private Map<String, Object> buildRawJson(String protocolType,
                                             DataPoint point,
                                             ProcessResult processResult,
                                             Map<String, Object> metadata,
                                             long eventTs) {
        Map<String, Object> raw = new LinkedHashMap<>();
        mergeMap(raw, metadata.get(TelemetryStorageJsonKeys.RAW_JSON));
        putIfAbsent(raw, CommonMapKeys.ADDRESS, point != null ? point.getAddress() : null);
        putIfAbsent(raw, TelemetryStorageJsonKeys.DATA_TYPE, point != null ? point.getDataType() : null);
        putIfAbsent(raw, TelemetryStorageJsonKeys.RAW_VALUE, firstNonNull(metadata.get(ProcessResultMetadataKeys.RAW_VALUE),
                processResult != null ? processResult.getRawValue() : null));
        putIfAbsent(raw, TelemetryStorageJsonKeys.RAW_BYTES, metadata.get(ProcessResultMetadataKeys.RAW_BYTES));
        putIfAbsent(raw, CommonMapKeys.PROTOCOL, protocolType != null ? protocolType : "UNKNOWN");
        putIfAbsent(raw, TelemetryStorageJsonKeys.UNIT_ID, point != null ? point.getUnitId() : null);
        putIfAbsent(raw, TelemetryStorageJsonKeys.COLLECT_TIME, resolveCollectTime(metadata, eventTs));
        return raw;
    }

    /**
     * 创建并返回业务对象。
     */
    private Map<String, Object> buildProcessedJson(DataPoint point,
                                                   ProcessResult processResult,
                                                   Object finalValue,
                                                   Map<String, Object> metadata,
                                                   long eventTs) {
        Map<String, Object> processed = new LinkedHashMap<>();
        mergeMap(processed, metadata.get(TelemetryStorageJsonKeys.PROCESSED_JSON));
        putIfAbsent(processed, CommonMapKeys.POINT_CODE, point != null ? point.getPointCode() : null);
        putIfAbsent(processed, CommonMapKeys.POINT_NAME, point != null ? point.getPointName() : null);
        putIfAbsent(processed, CommonMapKeys.VALUE, firstNonNull(metadata.get(ProcessResultMetadataKeys.PROCESSED_VALUE), finalValue));
        putIfAbsent(processed, TelemetryStorageJsonKeys.DATA_TYPE, resolveProcessedDataType(point, finalValue));
        putIfAbsent(processed, CommonMapKeys.QUALITY, resolveQualityText(processResult));
        putIfAbsent(processed, CommonMapKeys.TIMESTAMP, eventTs);
        return processed;
    }

    /**
     * 创建并返回业务对象。
     */
    private Map<String, Object> buildMetadataJson(String deviceId,
                                                  String protocolType,
                                                  DataPoint point,
                                                  ProcessResult processResult,
                                                  Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<>();
        mergeMap(result, metadata.get(TelemetryStorageJsonKeys.METADATA_JSON));
        putIfAbsent(result, CommonMapKeys.DEVICE_ID, firstNonNull(deviceId, point != null ? point.getDeviceId() : null));
        putIfAbsent(result, CommonMapKeys.DEVICE_NAME, firstNonNull(point != null ? point.getDeviceName() : null, metadata.get(CommonMapKeys.DEVICE_NAME)));
        putIfAbsent(result, CommonMapKeys.POINT_ID, point != null ? point.getPointId() : null);
        putIfAbsent(result, TelemetryStorageJsonKeys.PROTOCOL_TYPE, protocolType != null ? protocolType : "UNKNOWN");
        putIfAbsent(result, CommonMapKeys.COLLECTOR_ID, firstNonNull(metadata.get(ProcessResultMetadataKeys.COLLECTOR_ID), additionalConfig(point, CommonMapKeys.COLLECTOR_ID)));
        putIfAbsent(result, CommonMapKeys.BATCH_ID, firstNonNull(metadata.get(ProcessResultMetadataKeys.BATCH_ID), additionalConfig(point, CommonMapKeys.BATCH_ID)));
        putIfAbsent(result, CommonMapKeys.GROUP_ID, firstNonNull(point != null ? point.getGroupId() : null, metadata.get(ProcessResultMetadataKeys.GROUP_ID)));
        putIfAbsent(result, CommonMapKeys.SOURCE, firstNonNull(metadata.get(ProcessResultMetadataKeys.SOURCE), "POLLING"));
        putIfAbsent(result, TelemetryStorageJsonKeys.COLLECTION_INTERVAL, resolveCollectionInterval(deviceId, point));
        putIfAbsent(result, CommonMapKeys.PROCESSING_VERSION, firstNonNull(metadata.get(ProcessResultMetadataKeys.PROCESSING_VERSION), additionalConfig(point, CommonMapKeys.PROCESSING_VERSION)));
        putIfAbsent(result, TelemetryStorageJsonKeys.REPORT_ENABLED, point != null ? point.isReportEnabled() : null);
        putIfAbsent(result, TelemetryStorageJsonKeys.ALARM_ENABLED, point != null && point.getAlarmEnabled() != null ? point.getAlarmEnabled() == 1 : null);
        copyCustomMetadata(result, metadata);
        if (processResult != null && processResult.getProcessorName() != null) {
            putIfAbsent(result, TelemetryStorageJsonKeys.PROCESSOR_NAME, processResult.getProcessorName());
        }
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> metadataOf(ProcessResult processResult) {
        if (processResult == null || processResult.getMetadata() == null) {
            return Map.of();
        }
        return processResult.getMetadata();
    }

    /**
     * 执行当前业务逻辑。
     */
    private void copyCustomMetadata(Map<String, Object> target, Map<String, Object> metadata) {
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || INTERNAL_METADATA_KEYS.contains(entry.getKey())) {
                continue;
            }
            target.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void mergeMap(Map<String, Object> target, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            target.putIfAbsent(String.valueOf(entry.getKey()), entry.getValue());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private Object additionalConfig(DataPoint point, String key) {
        if (point == null || key == null) {
            return null;
        }
        try {
            return point.getAdditionalConfig(key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private Object resolveCollectionInterval(String deviceId, DataPoint point) {
        if (point == null) {
            return null;
        }
        return pointRuntimeStateService.snapshot(deviceId, point).currentCollectionInterval();
    }

    /**
     * 解析或转换业务数据。
     */
    private long resolveCollectTime(Map<String, Object> metadata, long eventTs) {
        Object value = metadata.get(ProcessResultMetadataKeys.COLLECT_TIME);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return eventTs;
            }
        }
        return eventTs;
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveProcessedDataType(DataPoint point, Object finalValue) {
        if (finalValue instanceof Double || finalValue instanceof Float) {
            return "double";
        }
        if (finalValue instanceof Number) {
            return "long";
        }
        if (finalValue instanceof Boolean) {
            return "boolean";
        }
        if (finalValue instanceof CharSequence) {
            return "string";
        }
        String dataType = point != null ? point.getDataType() : null;
        return dataType != null ? dataType.toLowerCase(Locale.ROOT) : null;
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveQualityText(ProcessResult processResult) {
        if (processResult == null) {
            return null;
        }
        return QualityEnum.fromCode(processResult.getQuality()).getText();
    }

    /**
     * 执行当前业务逻辑。
     */
    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    /**
     * 执行当前业务逻辑。
     */
    private void putIfAbsent(Map<String, Object> target, String key, Object value) {
        if (value != null && !target.containsKey(key)) {
            target.put(key, value);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveSubTableName(String deviceId) {
        String prefix = sanitizeIdentifier(properties.getSubTablePrefix());
        return prefix + sanitizeIdentifier(deviceId);
    }

    /**
     * 执行当前业务逻辑。
     */
    private String sanitizeIdentifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String value = raw.replaceAll("[^a-zA-Z0-9_]", "_");
        if (!Character.isLetter(value.charAt(0)) && value.charAt(0) != '_') {
            value = "_" + value;
        }
        return value.toLowerCase();
    }

    /**
     * 执行当前业务逻辑。
     */
    private String escapeTag(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    /**
     * 解析或转换业务数据。
     */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.debug("序列化 遥测 载荷 to json 失败", e);
            return String.valueOf(value);
        }
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    private record TelemetryPayload(String rawJson, String processedJson, String metadataJson) {
    }
}

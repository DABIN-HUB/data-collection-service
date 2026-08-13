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
import com.wangbin.collector.storage.repository.TelemetryInsertRow;
import com.wangbin.collector.storage.repository.TdengineTableRows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
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
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class TimeSeriesService {

    private static final String TELEMETRY_UNIT_COLUMN = "unit";
    private static final String POINT_KEY_COLUMN = "point_key";
    private static final String COMPOSITE_KEY_NOTE = "COMPOSITE KEY";
    private static final String V2_SUFFIX = "_v2";
    private static final int POINT_KEY_MAX_BYTES = 128;
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
    private final Map<TdengineWriteMode, TdengineTelemetryWriter> writersByMode;
    private final TdengineWriteMetricRecorder writeMetricRecorder;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);
    private final Map<String, Boolean> ensuredTables = new ConcurrentHashMap<>();

    @Autowired
    public TimeSeriesService(DataRepository dataRepository,
                             DeviceRepository deviceRepository,
                             TdengineProperties properties,
                             ObjectMapper objectMapper,
                             PointRuntimeStateService pointRuntimeStateService,
                             List<TdengineTelemetryWriter> telemetryWriters,
                             TdengineWriteMetricRecorder writeMetricRecorder) {
        this.dataRepository = dataRepository;
        this.deviceRepository = deviceRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.pointRuntimeStateService = pointRuntimeStateService;
        this.writersByMode = resolveWriters(telemetryWriters);
        this.writeMetricRecorder = writeMetricRecorder;
    }

    TimeSeriesService(DataRepository dataRepository,
                      DeviceRepository deviceRepository,
                      TdengineProperties properties,
                      ObjectMapper objectMapper,
                      PointRuntimeStateService pointRuntimeStateService) {
        this(dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                pointRuntimeStateService,
                List.of(new MybatisTdengineTelemetryWriter(dataRepository)),
                new TdengineWriteMetricRecorder());
    }

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
        String superTableV2 = resolveV2Name(superTable);
        AppendRequest request = new AppendRequest(deviceId, protocolType, point, processResult, eventTs);
        TdengineWriteTarget target = resolveWriteTarget(database, superTableV2, request);
        TelemetryInsertRow row = buildInsertRow(request);

        try {
            recordWriteSuccess(activeWriter().writeSingle(target, row));
        } catch (TdengineWriteException exception) {
            recordWriteFailure(exception.outcome());
            throw exception;
        } catch (RuntimeException exception) {
            recordWriteFailure(null);
            throw exception;
        }
    }

    /**
     * 批量写入历史数据，调用方传入的数据会按 V2 子表分组后分别写入。
     */
    public void appendBatch(List<AppendRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        if (properties.getWrite().isMultiTableEnabled()) {
            appendMultiTableBatch(requests);
            return;
        }
        Map<TdengineWriteTarget, List<TelemetryInsertRow>> groupedRows = groupRows(requests);
        for (Map.Entry<TdengineWriteTarget, List<TelemetryInsertRow>> entry : groupedRows.entrySet()) {
            List<TelemetryInsertRow> rows = entry.getValue();
            if (!rows.isEmpty()) {
                try {
                    recordWriteSuccess(activeWriter().writeBatch(entry.getKey(), rows));
                } catch (TdengineWriteException exception) {
                    recordWriteFailure(exception.outcome());
                    throw exception;
                } catch (RuntimeException exception) {
                    recordWriteFailure(null);
                    throw exception;
                }
            }
        }
    }

    /**
     * 跨 V2 子表批量写入历史数据，保持每个设备仍写入自己的子表。
     */
    public void appendMultiTableBatch(List<AppendRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        if (!properties.getWrite().isMultiTableEnabled()) {
            appendBatch(requests);
            return;
        }
        Map<TdengineWriteTarget, List<TelemetryInsertRow>> groupedRows = groupRows(requests);
        List<TdengineTableRows> tables = new ArrayList<>();
        int rows = 0;
        String database = null;
        for (Map.Entry<TdengineWriteTarget, List<TelemetryInsertRow>> entry : groupedRows.entrySet()) {
            List<TelemetryInsertRow> tableRows = entry.getValue();
            if (tableRows.isEmpty()) {
                continue;
            }
            database = entry.getKey().database();
            rows += tableRows.size();
            tables.add(new TdengineTableRows(entry.getKey().subTable(), tableRows));
        }
        if (tables.isEmpty()) {
            return;
        }
        if (tables.size() == 1) {
            List<TelemetryInsertRow> tableRows = tables.get(0).rows();
            try {
                recordWriteSuccess(activeWriter().writeBatch(
                        new TdengineWriteTarget(database, tables.get(0).subTable()), tableRows));
            } catch (TdengineWriteException exception) {
                recordWriteFailure(exception.outcome());
                throw exception;
            } catch (RuntimeException exception) {
                recordWriteFailure(null);
                throw exception;
            }
            return;
        }
        try {
            recordWriteSuccess(activeWriter().writeMultiTableBatch(database, tables));
        } catch (TdengineWriteException exception) {
            recordWriteFailure(exception.outcome());
            throw exception;
        } catch (RuntimeException exception) {
            recordWriteFailure(null);
            throw exception;
        }
    }

    /**
     * 返回 TDengine 写入请求级指标，用于容量测试区分 rows/s 和 request/s。
     */
    public TdengineWriteMetrics writeMetrics() {
        return writeMetricRecorder.snapshot();
    }

    /**
     * 清理写入路径观测窗口，用于 warmup 之后重新统计 measurement latency。
     */
    public void resetWriteMetrics() {
        writeMetricRecorder.reset();
    }

    private Map<TdengineWriteTarget, List<TelemetryInsertRow>> groupRows(List<AppendRequest> requests) {
        ensureSchema();
        String database = sanitizeIdentifier(properties.getDatabase());
        String superTable = sanitizeIdentifier(properties.getSuperTable());
        String superTableV2 = resolveV2Name(superTable);
        Map<TdengineWriteTarget, List<TelemetryInsertRow>> groupedRows = new LinkedHashMap<>();
        for (AppendRequest request : requests) {
            if (request == null) {
                continue;
            }
            TdengineWriteTarget target = resolveWriteTarget(database, superTableV2, request);
            groupedRows.computeIfAbsent(target, ignored -> new ArrayList<>())
                    .add(buildInsertRow(request));
        }
        return groupedRows;
    }

    static String resolvePointStorageKey(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("TDengine V2 写入缺少点位信息，无法生成稳定 point_key");
        }
        String identity = firstNonBlank(point.getPointId());
        if (identity == null) {
            Long id = point.getId();
            if (id != null) {
                identity = "id:" + id;
            }
        }
        if (identity == null) {
            throw new IllegalArgumentException("TDengine V2 写入缺少 pointId/id，无法生成稳定 point_key");
        }
        int byteLength = identity.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > POINT_KEY_MAX_BYTES) {
            throw new IllegalArgumentException("TDengine V2 point_key 超过 128 字节，无法无碰撞保存: " + identity);
        }
        return identity;
    }

    private TdengineWriteTarget resolveWriteTarget(String database, String superTableV2, AppendRequest request) {
        String subTableV2 = resolveV2Name(resolveSubTableName(request.deviceId()));
        ensureSubTable(database, superTableV2, subTableV2, request.deviceId(), request.protocolType());
        return new TdengineWriteTarget(database, subTableV2);
    }

    private TelemetryInsertRow buildInsertRow(AppendRequest request) {
        DataPoint point = request.point();
        ProcessResult processResult = request.processResult();
        long eventTs = request.eventTs();
        Object finalValue = processResult != null ? processResult.getFinalValue() : null;
        TelemetryPayload payload = buildPayload(
                request.deviceId(), request.protocolType(), point, processResult, finalValue, eventTs);
        return new TelemetryInsertRow(
                eventTs,
                resolvePointStorageKey(point),
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

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
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
        String database = sanitizeIdentifier(properties.getDatabase());
        String v1SubTable = resolveSubTableName(deviceId);
        String v2SubTable = resolveV2Name(v1SubTable);
        List<Map<String, Object>> v2Rows = queryOptionalHistory(
                true,
                database,
                v2SubTable,
                pointId,
                startTs,
                endTs,
                guardedLimit
        );
        List<Map<String, Object>> v1Rows = queryOptionalHistory(
                false,
                database,
                v1SubTable,
                pointId,
                startTs,
                endTs,
                guardedLimit
        );
        return mergeHistoryRows(v2Rows, v1Rows, guardedLimit);
    }

    /**
     * 校验业务条件和参数边界。
     */
    private List<Map<String, Object>> queryOptionalHistory(boolean v2,
                                                           String database,
                                                           String subTable,
                                                           String pointId,
                                                           Long startTs,
                                                           Long endTs,
                                                           int limit) {
        try {
            if (v2) {
                return dataRepository.queryPointHistoryV2(database, subTable, pointId, startTs, endTs, limit);
            }
            return dataRepository.queryPointHistory(database, subTable, pointId, startTs, endTs, limit);
        } catch (RuntimeException exception) {
            if (isMissingTableError(exception, subTable)) {
                log.debug("TDengine 历史表不存在，按空结果处理:{}.{}", database, subTable);
                return List.of();
            }
            throw exception;
        }
    }

    private List<Map<String, Object>> mergeHistoryRows(List<Map<String, Object>> v2Rows,
                                                       List<Map<String, Object>> v1Rows,
                                                       int limit) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : v2Rows) {
            merged.put(historyIdentity(row), row);
        }
        for (Map<String, Object> row : v1Rows) {
            merged.putIfAbsent(historyIdentity(row), row);
        }
        return merged.values().stream()
                .sorted(this::compareHistoryRow)
                .limit(limit)
                .toList();
    }

    private String historyIdentity(Map<String, Object> row) {
        return rowText(row, "point_id", "pointId") + '\u0000' + rowLong(row, "event_ts", "eventTs");
    }

    private int compareHistoryRow(Map<String, Object> left, Map<String, Object> right) {
        int eventTs = Long.compare(
                rowLong(right, "event_ts", "eventTs"),
                rowLong(left, "event_ts", "eventTs"));
        if (eventTs != 0) {
            return eventTs;
        }
        return rowText(left, "point_id", "pointId")
                .compareTo(rowText(right, "point_id", "pointId"));
    }

    private long rowLong(Map<String, Object> row, String... keys) {
        Object value = rowValue(row, keys);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof CharSequence text && !text.toString().isBlank()) {
            try {
                return Long.parseLong(text.toString().trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private String rowText(Map<String, Object> row, String... keys) {
        Object value = rowValue(row, keys);
        return value == null ? "" : textValue(value);
    }

    private Object rowValue(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key)) {
                return row.get(key);
            }
            String camelKey = toCamelCase(key);
            if (row.containsKey(camelKey)) {
                return row.get(camelKey);
            }
        }
        return null;
    }

    private String toCamelCase(String value) {
        StringBuilder builder = new StringBuilder();
        boolean upperNext = false;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '_') {
                upperNext = true;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(ch) : ch);
            upperNext = false;
        }
        return builder.toString();
    }

    private static String textValue(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private boolean isMissingTableError(RuntimeException exception, String subTable) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String lowerMessage = message.toLowerCase(Locale.ROOT);
        boolean missingTable = lowerMessage.contains("not exist")
                || lowerMessage.contains("does not exist")
                || lowerMessage.contains("table does not exist");
        return missingTable && (lowerMessage.contains(subTable.toLowerCase(Locale.ROOT))
                || lowerMessage.contains("table"));
    }

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
            String superTableV2 = resolveV2Name(superTable);
            dataRepository.createDatabase(database, properties.getKeepDays());
            dataRepository.createStable(database, superTable);
            dataRepository.createStableV2(database, superTableV2);
            ensureTelemetryUnitColumn(database, superTable);
            validateV2StableSchema(database, superTableV2);
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
    void validateV2StableSchema(String database, String superTable) {
        validateV2StableSchema(database, superTable, dataRepository.showCreateStable(database, superTable));
    }

    static void validateV2StableSchema(String database,
                                       String superTable,
                                       List<Map<String, Object>> createRows) {
        String createSql = createRows == null ? "" : createRows.stream()
                .flatMap(row -> row.values().stream())
                .filter(value -> value != null)
                .map(TimeSeriesService::textValue)
                .reduce("", (left, right) -> left + " " + right);
        String normalized = createSql.toUpperCase(Locale.ROOT);
        if (!normalized.contains(POINT_KEY_COLUMN.toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("TDengine V2 超级表缺少 point_key 复合主键列: "
                    + database + "." + superTable + ", ddl=" + createSql);
        }
        if (!normalized.contains("VARCHAR") || !normalized.contains(COMPOSITE_KEY_NOTE)) {
            throw new IllegalStateException("TDengine V2 超级表 point_key 不是 VARCHAR COMPOSITE KEY: "
                    + database + "." + superTable + ", ddl=" + createSql);
        }
    }

    private void ensureSubTable(String database,
                                String superTable,
                                String subTable,
                                String deviceTag,
                                String protocolTag) {
        writeMetricRecorder.recordEnsureSubTableCall();
        if (Boolean.TRUE.equals(ensuredTables.get(subTable))) {
            writeMetricRecorder.recordEnsureSubTableCacheHit();
            return;
        }
        synchronized (ensuredTables) {
            if (Boolean.TRUE.equals(ensuredTables.get(subTable))) {
                writeMetricRecorder.recordEnsureSubTableCacheHit();
                return;
            }
            writeMetricRecorder.recordEnsureSubTableCacheMiss();
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
    static String resolveV2Name(String name) {
        return name + V2_SUFFIX;
    }

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
    /**
     * 批量写入入口参数，保持与单条 append 相同的业务字段。
     */
    private TdengineTelemetryWriter activeWriter() {
        TdengineWriteMode mode = properties.getWrite().getMode();
        TdengineTelemetryWriter writer = writersByMode.get(mode);
        if (writer == null) {
            throw new IllegalStateException("TDengine 写入模式未装配: " + mode);
        }
        return writer;
    }

    private void recordWriteSuccess(TdengineWriteOutcome outcome) {
        writeMetricRecorder.recordSuccess(outcome);
    }

    private void recordWriteFailure(TdengineWriteOutcome outcome) {
        writeMetricRecorder.recordFailure(outcome);
    }

    private Map<TdengineWriteMode, TdengineTelemetryWriter> resolveWriters(List<TdengineTelemetryWriter> telemetryWriters) {
        Map<TdengineWriteMode, TdengineTelemetryWriter> result = new EnumMap<>(TdengineWriteMode.class);
        if (telemetryWriters != null) {
            for (TdengineTelemetryWriter writer : telemetryWriters) {
                if (writer != null) {
                    result.put(writer.mode(), writer);
                }
            }
        }
        return Map.copyOf(result);
    }

    public record AppendRequest(String deviceId,
                                String protocolType,
                                DataPoint point,
                                ProcessResult processResult,
                                long eventTs) {
    }

    private record TelemetryPayload(String rawJson, String processedJson, String metadataJson) {
    }
}

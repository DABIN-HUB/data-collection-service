package com.wangbin.collector.storage.service;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.alert.AlertNotification;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.repository.AlarmRepository;
import com.wangbin.collector.storage.repository.DataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 处理当前模块的业务服务。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class AlarmHistoryService {

    private static final String ALARM_EVENT_TYPE_COLUMN = "alarm_event_type";

    private final AlarmRepository alarmRepository;
    private final DataRepository dataRepository;
    private final TdengineProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);
    private final Map<String, Boolean> ensuredTables = new ConcurrentHashMap<>();

    /**
     * 创建当前组件实例。
     */
    public AlarmHistoryService(AlarmRepository alarmRepository,
                               DataRepository dataRepository,
                               TdengineProperties properties,
                               ObjectMapper objectMapper,
                               @Qualifier("cacheAsyncExecutor") Executor executor) {
        this.alarmRepository = alarmRepository;
        this.dataRepository = dataRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /**
     * 写入或持久化业务数据。
     */
    public void saveAsync(AlertNotification notification) {
        if (!shouldSave(notification)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    save(notification);
                } catch (Exception e) {
                    log.error("save 告警 历史 失败, 设备={}, 点位={}",
                            notification.getDeviceId(), notification.getPointId(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("告警 历史 写入 被拒绝, 设备={}, 点位={}, 原因={}",
                    notification.getDeviceId(), notification.getPointId(), e.getMessage());
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    public void save(AlertNotification notification) {
        if (!shouldSave(notification)) {
            return;
        }
        ensureSchema();

        String database = sanitizeIdentifier(properties.getDatabase());
        String superTable = sanitizeIdentifier(properties.getAlarmSuperTable());
        String subTable = resolveSubTableName(notification.getDeviceId());
        ensureSubTable(database, superTable, subTable, notification.getDeviceId());

        Object value = notification.getValue();
        String valueText = value != null ? String.valueOf(value) : null;
        Double valueDouble = value instanceof Number n ? n.doubleValue() : null;
        Long valueLong = value instanceof Number n ? n.longValue() : null;
        Boolean valueBool = value instanceof Boolean b ? b : null;
        long eventTs = notification.getTimestamp() > 0 ? notification.getTimestamp() : System.currentTimeMillis();

        alarmRepository.insertAlarm(
                database,
                subTable,
                eventTs,
                notification.getDeviceName(),
                notification.getPointId(),
                notification.getPointCode(),
                notification.getRuleId(),
                notification.getRuleName(),
                notification.getLevel(),
                notification.getEventType(),
                notification.getMessage(),
                valueText,
                valueDouble,
                valueLong,
                valueBool,
                notification.getUnit(),
                toJson(notification),
                notification.getEventId(),
                notification.getRelatedEventId(),
                notification.getStartedAt() > 0 ? notification.getStartedAt() : null,
                notification.getLastOccurredAt() > 0 ? notification.getLastOccurredAt() : null,
                notification.getDurationMillis()
        );
    }

    /**
     * 查询并返回业务数据。
     */
    public List<Map<String, Object>> queryAlarmHistory(String deviceId,
                                                       String pointId,
                                                       String pointCode,
                                                       String level,
                                                       String ruleId,
                                                       Long startTs,
                                                       Long endTs,
                                                       Integer limit) {
        if (!properties.isEnabled() || deviceId == null || deviceId.isBlank()) {
            return Collections.emptyList();
        }
        ensureSchema();
        int resolvedLimit = limit == null || limit <= 0 ? properties.getQueryDefaultLimit() : limit;
        int guardedLimit = Math.max(1, Math.min(resolvedLimit, properties.getQueryMaxLimit()));
        List<Map<String, Object>> rows = alarmRepository.queryAlarmHistory(
                sanitizeIdentifier(properties.getDatabase()),
                resolveSubTableName(deviceId),
                blankToNull(pointId),
                blankToNull(pointCode),
                blankToNull(level),
                blankToNull(ruleId),
                startTs,
                endTs,
                guardedLimit
        );
        rows.forEach(this::addCompatibilityKeys);
        return rows;
    }

    /**
     * 查询全局最近告警记录，面向首页和监控页聚合展示。
     */
    public List<Map<String, Object>> queryRecentAlarmHistory(String deviceId,
                                                             String pointId,
                                                             String pointCode,
                                                             String level,
                                                             String ruleId,
                                                             Long startTs,
                                                             Long endTs,
                                                             Integer limit) {
        if (!properties.isEnabled()) {
            return Collections.emptyList();
        }
        ensureSchema();
        int resolvedLimit = limit == null || limit <= 0 ? properties.getQueryDefaultLimit() : limit;
        int guardedLimit = Math.max(1, Math.min(resolvedLimit, properties.getQueryMaxLimit()));
        List<Map<String, Object>> rows = alarmRepository.queryRecentAlarmHistory(
                sanitizeIdentifier(properties.getDatabase()),
                sanitizeIdentifier(properties.getAlarmSuperTable()),
                blankToNull(deviceId),
                blankToNull(pointId),
                blankToNull(pointCode),
                blankToNull(level),
                blankToNull(ruleId),
                startTs,
                endTs,
                guardedLimit
        );
        rows.forEach(this::addCompatibilityKeys);
        return rows;
    }

    /**
     * 记录或统计业务状态。
     */
    public long countRecentAlarmHistory(String deviceId,
                                        String pointId,
                                        String pointCode,
                                        String level,
                                        String ruleId,
                                        Long startTs,
                                        Long endTs) {
        if (!properties.isEnabled()) {
            return 0L;
        }
        ensureSchema();
        return alarmRepository.countRecentAlarmHistory(
                sanitizeIdentifier(properties.getDatabase()),
                sanitizeIdentifier(properties.getAlarmSuperTable()),
                blankToNull(deviceId),
                blankToNull(pointId),
                blankToNull(pointCode),
                blankToNull(level),
                blankToNull(ruleId),
                startTs,
                endTs
        );
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** 查询触发事件，恢复事件不占用分页名额。 */
    public List<Map<String, Object>> queryRecentAlarmActivations(String deviceId, String pointId,
                                                                  String pointCode, String ruleId, String level,
                                                                  Long startTs, Long endTs, Integer limit) {
        if (!properties.isEnabled()) {
            return Collections.emptyList();
        }
        ensureSchema();
        int guardedLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 200));
        List<Map<String, Object>> rows = alarmRepository.queryRecentAlarmActivations(
                sanitizeIdentifier(properties.getDatabase()), sanitizeIdentifier(properties.getAlarmSuperTable()),
                blankToNull(deviceId), blankToNull(pointId), blankToNull(pointCode), blankToNull(ruleId),
                blankToNull(level), startTs, endTs, guardedLimit);
        rows.forEach(this::addCompatibilityKeys);
        return rows;
    }

    /** 按告警标识查询恢复事件，单次最多 200 个标识。 */
    public List<Map<String, Object>> queryRecoveriesByAlarmIds(List<String> alarmIds) {
        if (alarmIds == null || alarmIds.isEmpty() || !properties.isEnabled()) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(alarmIds.stream()
                .filter(id -> id != null && !id.isBlank()).map(String::trim).toList()));
        if (ids.size() > 200) {
            throw new IllegalArgumentException("单次最多查询 200 条告警恢复记录");
        }
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        ensureSchema();
        List<Map<String, Object>> rows = alarmRepository.queryRecoveriesByAlarmIds(
                sanitizeIdentifier(properties.getDatabase()), sanitizeIdentifier(properties.getAlarmSuperTable()), ids);
        rows.forEach(this::addCompatibilityKeys);
        return rows;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean shouldSave(AlertNotification notification) {
        return properties.isEnabled()
                && notification != null
                && notification.getDeviceId() != null
                && !notification.getDeviceId().isBlank();
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
            String superTable = sanitizeIdentifier(properties.getAlarmSuperTable());
            dataRepository.createDatabase(database, properties.getKeepDays());
            alarmRepository.createStable(database, superTable);
            synchronized (AlarmRepository.class) {
                ensureAlarmEventTypeColumn(database, superTable);
                for (Map.Entry<String, String> column : Map.of(
                        "alarm_id", "NCHAR(128)",
                        "related_alarm_id", "NCHAR(128)",
                        "alarm_started_at", "BIGINT",
                        "alarm_last_occurred_at", "BIGINT",
                        "alarm_duration_ms", "BIGINT").entrySet()) {
                    Long count = dataRepository.countColumn(database, superTable, column.getKey());
                    if (count == null || count == 0) {
                        alarmRepository.addAlarmLifecycleColumn(database, superTable, column.getKey(), column.getValue());
                    }
                }
            }
            schemaReady.set(true);
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureAlarmEventTypeColumn(String database, String superTable) {
        Long count = dataRepository.countColumn(database, superTable, ALARM_EVENT_TYPE_COLUMN);
        if (count != null && count > 0) {
            return;
        }
        alarmRepository.addAlarmEventTypeColumn(database, superTable);
        log.info("TDengine 告警 超级表 已升级 with 字段 {}:{}.{}",
                ALARM_EVENT_TYPE_COLUMN, database, superTable);
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureSubTable(String database,
                                String superTable,
                                String subTable,
                                String deviceTag) {
        if (Boolean.TRUE.equals(ensuredTables.get(subTable))) {
            return;
        }
        synchronized (ensuredTables) {
            if (Boolean.TRUE.equals(ensuredTables.get(subTable))) {
                return;
            }
            alarmRepository.createChildTable(database, subTable, superTable, escapeTag(deviceTag));
            ensuredTables.put(subTable, true);
            log.info("TDengine 告警 子表 ready:{}", subTable);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void addCompatibilityKeys(Map<String, Object> row) {
        if (row == null) {
            return;
        }
        for (Map.Entry<String, String> key : Map.of(
                "alarm_id", "alarmId", "related_alarm_id", "relatedAlarmId",
                "alarm_started_at", "alarmStartedAt", "alarm_last_occurred_at", "alarmLastOccurredAt",
                "alarm_duration_ms", "alarmDurationMs").entrySet()) {
            Object value = row.get(key.getKey());
            if (value != null) {
                row.putIfAbsent(key.getValue(), value);
            }
        }
        Object payload = row.getOrDefault("payload_json", row.get("payloadJson"));
        if (payload instanceof String json && !json.isBlank()) {
            try {
                Map<?, ?> legacy = objectMapper.readValue(json, Map.class);
                for (Map.Entry<String, String> field : Map.of(
                        "alarmId", "eventId", "relatedAlarmId", "relatedEventId",
                        "alarmStartedAt", "startedAt", "alarmLastOccurredAt", "lastOccurredAt",
                        "alarmDurationMs", "durationMillis").entrySet()) {
                    if (row.get(field.getKey()) == null && legacy.get(field.getValue()) != null) {
                        row.put(field.getKey(), legacy.get(field.getValue()));
                    }
                }
            } catch (JsonProcessingException ignored) {
                // 旧记录可能包含非 JSON 内容，保持已有查询结果。
            }
        }
        if (!row.containsKey("alarm_event_type")) {
            return;
        }
        Object value = row.get("alarm_event_type");
        row.putIfAbsent(CommonMapKeys.EVENT_TYPE, value);
        row.putIfAbsent("event_type", value);
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveSubTableName(String deviceId) {
        return sanitizeIdentifier(properties.getAlarmSubTablePrefix()) + sanitizeIdentifier(deviceId);
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
            log.debug("序列化 告警 通知 to json 失败", e);
            return String.valueOf(value);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

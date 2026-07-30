package com.wangbin.collector.storage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.monitor.alert.AlertNotification;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public void saveAsync(AlertNotification notification) {
        if (!shouldSave(notification)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    save(notification);
                } catch (Exception e) {
                    log.error("save alarm history failed, device={}, point={}",
                            notification.getDeviceId(), notification.getPointId(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("alarm history write rejected, device={}, point={}, reason={}",
                    notification.getDeviceId(), notification.getPointId(), e.getMessage());
        }
    }

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
                toJson(notification)
        );
    }

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
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    private boolean shouldSave(AlertNotification notification) {
        return properties.isEnabled()
                && notification != null
                && notification.getDeviceId() != null
                && !notification.getDeviceId().isBlank();
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
            String superTable = sanitizeIdentifier(properties.getAlarmSuperTable());
            dataRepository.createDatabase(database, properties.getKeepDays());
            alarmRepository.createStable(database, superTable);
            ensureAlarmEventTypeColumn(database, superTable);
            schemaReady.set(true);
        }
    }

    private void ensureAlarmEventTypeColumn(String database, String superTable) {
        Long count = dataRepository.countColumn(database, superTable, ALARM_EVENT_TYPE_COLUMN);
        if (count != null && count > 0) {
            return;
        }
        alarmRepository.addAlarmEventTypeColumn(database, superTable);
        log.info("TDengine alarm stable upgraded with column {}: {}.{}",
                ALARM_EVENT_TYPE_COLUMN, database, superTable);
    }

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
            log.info("TDengine alarm child table ready: {}", subTable);
        }
    }

    private void addCompatibilityKeys(Map<String, Object> row) {
        if (row == null || !row.containsKey("alarm_event_type")) {
            return;
        }
        Object value = row.get("alarm_event_type");
        row.putIfAbsent("eventType", value);
        row.putIfAbsent("event_type", value);
    }

    private String resolveSubTableName(String deviceId) {
        return sanitizeIdentifier(properties.getAlarmSubTablePrefix()) + sanitizeIdentifier(deviceId);
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

    private String escapeTag(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.debug("serialize alarm notification to json failed", e);
            return String.valueOf(value);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

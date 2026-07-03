package com.wangbin.collector.storage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.repository.DataRepository;
import com.wangbin.collector.storage.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class TimeSeriesService {

    private final DataRepository dataRepository;
    private final DeviceRepository deviceRepository;
    private final TdengineProperties properties;
    private final ObjectMapper objectMapper;
    private final TdengineSchemaInitializer schemaInitializer;
    private final Map<String, Boolean> ensuredTables = new ConcurrentHashMap<>();

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
        String valueText = finalValue != null ? String.valueOf(finalValue) : null;
        Double valueDouble = finalValue instanceof Number n ? n.doubleValue() : null;
        Long valueLong = finalValue instanceof Number n ? n.longValue() : null;
        Boolean valueBool = finalValue instanceof Boolean b ? b : null;

        dataRepository.insertTelemetry(
                database,
                subTable,
                eventTs,
                point != null ? point.getPointId() : null,
                point != null ? point.getPointCode() : null,
                point != null ? point.getPointName() : null,
                valueText,
                valueDouble,
                valueLong,
                valueBool,
                processResult != null ? processResult.getQuality() : null,
                processResult != null ? processResult.isSuccess() : null,
                processResult != null ? processResult.getMessage() : null,
                toJson(processResult != null ? processResult.getRawValue() : null),
                toJson(processResult != null ? processResult.getProcessedValue() : null),
                toJson(processResult != null ? processResult.getMetadata() : null)
        );
    }

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

    private void ensureSchema() {
        schemaInitializer.ensureTelemetrySuperTable();
    }

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
            log.info("TDengine child table ready: {}", subTable);
        }
    }

    private String resolveSubTableName(String deviceId) {
        String prefix = sanitizeIdentifier(properties.getSubTablePrefix());
        return prefix + sanitizeIdentifier(deviceId);
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
            log.debug("serialize value to json failed", e);
            return String.valueOf(value);
        }
    }
}

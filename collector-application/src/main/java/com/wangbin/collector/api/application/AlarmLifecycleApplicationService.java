package com.wangbin.collector.api.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.api.controller.dto.AlarmLifecycleResponse;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgement;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgementService;
import com.wangbin.collector.core.alarm.AlarmStateRepository;
import com.wangbin.collector.core.alarm.AlarmStateSnapshot;
import com.wangbin.collector.storage.service.AlarmHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 告警触发、恢复及确认记录的查询编排。 */
@Service
public class AlarmLifecycleApplicationService {
    private final AlarmHistoryService history;
    private final AlarmAcknowledgementService acknowledgements;
    private final ObjectMapper objectMapper;
    private final AlarmStateRepository states;

    @Autowired
    public AlarmLifecycleApplicationService(ObjectProvider<AlarmHistoryService> historyProvider,
                                            AlarmAcknowledgementService acknowledgements,
                                            ObjectMapper objectMapper,
                                            ObjectProvider<AlarmStateRepository> stateProvider) {
        this.history = historyProvider.getIfAvailable();
        this.acknowledgements = acknowledgements;
        this.objectMapper = objectMapper;
        this.states = stateProvider.getIfAvailable();
    }

    /** 保持已有三参数构造调用方的兼容性。 */
    public AlarmLifecycleApplicationService(ObjectProvider<AlarmHistoryService> historyProvider,
                                            AlarmAcknowledgementService acknowledgements,
                                            ObjectMapper objectMapper) {
        this.history = historyProvider.getIfAvailable();
        this.acknowledgements = acknowledgements;
        this.objectMapper = objectMapper;
        this.states = null;
    }

    /** 查询触发事件并叠加不限于级别过滤范围内的恢复事件。 */
    public AlarmLifecycleResponse query(String deviceId, String pointId, String pointCode, String ruleId,
                                        String level, String state, Long startTs, Long endTs, Integer limit) {
        if (history == null || !history.isEnabled()) {
            return new AlarmLifecycleResponse("disabled", List.of(), 0);
        }
        List<Map<String, Object>> activations = history.queryRecentAlarmActivations(
                deviceId, pointId, pointCode, ruleId, level, startTs, endTs, limit);
        List<String> ids = activations.stream().map(row -> text(row, "alarmId", "alarm_id", "eventId"))
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        Map<String, Map<String, Object>> recoveryById = new HashMap<>();
        for (Map<String, Object> recovery : history.queryRecoveriesByAlarmIds(ids)) {
            String relatedId = text(recovery, "relatedAlarmId", "related_alarm_id", "relatedEventId");
            if (relatedId != null) {
                recoveryById.putIfAbsent(relatedId, recovery);
            }
        }
        Map<String, AlarmAcknowledgement> ackById = acknowledgements.findAll(ids);
        List<AlarmLifecycleResponse.AlarmItem> items = new ArrayList<>();
        for (Map<String, Object> activation : activations) {
            String id = text(activation, "alarmId", "alarm_id", "eventId");
            Map<String, Object> recovery = recoveryById.get(id);
            AlarmAcknowledgement acknowledgement = ackById.get(id);
            long occurredAt = number(activation, "event_ts", "eventTs");
            long startedAt = number(activation, "alarmStartedAt", "alarm_started_at", "startedAt");
            if (startedAt <= 0) {
                startedAt = occurredAt;
            }
            Long recoveredAt = recovery == null ? null : number(recovery, "event_ts", "eventTs");
            Long duration = recovery == null ? null : number(recovery, "alarmDurationMs", "alarm_duration_ms", "durationMillis");
            String lifecycle = recovery != null ? "RECOVERED" : acknowledgement != null ? "ACKED" : "ACTIVE";
            long lastOccurredAt = recovery == null
                    ? number(activation, "alarmLastOccurredAt", "alarm_last_occurred_at", "lastOccurredAt")
                    : number(recovery, "alarmLastOccurredAt", "alarm_last_occurred_at", "lastOccurredAt");
            if (recovery == null && states != null && id != null) {
                lastOccurredAt = states.findByAlarmId(id).map(AlarmStateSnapshot::getLastOccurredAt)
                        .filter(value -> value > 0).orElse(lastOccurredAt);
            }
            if (lastOccurredAt <= 0) lastOccurredAt = occurredAt;
            if (state != null && !state.isBlank() && !state.equalsIgnoreCase(lifecycle)) {
                continue;
            }
            items.add(new AlarmLifecycleResponse.AlarmItem(id,
                    text(activation, "deviceId", "device_id"), text(activation, "deviceName", "device_name"),
                    text(activation, "pointId", "point_id"), text(activation, "pointCode", "point_code"),
                    text(activation, "ruleId", "rule_id"), text(activation, "ruleName", "rule_name"),
                    text(activation, "level", "alarm_level"), text(activation, "message"),
                    value(activation, "valueText", "value_text", "valueDouble", "value_double", "valueLong", "value_long", "value"),
                    text(activation, "unit"), lifecycle, acknowledgement != null,
                    acknowledgement == null ? null : acknowledgement.acknowledgedAt(),
                    acknowledgement == null ? null : acknowledgement.operator(),
                    acknowledgement == null ? null : acknowledgement.note(),
                    startedAt, occurredAt, lastOccurredAt, recoveredAt, duration));
        }
        return new AlarmLifecycleResponse("success", items, items.size());
    }

    private String text(Map<String, Object> row, String... keys) {
        Object value = value(row, keys);
        return value == null ? null : String.valueOf(value);
    }

    private long number(Map<String, Object> row, String... keys) {
        Object value = value(row, keys);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 历史记录字段格式不可解析时保留未知时间戳。
            }
        }
        return 0L;
    }

    private Object value(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.get(key) != null) {
                return row.get(key);
            }
        }
        Object json = row.getOrDefault("payload_json", row.get("payloadJson"));
        if (json instanceof String payload && !payload.isBlank()) {
            try {
                Map<?, ?> legacy = objectMapper.readValue(payload, Map.class);
                for (String key : keys) {
                    if (legacy.get(key) != null) {
                        return legacy.get(key);
                    }
                }
            } catch (JsonProcessingException ignored) {
                // 兼容历史不规范载荷，不中断查询。
            }
        }
        return null;
    }
}

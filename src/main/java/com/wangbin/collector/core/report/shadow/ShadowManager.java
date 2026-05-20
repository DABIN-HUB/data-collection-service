package com.wangbin.collector.core.report.shadow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.AlarmRule;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.enums.QualityEnum;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.report.config.ReportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备影子管理器：负责缓存设备最新属性、判断变化/事件触发，并维护需要刷新的设备列表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShadowManager {

    private static final String SHADOW_KEY_PREFIX = "collector:shadow:";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ReportProperties reportProperties;
    private final Map<String, DeviceShadow> shadows = new ConcurrentHashMap<>();
    private final Set<String> dirtyDevices = ConcurrentHashMap.newKeySet();

    @Autowired(required = false)
    @Qualifier("cacheRedisTemplate")
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    public ShadowUpdateResult apply(String deviceId, DataPoint point, ProcessResult result) {
        if (deviceId == null || point == null || result == null) {
            return ShadowUpdateResult.EMPTY;
        }

        DeviceShadow shadow = shadows.computeIfAbsent(deviceId, DeviceShadow::new);
        boolean changeTriggered = false;
        EventInfo eventInfo = null;
        String field = point.getReportField();


        if (field != null && point.isReportEnabled()) {
            QualityEnum qualityEnum = QualityEnum.fromCode(result.getQuality());
            ValueMeta meta = new ValueMeta(result.getFinalValue(), System.currentTimeMillis(), qualityEnum.getText());
            shadow.update(field, meta, point);
            dirtyDevices.add(deviceId);
            changeTriggered = shouldTriggerChange(shadow, point, result, field);
        }

        if (point.isEventReportingEnabled()) {
            String eventFieldKey = field != null
                    ? field
                    : Optional.ofNullable(point.getPointCode()).orElse(point.getPointId());
            eventInfo = evaluateEvent(shadow, point, result, eventFieldKey);
        }

        persistShadow(shadow);
        return new ShadowUpdateResult(changeTriggered, eventInfo);
    }

    private boolean shouldTriggerChange(DeviceShadow shadow,
                                        DataPoint point,
                                        ProcessResult result,
                                        String field) {
        if (field == null || !point.isChangeTriggerEnabled()) {
            return false;
        }

        Double threshold = point.getChangeThreshold();
        if (threshold == null || threshold <= 0) {
            return false;
        }

        Double lastReported = toDouble(shadow.getLastReportedValue(field));
        Double current = toDouble(result.getFinalValue());
        if (lastReported == null || current == null) {
            return false;
        }
        if (Math.abs(current - lastReported) < threshold) {
            return false;
        }

        long now = System.currentTimeMillis();
        long minInterval = point.getChangeMinIntervalMs(reportProperties.getMinReportIntervalMs());
        String changeKey = field + ":change";
        if (now - shadow.getLastChangeTriggerAt(changeKey) < minInterval) {
            return false;
        }
        shadow.markChangeTrigger(changeKey, now);
        return true;
    }

    private EventInfo evaluateEvent(DeviceShadow shadow,
                                    DataPoint point,
                                    ProcessResult result,
                                    String fieldKey) {
        long now = System.currentTimeMillis();
        long minInterval = point.getEventMinIntervalMs(reportProperties.getEventMinIntervalMs());

        EventInfo info = null;
        if (Strings.isNotBlank(fieldKey)) {
            info = matchAlarmRule(point, point.getAlarmRule(), result);
        }
        if (info == null) {
            info = evaluateProcessResultEvent(result);
        }
        if (info == null) {
            return null;
        }

        String eventType = info.eventType() != null ? info.eventType() : "EVENT";
        String eventKey = (fieldKey != null ? fieldKey : point.getPointId()) + "|" + eventType;
        if (now - shadow.getLastEventTriggerAt(eventKey) < minInterval) {
            return null;
        }

        String signatureBase = eventType + ":" + Objects.hash(info.message(), info.ruleId(), info.ruleName());
        if (now - shadow.getLastEventSignatureAt(signatureBase) < minInterval) {
            return null;
        }

        shadow.markEventTrigger(eventKey, now);
        shadow.markEventSignature(signatureBase, now);
        return info;
    }

    private EventInfo matchAlarmRule(DataPoint point, List<AlarmRule> rules, ProcessResult result) {
        if (point == null || point.getAlarmEnabled() == null || point.getAlarmEnabled() != 1) {
            return null;
        }
        if (CollectionUtils.isEmpty(rules)) {
            return null;
        }
        Double value = toDouble(result.getFinalValue());
        if (value == null) {
            return null;
        }
        for (AlarmRule rule : rules) {
            if (rule == null || Boolean.FALSE.equals(rule.getEnabled())) {
                continue;
            }
            if (rule.checkAlarm(value)) {
                return new EventInfo(
                        rule.getRuleId(),
                        rule.getRuleName(),
                        rule.getLevel(),
                        rule.getDescription() != null ? rule.getDescription() : "alarm triggered",
                        "ALARM"
                );
            }
        }
        return null;
    }

    private EventInfo evaluateProcessResultEvent(ProcessResult result) {
        Map<String, Object> metadata = result.getMetadata();
        if (metadata != null) {
            Object eventFlag = metadata.get("eventTriggered");
            if (eventFlag instanceof Boolean bool && bool) {
                return new EventInfo(
                        metadataToString(metadata, "ruleId"),
                        metadataToString(metadata, "ruleName"),
                        String.valueOf(metadata.getOrDefault("eventLevel", "WARNING")),
                        String.valueOf(metadata.getOrDefault("eventMessage", result.getMessage())),
                        String.valueOf(metadata.getOrDefault("eventType", "EVENT")));
            }
            if (metadata.get("eventType") != null) {
                return new EventInfo(
                        metadataToString(metadata, "ruleId"),
                        metadataToString(metadata, "ruleName"),
                        String.valueOf(metadata.getOrDefault("eventLevel", "INFO")),
                        String.valueOf(metadata.getOrDefault("eventMessage", result.getMessage())),
                        String.valueOf(metadata.get("eventType")));
            }
        }

        if (!result.isSuccess() || result.getQuality() < QualityEnum.WARNING.getCode()) {
            return new EventInfo(null, null, "WARNING",
                    result.getMessage() != null ? result.getMessage() : "quality degraded",
                    "QUALITY");
        }
        return null;
    }

    private String metadataToString(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value != null ? value.toString() : null;
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    public DeviceShadow getShadow(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        DeviceShadow shadow = shadows.get(deviceId);
        if (shadow != null) {
            return shadow;
        }
        DeviceShadow restored = loadShadow(deviceId);
        if (restored == null) {
            return null;
        }
        DeviceShadow existing = shadows.putIfAbsent(deviceId, restored);
        return existing != null ? existing : restored;
    }

    public Set<String> getDirtyDevices() {
        return Collections.unmodifiableSet(dirtyDevices);
    }

    public void clearDirty(String deviceId) {
        if (deviceId != null) {
            dirtyDevices.remove(deviceId);
        }
    }

    public void markReported(String deviceId, long windowStart, long windowEnd) {
        DeviceShadow shadow = getShadow(deviceId);
        if (shadow != null) {
            shadow.setLastReportAt(System.currentTimeMillis());
            shadow.setLastWindow(windowStart, windowEnd);
            persistShadow(shadow);
        }
        clearDirty(deviceId);
    }

    public void markReportedValues(String deviceId,
                                   Map<String, Object> properties,
                                   Map<String, Long> propertyTs) {
        DeviceShadow shadow = getShadow(deviceId);
        if (shadow != null) {
            shadow.markReportedValues(properties, propertyTs);
            persistShadow(shadow);
        }
    }

    public void removeShadow(String deviceId) {
        if (deviceId == null) {
            return;
        }
        shadows.remove(deviceId);
        dirtyDevices.remove(deviceId);
        deletePersistedShadow(deviceId);
    }

    public Map<String, Object> getShadowDocument(String deviceId) {
        DeviceShadow shadow = getShadow(deviceId);
        return shadow == null ? null : buildShadowDocument(shadow);
    }

    public Map<String, Object> getShadowDelta(String deviceId) {
        DeviceShadow shadow = getShadow(deviceId);
        if (shadow == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", shadow.getDeviceId());
        result.put("version", shadow.currentVersion());
        result.put("timestamp", shadow.getUpdatedAt());
        result.put("delta", toValueMap(shadow.deltaSnapshot()));
        result.put("metadata", toMetaMap(shadow.deltaSnapshot()));
        return result;
    }

    public Map<String, Object> updateDesired(String deviceId, Map<String, Object> desiredValues, String source) {
        return updateDesired(deviceId, desiredValues, source, null);
    }

    public Map<String, Object> updateDesired(String deviceId,
                                             Map<String, Object> desiredValues,
                                             String source,
                                             Long expectedVersion) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        DeviceShadow shadow = getShadow(deviceId);
        if (shadow == null) {
            shadow = shadows.computeIfAbsent(deviceId, DeviceShadow::new);
        }
        Map<String, Object> document;
        synchronized (shadow) {
            if (expectedVersion != null && shadow.currentVersion() != expectedVersion) {
                throw new IllegalStateException("shadow version conflict: expected="
                        + expectedVersion + ", actual=" + shadow.currentVersion());
            }
            shadow.updateDesired(desiredValues, source);
            document = buildShadowDocument(shadow);
        }
        persistDocument(deviceId, document);
        return document;
    }

    public Map<String, Object> clearDesired(String deviceId, Collection<String> fields) {
        DeviceShadow shadow = getShadow(deviceId);
        if (shadow == null) {
            return null;
        }
        shadow.clearDesired(fields);
        Map<String, Object> document = buildShadowDocument(shadow);
        persistDocument(deviceId, document);
        return document;
    }

    private Map<String, Object> buildShadowDocument(DeviceShadow shadow) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("deviceId", shadow.getDeviceId());
        doc.put("version", shadow.currentVersion());
        doc.put("timestamp", shadow.getUpdatedAt());
        doc.put("createdAt", shadow.getCreatedAt());
        doc.put("lastReportAt", shadow.getLastReportAt());
        doc.put("lastWindowStart", shadow.getLastWindowStart());
        doc.put("lastWindowEnd", shadow.getLastWindowEnd());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("reported", toValueMap(shadow.snapshot()));
        state.put("desired", toValueMap(shadow.desiredSnapshot()));
        state.put("delta", toValueMap(shadow.deltaSnapshot()));
        doc.put("state", state);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reported", toMetaMap(shadow.snapshot()));
        metadata.put("desired", toMetaMap(shadow.desiredSnapshot()));
        doc.put("metadata", metadata);

        return doc;
    }

    private Map<String, Object> toValueMap(Map<String, ValueMeta> metas) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (metas == null) {
            return values;
        }
        metas.forEach((field, meta) -> {
            if (field != null && meta != null) {
                values.put(field, meta.getValue());
            }
        });
        return values;
    }

    private Map<String, Object> toMetaMap(Map<String, ValueMeta> metas) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (metas == null) {
            return values;
        }
        metas.forEach((field, meta) -> {
            if (field == null || meta == null) {
                return;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("timestamp", meta.getTimestamp());
            metadata.put("updatedAt", meta.getUpdatedAt());
            if (meta.getQuality() != null) {
                metadata.put("quality", meta.getQuality());
            }
            if (meta.getSource() != null) {
                metadata.put("source", meta.getSource());
            }
            values.put(field, metadata);
        });
        return values;
    }

    private void persistShadow(DeviceShadow shadow) {
        if (shadow == null) {
            return;
        }
        persistDocument(shadow.getDeviceId(), buildShadowDocument(shadow));
    }

    private void persistDocument(String deviceId, Map<String, Object> document) {
        if (redisTemplate == null || deviceId == null || document == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(shadowKey(deviceId), document);
        } catch (Exception e) {
            log.warn("设备影子持久化失败 deviceId={} err={}", deviceId, e.getMessage());
        }
    }

    private DeviceShadow loadShadow(String deviceId) {
        if (redisTemplate == null || deviceId == null) {
            return null;
        }
        try {
            Object stored = redisTemplate.opsForValue().get(shadowKey(deviceId));
            if (stored == null) {
                return null;
            }
            Map<String, Object> document = toMap(stored);
            if (document.isEmpty()) {
                return null;
            }
            return restoreShadow(deviceId, document);
        } catch (Exception e) {
            log.warn("设备影子恢复失败 deviceId={} err={}", deviceId, e.getMessage());
            return null;
        }
    }

    private DeviceShadow restoreShadow(String fallbackDeviceId, Map<String, Object> document) {
        String deviceId = asString(document.get("deviceId"));
        DeviceShadow shadow = new DeviceShadow(deviceId != null ? deviceId : fallbackDeviceId);
        Long version = asLong(document.get("version"));
        if (version != null) {
            shadow.restoreVersion(version);
        }
        Long createdAt = asLong(document.get("createdAt"));
        if (createdAt != null) {
            shadow.setCreatedAt(createdAt);
        }
        Long updatedAt = asLong(document.get("timestamp"));
        if (updatedAt != null) {
            shadow.setUpdatedAt(updatedAt);
        }
        Long lastReportAt = asLong(document.get("lastReportAt"));
        if (lastReportAt != null) {
            shadow.setLastReportAt(lastReportAt);
        }
        Long lastWindowStart = asLong(document.get("lastWindowStart"));
        Long lastWindowEnd = asLong(document.get("lastWindowEnd"));
        if (lastWindowStart != null && lastWindowEnd != null) {
            shadow.setLastWindow(lastWindowStart, lastWindowEnd);
        }

        Map<String, Object> state = toMap(document.get("state"));
        Map<String, Object> metadata = toMap(document.get("metadata"));
        restoreValues(shadow, state.get("reported"), toMap(metadata.get("reported")), true);
        restoreValues(shadow, state.get("desired"), toMap(metadata.get("desired")), false);
        return shadow;
    }

    private void restoreValues(DeviceShadow shadow,
                               Object valuesObject,
                               Map<String, Object> metadata,
                               boolean reported) {
        Map<String, Object> values = toMap(valuesObject);
        values.forEach((field, value) -> {
            Map<String, Object> metaMap = toMap(metadata.get(field));
            long timestamp = Optional.ofNullable(asLong(metaMap.get("timestamp"))).orElse(System.currentTimeMillis());
            long updatedAt = Optional.ofNullable(asLong(metaMap.get("updatedAt"))).orElse(timestamp);
            String quality = asString(metaMap.get("quality"));
            String source = asString(metaMap.get("source"));
            ValueMeta meta = new ValueMeta(value, timestamp, quality, source, updatedAt);
            if (reported) {
                shadow.restoreReported(field, meta);
            } else {
                shadow.restoreDesired(field, meta);
            }
        });
    }

    private void deletePersistedShadow(String deviceId) {
        if (redisTemplate == null || deviceId == null) {
            return;
        }
        try {
            redisTemplate.delete(shadowKey(deviceId));
        } catch (Exception e) {
            log.warn("删除设备影子持久化数据失败 deviceId={} err={}", deviceId, e.getMessage());
        }
    }

    private String shadowKey(String deviceId) {
        return SHADOW_KEY_PREFIX + deviceId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        }
        if (objectMapper != null && value != null) {
            try {
                return objectMapper.convertValue(value, MAP_TYPE);
            } catch (Exception ignored) {
                return Collections.emptyMap();
            }
        }
        return Collections.emptyMap();
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    public record ShadowUpdateResult(boolean changeTriggered, EventInfo eventInfo) {
        public static final ShadowUpdateResult EMPTY = new ShadowUpdateResult(false, null);
    }

    public record EventInfo(String ruleId,
                            String ruleName,
                            String level,
                            String message,
                            String eventType) {
    }
}

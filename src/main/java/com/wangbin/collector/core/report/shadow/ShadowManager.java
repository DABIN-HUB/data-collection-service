package com.wangbin.collector.core.report.shadow;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.enums.QualityEnum;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.report.config.ReportProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 设备影子管理器：负责缓存设备最新属性、判断变化/事件触发，并维护需要刷新的设备列表。
 */
@Slf4j
@Component
public class ShadowManager {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String DEFAULT_SHADOW_KEY_PREFIX = "collector:shadow:";
    private static final String DEFAULT_DIRTY_SET_KEY = "collector:shadow:dirty";
    private static final Set<String> STABLE_VALUE_METADATA_KEYS = Set.of(
            "address",
            "objectType",
            "instanceNumber",
            "propertyIdentifier",
            "processingMode",
            "bacnetValueType",
            "bacnetComplexValue",
            "bacnetValueMetadata",
            "source"
    );
    private static final String SHADOW_CAS_SCRIPT = """
            local current = redis.call('GET', KEYS[1])
            local expected = ARGV[1]
            local nextVersion = ARGV[2]
            local ttlMs = tonumber(ARGV[3])
            local payload = ARGV[4]
            if current then
                local ok, doc = pcall(cjson.decode, current)
                local actual = '-1'
                if ok and type(doc) == 'table' then
                    if doc['version'] ~= nil then
                        actual = tostring(doc['version'])
                    elseif type(doc[2]) == 'table' and doc[2]['version'] ~= nil then
                        actual = tostring(doc[2]['version'])
                    end
                end
                if actual ~= expected then
                    return {0, actual}
                end
            elseif expected ~= '0' then
                return {0, '-1'}
            end
            if ttlMs and ttlMs > 0 then
                redis.call('PSETEX', KEYS[1], ttlMs, payload)
            else
                redis.call('SET', KEYS[1], payload)
            end
            return {1, nextVersion}
            """;

    private final ReportProperties reportProperties;
    private final Map<String, DeviceShadow> shadows = new ConcurrentHashMap<>();
    private final Set<String> dirtyDevices = ConcurrentHashMap.newKeySet();
    @Nullable
    private final RedisTemplate<String, Object> redisTemplate;
    @Nullable
    private final StringRedisTemplate stringRedisTemplate;
    @Nullable
    private final ObjectMapper objectMapper;

    /**
     * 创建设备影子管理器。
     */
    public ShadowManager(ReportProperties reportProperties,
                         @Qualifier("cacheRedisTemplate") @Nullable RedisTemplate<String, Object> redisTemplate,
                         @Nullable StringRedisTemplate stringRedisTemplate,
                         @Nullable ObjectMapper objectMapper) {
        this.reportProperties = reportProperties;
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理当前业务流程。
     */
    public ShadowUpdateResult apply(String deviceId, DataPoint point, ProcessResult result) {
        if (deviceId == null || point == null || result == null) {
            return ShadowUpdateResult.EMPTY;
        }
        String reportField = point.getReportField();
        boolean shouldMarkDirty = reportField != null && point.isReportEnabled();
        ShadowUpdateResult updateResult = mutateReportedShadow(deviceId,
                shadow -> applyToShadow(shadow, deviceId, point, result));
        if (shouldMarkDirty) {
            markDirty(deviceId);
        }
        return updateResult != null ? updateResult : ShadowUpdateResult.EMPTY;
    }

    /**
     * 处理当前业务流程。
     */
    private ShadowUpdateResult applyToShadow(DeviceShadow shadow,
                                             String deviceId,
                                             DataPoint point,
                                             ProcessResult result) {
        boolean changeTriggered = false;
        EventInfo eventInfo = null;
        String field = point.getReportField();

        if (field != null && point.isReportEnabled()) {
            QualityEnum qualityEnum = QualityEnum.fromCode(result.getQuality());
            ValueMeta meta = new ValueMeta(
                    result.getFinalValue(),
                    System.currentTimeMillis(),
                    qualityEnum.getText(),
                    metadataToString(result.getMetadata(), "source"),
                    System.currentTimeMillis(),
                    stableValueMetadata(result)
            );
            shadow.update(field, meta, point);
            changeTriggered = shouldTriggerChange(shadow, point, result, field);
        }

        if (point.isEventReportingEnabled()) {
            String eventFieldKey = field != null
                    ? field
                    : Optional.ofNullable(point.getPointCode()).orElse(point.getPointId());
            eventInfo = evaluateEvent(shadow, point, result, eventFieldKey);
        }
        return new ShadowUpdateResult(changeTriggered, eventInfo);
    }

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 执行当前业务逻辑。
     */
    private EventInfo evaluateEvent(DeviceShadow shadow,
                                    DataPoint point,
                                    ProcessResult result,
                                    String fieldKey) {
        long now = System.currentTimeMillis();
        long minInterval = point.getEventMinIntervalMs(reportProperties.getEventMinIntervalMs());

        EventInfo info = evaluateProcessResultEvent(result);
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

    /**
     * 执行当前业务逻辑。
     */
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
            if (metadata.get(CommonMapKeys.EVENT_TYPE) != null) {
                return new EventInfo(
                        metadataToString(metadata, "ruleId"),
                        metadataToString(metadata, "ruleName"),
                        String.valueOf(metadata.getOrDefault("eventLevel", "INFO")),
                        String.valueOf(metadata.getOrDefault("eventMessage", result.getMessage())),
                        String.valueOf(metadata.get(CommonMapKeys.EVENT_TYPE)));
            }
        }

        if (!result.isSuccess() || result.getQuality() < QualityEnum.WARNING.getCode()) {
            return new EventInfo(null, null, "WARNING",
                    result.getMessage() != null ? result.getMessage() : "quality degraded",
                    "QUALITY");
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private String metadataToString(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 解析或转换业务数据。
     */
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
        Set<String> devices = new java.util.LinkedHashSet<>(dirtyDevices);
        devices.addAll(loadPersistedDirtyDevices());
        return Collections.unmodifiableSet(devices);
    }

    /**
     * 清理或删除业务数据。
     */
    public void clearDirty(String deviceId) {
        if (deviceId != null) {
            dirtyDevices.remove(deviceId);
            removePersistedDirty(deviceId);
        }
    }

    /**
     * 记录或统计业务状态。
     */
    public void markReportedWindowCommitted(String deviceId, long windowStart, long windowEnd) {
        mutateReportedShadow(deviceId, shadow -> {
            shadow.markReportedWindowCommitted(System.currentTimeMillis(), windowStart, windowEnd);
            return shadow;
        });
        clearDirty(deviceId);
    }

    /**
     * 记录或统计业务状态。
     */
    public void markReportedValuesChunk(String deviceId,
                                        Map<String, Object> properties) {
        mutateReportedShadow(deviceId, shadow -> {
            shadow.markReportedValues(properties);
            return shadow;
        });
    }

    /**
     * 提交持久化发件箱对应的影子字段，并在确认没有后续消息时完成窗口。
     *
     * @param deviceId 本地设备ID
     * @param properties 已确认的属性
     * @param windowStart 窗口开始时间
     * @param windowEnd 窗口结束时间
     * @param allowWindowCommit 是否允许完成窗口
     */
    public void markOutboxReported(String deviceId,
                                   Map<String, Object> properties,
                                   long windowStart,
                                   long windowEnd,
                                   boolean allowWindowCommit) {
        Boolean committed = mutateReportedShadow(deviceId, shadow -> {
            shadow.markReportedValues(properties);
            if (allowWindowCommit && shadow.allLatestValuesReported()) {
                shadow.markReportedWindowCommitted(System.currentTimeMillis(), windowStart, windowEnd);
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
        if (Boolean.TRUE.equals(committed)) {
            clearDirty(deviceId);
        }
    }

    /**
     * 清理或删除业务数据。
     */
    public void removeShadow(String deviceId) {
        if (deviceId == null) {
            return;
        }
        shadows.remove(deviceId);
        clearDirty(deviceId);
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
        result.put(CommonMapKeys.DEVICE_ID, shadow.getDeviceId());
        result.put("version", shadow.currentVersion());
        result.put(CommonMapKeys.TIMESTAMP, shadow.getUpdatedAt());
        result.put("delta", toValueMap(shadow.deltaSnapshot()));
        result.put(CommonMapKeys.METADATA, toMetaMap(shadow.deltaSnapshot()));
        return result;
    }

    public List<Map<String, Object>> getShadowHistory(String deviceId, int limit) {
        if (!shadowHistoryEnabled() || stringRedisTemplate == null || objectMapper == null
                || deviceId == null || deviceId.isBlank()) {
            return List.of();
        }
        int resolvedLimit = limit <= 0 ? 20 : Math.min(limit, 200);
        try {
            List<String> values = stringRedisTemplate.opsForList()
                    .range(historyKey(deviceId), 0, resolvedLimit - 1L);
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> history = new ArrayList<>();
            for (String value : values) {
                Map<String, Object> item = toMap(value);
                if (!item.isEmpty()) {
                    history.add(item);
                }
            }
            return history;
        } catch (Exception e) {
            log.warn("查询设备影子历史失败 设备={} err={}", deviceId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 更新或刷新业务状态。
     */
    public Map<String, Object> updateDesired(String deviceId, Map<String, Object> desiredValues, String source) {
        return updateDesired(deviceId, desiredValues, source, null);
    }

    /**
     * 更新或刷新业务状态。
     */
    public Map<String, Object> updateDesired(String deviceId,
                                             Map<String, Object> desiredValues,
                                             String source,
                                             Long expectedVersion) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        if (expectedVersion == null && redisCasEnabled() && shadowAutoMergeEnabled()) {
            return updateDesiredWithAutoMerge(deviceId, desiredValues, source);
        }
        return updateDesiredStrict(deviceId, desiredValues, source, expectedVersion);
    }

    /**
     * 更新或刷新业务状态。
     */
    private Map<String, Object> updateDesiredStrict(String deviceId,
                                                    Map<String, Object> desiredValues,
                                                    String source,
                                                    Long expectedVersion) {
        DeviceShadow shadow = getShadow(deviceId);
        if (shadow == null) {
            shadow = shadows.computeIfAbsent(deviceId, DeviceShadow::new);
        }
        if (expectedVersion != null && redisCasEnabled()) {
            shadow = refreshLocalShadowIfVersionMismatch(deviceId, shadow, expectedVersion);
        }
        Map<String, Object> document;
        long previousVersion;
        long casExpectedVersion;
        synchronized (shadow) {
            if (expectedVersion != null && shadow.currentVersion() != expectedVersion) {
                throw new IllegalStateException("shadow version conflict: expected="
                        + expectedVersion + ", actual=" + shadow.currentVersion());
            }
            previousVersion = shadow.currentVersion();
            casExpectedVersion = expectedVersion != null ? expectedVersion : previousVersion;
            shadow.updateDesired(desiredValues, source);
            document = buildShadowDocument(shadow);
        }
        try {
            persistDocumentCas(deviceId, document, casExpectedVersion, "desired_update");
        } catch (IllegalStateException e) {
            reloadLocalShadow(deviceId);
            throw e;
        }
        return document;
    }

    /**
     * 清理或删除业务数据。
     */
    public Map<String, Object> clearDesired(String deviceId, Collection<String> fields) {
        DeviceShadow shadow = getShadow(deviceId);
        if (shadow == null) {
            return null;
        }
        if (redisCasEnabled() && shadowAutoMergeEnabled()) {
            return clearDesiredWithAutoMerge(deviceId, fields);
        }
        return clearDesiredStrict(deviceId, fields, shadow);
    }

    /**
     * 清理或删除业务数据。
     */
    private Map<String, Object> clearDesiredStrict(String deviceId,
                                                   Collection<String> fields,
                                                   DeviceShadow shadow) {
        Map<String, Object> document;
        long previousVersion;
        synchronized (shadow) {
            previousVersion = shadow.currentVersion();
            shadow.clearDesired(fields);
            document = buildShadowDocument(shadow);
        }
        try {
            persistDocumentCas(deviceId, document, previousVersion, "desired_clear");
        } catch (IllegalStateException e) {
            reloadLocalShadow(deviceId);
            throw e;
        }
        return document;
    }

    /**
     * 更新或刷新业务状态。
     */
    private Map<String, Object> updateDesiredWithAutoMerge(String deviceId,
                                                           Map<String, Object> desiredValues,
                                                           String source) {
        IllegalStateException lastConflict = null;
        int attempts = shadowMergeAttempts();
        for (int attempt = 0; attempt < attempts; attempt++) {
            DeviceShadow shadow = resolveWritableShadow(deviceId);
            Map<String, Object> document;
            long previousVersion;
            synchronized (shadow) {
                previousVersion = shadow.currentVersion();
                shadow.updateDesired(desiredValues, source);
                document = buildShadowDocument(shadow);
            }
            try {
                persistDocumentCas(deviceId, document, previousVersion, "desired_update");
                shadows.put(deviceId, shadow);
                return document;
            } catch (ShadowVersionConflictException e) {
                lastConflict = e;
                reloadLocalShadow(deviceId);
                log.debug("设备影子 desired CAS 冲突，准备自动合并重试 设备={} 尝试次数={} err={}",
                        deviceId, attempt + 1, e.getMessage());
            } catch (IllegalStateException e) {
                reloadLocalShadow(deviceId);
                throw e;
            }
        }
        if (lastConflict != null) {
            throw lastConflict;
        }
        return updateDesiredStrict(deviceId, desiredValues, source, null);
    }

    /**
     * 清理或删除业务数据。
     */
    private Map<String, Object> clearDesiredWithAutoMerge(String deviceId, Collection<String> fields) {
        IllegalStateException lastConflict = null;
        int attempts = shadowMergeAttempts();
        for (int attempt = 0; attempt < attempts; attempt++) {
            DeviceShadow shadow = resolveWritableShadow(deviceId);
            Map<String, Object> document;
            long previousVersion;
            synchronized (shadow) {
                previousVersion = shadow.currentVersion();
                shadow.clearDesired(fields);
                document = buildShadowDocument(shadow);
            }
            try {
                persistDocumentCas(deviceId, document, previousVersion, "desired_clear");
                shadows.put(deviceId, shadow);
                return document;
            } catch (ShadowVersionConflictException e) {
                lastConflict = e;
                reloadLocalShadow(deviceId);
                log.debug("设备影子 clear desired CAS 冲突，准备自动合并重试 设备={} 尝试次数={} err={}",
                        deviceId, attempt + 1, e.getMessage());
            } catch (IllegalStateException e) {
                reloadLocalShadow(deviceId);
                throw e;
            }
        }
        if (lastConflict != null) {
            throw lastConflict;
        }
        DeviceShadow shadow = getShadow(deviceId);
        return shadow == null ? null : clearDesiredStrict(deviceId, fields, shadow);
    }

    /**
     * 执行当前业务逻辑。
     */
    private <T> T mutateReportedShadow(String deviceId, Function<DeviceShadow, T> mutator) {
        if (deviceId == null || deviceId.isBlank() || mutator == null) {
            return null;
        }
        if (!redisCasEnabled()) {
            DeviceShadow shadow = resolveWritableShadow(deviceId);
            synchronized (shadow) {
                T result = mutator.apply(shadow);
                persistShadow(shadow);
                shadows.put(deviceId, shadow);
                return result;
            }
        }

        IllegalStateException lastConflict = null;
        int attempts = shadowMergeAttempts();
        for (int attempt = 0; attempt < attempts; attempt++) {
            DeviceShadow shadow = resolveWritableShadow(deviceId);
            long previousVersion;
            T result;
            Map<String, Object> document;
            synchronized (shadow) {
                previousVersion = shadow.currentVersion();
                result = mutator.apply(shadow);
                document = buildShadowDocument(shadow);
            }
            try {
                persistDocumentCas(deviceId, document, previousVersion, "reported_update");
                shadows.put(deviceId, shadow);
                return result;
            } catch (ShadowVersionConflictException e) {
                lastConflict = e;
                reloadLocalShadow(deviceId);
                log.debug("设备影子 reported CAS 冲突，准备重试，设备={} 尝试次数={} err={}",
                        deviceId, attempt + 1, e.getMessage());
            } catch (IllegalStateException e) {
                reloadLocalShadow(deviceId);
                throw e;
            }
        }
        if (lastConflict != null) {
            throw lastConflict;
        }
        return null;
    }

    /**
     * 解析或转换业务数据。
     */
    private DeviceShadow resolveWritableShadow(String deviceId) {
        DeviceShadow shadow = getShadow(deviceId);
        if (shadow != null) {
            return shadow;
        }
        DeviceShadow created = new DeviceShadow(deviceId);
        DeviceShadow existing = shadows.putIfAbsent(deviceId, created);
        return existing != null ? existing : created;
    }

    /**
     * 创建并返回业务对象。
     */
    private Map<String, Object> buildShadowDocument(DeviceShadow shadow) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(CommonMapKeys.DEVICE_ID, shadow.getDeviceId());
        doc.put("version", shadow.currentVersion());
        doc.put(CommonMapKeys.TIMESTAMP, shadow.getUpdatedAt());
        doc.put("createdAt", shadow.getCreatedAt());
        doc.put("lastReportAt", shadow.getLastReportAt());
        doc.put("lastWindowStart", shadow.getLastWindowStart());
        doc.put("lastWindowEnd", shadow.getLastWindowEnd());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("reported", toValueMap(shadow.snapshot()));
        state.put("desired", toValueMap(shadow.desiredSnapshot()));
        state.put("delta", toValueMap(shadow.deltaSnapshot()));
        state.put("lastReported", new LinkedHashMap<>(shadow.snapshotLastReportedValues()));
        doc.put("state", state);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reported", toMetaMap(shadow.snapshot()));
        metadata.put("desired", toMetaMap(shadow.desiredSnapshot()));
        doc.put(CommonMapKeys.METADATA, metadata);

        return doc;
    }

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 解析或转换业务数据。
     */
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
            metadata.put(CommonMapKeys.TIMESTAMP, meta.getTimestamp());
            metadata.put("updatedAt", meta.getUpdatedAt());
            if (meta.getQuality() != null) {
                metadata.put(CommonMapKeys.QUALITY, meta.getQuality());
            }
            if (meta.getSource() != null) {
                metadata.put(CommonMapKeys.SOURCE, meta.getSource());
            }
            if (meta.getMetadata() != null && !meta.getMetadata().isEmpty()) {
                metadata.put("valueMetadata", meta.getMetadata());
            }
            values.put(field, metadata);
        });
        return values;
    }

    /**
     * 写入或持久化业务数据。
     */
    private void persistDocumentCas(String deviceId,
                                    Map<String, Object> document,
                                    long expectedVersion,
                                    String action) {
        if (!redisCasEnabled()) {
            persistDocument(deviceId, document, action);
            return;
        }
        try {
            String key = shadowKey(deviceId);
            String payload = objectMapper.writeValueAsString(document);
            long ttlMs = shadowTtlMillis();
            long nextVersion = Optional.ofNullable(asLong(document.get("version"))).orElse(expectedVersion);
            Object result = stringRedisTemplate.execute((RedisCallback<Object>) connection -> connection.eval(
                    SHADOW_CAS_SCRIPT.getBytes(StandardCharsets.UTF_8),
                    ReturnType.MULTI,
                    1,
                    key.getBytes(StandardCharsets.UTF_8),
                    String.valueOf(expectedVersion).getBytes(StandardCharsets.UTF_8),
                    String.valueOf(nextVersion).getBytes(StandardCharsets.UTF_8),
                    String.valueOf(ttlMs).getBytes(StandardCharsets.UTF_8),
                    payload.getBytes(StandardCharsets.UTF_8)
            ));
            CasResult casResult = parseCasResult(result);
            if (!casResult.success()) {
                throw new ShadowVersionConflictException(expectedVersion, casResult.actualVersion());
            }
            if (shouldRecordHistory(action, expectedVersion, nextVersion)) {
                appendShadowHistory(deviceId, action, document, expectedVersion);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("shadow CAS failed: " + e.getMessage(), e);
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    private void persistShadow(DeviceShadow shadow) {
        if (shadow == null) {
            return;
        }
        persistDocument(shadow.getDeviceId(), buildShadowDocument(shadow));
    }

    /**
     * 写入或持久化业务数据。
     */
    private void persistDocument(String deviceId, Map<String, Object> document) {
        persistDocument(deviceId, document, null);
    }

    /**
     * 写入或持久化业务数据。
     */
    private void persistDocument(String deviceId, Map<String, Object> document, String action) {
        if (!shadowPersistenceEnabled() || deviceId == null || document == null) {
            return;
        }
        long ttlMs = shadowTtlMillis();
        long baseVersion = Optional.ofNullable(asLong(document.get("version"))).orElse(0L) - 1;
        try {
            if (stringRedisTemplate != null && objectMapper != null) {
                String payload = objectMapper.writeValueAsString(document);
                if (ttlMs > 0) {
                    stringRedisTemplate.opsForValue().set(shadowKey(deviceId), payload, ttlMs, TimeUnit.MILLISECONDS);
                } else {
                    stringRedisTemplate.opsForValue().set(shadowKey(deviceId), payload);
                }
                if (shouldRecordHistory(action, baseVersion, asLong(document.get("version")))) {
                    appendShadowHistory(deviceId, action, document, Math.max(0, baseVersion));
                }
                return;
            }
            if (redisTemplate == null) {
                return;
            }
            if (ttlMs > 0) {
                redisTemplate.opsForValue().set(shadowKey(deviceId), document, ttlMs, TimeUnit.MILLISECONDS);
            } else {
                redisTemplate.opsForValue().set(shadowKey(deviceId), document);
            }
            if (shouldRecordHistory(action, baseVersion, asLong(document.get("version")))) {
                appendShadowHistory(deviceId, action, document, Math.max(0, baseVersion));
            }
        } catch (Exception e) {
            log.warn("设备影子持久化失败 设备={} err={}", deviceId, e.getMessage());
        }
    }

    /**
     * 查询并返回业务数据。
     */
    private DeviceShadow loadShadow(String deviceId) {
        if (!shadowPersistenceEnabled() || deviceId == null) {
            return null;
        }
        if (stringRedisTemplate != null) {
            try {
                String stored = stringRedisTemplate.opsForValue().get(shadowKey(deviceId));
                if (stored != null) {
                    Map<String, Object> document = toMap(stored);
                    if (!document.isEmpty()) {
                        return restoreShadow(deviceId, document);
                    }
                }
            } catch (Exception e) {
                log.warn("设备影子字符串格式恢复失败 设备={} err={}", deviceId, e.getMessage());
            }
        }
        if (redisTemplate != null) {
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
                log.warn("设备影子恢复失败 设备={} err={}", deviceId, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private DeviceShadow restoreShadow(String fallbackDeviceId, Map<String, Object> document) {
        String deviceId = asString(document.get(CommonMapKeys.DEVICE_ID));
        DeviceShadow shadow = new DeviceShadow(deviceId != null ? deviceId : fallbackDeviceId);
        Long version = asLong(document.get("version"));
        if (version != null) {
            shadow.restoreVersion(version);
        }
        Long createdAt = asLong(document.get("createdAt"));
        if (createdAt != null) {
            shadow.setCreatedAt(createdAt);
        }
        Long updatedAt = asLong(document.get(CommonMapKeys.TIMESTAMP));
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
        Map<String, Object> metadata = toMap(document.get(CommonMapKeys.METADATA));
        restoreValues(shadow, state.get("reported"), toMap(metadata.get("reported")), true);
        restoreValues(shadow, state.get("desired"), toMap(metadata.get("desired")), false);
        shadow.restoreLastReportedValues(toMap(state.get("lastReported")));
        return shadow;
    }

    /**
     * 执行当前业务逻辑。
     */
    private void restoreValues(DeviceShadow shadow,
                               Object valuesObject,
                               Map<String, Object> metadata,
                               boolean reported) {
        Map<String, Object> values = toMap(valuesObject);
        values.forEach((field, value) -> {
            Map<String, Object> metaMap = toMap(metadata.get(field));
            long timestamp = Optional.ofNullable(asLong(metaMap.get(CommonMapKeys.TIMESTAMP))).orElse(System.currentTimeMillis());
            long updatedAt = Optional.ofNullable(asLong(metaMap.get("updatedAt"))).orElse(timestamp);
            String quality = asString(metaMap.get(CommonMapKeys.QUALITY));
            String source = asString(metaMap.get(CommonMapKeys.SOURCE));
            ValueMeta meta = new ValueMeta(
                    value,
                    timestamp,
                    quality,
                    source,
                    updatedAt,
                    toMap(metaMap.get("valueMetadata"))
            );
            if (reported) {
                shadow.restoreReported(field, meta);
            } else {
                shadow.restoreDesired(field, meta);
            }
        });
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> stableValueMetadata(ProcessResult result) {
        Map<String, Object> metadata = result != null ? result.getMetadata() : null;
        if (metadata == null || metadata.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> stable = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && STABLE_VALUE_METADATA_KEYS.contains(key) && value != null) {
                stable.put(key, value);
            }
        });
        return stable;
    }

    /**
     * 清理或删除业务数据。
     */
    private void deletePersistedShadow(String deviceId) {
        if (deviceId == null) {
            return;
        }
        try {
            if (stringRedisTemplate != null) {
                stringRedisTemplate.delete(shadowKey(deviceId));
            }
            if (redisTemplate != null) {
                redisTemplate.delete(shadowKey(deviceId));
            }
        } catch (Exception e) {
            log.warn("删除设备影子持久化数据失败 设备={} err={}", deviceId, e.getMessage());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private String shadowKey(String deviceId) {
        ReportProperties.Shadow shadow = reportProperties.getShadow();
        String prefix = shadow == null ? DEFAULT_SHADOW_KEY_PREFIX : shadow.getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = DEFAULT_SHADOW_KEY_PREFIX;
        }
        return prefix + deviceId;
    }

    /**
     * 执行当前业务逻辑。
     */
    private String historyKey(String deviceId) {
        ReportProperties.Shadow shadow = reportProperties.getShadow();
        String prefix = shadow == null ? DEFAULT_SHADOW_KEY_PREFIX + "history:" : shadow.getHistoryKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = DEFAULT_SHADOW_KEY_PREFIX + "history:";
        }
        return prefix + deviceId;
    }

    /**
     * 记录或统计业务状态。
     */
    private void markDirty(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        dirtyDevices.add(deviceId);
        try {
            if (stringRedisTemplate != null && shadowPersistenceEnabled()) {
                stringRedisTemplate.opsForSet().add(dirtySetKey(), deviceId);
                long ttlMs = shadowTtlMillis();
                if (ttlMs > 0) {
                    stringRedisTemplate.expire(dirtySetKey(), ttlMs, TimeUnit.MILLISECONDS);
                }
                return;
            }
            if (redisTemplate != null && shadowPersistenceEnabled()) {
                redisTemplate.opsForSet().add(dirtySetKey(), deviceId);
                long ttlMs = shadowTtlMillis();
                if (ttlMs > 0) {
                    redisTemplate.expire(dirtySetKey(), ttlMs, TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception e) {
            log.warn("标记影子 dirty 设备失败，设备={} err={}", deviceId, e.getMessage());
        }
    }

    /**
     * 查询并返回业务数据。
     */
    private Set<String> loadPersistedDirtyDevices() {
        if (!shadowPersistenceEnabled()) {
            return Collections.emptySet();
        }
        try {
            if (stringRedisTemplate != null) {
                Set<String> members = stringRedisTemplate.opsForSet().members(dirtySetKey());
                return members != null ? members : Collections.emptySet();
            }
            if (redisTemplate != null) {
                Set<Object> members = redisTemplate.opsForSet().members(dirtySetKey());
                if (members == null || members.isEmpty()) {
                    return Collections.emptySet();
                }
                Set<String> values = new java.util.LinkedHashSet<>();
                for (Object member : members) {
                    if (member != null) {
                        values.add(String.valueOf(member));
                    }
                }
                return values;
            }
        } catch (Exception e) {
            log.warn("加载影子 dirty 设备集合失败，err={}", e.getMessage());
        }
        return Collections.emptySet();
    }

    /**
     * 清理或删除业务数据。
     */
    private void removePersistedDirty(String deviceId) {
        try {
            if (stringRedisTemplate != null) {
                stringRedisTemplate.opsForSet().remove(dirtySetKey(), deviceId);
            }
            if (redisTemplate != null) {
                redisTemplate.opsForSet().remove(dirtySetKey(), deviceId);
            }
        } catch (Exception e) {
            log.warn("移除影子 dirty 设备失败，设备={} err={}", deviceId, e.getMessage());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private String dirtySetKey() {
        ReportProperties.Shadow shadow = reportProperties.getShadow();
        if (shadow == null || shadow.getKeyPrefix() == null || shadow.getKeyPrefix().isBlank()) {
            return DEFAULT_DIRTY_SET_KEY;
        }
        return shadow.getKeyPrefix() + "dirty";
    }

    /**
     * 更新或刷新业务状态。
     */
    private DeviceShadow refreshLocalShadowIfVersionMismatch(String deviceId,
                                                             DeviceShadow current,
                                                             long expectedVersion) {
        if (current != null && current.currentVersion() == expectedVersion) {
            return current;
        }
        DeviceShadow restored = loadShadow(deviceId);
        if (restored == null) {
            return current;
        }
        shadows.put(deviceId, restored);
        return restored;
    }

    /**
     * 更新或刷新业务状态。
     */
    private void reloadLocalShadow(String deviceId) {
        DeviceShadow restored = loadShadow(deviceId);
        if (restored != null) {
            shadows.put(deviceId, restored);
        } else {
            shadows.remove(deviceId);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean redisCasEnabled() {
        ReportProperties.Shadow shadow = reportProperties.getShadow();
        return shadowPersistenceEnabled()
                && shadow != null
                && shadow.isCasEnabled()
                && stringRedisTemplate != null
                && objectMapper != null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean shadowAutoMergeEnabled() {
        ReportProperties.Shadow shadow = reportProperties.getShadow();
        return shadow == null || shadow.isAutoMergeEnabled();
    }

    /**
     * 执行当前业务逻辑。
     */
    private int shadowMergeAttempts() {
        ReportProperties.Shadow shadow = reportProperties.getShadow();
        int retries = shadow == null ? 2 : Math.max(0, shadow.getMergeRetryTimes());
        return retries + 1;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean shadowPersistenceEnabled() {
        ReportProperties.Shadow shadow = reportProperties.getShadow();
        return shadow == null || shadow.isPersistenceEnabled();
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean shadowHistoryEnabled() {
        ReportProperties.Shadow shadow = reportProperties.getShadow();
        return shadow != null && shadow.isHistoryEnabled();
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean shouldRecordHistory(String action, long baseVersion, Long nextVersion) {
        if (!shadowHistoryEnabled() || action == null || action.isBlank() || nextVersion == null) {
            return false;
        }
        if (!"desired_update".equals(action) && !"desired_clear".equals(action)) {
            return false;
        }
        return nextVersion != baseVersion;
    }

    /**
     * 执行当前业务逻辑。
     */
    private long shadowTtlMillis() {
        ReportProperties.Shadow shadow = reportProperties.getShadow();
        if (shadow == null || shadow.getTtlSeconds() <= 0) {
            return 0;
        }
        return TimeUnit.SECONDS.toMillis(shadow.getTtlSeconds());
    }

    /**
     * 执行当前业务逻辑。
     */
    private long shadowHistoryTtlSeconds() {
        ReportProperties.Shadow shadow = reportProperties.getShadow();
        return shadow == null ? 0 : shadow.getHistoryTtlSeconds();
    }

    /**
     * 执行当前业务逻辑。
     */
    private int shadowHistoryMaxRecords() {
        ReportProperties.Shadow shadow = reportProperties.getShadow();
        return shadow == null ? 0 : shadow.getHistoryMaxRecords();
    }

    /**
     * 写入或持久化业务数据。
     */
    private void appendShadowHistory(String deviceId,
                                     String action,
                                     Map<String, Object> document,
                                     long baseVersion) {
        if (!shadowHistoryEnabled() || stringRedisTemplate == null || objectMapper == null
                || deviceId == null || document == null) {
            return;
        }
        try {
            Map<String, Object> history = new LinkedHashMap<>();
            history.put(CommonMapKeys.DEVICE_ID, deviceId);
            history.put("action", action);
            history.put("baseVersion", baseVersion);
            history.put("version", document.get("version"));
            history.put(CommonMapKeys.TIMESTAMP, System.currentTimeMillis());
            history.put("document", document);

            String key = historyKey(deviceId);
            stringRedisTemplate.opsForList().leftPush(key, objectMapper.writeValueAsString(history));
            int maxRecords = shadowHistoryMaxRecords();
            if (maxRecords > 0) {
                stringRedisTemplate.opsForList().trim(key, 0, maxRecords - 1L);
            }
            long ttlSeconds = shadowHistoryTtlSeconds();
            if (ttlSeconds > 0) {
                stringRedisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("记录设备影子历史失败 设备={} action={} err={}", deviceId, action, e.getMessage());
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private CasResult parseCasResult(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            Long success = asRedisLong(list.get(0));
            Long actualVersion = list.size() > 1 ? asRedisLong(list.get(1)) : null;
            return new CasResult(success != null && success == 1, actualVersion);
        }
        Long success = asRedisLong(value);
        return new CasResult(success != null && success == 1, null);
    }

    /**
     * 执行当前业务逻辑。
     */
    private Long asRedisLong(Object value) {
        if (value instanceof byte[] bytes) {
            return asLong(new String(bytes, StandardCharsets.UTF_8));
        }
        return asLong(value);
    }

    /**
     * 解析或转换业务数据。
     */
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
        if (value instanceof List<?> list && list.size() > 1 && list.get(1) instanceof Map<?, ?>) {
            return toMap(list.get(1));
        }
        if (value instanceof String text) {
            if (text.isBlank() || objectMapper == null) {
                return Collections.emptyMap();
            }
            try {
                return objectMapper.readValue(text, MAP_TYPE);
            } catch (Exception ignored) {
                try {
                    Object parsed = objectMapper.readValue(text, Object.class);
                    if (parsed != value) {
                        return toMap(parsed);
                    }
                } catch (Exception ignoredAgain) {
                    return Collections.emptyMap();
                }
            }
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

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 执行当前业务逻辑。
     */
    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    public record ShadowUpdateResult(boolean changeTriggered, EventInfo eventInfo) {
        public static final ShadowUpdateResult EMPTY = new ShadowUpdateResult(false, null);
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    public record EventInfo(String ruleId,
                            String ruleName,
                            String level,
                            String message,
                            String eventType) {
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    private record CasResult(boolean success, Long actualVersion) {
    }

    /**
     * 表示当前模块的异常语义。
     */
    private static class ShadowVersionConflictException extends IllegalStateException {
        /**
         * 创建当前组件实例。
         */
        ShadowVersionConflictException(long expectedVersion, Long actualVersion) {
            super("shadow version conflict: expected=" + expectedVersion + ", actual=" + actualVersion);
        }
    }
}

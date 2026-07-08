package com.wangbin.collector.core.report.service;

import com.wangbin.collector.common.config.DistributedLock;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.enums.QualityEnum;
import com.wangbin.collector.core.cloud.aggregation.CloudAggregateSnapshot;
import com.wangbin.collector.core.cloud.aggregation.CloudAggregationService;
import com.wangbin.collector.core.cloud.aggregation.CloudBatchAccumulator;
import com.wangbin.collector.core.cloud.aggregation.CloudBatchAccumulator.CloudBatchReport;
import com.wangbin.collector.core.cloud.aggregation.CloudPointBinding;
import com.wangbin.collector.core.cloud.config.CloudBatchFlushPolicy;
import com.wangbin.collector.core.cloud.mapping.CloudPointMappingResolver;
import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import com.wangbin.collector.core.cloud.service.CloudReportTargetContext;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.model.ReportConfig;
import com.wangbin.collector.core.report.model.ReportData;
import com.wangbin.collector.core.report.model.ReportIdentity;
import com.wangbin.collector.core.report.model.ReportResult;
import com.wangbin.collector.core.report.service.support.GatewayRateLimiter;
import com.wangbin.collector.core.report.service.support.ReportConfigProvider;
import com.wangbin.collector.core.report.service.support.ReportIdentityResolver;
import com.wangbin.collector.core.report.shadow.DeviceShadow;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import com.wangbin.collector.core.report.shadow.ShadowManager.EventInfo;
import com.wangbin.collector.core.report.shadow.ShadowManager.ShadowUpdateResult;
import com.wangbin.collector.core.report.shadow.ValueMeta;
import com.wangbin.collector.monitor.alert.AlertNotification;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Aggregates telemetry changes into report snapshots and dispatches them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheReportService {

    private static final String SNAPSHOT_POINT_CODE = "snapshot";
    private static final String FLUSH_LOCK_KEY_PREFIX = "collector:report:flush:";
    private static final long FLUSH_LOCK_EXPIRE_MS = 30000L;

    private final ReportManager reportManager;
    private final ReportProperties reportProperties;
    private final ShadowManager shadowManager;
    private final ReportIdentityResolver identityResolver;
    private final ReportConfigProvider reportConfigProvider;
    private final GatewayRateLimiter gatewayRateLimiter;
    @Nullable
    private final DistributedLock distributedLock;
    @Qualifier("taskScheduler")
    private final TaskScheduler taskScheduler;
    @Autowired(required = false)
    private CloudPointMappingResolver cloudPointMappingResolver;
    @Autowired(required = false)
    private CloudAggregationService cloudAggregationService;
    @Autowired(required = false)
    private CloudBatchAccumulator cloudBatchAccumulator;
    private final ConcurrentMap<String, String> identityGatewayMapping = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> identityProductKeys = new ConcurrentHashMap<>();
    private final Set<String> flushingDevices = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, FlushTracker> flushTrackers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, FlushSession> flushSessions = new ConcurrentHashMap<>();
    private ScheduledFuture<?> flushTask;

    @PostConstruct
    public void start() {
        if (!isMqttEnabled()) {
            return;
        }
        long interval = Math.max(1000L, reportProperties.getIntervalMs());
        flushTask = taskScheduler.scheduleAtFixedRate(this::flushDirtyDevices, Duration.ofMillis(interval));
    }

    @PreDestroy
    public void shutdown() {
        if (flushTask != null) {
            flushTask.cancel(true);
        }
    }

    public void reportPoint(String deviceId, String method, DataPoint point, Object cacheValue) {
        if (!isMqttEnabled() || deviceId == null || point == null || cacheValue == null) {
            return;
        }
        ProcessResult processResult = identityResolver.toProcessResult(cacheValue);
        if (processResult == null) {
            return;
        }
        if (hasCloudBindings(point) && reportCloudBindings(deviceId, point, processResult)) {
            return;
        }
        List<ReportIdentity> identities = identityResolver.resolve(deviceId, point, defaultProductKey());
        if (identities.isEmpty()) {
            identities = List.of(new ReportIdentity(deviceId, deviceId, defaultProductKey()));
        }
        for (ReportIdentity identity : identities) {
            identityGatewayMapping.put(identity.cloudDeviceId(), identity.gatewayDeviceId());
            identityProductKeys.put(identity.cloudDeviceId(), identity.productKey());
            ShadowUpdateResult updateResult = shadowManager.apply(identity.cloudDeviceId(), point, processResult);
            if (updateResult.changeTriggered()) {
                triggerImmediateFlush(identity.cloudDeviceId());
            }
            EventInfo eventInfo = updateResult.eventInfo();
            if (eventInfo != null) {
                dispatchEvent(identity, point, processResult, eventInfo);
            }
        }
    }

    private String defaultProductKey() {
        ReportProperties.Mqtt mqtt = reportProperties.getMqtt();
        if (mqtt == null || mqtt.getGatewayProductKey() == null) {
            return "";
        }
        return mqtt.getGatewayProductKey();
    }

    private boolean hasCloudBindings(DataPoint point) {
        return point != null && point.getAdditionalConfig("cloudBindings") != null;
    }

    private boolean reportCloudBindings(String gatewayDeviceId, DataPoint point, ProcessResult processResult) {
        if (cloudPointMappingResolver == null) {
            return false;
        }
        List<CloudPointBinding> bindings = cloudPointMappingResolver.resolve(point, defaultProductKey(), gatewayDeviceId);
        if (bindings.isEmpty()) {
            return false;
        }
        boolean handled = false;
        for (CloudPointBinding binding : bindings) {
            if (binding == null || binding.identity() == null || !binding.identity().valid()) {
                continue;
            }
            ReportIdentity identity = new ReportIdentity(
                    gatewayDeviceId,
                    binding.identity().deviceName(),
                    binding.identity().productKey());
            identityGatewayMapping.put(identity.cloudDeviceId(), identity.gatewayDeviceId());
            identityProductKeys.put(identity.cloudDeviceId(), identity.productKey());
            if (!MessageConstant.MESSAGE_TYPE_PROPERTY_POST.equals(binding.messageType())) {
                handled |= dispatchCloudBindingDirect(gatewayDeviceId, point, processResult, binding);
                continue;
            }
            DataPoint mappedPoint = pointWithCloudField(point, binding.field());
            ShadowUpdateResult updateResult = shadowManager.apply(identity.cloudDeviceId(), mappedPoint, processResult);
            if (updateResult.changeTriggered()) {
                triggerImmediateFlush(identity.cloudDeviceId());
            }
            EventInfo eventInfo = updateResult.eventInfo();
            if (eventInfo != null) {
                dispatchEvent(identity, mappedPoint, processResult, eventInfo);
            }
            handled = true;
        }
        return handled;
    }

    private boolean dispatchCloudBindingDirect(String gatewayDeviceId,
                                               DataPoint point,
                                               ProcessResult processResult,
                                               CloudPointBinding binding) {
        if (cloudAggregationService == null) {
            return false;
        }
        ReportConfig reportConfig = resolveReportConfig(binding.identity().deviceName());
        if (reportConfig == null || !reportConfig.validate()) {
            log.warn("Skip cloud binding report because config is invalid, device={}", binding.identity().deviceName());
            return true;
        }
        ReportData data = cloudAggregationService.toReportData(gatewayDeviceId, point, processResult, binding);
        if (data == null) {
            return true;
        }
        dispatch(data, reportConfig, true, null);
        return true;
    }

    private DataPoint pointWithCloudField(DataPoint point, String field) {
        if (point == null || field == null || field.isBlank()) {
            return point;
        }
        DataPoint copy = new DataPoint();
        copy.setId(point.getId());
        copy.setUnitId(point.getUnitId());
        copy.setCommonAddress(point.getCommonAddress());
        copy.setPointId(point.getPointId());
        copy.setPointCode(point.getPointCode());
        copy.setPointName(point.getPointName());
        copy.setPointAlias(point.getPointAlias());
        copy.setDeviceId(point.getDeviceId());
        copy.setDeviceName(point.getDeviceName());
        copy.setGroupId(point.getGroupId());
        copy.setAddress(point.getAddress());
        copy.setDataType(point.getDataType());
        copy.setReadWrite(point.getReadWrite());
        copy.setUnit(point.getUnit());
        copy.setStatus(point.getStatus());
        copy.setPrecision(point.getPrecision());
        copy.setRemark(point.getRemark());
        copy.setReportFieldConflict(point.isReportFieldConflict());
        Map<String, Object> additionalConfig = point.getAdditionalConfig();
        additionalConfig.put("reportField", field);
        copy.setAdditionalConfig(additionalConfig);
        return copy;
    }

    private void triggerImmediateFlush(String deviceId) {
        DeviceShadow shadow = shadowManager.getShadow(deviceId);
        if (shadow == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - shadow.getLastReportAt() < reportProperties.getMinReportIntervalMs()) {
            return;
        }
        taskScheduler.schedule(() -> flushDevice(deviceId), Instant.now());
    }

    private void flushDirtyDevices() {
        Set<String> dirtyDevices = shadowManager.getDirtyDevices();
        if (dirtyDevices.isEmpty()) {
            return;
        }
        Set<String> batchedDevices = tryFlushGatewayBatches(dirtyDevices);
        for (String deviceId : dirtyDevices) {
            if (batchedDevices.contains(deviceId)) {
                continue;
            }
            try {
                flushDevice(deviceId);
            } catch (Exception e) {
                log.error("Flush dirty device failed: {}", deviceId, e);
            }
        }
    }

    private Set<String> tryFlushGatewayBatches(Set<String> dirtyDevices) {
        Set<String> batchedDevices = new java.util.LinkedHashSet<>();
        if (cloudBatchAccumulator == null || cloudAggregationService == null || dirtyDevices.size() < 2) {
            return batchedDevices;
        }
        Map<String, List<String>> devicesByGateway = new java.util.LinkedHashMap<>();
        for (String deviceId : dirtyDevices) {
            String gatewayDeviceId = identityGatewayMapping.get(deviceId);
            if (gatewayDeviceId == null || gatewayDeviceId.isBlank()) {
                continue;
            }
            devicesByGateway.computeIfAbsent(gatewayDeviceId, key -> new ArrayList<>()).add(deviceId);
        }
        for (Map.Entry<String, List<String>> entry : devicesByGateway.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            batchedDevices.addAll(tryFlushGatewayBatch(entry.getKey(), entry.getValue()));
        }
        return batchedDevices;
    }

    private Set<String> tryFlushGatewayBatch(String gatewayDeviceId, List<String> deviceIds) {
        Set<String> selectedDeviceIds = new java.util.LinkedHashSet<>();
        ReportConfig reportConfig = reportConfigProvider.getConfig(gatewayDeviceId);
        if (reportConfig == null || !reportConfig.validate()) {
            return selectedDeviceIds;
        }
        Optional<CloudReportTargetContext> targetContext = resolveCloudTargetContext(reportConfig);
        if (targetContext.isEmpty()) {
            return selectedDeviceIds;
        }
        CloudBatchFlushPolicy policy = targetContext.get().batchPolicy();
        if (policy == null || !policy.enabled()) {
            return selectedDeviceIds;
        }

        List<BatchFlushCandidate> candidates = collectBatchCandidates(gatewayDeviceId, deviceIds);
        if (candidates.size() < 2) {
            candidates.forEach(candidate -> abortFlush(candidate.session));
            return selectedDeviceIds;
        }

        List<CloudAggregateSnapshot> snapshots = new ArrayList<>(candidates.size());
        for (BatchFlushCandidate candidate : candidates) {
            snapshots.add(candidate.aggregateSnapshot);
        }
        Optional<CloudBatchReport> batchReport = cloudBatchAccumulator.tryAssemble(
                resolveGatewayCloudIdentity(reportConfig, gatewayDeviceId),
                gatewayDeviceId,
                snapshots,
                policy);
        if (batchReport.isEmpty()) {
            candidates.forEach(candidate -> abortFlush(candidate.session));
            return selectedDeviceIds;
        }

        for (CloudAggregateSnapshot snapshot : batchReport.get().snapshots()) {
            selectedDeviceIds.add(snapshot.identity().deviceName());
        }
        List<BatchFlushCandidate> selectedCandidates = new ArrayList<>();
        for (BatchFlushCandidate candidate : candidates) {
            if (selectedDeviceIds.contains(candidate.deviceId)) {
                selectedCandidates.add(candidate);
            } else {
                abortFlush(candidate.session);
            }
        }
        if (selectedCandidates.size() < 2) {
            selectedCandidates.forEach(candidate -> abortFlush(candidate.session));
            selectedDeviceIds.clear();
            return selectedDeviceIds;
        }
        if (!gatewayRateLimiter.tryAcquire(false)) {
            selectedCandidates.forEach(candidate -> closeFlushSession(candidate.session, false));
            selectedDeviceIds.clear();
            return selectedDeviceIds;
        }

        CompletableFuture<ReportResult> future = reportManager.reportAsync(batchReport.get().reportData(), reportConfig);
        future.whenComplete((result, throwable) ->
                handleBatchFlushResult(batchReport.get(), selectedCandidates, result, throwable, reportConfig));
        return selectedDeviceIds;
    }

    private List<BatchFlushCandidate> collectBatchCandidates(String gatewayDeviceId, List<String> deviceIds) {
        List<BatchFlushCandidate> candidates = new ArrayList<>();
        for (String deviceId : deviceIds) {
            if (!flushingDevices.add(deviceId)) {
                continue;
            }
            FlushSession session = tryOpenFlushSession(deviceId);
            if (session == null) {
                flushingDevices.remove(deviceId);
                continue;
            }
            String mappedGateway = identityGatewayMapping.get(deviceId);
            if (!gatewayDeviceId.equals(mappedGateway)) {
                abortFlush(session);
                continue;
            }
            DeviceShadow shadow = shadowManager.getShadow(deviceId);
            if (shadow == null || shadow.isEmpty()) {
                shadowManager.clearDirty(deviceId);
                abortFlush(session);
                continue;
            }
            ReportData snapshot = buildSnapshot(shadow, gatewayDeviceId);
            if (!snapshot.hasProperties()) {
                shadowManager.clearDirty(deviceId);
                abortFlush(session);
                continue;
            }
            CloudAggregateSnapshot aggregateSnapshot = cloudAggregationService.snapshotOf(snapshot);
            if (aggregateSnapshot == null || aggregateSnapshot.identity() == null || !aggregateSnapshot.identity().valid()) {
                abortFlush(session);
                continue;
            }
            candidates.add(new BatchFlushCandidate(deviceId, session, snapshot, aggregateSnapshot));
        }
        return candidates;
    }

    private Optional<CloudReportTargetContext> resolveCloudTargetContext(ReportConfig config) {
        if (config == null) {
            return Optional.empty();
        }
        Object raw = config.getParam("cloudTargetContext");
        if (raw instanceof CloudReportTargetContext context) {
            return Optional.of(context);
        }
        return Optional.empty();
    }

    private CloudDeviceIdentity resolveGatewayCloudIdentity(ReportConfig config, String gatewayDeviceId) {
        String productKey = config.getStringParam("gatewayProductKey");
        if (productKey == null || productKey.isBlank()) {
            productKey = defaultProductKey();
        }
        String deviceName = config.getStringParam("gatewayDeviceName");
        if (deviceName == null || deviceName.isBlank()) {
            deviceName = gatewayDeviceId;
        }
        return CloudDeviceIdentity.of(productKey, deviceName);
    }

    private void handleBatchFlushResult(CloudBatchReport batchReport,
                                        List<BatchFlushCandidate> candidates,
                                        ReportResult result,
                                        Throwable throwable,
                                        ReportConfig config) {
        boolean success = throwable == null && result != null && result.isSuccess();
        long now = System.currentTimeMillis();
        if (throwable != null) {
            log.error("Send gateway property pack failed: {} -> {}", batchReport.reportData().getPointCode(),
                    config.getTargetId(), throwable);
        } else if (!success) {
            log.warn("Gateway property pack rejected: {} -> {}, err={}",
                    batchReport.reportData().getPointCode(), config.getTargetId(),
                    result != null ? result.getErrorMessage() : "unknown");
        }

        for (BatchFlushCandidate candidate : candidates) {
            if (success) {
                shadowManager.markReportedValuesChunk(candidate.deviceId, candidate.snapshot.getProperties());
                shadowManager.markReportedWindowCommitted(
                        candidate.deviceId,
                        now - reportProperties.getIntervalMs(),
                        now);
            } else {
                scheduleBatchFallbackRetry(candidate.deviceId);
            }
            closeFlushSession(candidate.session, false);
        }
    }

    private void scheduleBatchFallbackRetry(String deviceId) {
        long delayMillis = computeRetryDelayMillis(0);
        taskScheduler.schedule(() -> flushDevice(deviceId), Instant.now().plusMillis(delayMillis));
    }
    private void flushDevice(String deviceId) {
        if (!flushingDevices.add(deviceId)) {
            return;
        }

        FlushSession session = tryOpenFlushSession(deviceId);
        if (session == null) {
            flushingDevices.remove(deviceId);
            return;
        }

        String gatewayDeviceId = identityGatewayMapping.get(deviceId);
        DeviceShadow shadow = shadowManager.getShadow(deviceId);
        if (gatewayDeviceId == null) {
            log.warn("Skip flush because gateway mapping is missing, device={}", deviceId);
            shadowManager.clearDirty(deviceId);
            abortFlush(session);
            return;
        }
        if (shadow == null || shadow.isEmpty()) {
            shadowManager.clearDirty(deviceId);
            abortFlush(session);
            return;
        }

        ReportData snapshot = buildSnapshot(shadow, gatewayDeviceId);
        if (!snapshot.hasProperties()) {
            shadowManager.clearDirty(deviceId);
            abortFlush(session);
            return;
        }

        ReportConfig reportConfig = resolveReportConfig(deviceId);
        if (reportConfig == null || !reportConfig.validate()) {
            log.warn("Skip flush because report config is invalid, device={}", deviceId);
            abortFlush(session);
            return;
        }

        List<ReportData> chunks = splitSnapshot(snapshot);
        if (chunks.isEmpty()) {
            shadowManager.clearDirty(deviceId);
            abortFlush(session);
            return;
        }

        long now = System.currentTimeMillis();
        FlushTracker tracker = new FlushTracker(
                deviceId,
                now - reportProperties.getIntervalMs(),
                now,
                Math.max(0, reportProperties.getRetryTimes()),
                Math.max(1, reportProperties.getMaxPendingChunksPerDevice())
        );
        session.bind(tracker);
        flushTrackers.put(deviceId, tracker);
        for (ReportData chunk : chunks) {
            dispatch(chunk, reportConfig, false, tracker, 0);
        }
    }

    private @Nullable FlushSession tryOpenFlushSession(String deviceId) {
        String lockKey = FLUSH_LOCK_KEY_PREFIX + deviceId;
        DistributedLock.LockHandle lockHandle = null;
        if (distributedLock != null) {
            Optional<DistributedLock.LockHandle> acquired = distributedLock.tryLock(
                    lockKey,
                    FLUSH_LOCK_EXPIRE_MS,
                    TimeUnit.MILLISECONDS
            );
            if (acquired.isEmpty()) {
                log.debug("Skip flush because distributed lock is held, device={}", deviceId);
                return null;
            }
            lockHandle = acquired.get();
        }
        FlushSession session = new FlushSession(deviceId, lockKey, lockHandle);
        FlushSession previous = flushSessions.putIfAbsent(deviceId, session);
        if (previous != null) {
            if (lockHandle != null) {
                lockHandle.unlock();
            }
            return null;
        }
        return session;
    }

    private void abortFlush(FlushSession session) {
        closeFlushSession(session, false);
    }

    private void completeFlush(FlushTracker tracker) {
        FlushSession session = flushSessions.get(tracker.deviceId);
        if (!tracker.hasFailure()) {
            shadowManager.markReportedWindowCommitted(tracker.deviceId, tracker.windowStart, tracker.windowEnd);
        }
        if (session != null) {
            closeFlushSession(session, true);
        } else {
            flushTrackers.remove(tracker.deviceId);
            flushingDevices.remove(tracker.deviceId);
        }
    }

    private void closeFlushSession(FlushSession session, boolean clearTracker) {
        FlushTracker tracker = session.tracker;
        if (clearTracker && tracker != null) {
            flushTrackers.remove(session.deviceId, tracker);
        } else if (tracker != null && !tracker.hasOutstandingWork()) {
            flushTrackers.remove(session.deviceId, tracker);
        }
        flushSessions.remove(session.deviceId, session);
        flushingDevices.remove(session.deviceId);
        if (session.lockHandle != null && !session.lockHandle.unlock()) {
            log.debug("Distributed flush lock already released or expired, key={}", session.lockKey);
        }
    }

    private ReportData buildSnapshot(DeviceShadow shadow, String rawDeviceId) {
        ReportData data = new ReportData();
        data.setDeviceId(shadow.getDeviceId());
        data.setTimestamp(System.currentTimeMillis());
        data.addMetadata("schemaVersion", reportProperties.getSchemaVersion());
        data.addMetadata("seq", shadow.nextSeq());
        if (rawDeviceId != null) {
            data.addMetadata("rawDeviceId", rawDeviceId);
        }
        String productKey = identityProductKeys.get(shadow.getDeviceId());
        if (productKey == null || productKey.isEmpty()) {
            productKey = defaultProductKey();
        }
        if (productKey != null && !productKey.isEmpty()) {
            data.addMetadata("productKey", productKey);
        }
        Map<String, ValueMeta> latest = shadow.snapshot();
        DeviceShadow.PointInfo primaryPoint = resolvePrimaryPointInfo(shadow, latest);
        if (primaryPoint != null) {
            ReportData.applyPointInfo(data, primaryPoint.pointId(), primaryPoint.pointCode(), primaryPoint.pointName());
        } else {
            ReportData.applyPointInfo(data, SNAPSHOT_POINT_CODE, SNAPSHOT_POINT_CODE, "snapshot");
        }
        latest.forEach((field, meta) -> data.addProperty(field,
                meta.getValue(),
                meta.getTimestamp(),
                meta.getQuality(),
                meta.getMetadata()));
        return data;
    }

    List<ReportData> splitSnapshot(ReportData snapshot) {
        int maxFields = Math.max(1, reportProperties.getMaxPropertiesPerMessage());
        int maxBytes = reportProperties.getMaxPayloadBytes();
        List<ReportData> result = new ArrayList<>();
        if (snapshot.size() <= maxFields && (maxBytes <= 0 || snapshot.estimatePayloadSize() <= maxBytes)) {
            result.add(snapshot);
        } else {
            List<String> fields = new ArrayList<>(snapshot.getProperties().keySet());
            int index = 0;
            while (index < fields.size()) {
                ReportData chunk = snapshot.shallowCopy();
                int added = 0;
                while (index < fields.size() && added < maxFields) {
                    String field = fields.get(index);
                    chunk.addProperty(field,
                            snapshot.getProperties().get(field),
                            snapshot.getPropertyTs().getOrDefault(field, snapshot.getTimestamp()),
                            snapshot.getPropertyQuality().get(field),
                            snapshot.getPropertyMetadata().get(field));
                    index++;
                    added++;
                    if (maxBytes > 0 && chunk.estimatePayloadSize() >= maxBytes) {
                        break;
                    }
                }
                result.add(chunk);
            }
        }

        String batchId = UUID.randomUUID().toString();
        for (int i = 0; i < result.size(); i++) {
            ReportData chunk = result.get(i);
            if (result.size() > 1) {
                String base = snapshot.getPointCode() != null ? snapshot.getPointCode() : SNAPSHOT_POINT_CODE;
                chunk.setPointCode(base + "-" + i);
            }
            chunk.applyChunkMetadata(batchId, i, result.size());
        }
        return result;
    }

    private DeviceShadow.PointInfo resolvePrimaryPointInfo(DeviceShadow shadow, Map<String, ValueMeta> latest) {
        if (shadow == null) {
            return null;
        }
        if (latest != null) {
            for (String field : latest.keySet()) {
                DeviceShadow.PointInfo info = shadow.getPointInfo(field);
                if (info != null) {
                    return info;
                }
            }
        }
        return shadow.snapshotPointInfos().values().stream().findFirst().orElse(null);
    }

    private void dispatch(ReportData data,
                          ReportConfig config,
                          boolean highPriority,
                          FlushTracker tracker) {
        dispatch(data, config, highPriority, tracker, 0);
    }

    private void dispatch(ReportData data,
                          ReportConfig config,
                          boolean highPriority,
                          FlushTracker tracker,
                          int attempt) {
        String chunkKey = tracker != null ? tracker.tryRegisterDispatch(data) : null;
        if (tracker != null && chunkKey == null) {
            tracker.markFailure();
            log.warn("Skip chunk dispatch due to pending limit, device={}, point={}",
                    data.getDeviceId(), data.getPointCode());
            completeIfIdle(tracker);
            return;
        }
        if (!gatewayRateLimiter.tryAcquire(highPriority)) {
            log.warn("Gateway rate limit dropped current report: {} -> {}", data.getPointCode(), config.getTargetId());
            handleChunkResult(data, null, null, tracker, chunkKey, config, highPriority, attempt);
            return;
        }

        CompletableFuture<ReportResult> future = reportManager.reportAsync(data, config);
        future.whenComplete((result, throwable) ->
                handleChunkResult(data, result, throwable, tracker, chunkKey, config, highPriority, attempt));
    }

    private void handleChunkResult(ReportData data,
                                   ReportResult result,
                                   Throwable throwable,
                                   FlushTracker tracker,
                                   String chunkKey,
                                   ReportConfig config,
                                   boolean highPriority,
                                   int attempt) {
        if (tracker == null) {
            return;
        }

        boolean success = throwable == null && result != null && result.isSuccess();
        boolean deferred = isDeferredResult(result);
        boolean retryable = isRetryableResult(result, throwable);
        boolean scheduledRetry = false;

        if (throwable != null) {
            log.error("Send telemetry failed: {} -> {}", data.getPointCode(), config.getTargetId(), throwable);
        } else if (result != null && !result.isSuccess() && !deferred) {
            log.warn("Report rejected: {} -> {}, err={}",
                    data.getPointCode(), config.getTargetId(), result.getErrorMessage());
        }

        if (deferred) {
            if (chunkKey != null && tracker.shouldRetry(chunkKey)) {
                scheduledRetry = true;
                log.warn("Deferred report retry: device={}, key={}, attempt={} / {}",
                        tracker.deviceId, chunkKey, tracker.getAttemptCount(chunkKey), reportProperties.getRetryTimes());
                scheduleDeferredRetry(data, config, highPriority, tracker, attempt);
            } else {
                tracker.markFailure();
                log.warn("Deferred report retry exhausted: device={}, key={}, maxRetries={}",
                        tracker.deviceId, chunkKey, reportProperties.getRetryTimes());
            }
        } else if (success) {
            shadowManager.markReportedValuesChunk(data.getDeviceId(), data.getProperties());
        } else if (chunkKey != null && retryable && tracker.shouldRetry(chunkKey)) {
            scheduledRetry = true;
            log.warn("Chunk retry: device={}, key={}, attempt={} / {}",
                    tracker.deviceId, chunkKey, tracker.getAttemptCount(chunkKey), reportProperties.getRetryTimes());
            scheduleChunkRetry(data, config, highPriority, tracker, attempt + 1);
        } else {
            tracker.markFailure();
        }

        boolean allCompleted = tracker.markCompleted();
        if (!scheduledRetry && allCompleted) {
            completeFlush(tracker);
        }
    }

    private void completeIfIdle(FlushTracker tracker) {
        if (tracker != null && !tracker.hasOutstandingWork()) {
            completeFlush(tracker);
        }
    }

    private boolean isDeferredResult(ReportResult result) {
        if (result == null || result.getMetadata() == null) {
            return false;
        }
        Object deferred = result.getMetadata().get("deferred");
        if (deferred instanceof Boolean bool) {
            return bool;
        }
        if (deferred instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return false;
    }

    private void scheduleDeferredRetry(ReportData data,
                                       ReportConfig config,
                                       boolean highPriority,
                                       FlushTracker tracker,
                                       int attempt) {
        long delayMillis = Math.max(2000L, computeRetryDelayMillis(attempt + 1));
        taskScheduler.schedule(() -> dispatch(data, config, highPriority, tracker, attempt + 1),
                Instant.now().plusMillis(delayMillis));
        log.debug("Deferred retry scheduled device={} point={} delay={}ms",
                data.getDeviceId(), data.getPointCode(), delayMillis);
    }

    private void scheduleChunkRetry(ReportData data,
                                    ReportConfig config,
                                    boolean highPriority,
                                    FlushTracker tracker,
                                    int attempt) {
        long delayMillis = computeRetryDelayMillis(attempt);
        taskScheduler.schedule(() -> dispatch(data, config, highPriority, tracker, attempt),
                Instant.now().plusMillis(delayMillis));
    }

    private boolean isRetryableResult(ReportResult result, Throwable throwable) {
        if (throwable != null || result == null || isDeferredResult(result)) {
            return true;
        }
        Map<String, Object> metadata = result.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        Object retryable = metadata.get("retryable");
        if (retryable instanceof Boolean bool) {
            return bool;
        }
        if (retryable instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return false;
    }

    private long computeRetryDelayMillis(int attempt) {
        long base = Math.max(100L, reportProperties.getRetryBackoffMs());
        long max = Math.max(base, reportProperties.getMaxRetryBackoffMs());
        int normalizedAttempt = Math.max(0, attempt);
        long multiplier = 1L << Math.min(normalizedAttempt, 10);
        long delay = Math.min(max, base * multiplier);
        if (reportProperties.isRetryJitterEnabled() && delay > 1) {
            long jitter = Math.max(1L, delay / 10L);
            delay = Math.min(max, delay - jitter + ThreadLocalRandom.current().nextLong(jitter * 2L));
        }
        return delay;
    }

    private void dispatchEvent(ReportIdentity identity,
                               DataPoint point,
                               ProcessResult result,
                               EventInfo eventInfo) {
        String deviceId = identity.cloudDeviceId();
        ReportConfig reportConfig = resolveReportConfig(deviceId);
        if (reportConfig == null || !reportConfig.validate()) {
            log.warn("Skip event report because config is invalid, device={}", deviceId);
            return;
        }
        ReportData eventData = new ReportData();
        eventData.setDeviceId(deviceId);
        ReportData.applyPointInfo(eventData, point);
        eventData.setTimestamp(System.currentTimeMillis());
        eventData.setMethod(MessageConstant.MESSAGE_TYPE_EVENT_POST);
        eventData.setValue(result.getFinalValue());
        eventData.setQuality(QualityEnum.fromCode(result.getQuality()).getText());
        eventData.addMetadata("rawDeviceId", identity.gatewayDeviceId());
        if (identity.productKey() != null && !identity.productKey().isEmpty()) {
            eventData.addMetadata("productKey", identity.productKey());
        }
        eventData.addMetadata("eventType", eventInfo.eventType());
        if (eventInfo.level() != null) {
            eventData.addMetadata("eventLevel", eventInfo.level());
        }
        if (eventInfo.message() != null) {
            eventData.addMetadata("eventMessage", eventInfo.message());
        }
        if (eventInfo.ruleId() != null) {
            eventData.addMetadata("ruleId", eventInfo.ruleId());
        }
        if (eventInfo.ruleName() != null) {
            eventData.addMetadata("ruleName", eventInfo.ruleName());
        }
        eventData.addMetadata("pointAlias", point.getReportField());
        if (point.getUnit() != null) {
            eventData.addMetadata("unit", point.getUnit());
        }
        if (point.getDeviceName() != null) {
            eventData.addMetadata("deviceName", point.getDeviceName());
        }
        dispatch(eventData, reportConfig, true, null);
    }

    public void reportAlert(AlertNotification notification) {
        if (!isMqttEnabled() || notification == null) {
            return;
        }
        String deviceId = notification.getDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            log.warn("Skip alert upload, deviceId missing");
            return;
        }
        ReportConfig reportConfig = resolveReportConfig(deviceId);
        if (reportConfig == null) {
            log.warn("Skip alert upload, report config missing for {}", deviceId);
            return;
        }

        ReportData alertData = new ReportData();
        alertData.setDeviceId(deviceId);
        alertData.setPointId(notification.getPointId());
        String pointCode = Optional.ofNullable(notification.getPointCode())
                .orElse(Optional.ofNullable(notification.getPointId()).orElse("alarm"));
        alertData.setPointCode(pointCode);
        alertData.setPointName(notification.getPointCode());
        alertData.setMethod(MessageConstant.MESSAGE_TYPE_EVENT_POST);
        long timestamp = notification.getTimestamp() > 0
                ? notification.getTimestamp()
                : System.currentTimeMillis();
        alertData.setTimestamp(timestamp);
        alertData.setValue(notification.getValue());
        alertData.setQuality(QualityEnum.WARNING.getText());
        alertData.addMetadata("eventType",
                Optional.ofNullable(notification.getEventType()).orElse("ALARM"));
        alertData.addMetadata("eventLevel",
                Optional.ofNullable(notification.getLevel()).orElse("WARNING"));
        alertData.addMetadata("eventMessage", notification.getMessage());
        if (notification.getRuleId() != null) {
            alertData.addMetadata("ruleId", notification.getRuleId());
        }
        if (notification.getRuleName() != null) {
            alertData.addMetadata("ruleName", notification.getRuleName());
        }
        if (notification.getDeviceName() != null) {
            alertData.addMetadata("deviceName", notification.getDeviceName());
        }
        if (notification.getUnit() != null) {
            alertData.addMetadata("unit", notification.getUnit());
        }
        alertData.addMetadata("rawDeviceId", notification.getDeviceId());
        alertData.addProperty(pointCode, notification.getValue(),
                timestamp, QualityEnum.WARNING.getText());
        dispatch(alertData, reportConfig, true, null);
    }

    @EventListener
    public void handleConfigUpdate(ConfigUpdateEvent event) {
        if (event.getDeviceId() != null) {
            evictGateway(event.getDeviceId());
        }
    }

    private boolean isMqttEnabled() {
        return reportProperties != null && reportProperties.mqttEnabled();
    }

    private void evictGateway(String gatewayDeviceId) {
        if (gatewayDeviceId == null) {
            return;
        }
        reportConfigProvider.evict(gatewayDeviceId);
        List<String> orphanIdentities = new ArrayList<>();
        identityGatewayMapping.forEach((identity, gateway) -> {
            if (gatewayDeviceId.equals(gateway)) {
                orphanIdentities.add(identity);
            }
        });
        for (String identity : orphanIdentities) {
            identityGatewayMapping.remove(identity);
            identityProductKeys.remove(identity);
            shadowManager.removeShadow(identity);
        }
    }

    private ReportConfig resolveReportConfig(String deviceId) {
        String gatewayDeviceId = identityGatewayMapping.getOrDefault(deviceId, deviceId);
        ReportConfig config = reportConfigProvider.getConfig(gatewayDeviceId);
        if (config != null && config.validate()) {
            return config;
        }
        return null;
    }

    private static class BatchFlushCandidate {
        private final String deviceId;
        private final FlushSession session;
        private final ReportData snapshot;
        private final CloudAggregateSnapshot aggregateSnapshot;

        private BatchFlushCandidate(String deviceId,
                                    FlushSession session,
                                    ReportData snapshot,
                                    CloudAggregateSnapshot aggregateSnapshot) {
            this.deviceId = deviceId;
            this.session = session;
            this.snapshot = snapshot;
            this.aggregateSnapshot = aggregateSnapshot;
        }
    }
    private static class FlushSession {
        private final String deviceId;
        private final String lockKey;
        private final DistributedLock.LockHandle lockHandle;
        private volatile FlushTracker tracker;

        private FlushSession(String deviceId,
                             String lockKey,
                             @Nullable DistributedLock.LockHandle lockHandle) {
            this.deviceId = deviceId;
            this.lockKey = lockKey;
            this.lockHandle = lockHandle;
        }

        private void bind(FlushTracker tracker) {
            this.tracker = tracker;
        }
    }

    private static class FlushTracker {
        private final String deviceId;
        private final AtomicInteger inFlight = new AtomicInteger(0);
        private final AtomicBoolean failure = new AtomicBoolean(false);
        private final long windowStart;
        private final long windowEnd;
        private final int maxRetries;
        private final int maxPendingChunks;
        private final ConcurrentMap<String, Integer> attempts = new ConcurrentHashMap<>();

        private FlushTracker(String deviceId, long windowStart, long windowEnd, int maxRetries, int maxPendingChunks) {
            this.deviceId = deviceId;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.maxRetries = Math.max(0, maxRetries);
            this.maxPendingChunks = Math.max(1, maxPendingChunks);
        }

        String tryRegisterDispatch(ReportData data) {
            while (true) {
                int current = inFlight.get();
                if (current >= maxPendingChunks) {
                    return null;
                }
                if (inFlight.compareAndSet(current, current + 1)) {
                    break;
                }
            }
            String chunkKey = buildChunkKey(data);
            attempts.merge(chunkKey, 1, Integer::sum);
            return chunkKey;
        }

        private String buildChunkKey(ReportData data) {
            Object batchId = data.getMetadata().getOrDefault("batchId", data.getDeviceId());
            Object chunkIndex = data.getMetadata().getOrDefault("chunkIndex", data.getPointCode());
            Object seq = data.getMetadata().getOrDefault("seq", data.getTimestamp());
            return batchId + ":" + chunkIndex + ":" + seq;
        }

        boolean shouldRetry(String chunkKey) {
            if (chunkKey == null) {
                return false;
            }
            return attempts.getOrDefault(chunkKey, 0) <= maxRetries;
        }

        int getAttemptCount(String chunkKey) {
            return attempts.getOrDefault(chunkKey, 0);
        }

        void markFailure() {
            failure.set(true);
        }

        boolean hasFailure() {
            return failure.get();
        }

        boolean markCompleted() {
            return inFlight.decrementAndGet() == 0;
        }

        boolean hasOutstandingWork() {
            return inFlight.get() > 0;
        }
    }
}

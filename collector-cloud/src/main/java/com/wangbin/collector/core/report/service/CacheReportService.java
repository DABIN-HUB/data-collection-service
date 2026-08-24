package com.wangbin.collector.core.report.service;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.config.DistributedLock;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.enums.QualityEnum;
import com.wangbin.collector.core.cloud.aggregation.CloudAggregateSnapshot;
import com.wangbin.collector.core.cloud.aggregation.CloudAggregationService;
import com.wangbin.collector.core.cloud.aggregation.CloudBatchAccumulator;
import com.wangbin.collector.core.cloud.aggregation.CloudBatchAccumulator.CloudBatchReport;
import com.wangbin.collector.core.cloud.aggregation.CloudPackReportAssembler;
import com.wangbin.collector.core.cloud.config.CloudBatchFlushPolicy;
import com.wangbin.collector.common.domain.cloud.CloudDeviceIdentity;
import com.wangbin.collector.common.domain.cloud.CloudTargetConfig;
import com.wangbin.collector.core.cloud.service.CloudDeviceIdentityService;
import com.wangbin.collector.core.cloud.service.CloudReportTargetContext;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.model.ReportConfig;
import com.wangbin.collector.core.report.model.ReportData;
import com.wangbin.collector.core.report.model.ReportResult;
import com.wangbin.collector.core.report.outbox.CloudOutboxMetadataKeys;
import com.wangbin.collector.core.report.outbox.CloudOutboxMessage;
import com.wangbin.collector.core.report.outbox.CloudOutboxService;
import com.wangbin.collector.core.report.service.support.GatewayRateLimiter;
import com.wangbin.collector.core.report.service.support.ReportConfigProvider;
import com.wangbin.collector.core.report.shadow.DeviceShadow;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import com.wangbin.collector.core.report.shadow.ShadowManager.EventInfo;
import com.wangbin.collector.core.report.shadow.ShadowManager.ShadowUpdateResult;
import com.wangbin.collector.core.report.shadow.ValueMeta;
import com.wangbin.collector.common.domain.alert.AlertNotification;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 聚合遥测变化并调度上报快照。
 */
@Slf4j
@Service
public class CacheReportService {

    private static final String SNAPSHOT_POINT_CODE = "snapshot";
    private static final String DEFAULT_GATEWAY_DEVICE_ID = "gateway";
    private static final String FLUSH_LOCK_KEY_PREFIX = "collector:report:flush:";
    private static final long FLUSH_LOCK_EXPIRE_MS = 30000L;

    private final ReportManager reportManager;
    private final ReportProperties reportProperties;
    private final ShadowManager shadowManager;
    private final CloudDeviceIdentityService cloudDeviceIdentityService;
    private final ReportConfigProvider reportConfigProvider;
    private final GatewayRateLimiter gatewayRateLimiter;
    private final CloudPackReportAssembler cloudPackReportAssembler;
    @Nullable
    private final DistributedLock distributedLock;
    private final TaskScheduler taskScheduler;
    @Nullable
    private final CloudAggregationService cloudAggregationService;
    @Nullable
    private final CloudBatchAccumulator cloudBatchAccumulator;
    @Nullable
    private final CloudOutboxService cloudOutboxService;
    private final ConcurrentMap<String, String> shadowGatewayMapping = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CloudDeviceIdentity> shadowIdentities = new ConcurrentHashMap<>();
    private final Set<String> flushingDevices = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, FlushTracker> flushTrackers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, FlushSession> flushSessions = new ConcurrentHashMap<>();
    private ScheduledFuture<?> flushTask;

    /**
     * 创建缓存上报服务。
     */
    public CacheReportService(ReportManager reportManager,
                              ReportProperties reportProperties,
                              ShadowManager shadowManager,
                              CloudDeviceIdentityService cloudDeviceIdentityService,
                              ReportConfigProvider reportConfigProvider,
                              GatewayRateLimiter gatewayRateLimiter,
                              CloudPackReportAssembler cloudPackReportAssembler,
                              @Nullable DistributedLock distributedLock,
                              @Qualifier("taskScheduler") TaskScheduler taskScheduler,
                              @Nullable CloudAggregationService cloudAggregationService,
                              @Nullable CloudBatchAccumulator cloudBatchAccumulator,
                              @Nullable CloudOutboxService cloudOutboxService) {
        this.reportManager = reportManager;
        this.reportProperties = reportProperties;
        this.shadowManager = shadowManager;
        this.cloudDeviceIdentityService = cloudDeviceIdentityService;
        this.reportConfigProvider = reportConfigProvider;
        this.gatewayRateLimiter = gatewayRateLimiter;
        this.cloudPackReportAssembler = cloudPackReportAssembler;
        this.distributedLock = distributedLock;
        this.taskScheduler = taskScheduler;
        this.cloudAggregationService = cloudAggregationService;
        this.cloudBatchAccumulator = cloudBatchAccumulator;
        this.cloudOutboxService = cloudOutboxService;
    }

    /**
     * 处理组件生命周期。
     */
    @PostConstruct
    public void start() {
        if (!isMqttEnabled()) {
            return;
        }
        long interval = Math.max(1000L, reportProperties.getIntervalMs());
        flushTask = taskScheduler.scheduleAtFixedRate(this::flushDirtyDevices, Duration.ofMillis(interval));
    }

    /**
     * 处理组件生命周期。
     */
    @PreDestroy
    public void shutdown() {
        if (flushTask != null) {
            flushTask.cancel(false);
        }
        try {
            flushDirtyDevices();
        } catch (RuntimeException e) {
            log.warn("停机前刷新待上报设备失败", e);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    public void reportPoint(String localDeviceId, String method, DataPoint point, Object cacheValue) {
        if (!isMqttEnabled() || localDeviceId == null || point == null || cacheValue == null) {
            return;
        }
        CloudTargetConfig cloudTarget = cloudDeviceIdentityService.resolveTarget(localDeviceId);
        if (cloudTarget == null || !cloudTarget.valid()) {
            if (point.isReportEnabled()) {
                log.warn("跳过云端上报，设备未配置有效 cloudTarget，设备={}, 点位编码={}",
                        localDeviceId, point.getPointCode());
            }
            return;
        }
        ProcessResult processResult = toProcessResult(cacheValue);
        if (processResult == null) {
            return;
        }
        String gatewayDeviceId = gatewayDeviceId();
        shadowGatewayMapping.put(localDeviceId, gatewayDeviceId);
        shadowIdentities.put(localDeviceId, cloudTarget.identity());

        ShadowUpdateResult updateResult = shadowManager.apply(localDeviceId, point, processResult);
        if (updateResult.changeTriggered()) {
            triggerImmediateFlush(localDeviceId);
        }
        EventInfo eventInfo = updateResult.eventInfo();
        if (eventInfo != null) {
            dispatchEvent(gatewayDeviceId, cloudTarget, localDeviceId, point, processResult, eventInfo);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private ProcessResult toProcessResult(Object cacheValue) {
        if (cacheValue instanceof ProcessResult processResult) {
            return processResult;
        }
        ProcessResult result = new ProcessResult();
        result.setSuccess(true);
        result.setRawValue(cacheValue);
        result.setProcessedValue(cacheValue);
        result.setQuality(QualityEnum.GOOD.getCode());
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    private String gatewayDeviceId() {
        ReportProperties.Mqtt mqtt = reportProperties.getMqtt();
        if (mqtt == null || mqtt.getGatewayDeviceName() == null || mqtt.getGatewayDeviceName().isBlank()) {
            return DEFAULT_GATEWAY_DEVICE_ID;
        }
        return mqtt.getGatewayDeviceName();
    }

    /**
     * 执行当前业务逻辑。
     */
    private String gatewayProductKey() {
        ReportProperties.Mqtt mqtt = reportProperties.getMqtt();
        if (mqtt == null || mqtt.getGatewayProductKey() == null) {
            return "";
        }
        return mqtt.getGatewayProductKey();
    }

    /**
     * 从正式设备配置恢复影子上报所需的运行时索引。
     *
     * @param localDeviceId 本地设备ID
     * @return 网关设备ID，云身份无效时返回空
     */
    private String restoreCloudContext(String localDeviceId) {
        if (localDeviceId == null || localDeviceId.isBlank()) {
            return null;
        }
        CloudTargetConfig cloudTarget = cloudDeviceIdentityService.resolveTarget(localDeviceId);
        if (cloudTarget == null || !cloudTarget.valid()) {
            return null;
        }
        String gatewayDeviceId = gatewayDeviceId();
        shadowGatewayMapping.put(localDeviceId, gatewayDeviceId);
        shadowIdentities.put(localDeviceId, cloudTarget.identity());
        return gatewayDeviceId;
    }

    /**
     * 解析或转换业务数据。
     */
    private CloudDeviceIdentity resolveShadowIdentity(String localDeviceId) {
        CloudDeviceIdentity identity = shadowIdentities.get(localDeviceId);
        if (identity != null && identity.valid()) {
            return identity;
        }
        if (restoreCloudContext(localDeviceId) == null) {
            return null;
        }
        return shadowIdentities.get(localDeviceId);
    }

    /**
     * 更新或刷新业务状态。
     */
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

    /**
     * 执行当前业务逻辑。
     */
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
                log.error("Flush 脏数据 设备 失败:{}", deviceId, e);
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private Set<String> tryFlushGatewayBatches(Set<String> dirtyDevices) {
        Set<String> batchedDevices = new java.util.LinkedHashSet<>();
        if (cloudBatchAccumulator == null || cloudAggregationService == null || dirtyDevices.size() < 2) {
            return batchedDevices;
        }
        Map<String, List<String>> devicesByGateway = new java.util.LinkedHashMap<>();
        for (String deviceId : dirtyDevices) {
            String gatewayDeviceId = restoreCloudContext(deviceId);
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

    /**
     * 执行当前业务逻辑。
     */
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
            selectedDeviceIds.add(snapshot.aggregateTargetId());
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
        ReportData aggregatedData = batchReport.get().reportData();
        aggregatedData.addMetadata(CloudOutboxMetadataKeys.GATEWAY_DEVICE_ID, gatewayDeviceId);
        String outboxMessageId = null;
        if (cloudOutboxService != null && cloudOutboxService.isEnabled()) {
            try {
                List<CloudOutboxMessage.CloudOutboxCommit> commits =
                        new ArrayList<>(selectedCandidates.size());
                long now = System.currentTimeMillis();
                for (BatchFlushCandidate candidate : selectedCandidates) {
                    commits.add(new CloudOutboxMessage.CloudOutboxCommit(
                            candidate.deviceId,
                            metadataLong(candidate.snapshot, CloudOutboxMetadataKeys.SHADOW_VERSION),
                            now - reportProperties.getIntervalMs(),
                            now,
                            new LinkedHashMap<>(candidate.snapshot.getProperties())));
                }
                outboxMessageId = cloudOutboxService.stageBatch(commits, aggregatedData);
            } catch (RuntimeException exception) {
                log.error("持久化多设备聚合上报失败，本次不发送，网关设备={}",
                        gatewayDeviceId, exception);
                selectedCandidates.forEach(candidate -> closeFlushSession(candidate.session, false));
                return selectedDeviceIds;
            }
        }
        if (!gatewayRateLimiter.tryAcquire(false)) {
            selectedCandidates.forEach(candidate -> closeFlushSession(candidate.session, false));
            if (outboxMessageId == null) {
                selectedDeviceIds.clear();
            }
            return selectedDeviceIds;
        }

        if (!markOutboxPublishing(outboxMessageId)) {
            selectedCandidates.forEach(candidate -> closeFlushSession(candidate.session, false));
            return selectedDeviceIds;
        }
        CompletableFuture<ReportResult> future = reportManager.reportAsync(aggregatedData, reportConfig);
        String stagedMessageId = outboxMessageId;
        future.whenComplete((result, throwable) ->
                handleBatchFlushResult(batchReport.get(), selectedCandidates, result, throwable,
                        reportConfig, stagedMessageId));
        return selectedDeviceIds;
    }

    /**
     * 执行当前业务逻辑。
     */
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
            String mappedGateway = restoreCloudContext(deviceId);
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

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 解析或转换业务数据。
     */
    private CloudDeviceIdentity resolveGatewayCloudIdentity(ReportConfig config, String gatewayDeviceId) {
        String productKey = config.getStringParam("gatewayProductKey");
        if (productKey == null || productKey.isBlank()) {
            productKey = gatewayProductKey();
        }
        String deviceName = config.getStringParam("gatewayDeviceName");
        if (deviceName == null || deviceName.isBlank()) {
            deviceName = gatewayDeviceId;
        }
        return CloudDeviceIdentity.of(productKey, deviceName);
    }

    /**
     * 处理当前业务流程。
     */
    private void handleBatchFlushResult(CloudBatchReport batchReport,
                                        List<BatchFlushCandidate> candidates,
                                        ReportResult result,
                                        Throwable throwable,
                                        ReportConfig config,
                                        String outboxMessageId) {
        if (cloudOutboxService != null) {
            cloudOutboxService.handlePublishResult(outboxMessageId, result, throwable);
        }
        boolean success = throwable == null && result != null && result.isSuccess();
        long now = System.currentTimeMillis();
        if (throwable != null) {
            log.error("发送网关属性包失败:{} -> {}", batchReport.reportData().getPointCode(),
                    config.getTargetId(), throwable);
        } else if (!success) {
            log.warn("网关 属性包 被拒绝:{} -> {}, 错误={}",
                    batchReport.reportData().getPointCode(), config.getTargetId(),
                    result != null ? result.getErrorMessage() : "unknown");
        }

        for (BatchFlushCandidate candidate : candidates) {
            if (outboxMessageId == null) {
                if (success) {
                    shadowManager.markReportedValuesChunk(candidate.deviceId, candidate.snapshot.getProperties());
                    shadowManager.markReportedWindowCommitted(
                            candidate.deviceId,
                            now - reportProperties.getIntervalMs(),
                            now);
                } else {
                    scheduleBatchFallbackRetry(candidate.deviceId);
                }
            }
            closeFlushSession(candidate.session, false);
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void scheduleBatchFallbackRetry(String deviceId) {
        long delayMillis = computeRetryDelayMillis(0);
        taskScheduler.schedule(() -> flushDevice(deviceId), Instant.now().plusMillis(delayMillis));
    }
    /**
     * 执行当前业务逻辑。
     */
    private void flushDevice(String deviceId) {
        if (!flushingDevices.add(deviceId)) {
            return;
        }

        FlushSession session = tryOpenFlushSession(deviceId);
        if (session == null) {
            flushingDevices.remove(deviceId);
            return;
        }

        String gatewayDeviceId = restoreCloudContext(deviceId);
        DeviceShadow shadow = shadowManager.getShadow(deviceId);
        if (gatewayDeviceId == null) {
            log.warn("保留待上报影子，设备云身份暂时无法恢复，设备={}", deviceId);
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
            log.warn("跳过 刷新 because 上报 配置 is 无效, 设备={}", deviceId);
            abortFlush(session);
            return;
        }

        if (shouldUseGatewayTopicForSubDevice(snapshot, reportConfig, gatewayDeviceId)) {
            ReportData propertyPack = buildSingleSubDevicePack(snapshot, reportConfig, gatewayDeviceId);
            if (propertyPack == null) {
                log.warn("跳过 刷新 because 网关 属性包 is 无效, 设备={}", deviceId);
                abortFlush(session);
                return;
            }
            dispatchGatewayPack(deviceId, session, snapshot, propertyPack, reportConfig);
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

    /**
     * 执行当前业务逻辑。
     */
    private boolean shouldUseGatewayTopicForSubDevice(ReportData snapshot,
                                                      ReportConfig reportConfig,
                                                      String gatewayDeviceId) {
        if (reportProperties.getCloud().isSubDeviceTopicProxyEnabled()) {
            return false;
        }
        CloudDeviceIdentity gatewayIdentity = resolveGatewayCloudIdentity(reportConfig, gatewayDeviceId);
        CloudDeviceIdentity reportIdentity = resolveReportIdentity(snapshot);
        return gatewayIdentity.valid()
                && reportIdentity.valid()
                && !gatewayIdentity.equals(reportIdentity);
    }

    /**
     * 创建并返回业务对象。
     */
    private ReportData buildSingleSubDevicePack(ReportData snapshot,
                                                ReportConfig reportConfig,
                                                String gatewayDeviceId) {
        CloudAggregateSnapshot aggregateSnapshot = snapshotToAggregateSnapshot(snapshot);
        if (aggregateSnapshot == null) {
            return null;
        }
        return cloudPackReportAssembler.assemble(
                resolveGatewayCloudIdentity(reportConfig, gatewayDeviceId),
                gatewayDeviceId,
                List.of(aggregateSnapshot)
        );
    }

    /**
     * 查询并返回业务数据。
     */
    private CloudAggregateSnapshot snapshotToAggregateSnapshot(ReportData snapshot) {
        CloudDeviceIdentity identity = resolveReportIdentity(snapshot);
        if (!identity.valid() || (!snapshot.hasProperties() && !snapshot.hasEvents())) {
            return null;
        }
        String aggregateTargetId = Optional.ofNullable(snapshot.getMetadata().get("shadowKey"))
                .map(String::valueOf)
                .orElse(snapshot.getDeviceId());
        return new CloudAggregateSnapshot(
                aggregateTargetId,
                identity,
                MessageConstant.MESSAGE_TYPE_EVENT_POST.equals(snapshot.getMethod())
                        ? Map.of()
                        : snapshot.getProperties(),
                snapshot.getPropertyTs(),
                snapshot.getPropertyQuality(),
                snapshot.getPropertyMetadata(),
                snapshot.getEvents()
        );
    }

    /**
     * 解析或转换业务数据。
     */
    private CloudDeviceIdentity resolveReportIdentity(ReportData data) {
        if (data == null) {
            return CloudDeviceIdentity.of(null, null);
        }
        Object productKey = data.getMetadata().get("productKey");
        return CloudDeviceIdentity.of(productKey != null ? String.valueOf(productKey) : null, data.getDeviceId());
    }

    /**
     * 处理当前业务流程。
     */
    private void dispatchGatewayPack(String deviceId,
                                     FlushSession session,
                                     ReportData snapshot,
                                     ReportData propertyPack,
                                     ReportConfig reportConfig) {
        // property pack 的 MQTT payload 来自 metadata.propertyPack；这里的 properties 仅用于本地影子提交。
        snapshot.getProperties().forEach((field, value) -> propertyPack.addProperty(
                field,
                value,
                snapshot.getPropertyTs().getOrDefault(field, snapshot.getTimestamp()),
                snapshot.getPropertyQuality().get(field),
                snapshot.getPropertyMetadata().get(field)
        ));
        FlushTracker tracker = new FlushTracker(
                deviceId,
                System.currentTimeMillis() - reportProperties.getIntervalMs(),
                System.currentTimeMillis(),
                Math.max(0, reportProperties.getRetryTimes()),
                Math.max(1, reportProperties.getMaxPendingChunksPerDevice())
        );
        session.bind(tracker);
        flushTrackers.put(deviceId, tracker);
        dispatch(propertyPack, reportConfig, false, tracker, 0);
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
                log.debug("跳过 刷新 because distributed lock is held, 设备={}", deviceId);
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

    /**
     * 执行当前业务逻辑。
     */
    private void abortFlush(FlushSession session) {
        closeFlushSession(session, false);
    }

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 执行当前业务逻辑。
     */
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
            log.debug("Distributed 刷新 lock 已存在 released or expired, 键={}", session.lockKey);
        }
    }

    /**
     * 创建并返回业务对象。
     */
    private ReportData buildSnapshot(DeviceShadow shadow, String gatewayDeviceId) {
        ReportData data = new ReportData();
        String localDeviceId = shadow.getDeviceId();
        CloudDeviceIdentity identity = resolveShadowIdentity(localDeviceId);
        data.setDeviceId(identity != null && identity.valid() ? identity.deviceName() : localDeviceId);
        data.setTimestamp(System.currentTimeMillis());
        data.addMetadata("schemaVersion", reportProperties.getSchemaVersion());
        data.addMetadata("seq", shadow.nextSeq());
        data.addMetadata(CloudOutboxMetadataKeys.SHADOW_VERSION, shadow.currentVersion());
        if (localDeviceId != null) {
            data.addMetadata(CommonMapKeys.RAW_DEVICE_ID, localDeviceId);
        }
        if (gatewayDeviceId != null) {
            data.addMetadata("gatewayDeviceId", gatewayDeviceId);
        }
        if (identity != null && identity.valid()) {
            data.addMetadata("productKey", identity.productKey());
            data.addMetadata("cloudDeviceName", identity.deviceName());
            data.addMetadata("shadowKey", localDeviceId);
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

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 处理当前业务流程。
     */
    private void dispatch(ReportData data,
                          ReportConfig config,
                          boolean highPriority,
                          FlushTracker tracker) {
        dispatch(data, config, highPriority, tracker, 0);
    }

    /**
     * 处理当前业务流程。
     */
    private void dispatch(ReportData data,
                          ReportConfig config,
                          boolean highPriority,
                          FlushTracker tracker,
                          int attempt) {
        String chunkKey = tracker != null ? tracker.tryRegisterDispatch(data) : null;
        if (tracker != null && chunkKey == null) {
            tracker.markFailure();
            log.warn("跳过分片分发，原因=待处理数量达到上限, 设备={}, 点位={}",
                    data.getDeviceId(), data.getPointCode());
            completeIfIdle(tracker);
            return;
        }
        String outboxMessageId;
        try {
            outboxMessageId = stageOutbox(data, tracker);
        } catch (RuntimeException exception) {
            log.error("持久化云端上报消息失败，本次不发送，设备={}, 点位={}",
                    resolveLocalDeviceId(data, tracker), data.getPointCode(), exception);
            if (tracker != null) {
                tracker.markFailure();
                if (tracker.markCompleted()) {
                    completeFlush(tracker);
                }
            }
            return;
        }
        if (!gatewayRateLimiter.tryAcquire(highPriority)) {
            log.warn("网关限流丢弃本次上报:{} -> {}", data.getPointCode(), config.getTargetId());
            handleChunkResult(data, null, null, tracker, chunkKey, config, highPriority, attempt, outboxMessageId);
            return;
        }

        if (!markOutboxPublishing(outboxMessageId)) {
            if (tracker != null) {
                tracker.markFailure();
                if (tracker.markCompleted()) {
                    completeFlush(tracker);
                }
            }
            return;
        }
        CompletableFuture<ReportResult> future = reportManager.reportAsync(data, config);
        future.whenComplete((result, throwable) ->
                handleChunkResult(data, result, throwable, tracker, chunkKey, config,
                        highPriority, attempt, outboxMessageId));
    }

    /**
     * 处理当前业务流程。
     */
    private void handleChunkResult(ReportData data,
                                   ReportResult result,
                                   Throwable throwable,
                                   FlushTracker tracker,
                                   String chunkKey,
                                   ReportConfig config,
                                   boolean highPriority,
                                   int attempt) {
        handleChunkResult(data, result, throwable, tracker, chunkKey, config,
                highPriority, attempt, null);
    }

    /**
     * 处理当前业务流程。
     */
    private void handleChunkResult(ReportData data,
                                   ReportResult result,
                                   Throwable throwable,
                                   FlushTracker tracker,
                                   String chunkKey,
                                   ReportConfig config,
                                   boolean highPriority,
                                   int attempt,
                                   String outboxMessageId) {
        if (cloudOutboxService != null) {
            cloudOutboxService.handlePublishResult(outboxMessageId, result, throwable);
        }
        if (tracker == null) {
            return;
        }

        boolean success = throwable == null && result != null && result.isSuccess();
        boolean deferred = isDeferredResult(result);
        boolean retryable = isRetryableResult(result, throwable);
        boolean scheduledRetry = false;

        if (throwable != null) {
            log.error("发送遥测失败:{} -> {}", data.getPointCode(), config.getTargetId(), throwable);
        } else if (result != null && !result.isSuccess() && !deferred) {
            log.warn("上报被拒绝:{} -> {}, 错误={}",
                    data.getPointCode(), config.getTargetId(), result.getErrorMessage());
        }

        if (deferred) {
            if (chunkKey != null && tracker.shouldRetry(chunkKey)) {
                scheduledRetry = true;
                log.warn("延迟上报重试：设备={}, 键={}, 重试次数={} / {}",
                        tracker.deviceId, chunkKey, tracker.getAttemptCount(chunkKey), reportProperties.getRetryTimes());
                scheduleDeferredRetry(data, config, highPriority, tracker, attempt);
            } else {
                tracker.markFailure();
                log.warn("延迟上报重试已耗尽：设备={}, 键={}, 最大重试次数={}",
                        tracker.deviceId, chunkKey, reportProperties.getRetryTimes());
            }
        } else if (success && cloudOutboxService != null && cloudOutboxService.isAwaitingAck(result)) {
            tracker.markFailure();
            log.debug("云端消息等待平台业务确认，暂不提交影子，设备={}, 键={}",
                    tracker.deviceId, chunkKey);
        } else if (success) {
            shadowManager.markReportedValuesChunk(tracker.deviceId, data.getProperties());
        } else if (chunkKey != null && retryable && tracker.shouldRetry(chunkKey)) {
            scheduledRetry = true;
            log.warn("分片重试：设备={}, 键={}, 重试次数={} / {}",
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

    /**
     * 执行当前业务逻辑。
     */
    private String stageOutbox(ReportData data, FlushTracker tracker) {
        if (cloudOutboxService == null || !cloudOutboxService.isEnabled()) {
            return null;
        }
        String localDeviceId = resolveLocalDeviceId(data, tracker);
        long shadowVersion = metadataLong(data, CloudOutboxMetadataKeys.SHADOW_VERSION);
        long windowStart = tracker != null ? tracker.windowStart : data.getTimestamp();
        long windowEnd = tracker != null ? tracker.windowEnd : data.getTimestamp();
        return cloudOutboxService.stage(localDeviceId, shadowVersion, windowStart, windowEnd, data);
    }

    /**
     * 真实发送前先推进发件箱状态，使快速 ACK 有持久状态可以匹配。
     */
    private boolean markOutboxPublishing(String outboxMessageId) {
        return cloudOutboxService == null
                || outboxMessageId == null
                || cloudOutboxService.markPublishing(outboxMessageId);
    }

    private String resolveLocalDeviceId(ReportData data, FlushTracker tracker) {
        if (tracker != null) {
            return tracker.deviceId;
        }
        Object value = data.getMetadata().get(CloudOutboxMetadataKeys.RAW_DEVICE_ID);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 执行当前业务逻辑。
     */
    private long metadataLong(ReportData data, String key) {
        Object value = data.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 处理当前业务流程。
     */
    private void scheduleDeferredRetry(ReportData data,
                                       ReportConfig config,
                                       boolean highPriority,
                                       FlushTracker tracker,
                                       int attempt) {
        long delayMillis = Math.max(2000L, computeRetryDelayMillis(attempt + 1));
        taskScheduler.schedule(() -> dispatch(data, config, highPriority, tracker, attempt + 1),
                Instant.now().plusMillis(delayMillis));
        log.debug("延迟 重试 已调度 设备={} 点位={} 延迟={}ms",
                data.getDeviceId(), data.getPointCode(), delayMillis);
    }

    /**
     * 处理当前业务流程。
     */
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

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 处理当前业务流程。
     */
    private void dispatchEvent(String gatewayDeviceId,
                               CloudTargetConfig cloudTarget,
                               String localDeviceId,
                               DataPoint point,
                               ProcessResult result,
                               EventInfo eventInfo) {
        if (cloudTarget == null || !cloudTarget.valid()) {
            return;
        }
        String cloudDeviceName = cloudTarget.getDeviceName();
        ReportConfig reportConfig = resolveReportConfig(localDeviceId);
        if (reportConfig == null || !reportConfig.validate()) {
            log.warn("跳过 event 上报 because 配置 is 无效, 设备={}", cloudDeviceName);
            return;
        }
        ReportData eventData = new ReportData();
        eventData.setDeviceId(cloudDeviceName);
        ReportData.applyPointInfo(eventData, point);
        eventData.setTimestamp(System.currentTimeMillis());
        eventData.setMethod(MessageConstant.MESSAGE_TYPE_EVENT_POST);
        eventData.setValue(result.getFinalValue());
        eventData.setQuality(QualityEnum.fromCode(result.getQuality()).getText());
        eventData.addMetadata(CommonMapKeys.RAW_DEVICE_ID, localDeviceId);
        eventData.addMetadata("gatewayDeviceId", gatewayDeviceId);
        eventData.addMetadata("productKey", cloudTarget.getProductKey());
        eventData.addMetadata("cloudDeviceName", cloudTarget.getDeviceName());
        eventData.addMetadata("shadowKey", localDeviceId);
        eventData.addMetadata(CommonMapKeys.EVENT_TYPE, eventInfo.eventType());
        if (eventInfo.level() != null) {
            eventData.addMetadata("eventLevel", eventInfo.level());
        }
        if (eventInfo.message() != null) {
            eventData.addMetadata("eventMessage", eventInfo.message());
        }
        if (eventInfo.ruleId() != null) {
            eventData.addMetadata(CommonMapKeys.RULE_ID, eventInfo.ruleId());
        }
        if (eventInfo.ruleName() != null) {
            eventData.addMetadata(CommonMapKeys.RULE_NAME, eventInfo.ruleName());
        }
        eventData.addMetadata("reportField", point.getReportField());
        if (point.getUnit() != null) {
            eventData.addMetadata(CommonMapKeys.UNIT, point.getUnit());
        }
        if (point.getDeviceName() != null) {
            eventData.addMetadata("deviceName", point.getDeviceName());
        }
        addEventPayload(eventData);
        if (shouldUseGatewayTopicForSubDevice(eventData, reportConfig, gatewayDeviceId)) {
            ReportData propertyPack = buildSingleSubDevicePack(eventData, reportConfig, gatewayDeviceId);
            if (propertyPack != null) {
                dispatch(propertyPack, reportConfig, true, null);
                return;
            }
        }
        dispatch(eventData, reportConfig, true, null);
    }

    /**
     * 执行当前业务逻辑。
     */
    public void reportAlert(AlertNotification notification) {
        if (!isMqttEnabled() || notification == null) {
            return;
        }
        String localDeviceId = notification.getDeviceId();
        if (localDeviceId == null || localDeviceId.isEmpty()) {
            log.warn("跳过告警上传，原因=设备缺失");
            return;
        }
        CloudTargetConfig cloudTarget = cloudDeviceIdentityService.resolveTarget(localDeviceId);
        if (cloudTarget == null || !cloudTarget.valid()) {
            log.warn("跳过告警上传，原因=云平台目标缺失，设备={}", localDeviceId);
            return;
        }
        String gatewayDeviceId = gatewayDeviceId();
        shadowGatewayMapping.put(localDeviceId, gatewayDeviceId);
        shadowIdentities.put(localDeviceId, cloudTarget.identity());
        ReportConfig reportConfig = resolveReportConfig(localDeviceId);
        if (reportConfig == null) {
            log.warn("跳过告警上传，原因=上报配置缺失，设备={}", localDeviceId);
            return;
        }

        ReportData alertData = new ReportData();
        alertData.setDeviceId(cloudTarget.getDeviceName());
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
        alertData.addMetadata(CommonMapKeys.EVENT_TYPE,
                Optional.ofNullable(notification.getEventType()).orElse("ALARM"));
        alertData.addMetadata("eventLevel",
                Optional.ofNullable(notification.getLevel()).orElse("WARNING"));
        alertData.addMetadata("eventMessage", notification.getMessage());
        if (notification.getRuleId() != null) {
            alertData.addMetadata(CommonMapKeys.RULE_ID, notification.getRuleId());
        }
        if (notification.getRuleName() != null) {
            alertData.addMetadata(CommonMapKeys.RULE_NAME, notification.getRuleName());
        }
        if (notification.getDeviceName() != null) {
            alertData.addMetadata("deviceName", notification.getDeviceName());
        }
        if (notification.getUnit() != null) {
            alertData.addMetadata(CommonMapKeys.UNIT, notification.getUnit());
        }
        alertData.addMetadata(CommonMapKeys.RAW_DEVICE_ID, localDeviceId);
        alertData.addMetadata("gatewayDeviceId", gatewayDeviceId);
        alertData.addMetadata("productKey", cloudTarget.getProductKey());
        alertData.addMetadata("cloudDeviceName", cloudTarget.getDeviceName());
        alertData.addMetadata("shadowKey", localDeviceId);
        alertData.addProperty(pointCode, notification.getValue(),
                timestamp, QualityEnum.WARNING.getText());
        addEventPayload(alertData);
        if (shouldUseGatewayTopicForSubDevice(alertData, reportConfig, gatewayDeviceId)) {
            ReportData propertyPack = buildSingleSubDevicePack(alertData, reportConfig, gatewayDeviceId);
            if (propertyPack != null) {
                dispatch(propertyPack, reportConfig, true, null);
                return;
            }
        }
        dispatch(alertData, reportConfig, true, null);
    }

    /**
     * 执行当前业务逻辑。
     */
    private void addEventPayload(ReportData eventData) {
        if (eventData == null) {
            return;
        }
        String identifier = Optional.ofNullable(eventData.getMetadata().get(CommonMapKeys.EVENT_TYPE))
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .orElse(eventData.getPointCode() != null ? eventData.getPointCode() : "event");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(CommonMapKeys.VALUE, eventData.getValue());
        if (eventData.getQuality() != null) {
            value.put(CommonMapKeys.QUALITY, eventData.getQuality());
        }
        if (eventData.getMetadata() != null && !eventData.getMetadata().isEmpty()) {
            value.putAll(eventData.getMetadata());
        }
        eventData.addEvent(identifier, value, eventData.getTimestamp());
    }

    /**
     * 处理当前业务流程。
     */
    @EventListener
    public void handleConfigUpdate(ConfigUpdateEvent event) {
        if (event.getDeviceId() != null) {
            evictReportMappings(event.getDeviceId(), "local-delete".equals(event.getConfigType()));
        }
    }

    private boolean isMqttEnabled() {
        return reportProperties != null && reportProperties.mqttEnabled();
    }

    /**
     * 执行当前业务逻辑。
     */
    private void evictReportMappings(String deviceId, boolean removeShadow) {
        if (deviceId == null) {
            return;
        }
        reportConfigProvider.evict(deviceId);
        String mappedGateway = shadowGatewayMapping.remove(deviceId);
        shadowIdentities.remove(deviceId);
        if (mappedGateway != null) {
            reportConfigProvider.evict(mappedGateway);
        }
        if (removeShadow) {
            shadowManager.removeShadow(deviceId);
        }

        List<String> localDeviceIds = new ArrayList<>();
        shadowGatewayMapping.forEach((localDeviceId, gatewayDeviceId) -> {
            if (deviceId.equals(gatewayDeviceId)) {
                localDeviceIds.add(localDeviceId);
            }
        });
        for (String localDeviceId : localDeviceIds) {
            shadowGatewayMapping.remove(localDeviceId);
            shadowIdentities.remove(localDeviceId);
            if (removeShadow) {
                shadowManager.removeShadow(localDeviceId);
            }
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private ReportConfig resolveReportConfig(String deviceId) {
        String gatewayDeviceId = restoreCloudContext(deviceId);
        if (gatewayDeviceId == null) {
            return null;
        }
        ReportConfig config = reportConfigProvider.getConfig(gatewayDeviceId);
        if (config != null && config.validate()) {
            return config;
        }
        return null;
    }

    /**
     * 定义当前模块的业务组件。
     */
    private static class BatchFlushCandidate {
        private final String deviceId;
        private final FlushSession session;
        private final ReportData snapshot;
        private final CloudAggregateSnapshot aggregateSnapshot;

        /**
         * 创建当前组件实例。
         */
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
    /**
     * 定义当前模块的业务组件。
     */
    private static class FlushSession {
        private final String deviceId;
        private final String lockKey;
        private final DistributedLock.LockHandle lockHandle;
        private volatile FlushTracker tracker;

        /**
         * 创建当前组件实例。
         */
        private FlushSession(String deviceId,
                             String lockKey,
                             @Nullable DistributedLock.LockHandle lockHandle) {
            this.deviceId = deviceId;
            this.lockKey = lockKey;
            this.lockHandle = lockHandle;
        }

        /**
         * 执行当前业务逻辑。
         */
        private void bind(FlushTracker tracker) {
            this.tracker = tracker;
        }
    }

    /**
     * 定义当前模块的业务组件。
     */
    private static class FlushTracker {
        private final String deviceId;
        private final AtomicInteger inFlight = new AtomicInteger(0);
        private final AtomicBoolean failure = new AtomicBoolean(false);
        private final long windowStart;
        private final long windowEnd;
        private final int maxRetries;
        private final int maxPendingChunks;
        private final ConcurrentMap<String, Integer> attempts = new ConcurrentHashMap<>();

        /**
         * 创建当前组件实例。
         */
        private FlushTracker(String deviceId, long windowStart, long windowEnd, int maxRetries, int maxPendingChunks) {
            this.deviceId = deviceId;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.maxRetries = Math.max(0, maxRetries);
            this.maxPendingChunks = Math.max(1, maxPendingChunks);
        }

        /**
         * 执行当前业务逻辑。
         */
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

        /**
         * 创建并返回业务对象。
         */
        private String buildChunkKey(ReportData data) {
            Object batchId = data.getMetadata().getOrDefault("batchId", data.getDeviceId());
            Object chunkIndex = data.getMetadata().getOrDefault("chunkIndex", data.getPointCode());
            Object seq = data.getMetadata().getOrDefault("seq", data.getTimestamp());
            return batchId + ":" + chunkIndex + ":" + seq;
        }

        /**
         * 执行当前业务逻辑。
         */
        boolean shouldRetry(String chunkKey) {
            if (chunkKey == null) {
                return false;
            }
            return attempts.getOrDefault(chunkKey, 0) <= maxRetries;
        }

        int getAttemptCount(String chunkKey) {
            return attempts.getOrDefault(chunkKey, 0);
        }

        /**
         * 记录或统计业务状态。
         */
        void markFailure() {
            failure.set(true);
        }

        /**
         * 执行当前业务逻辑。
         */
        boolean hasFailure() {
            return failure.get();
        }

        /**
         * 记录或统计业务状态。
         */
        boolean markCompleted() {
            return inFlight.decrementAndGet() == 0;
        }

        /**
         * 执行当前业务逻辑。
         */
        boolean hasOutstandingWork() {
            return inFlight.get() > 0;
        }
    }
}

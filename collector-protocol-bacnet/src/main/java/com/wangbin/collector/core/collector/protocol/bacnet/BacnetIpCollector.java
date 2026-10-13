package com.wangbin.collector.core.collector.protocol.bacnet;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.enums.DataQuality;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetAddress;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetCovNotification;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPointPlan;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultiplePlan;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultiplePlanBuilder;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResultIndex;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetRemoteDevice;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetValue;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetDeviceSnapshot;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.service.BacnetDeviceSnapshotService;
import com.wangbin.collector.core.collector.protocol.bacnet.service.BacnetSubscriptionService;
import com.wangbin.collector.core.collector.protocol.bacnet.service.BacnetValueMapper;
import com.wangbin.collector.core.collector.protocol.bacnet.service.BacnetWriteRequestBuilder;
import com.wangbin.collector.core.collector.protocol.bacnet.util.BacnetAddressParser;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.scheduler.ProtocolPointSelectionSupport;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.connection.adapter.BacnetConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.BacnetIpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 实现当前协议或设备的采集能力。
 */
@Slf4j
public class BacnetIpCollector extends ConnectionBackedCollector implements ProtocolPointSelectionSupport {

    private final Map<String, BacnetAddress> configuredAddresses = new ConcurrentHashMap<>();
    private final Map<String, CovSubscriptionBinding> subscriptionBindings = new ConcurrentHashMap<>();
    private final Object covOwnershipLock = new Object();
    private volatile CovCallbackBinding covCallbackBinding;
    private final AtomicInteger invokeIdSequence = new AtomicInteger(1);
    private final AtomicInteger subscriberProcessSequence = new AtomicInteger(1000);
    private final AtomicLong readPropertyMultipleFallbackCount = new AtomicLong(0);
    private final AtomicLong covNotificationCount = new AtomicLong(0);
    private final AtomicLong subscriptionRequestCount = new AtomicLong(0);
    private final AtomicLong subscriptionCancelCount = new AtomicLong(0);
    private final AtomicLong covResubscribeCount = new AtomicLong(0);
    private final AtomicLong covResubscribeFailureCount = new AtomicLong(0);
    private final AtomicLong covConfirmedNotificationCount = new AtomicLong(0);
    private final AtomicLong covPollingBypassCount = new AtomicLong(0);
    private final AtomicLong writePropertyMultipleCount = new AtomicLong(0);
    private final AtomicLong writePropertyMultipleFallbackCount = new AtomicLong(0);
    private final BacnetDeviceSnapshotService deviceSnapshotService = new BacnetDeviceSnapshotService();
    private final BacnetWriteRequestBuilder writeRequestBuilder = new BacnetWriteRequestBuilder();
    private final BacnetValueMapper valueMapper = new BacnetValueMapper();
    private final BacnetSubscriptionService subscriptionService = new BacnetSubscriptionService();

    private volatile BacnetConnectionAdapter connectionAdapter;
    private volatile int requestTimeoutMs = 5000;
    private final CollectionTaskGuard covTaskGuard;
    private final ScheduledExecutorService covScheduler;
    private final Executor covExecutor;
    private final AtomicLong covRenewalCount = new AtomicLong();
    private final AtomicLong covRenewalFailureCount = new AtomicLong();
    private final AtomicLong covDispatchRejectedCount = new AtomicLong();
    private final ArrayBlockingQueue<CovWork> covQueue = new ArrayBlockingQueue<>(1000);
    private final AtomicBoolean covDraining = new AtomicBoolean();

    private record CovWork(CovCallbackBinding callback, BacnetCovNotification notification, long receivedAt,
                           CovSubscriptionBinding acknowledgedBinding) {
    }

    public BacnetIpCollector() {
        this(null, null, null);
    }

    @Autowired
    public BacnetIpCollector(CollectionTaskGuard covTaskGuard,
                             @Qualifier("timeSliceScheduler") ScheduledExecutorService covScheduler,
                             @Qualifier("asyncCollectorExecutor") Executor covExecutor) {
        this.covTaskGuard = covTaskGuard;
        this.covScheduler = covScheduler;
        this.covExecutor = covExecutor;
    }

    /** 回调只使用注册时的适配器、运行代次和配置版本。 */
    private record CovCallbackBinding(BacnetConnectionAdapter adapter, Object session,
                                      long generation, long configVersion) {
    }

    /** 点位及线上的订阅标识共同确定通知归属，不能仅按对象地址分发。 */
    private record CovSubscriptionBinding(DataPoint point,
                                          BacnetSubscriptionService.SubscriptionBinding subscription,
                                          CovCallbackBinding callback,
                                          int remoteDeviceInstance, CovLease lease) {
    }

    /** 每个绑定仅保存租约状态和一个共享调度器 Future，不创建点位线程。 */
    private static final class CovLease {
        private final int lifetimeSeconds;
        private final Double increment;
        private volatile boolean active = true;
        private long confirmedAt;
        private long expiresAt;
        private long nextRenewAt;
        private int renewalAttempts;
        private String renewalFailureReason;
        private ScheduledFuture<?> renewalTask;
        private boolean inFlight;
        private boolean awaitingFreshSample;
        private long recoverySentAt;
        private final java.util.ArrayDeque<CovWork> pendingNotifications = new java.util.ArrayDeque<>();

        private CovLease(int lifetimeSeconds, Double increment) {
            this.lifetimeSeconds = lifetimeSeconds;
            this.increment = increment;
        }
    }

    @Override
    public void setRuntimeGeneration(long generation) {
        synchronized (covOwnershipLock) {
            if (runtimeGeneration != generation) {
                clearCovOwnership();
            }
            super.setRuntimeGeneration(generation);
        }
    }

    @Override
    public void setRuntimeConfigurationVersion(long version) {
        synchronized (covOwnershipLock) {
            if (runtimeConfigVersion != version) {
                clearCovOwnership();
                configuredAddresses.clear();
            }
            super.setRuntimeConfigurationVersion(version);
        }
    }

    /** 在基类取消订阅或跳过已断开分支前先禁止旧回调提交。 */
    @Override
    public void disconnect() throws CollectorException {
        synchronized (covOwnershipLock) {
            covCallbackBinding = null;
        }
        super.disconnect();
    }

    @Override
    public String getCollectorType() {
        return protocolCode();
    }

    @Override
    public String getProtocolType() {
        return protocolCode();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        long generation = runtimeGeneration;
        long configVersion = runtimeConfigVersion;
        DeviceConnection desiredConfig = requireConnectionConfig();
        BacnetConnectionAdapter adapter = createBacnetConnectionAdapter(desiredConfig);
        bindCovConnection(adapter, generation, configVersion);
        DeviceConnection currentConfig = getCurrentConnectionConfig();
        if (currentConfig == null) {
            currentConfig = desiredConfig;
        }
        requestTimeoutMs = resolveRequestTimeout(currentConfig);
        configuredAddresses.clear();
        deviceSnapshotService.clear();
        reportMatchedDiscovery();
        log.info("{} 采集器 已连接, 设备={}, 超时毫秒={}", protocolDisplayName(), deviceInfo.getDeviceId(), requestTimeoutMs);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        synchronized (covOwnershipLock) {
            clearCovOwnership();
            connectionAdapter = null;
        }
        removeManagedConnection(protocolDisplayName());
        configuredAddresses.clear();
        deviceSnapshotService.clear();
        log.info("{} 采集器 已断开, 设备={}", protocolDisplayName(), deviceInfo.getDeviceId());
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public Object readPoint(DataPoint point) throws CollectorException {
        resetInvocationProcessResults();
        long startTime = System.currentTimeMillis();
        try {
            checkConnection();
            validateReadPointOwnership(point);
            if (shouldAutoSubscribePoint(point)) {
                ProcessResult cached = cachedCovReadResult(point);
                addInvocationProcessResult(point.getPointId(), cached);
                covPollingBypassCount.incrementAndGet();
                return cached.getFinalValue();
            }
            BacnetAddress address = requireAddress(point);
            Object rawValue = doReadPoint(point);
            ProcessResult processResult = buildScalarProcessResult(point, address, rawValue, "POLLING");
            ProcessResult snapshot = completePollingReadResult(point, processResult, protocolRawValue(rawValue), startTime,
                    protocolRawValue(rawValue) == null ? "NO_VALUE" : "QUALITY_BAD");
            addInvocationProcessResult(point.getPointId(), snapshot);
            totalReadCount.incrementAndGet();
            totalReadTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return snapshot.getFinalValue();
        } catch (Exception e) {
            clearInvocationProcessResults();
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, point);
            throw new CollectorException(protocolDisplayName() + " point read failed",
                    deviceInfo.getDeviceId(),
                    point != null ? point.getPointId() : null,
                    e);
        } finally {
            finishInvocationProcessResults();
        }
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public Map<String, Object> readPoints(List<DataPoint> points) throws CollectorException {
        resetInvocationProcessResults();
        long startTime = System.currentTimeMillis();
        Map<String, Object> results = new LinkedHashMap<>();
        Map<String, ProcessResult> invocationResults = new LinkedHashMap<>();
        try {
            checkConnection();
            List<DataPoint> validPoints = points == null
                    ? Collections.emptyList()
                    : points.stream().filter(point -> point != null && point.isEnabled()).collect(Collectors.toList());
            List<DataPoint> readablePoints = new ArrayList<>();
            for (DataPoint point : validPoints) {
                try {
                    validateReadPointOwnership(point);
                    requireAddress(point);
                    readablePoints.add(point);
                } catch (Exception ex) {
                    recordException(ex, point);
                    if (point.getPointId() != null && !point.getPointId().isBlank()
                            && deviceInfo != null && Objects.equals(deviceInfo.getDeviceId(), point.getDeviceId())) {
                        ProcessResult failure = ProcessResult.error(null, "点位归属或地址配置无效", DataQuality.CONFIG_ERROR);
                        invocationResults.put(point.getPointId(), completePollingReadResult(point, failure, null, startTime,
                                "CONFIG_ERROR"));
                        results.put(point.getPointId(), null);
                    }
                }
            }
            List<DataPoint> pollPoints = filterPollingPoints(readablePoints);
            Set<String> skippedPollingIds = new HashSet<>();
            for (DataPoint point : readablePoints) {
                if (!pollPoints.contains(point)) skippedPollingIds.add(point.getPointId());
            }
            Map<String, Object> rawValues = pollPoints.isEmpty() ? Collections.emptyMap() : doReadPoints(pollPoints);
            for (DataPoint point : readablePoints) {
                String pointId = point.getPointId();
                if (skippedPollingIds.contains(pointId)) {
                    covPollingBypassCount.incrementAndGet();
                    ProcessResult cached = cachedCovReadResult(point);
                    invocationResults.put(pointId, cached);
                    results.put(pointId, cached.getFinalValue());
                    continue;
                }
                try {
                    BacnetAddress address = requireAddress(point);
                    Object rawValue = rawValues.get(pointId);
                    ProcessResult processResult = rawValue instanceof ProcessResult failure ? failure
                            : buildScalarProcessResult(point, address, rawValue, "POLLING");
                    ProcessResult snapshot = completePollingReadResult(point, processResult,
                            rawValue instanceof ProcessResult ? null : protocolRawValue(rawValue), startTime, "QUALITY_BAD");
                    invocationResults.put(pointId, snapshot);
                    results.put(pointId, snapshot.getFinalValue());
                } catch (Exception ex) {
                    log.error("{} 批量 点位 处理失败, 设备={}, 点位={}", protocolDisplayName(),
                            deviceInfo.getDeviceId(), pointId, ex);
                    recordException(ex, point);
                    ProcessResult failure = failedProtocolReadResult(ex);
                    invocationResults.put(pointId, completePollingReadResult(point, failure, null, startTime, "PROCESS_ERROR"));
                    results.put(pointId, null);
                }
            }

            totalReadCount.addAndGet(pollPoints.size());
            totalReadTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            setInvocationProcessResults(invocationResults);
            return results;
        } catch (Exception e) {
            clearInvocationProcessResults();
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, null);
            throw new CollectorException(protocolDisplayName() + " batch point read failed", deviceInfo.getDeviceId(), null, e);
        } finally {
            finishInvocationProcessResults();
        }
    }

    /** 本轮失败的观察时间不能早于失败事实，否则核心提交门会把真实 BAD 结果当作旧样本丢弃。 */
    private ProcessResult completePollingReadResult(DataPoint point, ProcessResult result, Object rawValue,
                                                    long requestedAt, String failureReason) {
        if (!result.isSuccess()) {
            reportPointFailure(point, failureReason);
            result = result.snapshot();
            result.addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, System.currentTimeMillis());
        }
        return completeReadProcessResult(point, result, rawValue, requestedAt);
    }

    /** 缓存读取不重复转换或补采样时间，旧配置结果也不能借用当前版本。 */
    private ProcessResult cachedCovReadResult(DataPoint point) {
        synchronized (covOwnershipLock) {
            ProcessResult previous = lastProcessResults.get(point.getPointId());
            Object version = previous != null ? previous.getMetadata(ProcessResultMetadataKeys.CONFIG_VERSION) : null;
            if (!(version instanceof Number number) || number.longValue() != runtimeConfigVersion
                    || (configManager != null && configManager.getDeviceConfigVersion(deviceInfo.getDeviceId()) != runtimeConfigVersion)) {
                previous = null;
            }
            ProcessResult cached = cachedReadResult(point, previous);
            CovSubscriptionBinding binding = subscriptionBindings.get(point.getPointId());
            if (binding != null && (isCovLeaseExpired(binding) || binding.lease().awaitingFreshSample)) {
                cached.addMetadata("stale", true);
                cached.addMetadata("covLeaseExpiresAt", binding.lease().expiresAt);
                cached.addMetadata("covRenewalFailureReason", binding.lease().renewalFailureReason);
                cached.setQualityDescription("STALE：COV 订阅租期已过期，保留最后观测值");
            }
            return cached;
        }
    }

    /** 遥测原始值是协议解码值，响应包装及类型元数据仍由原映射器处理。 */
    private Object protocolRawValue(Object value) {
        if (value instanceof BacnetReadPropertyResponse response) return response.getValue();
        if (value instanceof BacnetValue bacnetValue) return bacnetValue.getValue();
        return value;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        BacnetAddress address = requireAddress(point);
        return exchange(readPropertyRequest(address));
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return values;
        }
        List<BacnetReadPointPlan> pointPlans = points.stream()
                .filter(point -> point != null && point.getPointId() != null)
                .map(point -> BacnetReadPointPlan.builder()
                        .point(point)
                        .address(requireAddress(point))
                        .build())
                .collect(Collectors.toList());
        if (pointPlans.isEmpty()) {
            return values;
        }

        if (!isReadPropertyMultipleEnabled() || pointPlans.size() <= 1) {
            readSequentially(pointPlans, values);
            return values;
        }

        List<BacnetReadPropertyMultiplePlan> plans = BacnetReadPropertyMultiplePlanBuilder.build(
                pointPlans,
                requireConnectionConfig().getIntConfig("maxPropertiesPerRequest", 16));

        for (BacnetReadPropertyMultiplePlan plan : plans) {
            try {
                readPlanByReadPropertyMultiple(plan, values);
            } catch (Exception ex) {
                readPropertyMultipleFallbackCount.incrementAndGet();
                log.warn("{} ReadPropertyMultiple 失败, 降级到 ReadProperty, 设备={}, 计划点位数量={}, 错误={}", protocolDisplayName(),
                        deviceInfo.getDeviceId(),
                        plan.getPointPlans() != null ? plan.getPointPlans().size() : 0,
                        ex.getMessage());
                readSequentially(plan.getPointPlans(), values);
            }
        }
        return values;
    }
    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        BacnetAddress address = requireAddress(point);
        BacnetWritePropertyRequest request = writeRequestBuilder.buildSingle(
                point,
                address,
                value,
                resolveWriteValueType(point, address, null),
                resolveWritePriority(point, null),
                nextInvokeId(),
                requireRemoteDeviceInstance());
        exchange(request);
        return true;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }
        if (isWritePropertyMultipleEnabled() && points.size() > 1) {
            try {
                exchange(buildWritePropertyMultipleRequest(points));
                writePropertyMultipleCount.incrementAndGet();
                for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
                    DataPoint point = entry.getKey();
                    if (point != null && point.getPointId() != null) {
                        results.put(point.getPointId(), true);
                    }
                }
                return results;
            } catch (Exception ex) {
                writePropertyMultipleFallbackCount.incrementAndGet();
                log.warn("{} WritePropertyMultiple 失败, 降级到 WriteProperty, 设备={}, 点位数量={}, 错误={}",
                        protocolDisplayName(),
                        deviceInfo != null ? deviceInfo.getDeviceId() : null,
                        points.size(),
                        ex.getMessage());
                if ((connectionAdapter == null || !connectionAdapter.isConnected()) && !attemptConnectionRecovery()) {
                    log.warn("{} 在 WriteProperty 降级前恢复连接失败，设备={}",
                            protocolDisplayName(),
                            deviceInfo != null ? deviceInfo.getDeviceId() : null);
                }
            }
        }
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            DataPoint point = entry.getKey();
            if (point == null || point.getPointId() == null) {
                continue;
            }
            try {
                results.put(point.getPointId(), doWritePoint(point, entry.getValue()));
            } catch (Exception ex) {
                log.error("{} 点位 写入 失败, 设备={}, 点位={}", protocolDisplayName(),
                        deviceInfo.getDeviceId(), point.getPointId(), ex);
                recordException(ex, point);
                results.put(point.getPointId(), false);
            }
        }
        return results;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doSubscribe(List<DataPoint> points) throws Exception {
        if (points == null || points.isEmpty()) {
            return;
        }
        doSubscribe(points, ensureNotificationListenerRegistered());
    }

    /** 重连回调不可在版本变更后为新运行身份重新创建订阅。 */
    private void doSubscribe(List<DataPoint> points, CovCallbackBinding callback) throws Exception {
        unsubscribeIfAlreadyBound(points, callback);
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            BacnetAddress address = requireAddress(point);
            subscribePoint(point, address, callback);
            subscriptionRequestCount.incrementAndGet();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) throws Exception {
        if (points == null || points.isEmpty()) {
            return;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            CovSubscriptionBinding binding;
            synchronized (covOwnershipLock) {
                binding = subscriptionBindings.remove(resolvePointCacheKey(point));
                if (binding != null) cancelCovRenewal(binding);
                lastProcessResults.remove(point.getPointId());
            }
            if (binding == null) {
                continue;
            }
            try {
                unsubscribeBinding(binding);
                subscriptionCancelCount.incrementAndGet();
            } catch (Exception ex) {
                log.warn("{} 取消订阅失败, 设备={}, 点位={}", protocolDisplayName(),
                        deviceInfo.getDeviceId(), point.getPointId(), ex);
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put(CommonMapKeys.PROTOCOL, protocolCode());
        status.put(CommonMapKeys.DRIVER, driverName());
        status.put("implemented", true);
        status.put(CommonMapKeys.TRANSPORT, resolveTransportName());
        status.put("readPropertyImplemented", true);
        status.put("readPropertyMultipleImplemented", true);
        status.put("writeImplemented", true);
        status.put("subscriptionImplemented", true);
        status.put(CommonMapKeys.CONNECTED, connectionAdapter != null && connectionAdapter.isConnected());
        status.put(CommonMapKeys.CONFIGURED_POINT_COUNT, configuredAddresses.size());
        status.put("activeSubscriptions", subscriptionBindings.size());
        status.put("covSubscriptions", covLeaseSnapshots());
        status.put("covRenewalCount", covRenewalCount.get());
        status.put("covRenewalFailureCount", covRenewalFailureCount.get());
        status.put("covDispatchRejectedCount", covDispatchRejectedCount.get());
        status.put("requestTimeoutMs", requestTimeoutMs);
        status.put("readPropertyMultipleFallbackCount", readPropertyMultipleFallbackCount.get());
        status.put("covNotificationCount", covNotificationCount.get());
        status.put("subscriptionRequestCount", subscriptionRequestCount.get());
        status.put("subscriptionCancelCount", subscriptionCancelCount.get());
        status.put("covResubscribeCount", covResubscribeCount.get());
        status.put("covResubscribeFailureCount", covResubscribeFailureCount.get());
        status.put("covConfirmedNotificationCount", covConfirmedNotificationCount.get());
        status.put("covPollingBypassCount", covPollingBypassCount.get());
        status.put("writePropertyMultipleCount", writePropertyMultipleCount.get());
        status.put("writePropertyMultipleFallbackCount", writePropertyMultipleFallbackCount.get());
        status.put("requestRetryCount", connectionAdapter != null ? connectionAdapter.getRequestRetryCount() : 0L);
        status.put("requestTimeoutCount", connectionAdapter != null ? connectionAdapter.getRequestTimeoutCount() : 0L);
        status.put("invokeIdMismatchCount", connectionAdapter != null ? connectionAdapter.getInvokeIdMismatchCount() : 0L);
        status.put("segmentedResponseCount", connectionAdapter != null ? connectionAdapter.getSegmentedResponseCount() : 0L);
        status.put("tokenReceiveCount", connectionAdapter != null ? connectionAdapter.getTokenReceiveCount() : 0L);
        status.put("tokenPassCount", connectionAdapter != null ? connectionAdapter.getTokenPassCount() : 0L);
        status.put("pollForMasterCount", connectionAdapter != null ? connectionAdapter.getPollForMasterCount() : 0L);
        status.put("replyToPollCount", connectionAdapter != null ? connectionAdapter.getReplyToPollCount() : 0L);
        status.put("frameErrorCount", connectionAdapter != null ? connectionAdapter.getFrameErrorCount() : 0L);
        status.put("crcErrorCount", connectionAdapter != null ? connectionAdapter.getCrcErrorCount() : 0L);
        status.put("sessionState", connectionAdapter != null ? connectionAdapter.getSessionState() : "DISCONNECTED");
        status.put("standardSessionEstablished", connectionAdapter != null
                && connectionAdapter.isStandardSessionEstablished());
        status.put("bbmdActive", connectionAdapter != null && connectionAdapter.isForeignDeviceRegistrationActive());
        status.put("foreignDeviceRegistrationCount", connectionAdapter != null ? connectionAdapter.getForeignDeviceRegistrationCount() : 0L);
        status.put("foreignDeviceRenewCount", connectionAdapter != null ? connectionAdapter.getForeignDeviceRenewCount() : 0L);
        status.put("foreignDeviceRenewFailureCount", connectionAdapter != null ? connectionAdapter.getForeignDeviceRenewFailureCount() : 0L);
        status.put("foreignDeviceLeaseExpiresAt", connectionAdapter != null ? connectionAdapter.getForeignDeviceLeaseExpiresAt() : 0L);
        if (connectionAdapter != null) {
            BacnetRemoteDevice remoteDevice = connectionAdapter.getRemoteDevice();
            if (remoteDevice != null) {
                status.put("remoteDeviceInstance", remoteDevice.getDeviceInstance());
                status.put("remoteAddress", remoteDevice.getSocketAddress() != null ? remoteDevice.getSocketAddress().toString() : null);
                status.put("remoteVendorId", remoteDevice.getVendorId());
                status.put("remoteSegmentationSupported", remoteDevice.getSegmentationSupported());
                status.put("discoveredByWhoIs", remoteDevice.isDiscoveredByWhoIs());
            }
        }
        status.put("protocolMetrics", buildProtocolMetrics());
        status.put(CommonMapKeys.MESSAGE, capabilityMessage());
        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        String normalized = normalizeCommand(command);
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        return switch (normalized) {
            case "read", "read_property", "readproperty" -> executeReadPropertyCommand(safeParams);
            case "read_multiple", "read_property_multiple", "readpropertymultiple" -> executeReadPropertyMultipleCommand(safeParams);
            case "write", "write_property", "writeproperty" -> executeWritePropertyCommand(safeParams);
            case "status", "diagnostic", "device_status" -> getDeviceStatus();
            case "device_info", "deviceinfo" -> executeDeviceInfoCommand();
            case "who_is", "whois" -> executeWhoIsCommand();
            case "discover_objects", "discoverobjects" -> executeDiscoverObjectsCommand();
            default -> throw new IllegalArgumentException("Unsupported " + protocolDisplayName() + " command: " + command);
        };
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        synchronized (covOwnershipLock) {
            clearCovOwnership();
            configuredAddresses.clear();
            if (points == null) return;
            for (DataPoint point : points) {
                if (point == null) continue;
                try {
                    validateReadPointOwnership(point);
                    configuredAddresses.put(resolvePointCacheKey(point), BacnetAddressParser.parse(point));
                } catch (Exception ex) {
                    log.warn("缓存 BACnet 地址失败, 设备={}, 点位={}, 错误={}",
                            deviceInfo != null ? deviceInfo.getDeviceId() : null, point.getPointId(), ex.getMessage());
                }
            }
        }
    }

    /**
     * 解析或转换业务数据。
     */
    @Override
    protected Object convertDataForWrite(DataPoint point, Object value) {
        if (value == null) {
            return null;
        }
        BacnetAddress address = point != null ? requireAddress(point) : null;
        String valueType = resolveWriteValueType(point, address, null);
        if (isStringType(valueType) || value instanceof String) {
            return String.valueOf(value);
        }
        if (isBooleanType(valueType) || value instanceof Boolean) {
            return toBoolean(value);
        }
        return super.convertDataForWrite(point, value);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected ProcessResult ingestPushedValue(DataPoint point, Object rawValue) {
        CovSubscriptionBinding binding = point != null ? subscriptionBindings.get(point.getPointId()) : null;
        return ingestPushedValue(binding, rawValue, null);
    }

    /** 映射在提交锁外执行；最终副作用再次核对绑定，元数据在异步交接前完成。 */
    private ProcessResult ingestPushedValue(CovSubscriptionBinding binding, Object rawValue,
                                           BacnetCovNotification notification) {
        return ingestPushedValue(binding, rawValue, notification, System.currentTimeMillis());
    }

    private ProcessResult ingestPushedValue(CovSubscriptionBinding binding, Object rawValue,
                                           BacnetCovNotification notification, long collectTime) {
        if (binding == null || !isCurrentCovCallback(binding.callback())) return null;
        DataPoint point = binding.point();
        String resolvedDeviceId = point.getDeviceId();
        ProcessResult processResult;
        try {
            validateReadPointOwnership(point);
            processResult = valueMapper.map(dataQualityProcessor, point, binding.subscription().address(),
                    rawValue, "PUSH", resolvedDeviceId, this::convertData,
                    commit -> commitCovBinding(binding, collectTime, commit));
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, point);
            processResult = ProcessResult.error(protocolRawValue(rawValue),
                    protocolDisplayName() + " 推送数据处理失败", DataQuality.PROCESS_ERROR);
        }
        processResult.setRawValue(protocolRawValue(rawValue));
        enrichTelemetryMetadata(processResult, protocolRawValue(rawValue), processResult.getProcessedValue(), collectTime, "PUSH");
        processResult.addMetadata(ProcessResultMetadataKeys.CONFIG_VERSION, binding.callback().configVersion());
        if (notification != null) {
            processResult.addMetadata("covSubscriberProcessIdentifier", notification.getSubscriberProcessIdentifier());
            processResult.addMetadata("covTimeRemaining", notification.getTimeRemaining());
        }
        ProcessResult snapshot = processResult.snapshot();
        if (!commitCovBinding(binding, collectTime, () -> {
            lastProcessResults.put(point.getPointId(), snapshot.snapshot());
            lastActivityTime = collectTime;
            if (binding.lease().awaitingFreshSample) {
                binding.lease().awaitingFreshSample = false;
                reportEventLease(point, binding.callback().generation(), binding.lease().expiresAt);
            }
        })) return null;
        // 已提交快照携带原版本和代次；异步入口及拒绝补偿不能持有 COV 或会话生命周期锁。
        if (telemetryIngressService != null) {
            telemetryIngressService.append(resolvedDeviceId, point, snapshot, binding.callback().generation());
        }
        return snapshot;
    }

    /**
     * 创建并返回业务对象。
     */
    private ProcessResult buildScalarProcessResult(DataPoint point,
                                                   BacnetAddress address,
                                                   Object rawValue,
                                                   String source) {
        return buildProcessResult(point, address, rawValue, source, deviceInfo.getDeviceId());
    }

    /**
     * 创建并返回业务对象。
     */
    private ProcessResult buildProcessResult(DataPoint point,
                                             BacnetAddress address,
                                             Object rawValue,
                                             String source,
                                             String deviceId) {
        if (protocolRawValue(rawValue) == null) {
            ProcessResult empty = ProcessResult.error(null, "本轮协议未返回有效值", DataQuality.VALUE_INVALID);
            empty.addMetadata(CommonMapKeys.ADDRESS, address.getCanonicalAddress());
            empty.addMetadata(CommonMapKeys.SOURCE, source);
            return empty;
        }
        ProcessResult processResult = valueMapper.map(
                dataQualityProcessor,
                point,
                address,
                rawValue,
                source,
                deviceId,
                this::convertData
        );
        if (!processResult.isSuccess()) {
            log.warn("{} 数据质量检查失败 {}.{}, 原因:{}", protocolDisplayName(),
                    deviceId, point.getPointName(), processResult.getMessage());
        }
        return processResult;
    }
    /**
     * 执行当前业务逻辑。
     */
    private BacnetReadPropertyResponse exchange(BacnetReadPropertyRequest request) throws Exception {
        try {
            return requireConnection().readProperty(request, requestTimeoutMs);
        } catch (Exception ex) {
            if (shouldInvalidateConnection(ex)) {
                invalidateConnection(ex);
            }
            throw ex;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private BacnetReadPropertyMultipleResponse exchange(BacnetReadPropertyMultipleRequest request) throws Exception {
        try {
            return requireConnection().readPropertyMultiple(request, requestTimeoutMs);
        } catch (Exception ex) {
            if (shouldInvalidateConnection(ex)) {
                invalidateConnection(ex);
            }
            throw ex;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void exchange(BacnetWritePropertyRequest request) throws Exception {
        try {
            requireConnection().writeProperty(request, requestTimeoutMs);
        } catch (Exception ex) {
            if (shouldInvalidateConnection(ex)) {
                invalidateConnection(ex);
            }
            throw ex;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void exchange(BacnetWritePropertyMultipleRequest request) throws Exception {
        try {
            requireConnection().writePropertyMultiple(request, requestTimeoutMs);
        } catch (Exception ex) {
            if (shouldInvalidateConnection(ex)) {
                invalidateConnection(ex);
            }
            throw ex;
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private BacnetAddress requireAddress(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("Point cannot be null");
        }
        return configuredAddresses.computeIfAbsent(resolvePointCacheKey(point), ignored -> BacnetAddressParser.parse(point));
    }

    /**
     * 校验业务条件和参数边界。
     */
    private BacnetConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException(protocolDisplayName() + " connection has not been established");
        }
        return connectionAdapter;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private int requireRemoteDeviceInstance() {
        DeviceConnection runtimeConfig = getCurrentConnectionConfig();
        if (runtimeConfig == null) {
            runtimeConfig = requireConnectionConfig();
        }
        Integer remoteDeviceInstance = runtimeConfig.getIntConfig("remoteDeviceInstance", null);
        if (remoteDeviceInstance == null || remoteDeviceInstance < 0) {
            throw new IllegalStateException(protocolDisplayName() + " requires remoteDeviceInstance");
        }
        return remoteDeviceInstance;
    }

    private boolean isReadPropertyMultipleEnabled() {
        DeviceConnection runtimeConfig = getCurrentConnectionConfig();
        if (runtimeConfig == null) {
            runtimeConfig = requireConnectionConfig();
        }
        return !Boolean.FALSE.equals(runtimeConfig.getBoolConfig("readPropertyMultipleEnabled", true));
    }

    private boolean isWritePropertyMultipleEnabled() {
        DeviceConnection runtimeConfig = getCurrentConnectionConfig();
        if (runtimeConfig == null) {
            runtimeConfig = requireConnectionConfig();
        }
        return Boolean.TRUE.equals(runtimeConfig.getBoolConfig("writePropertyMultipleEnabled", false));
    }

    /**
     * 创建并返回业务对象。
     */
    private BacnetWritePropertyMultipleRequest buildWritePropertyMultipleRequest(Map<DataPoint, Object> points) {
        return writeRequestBuilder.buildMultiple(
                points,
                this::requireAddress,
                (point, address) -> resolveWriteValueType(point, address, null),
                point -> resolveWritePriority(point, null),
                this::nextInvokeId,
                requireRemoteDeviceInstance());
    }

    /**
     * 查询并返回业务数据。
     */
    private void readSequentially(List<BacnetReadPointPlan> pointPlans, Map<String, Object> values) {
        for (BacnetReadPointPlan pointPlan : pointPlans) {
            DataPoint point = pointPlan.getPoint();
            try {
                values.put(point.getPointId(), doReadPoint(point));
            } catch (Exception ex) {
                recordException(ex, point);
                values.put(point.getPointId(), failedProtocolReadResult(ex));
            }
        }
    }

    /**
     * 查询并返回业务数据。
     */
    private void readPlanByReadPropertyMultiple(BacnetReadPropertyMultiplePlan plan,
                                                Map<String, Object> values) throws Exception {
        BacnetReadPropertyMultipleRequest.BacnetReadPropertyMultipleRequestBuilder requestBuilder =
                BacnetReadPropertyMultipleRequest.builder()
                        .invokeId(nextInvokeId())
                        .remoteDeviceInstance(requireRemoteDeviceInstance());
        for (BacnetReadPropertyMultiplePlan.ReadGroup group : plan.getGroups()) {
            BacnetReadPropertyMultipleRequest.ReadAccessSpec.ReadAccessSpecBuilder accessSpecBuilder =
                    BacnetReadPropertyMultipleRequest.ReadAccessSpec.builder()
                            .objectType(group.getObjectType())
                            .objectInstance(group.getObjectInstance());
            for (BacnetReadPointPlan pointPlan : group.getPointPlans()) {
                BacnetAddress address = pointPlan.getAddress();
                accessSpecBuilder.propertyReference(BacnetReadPropertyMultipleRequest.PropertyReferenceSpec.builder()
                        .propertyIdentifier(BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()))
                        .arrayIndex(address.getArrayIndex())
                        .build());
            }
            requestBuilder.accessSpecification(accessSpecBuilder.build());
        }

        BacnetReadPropertyMultipleResponse response = exchange(requestBuilder.build());
        BacnetReadPropertyMultipleResultIndex resultIndex = BacnetReadPropertyMultipleResultIndex.from(response);
        for (BacnetReadPointPlan pointPlan : plan.getPointPlans()) {
            BacnetReadPropertyMultipleResponse.PropertyValueResult propertyResult = resultIndex.get(pointPlan.getAddress());
            if (propertyResult == null || propertyResult.isError()) {
                // 单项错误只降级该点，已成功的 RPM 点位不被其失败替换。
                readPropertyMultipleFallbackCount.incrementAndGet();
                readSequentially(List.of(pointPlan), values);
                continue;
            }
            values.put(pointPlan.getPoint().getPointId(), BacnetValue.builder()
                    .value(propertyResult.getValue())
                    .valueType(propertyResult.getValueType())
                    .kind(valueMapper.inferKind(propertyResult.getValue()))
                    .metadata(propertyResult.getValueMetadata() != null ? propertyResult.getValueMetadata() : Collections.emptyMap())
                    .build());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private int nextInvokeId() {
        return invokeIdSequence.updateAndGet(current -> {
            int next = current + 1;
            if (next > 255) {
                next = 1;
            }
            return next;
        });
    }

    /**
     * 执行当前业务逻辑。
     */
    private int nextSubscriberProcessIdentifier() {
        return subscriberProcessSequence.updateAndGet(current -> {
            int next = current + 1;
            if (next > 65534) {
                next = 1000;
            }
            return next;
        });
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean shouldInvalidateConnection(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof SocketException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("timed out")
                    || message.contains("socket")
                    || message.contains("invokeId mismatch")
                    || message.contains("Unexpected BACnet")
                    || message.contains("frame length mismatch"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 执行当前业务逻辑。
     */
    private void invalidateConnection(Throwable cause) {
        if (!connected && connectionAdapter == null) {
            return;
        }
        log.warn("作废{} 连接，原因=协议或传输失败, 设备={}, 错误={}", protocolDisplayName(),
                deviceInfo != null ? deviceInfo.getDeviceId() : null,
                cause != null ? cause.getMessage() : null);
        try {
            BacnetConnectionAdapter adapter;
            synchronized (covOwnershipLock) {
                adapter = connectionAdapter;
                clearCovOwnership();
                connectionAdapter = null;
            }
            if (adapter instanceof ConnectionAdapter<?> managedAdapter) {
                managedAdapter.disconnect();
            }
        } catch (Exception disconnectError) {
            log.warn("断开异常 {} 适配器 失败, 设备={}", protocolDisplayName(),
                    deviceInfo != null ? deviceInfo.getDeviceId() : null, disconnectError);
        } finally {
            removeManagedConnection(protocolDisplayName());
            connected = false;
            connectionStatus = "DISCONNECTED";
            lastError = cause != null ? cause.getMessage() : lastError;
        }
    }
    /**
     * 维护注册或订阅关系。
     */
    private void subscribePoint(DataPoint point, BacnetAddress address, CovCallbackBinding callback) throws Exception {
        validateReadPointOwnership(point);
        boolean propertyLevel = shouldUsePropertySubscription(point, address);
        boolean confirmed = resolveConfirmedNotifications(point);
        int remoteDeviceInstance = requireRemoteDeviceInstance();
        int processIdentifier = nextSubscriberProcessIdentifier();
        int timeoutMs = requestTimeoutMs;
        CovSubscriptionBinding binding = new CovSubscriptionBinding(point,
                new BacnetSubscriptionService.SubscriptionBinding(processIdentifier, address, propertyLevel, confirmed),
                callback, remoteDeviceInstance,
                new CovLease(resolveCovLifetimeSeconds(point), resolveCovIncrement(point)));
        if (binding.lease().lifetimeSeconds > 0 && callback.adapter() instanceof BacnetIpConnectionAdapter
                && (covScheduler == null || covExecutor == null)) {
            throw new IllegalStateException("有限 COV 租期需要共享调度器和 I/O 执行器");
        }
        // 初始 COV 可能早于 Subscribe ACK；发送前登记，失败时只撤销本次绑定。
        if (!runIfCurrentCovCallback(callback, () -> subscriptionBindings.put(resolvePointCacheKey(point), binding))) {
            throw new IllegalStateException("BACnet 订阅回调身份已失效");
        }
        try {
            completeCovSubscription(binding, false);
        } catch (Exception ex) {
            synchronized (covOwnershipLock) {
                cancelCovRenewal(binding);
                if (subscriptionBindings.remove(point.getPointId(), binding)) lastProcessResults.remove(point.getPointId());
            }
            if (shouldInvalidateConnection(ex) && isCurrentCovIdentity(callback)) invalidateConnection(ex);
            throw ex;
        }
    }

    /**
     * 维护注册或订阅关系。
     */
    private void unsubscribeBinding(CovSubscriptionBinding binding) throws Exception {
        int timeoutMs = requestTimeoutMs;
        BacnetIpConnectionAdapter.SessionExchange cancel = () -> subscriptionService.unsubscribe(
                binding.subscription(), this::nextInvokeId, binding.remoteDeviceInstance(),
                request -> binding.callback().adapter().subscribeCov(request, timeoutMs),
                request -> binding.callback().adapter().subscribeCovProperty(request, timeoutMs));
        if (binding.callback().adapter() instanceof BacnetIpConnectionAdapter ipAdapter) {
            ipAdapter.exchangeIfCurrentSession(binding.callback().session(), () -> true, cancel);
        } else {
            cancel.run();
        }
    }

    /**
     * 维护注册或订阅关系。
     */
    private void unsubscribeIfAlreadyBound(List<DataPoint> points, CovCallbackBinding callback) throws Exception {
        List<CovSubscriptionBinding> existing = new ArrayList<>();
        if (!runIfCurrentCovCallback(callback, () -> {
            for (DataPoint point : points) {
                if (point == null) continue;
                String key = resolvePointCacheKey(point);
                CovSubscriptionBinding binding = subscriptionBindings.get(key);
                if (binding != null && binding.callback() == callback && subscriptionBindings.remove(key, binding)) {
                    cancelCovRenewal(binding);
                    lastProcessResults.remove(key);
                    existing.add(binding);
                }
            }
        })) throw new IllegalStateException("BACnet 重新订阅回调身份已失效");
        for (CovSubscriptionBinding binding : existing) {
            try {
                unsubscribeBinding(binding);
                subscriptionCancelCount.incrementAndGet();
            } catch (Exception ex) {
                log.warn("{} 取消旧订阅失败, 设备={}, 点位={}", protocolDisplayName(),
                        deviceInfo.getDeviceId(), binding.point().getPointId(), ex);
            }
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private CovCallbackBinding ensureNotificationListenerRegistered() {
        synchronized (covOwnershipLock) {
            BacnetConnectionAdapter adapter = requireConnection();
            Object session = adapter instanceof BacnetIpConnectionAdapter ipAdapter
                    ? ipAdapter.captureSessionIdentity() : null;
            CovCallbackBinding binding = covCallbackBinding;
            if (binding == null || binding.adapter() != adapter || binding.generation() != runtimeGeneration
                    || binding.configVersion() != runtimeConfigVersion || binding.session() != session) {
                binding = new CovCallbackBinding(adapter, session, runtimeGeneration, runtimeConfigVersion);
                covCallbackBinding = binding;
            }
            CovCallbackBinding captured = binding;
            adapter.setCovNotificationListener(notification -> dispatchCovNotification(captured, notification));
            adapter.setReconnectListener(() -> handleAdapterReconnect(captured));
            return captured;
        }
    }

    /** 失效建连只退役本次实例，不以设备键清理可能已经替换的管理项。 */
    private CovCallbackBinding bindCovConnection(BacnetConnectionAdapter adapter,
                                                 long generation, long configVersion) throws Exception {
        try {
            synchronized (covOwnershipLock) {
                requireCovRuntimeIdentity(generation, configVersion);
                clearCovOwnership();
                connectionAdapter = adapter;
                return ensureNotificationListenerRegistered();
            }
        } catch (Exception failure) {
            if (adapter instanceof ConnectionAdapter<?> managedAdapter) {
                try {
                    managedAdapter.closeResources();
                } catch (Exception closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    /** 建连期间版本改变时，不能将旧配置连接的回调包装为当前版本。 */
    private void requireCovRuntimeIdentity(long generation, long configVersion) {
        if (runtimeGeneration != generation || runtimeConfigVersion != configVersion
                || (configManager != null && configManager.getDeviceConfigVersion(deviceInfo.getDeviceId()) != configVersion)) {
            throw new IllegalStateException("BACnet 建连所属运行代次或配置版本已失效");
        }
    }

    /** 调用方持有提交锁；不进行网络清理，避免旧回调和替换连接交叉操作。 */
    private void clearCovOwnership() {
        covCallbackBinding = null;
        subscriptionBindings.values().forEach(this::cancelCovRenewal);
        subscriptionBindings.clear();
        covQueue.clear();
        lastProcessResults.clear();
    }

    /** 仅串行化身份核对及本地提交，不在锁内等待协议响应。 */
    private boolean runIfCurrentCovCallback(CovCallbackBinding binding, Runnable action) {
        boolean[] committed = {false};
        Runnable guarded = () -> {
            synchronized (covOwnershipLock) {
                if (!isCurrentCovCallback(binding)) return;
                Runnable configCommit = () -> committed[0] = configManager == null
                        ? runCovAction(action)
                        : configManager.runIfConfigurationCurrent(deviceInfo.getDeviceId(), binding.configVersion(), action);
                if (binding.adapter() instanceof BacnetIpConnectionAdapter ipAdapter) {
                    ipAdapter.runIfCurrentSession(binding.session(), configCommit);
                } else {
                    configCommit.run();
                }
            }
        };
        if (binding == null) return false;
        if (covTaskGuard != null && binding.generation() > 0L) {
            covTaskGuard.commitIfCurrent(deviceInfo.getDeviceId(), binding.generation(), guarded);
        } else {
            guarded.run();
        }
        return committed[0];
    }

    private boolean isCurrentCovCallback(CovCallbackBinding binding) {
        return isCurrentCovIdentity(binding) && binding.adapter().isConnected();
    }

    private boolean runCovAction(Runnable action) {
        action.run();
        return true;
    }

    /** 锁序固定为设备代次门、绑定、会话、配置读锁；慢转换和遥测背压均在外。 */
    private boolean commitCovBinding(CovSubscriptionBinding binding, long sampleAt, Runnable action) {
        if (binding == null) return false;
        boolean[] accepted = {false};
        runIfCurrentCovCallback(binding.callback(), () -> {
            CovLease lease = binding.lease();
            if (!isCurrentCovBinding(binding) || lease.confirmedAt == 0L || isCovLeaseExpired(binding)
                    || (lease.awaitingFreshSample && sampleAt < lease.recoverySentAt)) return;
            action.run();
            accepted[0] = true;
        });
        return accepted[0];
    }

    private boolean isCurrentCovBinding(CovSubscriptionBinding binding) {
        return binding.lease().active && isCurrentCovCallback(binding.callback())
                && subscriptionBindings.get(binding.point().getPointId()) == binding;
    }

    private boolean isCovLeaseExpired(CovSubscriptionBinding binding) {
        return binding.lease().lifetimeSeconds > 0 && binding.lease().expiresAt > 0L
                && System.currentTimeMillis() >= binding.lease().expiresAt;
    }

    private void cancelCovRenewal(CovSubscriptionBinding binding) {
        CovLease lease = binding.lease();
        lease.active = false;
        if (lease.renewalTask != null) lease.renewalTask.cancel(false);
        lease.renewalTask = null;
        lease.nextRenewAt = 0L;
        lease.inFlight = false;
        lease.pendingNotifications.clear();
    }

    private long sendCovSubscription(CovSubscriptionBinding binding) throws Exception {
        var subscription = binding.subscription();
        CovLease lease = binding.lease();
        int timeout = requestTimeoutMs;
        long[] sentAt = {0L};
        BacnetIpConnectionAdapter.SessionExchange exchange = () -> subscriptionService.subscribe(binding.point(),
                subscription.address(), subscription.propertyLevel(), subscription.issueConfirmedNotifications(),
                lease.lifetimeSeconds, lease.increment, subscription::processIdentifier, this::nextInvokeId,
                binding.remoteDeviceInstance(),
                request -> {
                    if (binding.callback().adapter() instanceof BacnetIpConnectionAdapter ipAdapter) {
                        ipAdapter.subscribeCov(request, timeout, time -> sentAt[0] = time);
                    } else {
                        sentAt[0] = System.currentTimeMillis();
                        binding.callback().adapter().subscribeCov(request, timeout);
                    }
                },
                request -> {
                    if (binding.callback().adapter() instanceof BacnetIpConnectionAdapter ipAdapter) {
                        ipAdapter.subscribeCovProperty(request, timeout, time -> sentAt[0] = time);
                    } else {
                        sentAt[0] = System.currentTimeMillis();
                        binding.callback().adapter().subscribeCovProperty(request, timeout);
                    }
                });
        if (binding.callback().adapter() instanceof BacnetIpConnectionAdapter ipAdapter) {
            ipAdapter.exchangeIfCurrentSession(binding.callback().session(), () -> isCurrentCovBinding(binding), exchange);
        } else {
            if (!isCurrentCovBinding(binding)) throw new IllegalStateException("COV 订阅已退役");
            exchange.run();
        }
        return sentAt[0];
    }

    /** 发送及半租期截止均为绝对时间，慢 ACK 不再额外延后续订。 */
    private void confirmCovLease(CovSubscriptionBinding binding, long sentAt) {
        CovLease lease = binding.lease();
        if (!lease.awaitingFreshSample && isCovLeaseExpired(binding)) {
            lease.awaitingFreshSample = true;
            lease.recoverySentAt = sentAt;
        }
        lease.confirmedAt = System.currentTimeMillis();
        lease.expiresAt = lease.lifetimeSeconds == 0 ? 0L : sentAt + lease.lifetimeSeconds * 1000L;
        lease.renewalAttempts = 0;
        lease.renewalFailureReason = null;
        if (!lease.awaitingFreshSample) {
            reportEventLease(binding.point(), binding.callback().generation(), lease.expiresAt);
        }
    }

    /** 初次及过期续订的初始通知先暂存，ACK 后按原收包时间在提交锁外重放。 */
    private void completeCovSubscription(CovSubscriptionBinding binding, boolean renewal) throws Exception {
        boolean[] started = {false};
        runIfCurrentCovCallback(binding.callback(), () -> {
            if (!isCurrentCovBinding(binding) || binding.lease().inFlight) return;
            binding.lease().inFlight = true;
            binding.lease().pendingNotifications.clear();
            started[0] = true;
        });
        if (!started[0]) throw new IllegalStateException("COV 订阅身份已失效或已有在途订阅");
        try {
            long sentAt = sendCovSubscription(binding);
            List<CovWork> pending = new ArrayList<>();
            boolean[] accepted = {false};
            runIfCurrentCovCallback(binding.callback(), () -> {
                if (!isCurrentCovBinding(binding)) return;
                confirmCovLease(binding, sentAt);
                pending.addAll(binding.lease().pendingNotifications);
                binding.lease().pendingNotifications.clear();
                binding.lease().inFlight = false;
                if (renewal) covRenewalCount.incrementAndGet();
                accepted[0] = true;
            });
            if (!accepted[0]) throw new IllegalStateException("BACnet 订阅完成时身份已失效");
            replayCovNotifications(binding, pending);
            runIfCurrentCovCallback(binding.callback(), () -> {
                if (binding.lease().lifetimeSeconds > 0
                        && binding.callback().adapter() instanceof BacnetIpConnectionAdapter) {
                    scheduleCovRenewal(binding, Math.max(0L,
                            sentAt + binding.lease().lifetimeSeconds * 500L - System.currentTimeMillis()));
                }
            });
        } catch (Exception failure) {
            List<CovWork> pending = new ArrayList<>();
            synchronized (covOwnershipLock) {
                // 续订失败不撤销尚有效的旧租约；只交接其预算内的真实通知。
                if (isCurrentCovBinding(binding) && binding.lease().confirmedAt > 0L
                        && !isCovLeaseExpired(binding)) {
                    pending.addAll(binding.lease().pendingNotifications);
                }
                binding.lease().inFlight = false;
                binding.lease().pendingNotifications.clear();
            }
            replayCovNotifications(binding, pending);
            throw failure;
        }
    }

    private void replayCovNotifications(CovSubscriptionBinding binding, List<CovWork> pending) {
        for (CovWork work : pending) {
            CovWork acknowledged = new CovWork(work.callback(), work.notification(), work.receivedAt(), binding);
            if (covExecutor == null) {
                handleCovNotification(acknowledged.callback(), acknowledged.notification(),
                        acknowledged.receivedAt(), acknowledged.acknowledgedBinding());
            } else if (!covQueue.offer(acknowledged)) {
                covDispatchRejectedCount.incrementAndGet();
            }
        }
        if (covExecutor != null && !pending.isEmpty()) drainCovQueue();
    }

    private boolean deferCovUntilAck(CovSubscriptionBinding binding, BacnetCovNotification notification,
                                     long receivedAt) {
        boolean[] deferred = {false};
        runIfCurrentCovCallback(binding.callback(), () -> {
            CovLease lease = binding.lease();
            if (!isCurrentCovBinding(binding) || !lease.inFlight) return;
            if (lease.pendingNotifications.size() < 128) {
                lease.pendingNotifications.addLast(new CovWork(binding.callback(), notification, receivedAt, null));
            } else {
                covDispatchRejectedCount.incrementAndGet();
            }
            deferred[0] = true;
        });
        return deferred[0];
    }

    private void scheduleCovRenewal(CovSubscriptionBinding binding, long delayMs) {
        CovLease lease = binding.lease();
        if (!isCurrentCovBinding(binding)) return;
        if (lease.renewalTask != null) lease.renewalTask.cancel(false);
        lease.nextRenewAt = System.currentTimeMillis() + delayMs;
        try {
            lease.renewalTask = covScheduler.schedule(() -> {
                if (!isCurrentCovBinding(binding)) return;
                try {
                    covExecutor.execute(() -> renewCovLease(binding));
                } catch (RejectedExecutionException rejected) {
                    covDispatchRejectedCount.incrementAndGet();
                    recordCovRenewalFailure(binding, rejected);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rejected) {
            lease.renewalFailureReason = "COV 续订调度被拒绝";
            lease.renewalTask = null;
            lease.nextRenewAt = 0L;
            covDispatchRejectedCount.incrementAndGet();
            log.warn("COV 续订调度被拒绝: 设备={}, 点位={}", binding.point().getDeviceId(),
                    binding.point().getPointId(), rejected);
        }
    }

    private void renewCovLease(CovSubscriptionBinding binding) {
        try {
            completeCovSubscription(binding, true);
        } catch (Exception failure) {
            recordCovRenewalFailure(binding, failure);
        }
    }

    private void recordCovRenewalFailure(CovSubscriptionBinding binding, Exception failure) {
        if (!runIfCurrentCovCallback(binding.callback(), () -> {
            if (!isCurrentCovBinding(binding)) return;
            CovLease lease = binding.lease();
            lease.renewalAttempts = Math.min(lease.renewalAttempts + 1, 30);
            lease.renewalFailureReason = failure.getClass().getSimpleName();
            covRenewalFailureCount.incrementAndGet();
            long cap = Math.max(1000L, Math.min(30_000L, lease.lifetimeSeconds * 500L));
            long backoff = Math.min(cap, 500L << Math.min(lease.renewalAttempts - 1, 6));
            scheduleCovRenewal(binding, backoff);
        })) {
            synchronized (covOwnershipLock) {
                cancelCovRenewal(binding);
            }
        }
    }

    private Map<String, Object> covLeaseSnapshots() {
        synchronized (covOwnershipLock) {
            Map<String, Object> snapshots = new LinkedHashMap<>();
            subscriptionBindings.forEach((pointId, binding) -> {
                CovLease lease = binding.lease();
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("subscriberProcessIdentifier", binding.subscription().processIdentifier());
                state.put("requestedLifetime", lease.lifetimeSeconds);
                state.put("confirmedAt", lease.confirmedAt);
                state.put("expiresAt", lease.expiresAt);
                state.put("nextRenewAt", lease.nextRenewAt);
                state.put("renewalAttempts", lease.renewalAttempts);
                state.put("renewalFailureReason", lease.renewalFailureReason);
                state.put("subscriptionStatus", !isCurrentCovBinding(binding) ? "RETIRED"
                        : isCovLeaseExpired(binding) || lease.awaitingFreshSample ? "STALE"
                        : lease.confirmedAt == 0L ? "SUBSCRIBING" : lease.lifetimeSeconds == 0 ? "PERMANENT" : "ACTIVE");
                snapshots.put(pointId, state);
            });
            return snapshots;
        }
    }

    /** UDP 接收线程只交接捕获身份；执行器拒绝计数，不使用 CallerRuns 补偿。 */
    private void dispatchCovNotification(CovCallbackBinding callback, BacnetCovNotification notification) {
        long receivedAt = System.currentTimeMillis();
        Object session = callback.adapter() instanceof BacnetIpConnectionAdapter ipAdapter
                ? ipAdapter.captureSessionIdentity() : null;
        if (session != callback.session()) return;
        if (covExecutor == null) {
            handleCovNotification(callback, notification, receivedAt);
            return;
        }
        if (!covQueue.offer(new CovWork(callback, notification, receivedAt, null))) {
            covDispatchRejectedCount.incrementAndGet();
            log.warn("COV 通知队列已满: 设备={}", deviceInfo.getDeviceId());
            return;
        }
        drainCovQueue();
    }

    /** 同设备按收包顺序处理，复用 AbortPolicy 执行器而不是 I/O 池的 CallerRunsPolicy。 */
    private void drainCovQueue() {
        if (!covDraining.compareAndSet(false, true)) return;
        try {
            covExecutor.execute(() -> {
                try {
                    CovWork work;
                    while ((work = covQueue.poll()) != null) {
                        handleCovNotification(work.callback(), work.notification(), work.receivedAt(),
                                work.acknowledgedBinding());
                    }
                } finally {
                    covDraining.set(false);
                    if (!covQueue.isEmpty()) drainCovQueue();
                }
            });
        } catch (RejectedExecutionException rejected) {
            covDraining.set(false);
            covQueue.clear();
            covDispatchRejectedCount.incrementAndGet();
            log.warn("COV 通知交接被拒绝: 设备={}", deviceInfo.getDeviceId(), rejected);
        }
    }

    /** 传输失败的资源清理核对身份而非健康状态，已关闭 Socket 仍需清理。 */
    private boolean isCurrentCovIdentity(CovCallbackBinding binding) {
        return binding != null && covCallbackBinding == binding && connectionAdapter == binding.adapter()
                && runtimeGeneration == binding.generation() && runtimeConfigVersion == binding.configVersion()
                && (covTaskGuard == null || binding.generation() == 0L
                    || covTaskGuard.isCurrent(deviceInfo.getDeviceId(), binding.generation()))
                && (configManager == null || (deviceInfo != null
                && configManager.getDeviceConfigVersion(deviceInfo.getDeviceId()) == binding.configVersion()));
    }

    /**
     * 校验业务条件和参数边界。
     */
    @Override
    protected void checkConnection() {
        if (connected && connectionAdapter != null && connectionAdapter.isConnected()) {
            return;
        }
        if (attemptConnectionRecovery()) {
            return;
        }
        throw new IllegalStateException("设备未连接:" + (deviceInfo != null ? deviceInfo.getDeviceId() : null));
    }

    /**
     * 处理当前业务流程。
     */
    private void handleCovNotification(CovCallbackBinding callback, BacnetCovNotification notification) {
        handleCovNotification(callback, notification, System.currentTimeMillis());
    }

    private void handleCovNotification(CovCallbackBinding callback, BacnetCovNotification notification, long receivedAt) {
        handleCovNotification(callback, notification, receivedAt, null);
    }

    private void handleCovNotification(CovCallbackBinding callback, BacnetCovNotification notification, long receivedAt,
                                       CovSubscriptionBinding acknowledgedBinding) {
        if (!isCurrentCovCallback(callback) || notification == null || notification.getPropertyValues() == null
                || notification.getPropertyValues().isEmpty()) return;
        covNotificationCount.incrementAndGet();
        if (notification.isConfirmed()) covConfirmedNotificationCount.incrementAndGet();
        List<CovSubscriptionBinding> bindings = new ArrayList<>(subscriptionBindings.values());
        for (BacnetCovNotification.PropertyValue propertyValue : notification.getPropertyValues()) {
            if (propertyValue == null) continue;
            for (CovSubscriptionBinding binding : bindings) {
                if (binding.callback() != callback
                        || binding.subscription().processIdentifier() != notification.getSubscriberProcessIdentifier()
                        || binding.remoteDeviceInstance() != notification.getInitiatingDeviceInstance()) continue;
                try {
                    if (!matchesNotification(notification, propertyValue, binding.subscription().address())) continue;
                    // 原绑定已接受 ACK 的通知，不因下一续订抢先启动而再次暂存。
                    if (acknowledgedBinding != binding && deferCovUntilAck(binding, notification, receivedAt)) continue;
                    BacnetValue rawValue = BacnetValue.builder()
                            .value(propertyValue.getValue()).valueType(propertyValue.getValueType())
                            .kind(valueMapper.inferKind(propertyValue.getValue()))
                            .metadata(propertyValue.getValueMetadata() != null
                                    ? propertyValue.getValueMetadata() : Collections.emptyMap()).build();
                    ingestPushedValue(binding, rawValue, notification, receivedAt);
                } catch (Exception ex) {
                    log.warn("{} COV 通知分发失败, 设备={}, 点位={}", protocolDisplayName(),
                            deviceInfo != null ? deviceInfo.getDeviceId() : null, binding.point().getPointId(), ex);
                }
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean matchesNotification(BacnetCovNotification notification,
                                        BacnetCovNotification.PropertyValue propertyValue,
                                        BacnetAddress address) {
        return notification.getMonitoredObjectType() != null
                && propertyValue.getPropertyIdentifier() != null
                && subscriptionService.matchesNotification(
                notification.getMonitoredObjectType().getId(),
                notification.getMonitoredObjectInstance(),
                propertyValue.getPropertyIdentifier().getId(),
                propertyValue.getArrayIndex(),
                address
        );
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean shouldUsePropertySubscription(DataPoint point, BacnetAddress address) {
        DeviceConnection connectionConfig = safeCurrentConnectionConfig();
        return subscriptionService.usePropertySubscription(
                point,
                address,
                connectionConfig != null ? connectionConfig.getBoolConfig("covPropertyEnabled", null) : null
        );
    }

    /**
     * 创建并返回业务对象。
     */
    private Map<String, Object> buildProtocolMetrics() {
        Map<String, Object> protocolMetrics = new LinkedHashMap<>();
        protocolMetrics.put(CommonMapKeys.CONFIGURED_POINT_COUNT, configuredAddresses.size());
        protocolMetrics.put("activeSubscriptionCount", subscriptionBindings.size());
        protocolMetrics.put("subscriptionRequestCount", subscriptionRequestCount.get());
        protocolMetrics.put("subscriptionCancelCount", subscriptionCancelCount.get());
        protocolMetrics.put("covNotificationCount", covNotificationCount.get());
        protocolMetrics.put("covConfirmedNotificationCount", covConfirmedNotificationCount.get());
        protocolMetrics.put("covResubscribeCount", covResubscribeCount.get());
        protocolMetrics.put("covResubscribeFailureCount", covResubscribeFailureCount.get());
        protocolMetrics.put("covPollingBypassCount", covPollingBypassCount.get());
        protocolMetrics.put("readPropertyMultipleFallbackCount", readPropertyMultipleFallbackCount.get());
        protocolMetrics.put("writePropertyMultipleCount", writePropertyMultipleCount.get());
        protocolMetrics.put("writePropertyMultipleFallbackCount", writePropertyMultipleFallbackCount.get());
        protocolMetrics.put("requestRetryCount", connectionAdapter != null ? connectionAdapter.getRequestRetryCount() : 0L);
        protocolMetrics.put("requestTimeoutCount", connectionAdapter != null ? connectionAdapter.getRequestTimeoutCount() : 0L);
        protocolMetrics.put("invokeIdMismatchCount", connectionAdapter != null ? connectionAdapter.getInvokeIdMismatchCount() : 0L);
        protocolMetrics.put("segmentedResponseCount", connectionAdapter != null ? connectionAdapter.getSegmentedResponseCount() : 0L);
        protocolMetrics.put("foreignDeviceRenewFailureCount", connectionAdapter != null ? connectionAdapter.getForeignDeviceRenewFailureCount() : 0L);
        protocolMetrics.put("bbmdActive", connectionAdapter != null && connectionAdapter.isForeignDeviceRegistrationActive());
        DeviceConnection connectionConfig = safeCurrentConnectionConfig();
        protocolMetrics.put("covEnabled", isCovEnabled(connectionConfig));
        protocolMetrics.put("resubscribeOnReconnect", shouldResubscribeOnReconnect(connectionConfig));
        return protocolMetrics;
    }

    /**
     * 解析或转换业务数据。
     */
    private boolean resolveConfirmedNotifications(DataPoint point) {
        Boolean pointValue = point.getAdditionalConfig("covConfirmedNotifications", null);
        if (pointValue != null) {
            return pointValue;
        }
        return Boolean.TRUE.equals(currentOrRequiredConnectionConfig().getBoolConfig("covConfirmedNotifications", false));
    }

    /**
     * 解析或转换业务数据。
     */
    private Integer resolveCovLifetimeSeconds(DataPoint point) {
        Integer pointValue = point.getAdditionalConfig("covLifetimeSeconds", null);
        if (pointValue != null && pointValue >= 0) {
            return pointValue;
        }
        Integer connectionValue = currentOrRequiredConnectionConfig().getIntConfig("defaultCovLifetimeSeconds", null);
        if (connectionValue != null && connectionValue >= 0) {
            return connectionValue;
        }
        return 60;
    }

    /**
     * 解析或转换业务数据。
     */
    private Double resolveCovIncrement(DataPoint point) {
        Object value = point.getAdditionalConfig("covIncrement", null);
        if (value == null) {
            DeviceConnection connection = currentOrRequiredConnectionConfig();
            value = firstPresent(connection.getExtJson(), "defaultCovIncrement");
            if (value == null) {
                value = connection.getProperty("defaultCovIncrement");
            }
        }
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean shouldAutoSubscribePoint(DataPoint point) {
        if (point == null || !point.isEnabled()) {
            return false;
        }
        if (!isCovEnabled()) {
            return false;
        }
        String collectionMode = point.getCollectionMode();
        if (collectionMode == null || collectionMode.isBlank()) {
            return false;
        }
        String normalized = collectionMode.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return "SUBSCRIPTION".equals(normalized) || "EVENT".equals(normalized);
    }

    /**
     * 执行当前业务逻辑。
     */
    public List<DataPoint> filterAutoSubscriptionPoints(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }
        return points.stream().filter(this::shouldAutoSubscribePoint).collect(Collectors.toList());
    }

    /**
     * 执行当前业务逻辑。
     */
    public List<DataPoint> filterPollingPoints(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }
        if (!isCovEnabled()) {
            return new ArrayList<>(points);
        }
        return points.stream()
                .filter(point -> point != null && !shouldAutoSubscribePoint(point))
                .collect(Collectors.toList());
    }

    private boolean isCovEnabled() {
        return isCovEnabled(safeCurrentConnectionConfig());
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean shouldResubscribeOnReconnect() {
        return shouldResubscribeOnReconnect(safeCurrentConnectionConfig());
    }

    /**
     * 执行当前业务逻辑。
     */
    private synchronized boolean attemptConnectionRecovery() {
        if (deviceInfo == null) {
            return false;
        }
        try {
            long generation = runtimeGeneration;
            long configVersion = runtimeConfigVersion;
            DeviceConnection desiredConfig = currentOrRequiredConnectionConfig();
            BacnetConnectionAdapter newAdapter = createBacnetConnectionAdapter(desiredConfig);
            CovCallbackBinding callback = bindCovConnection(newAdapter, generation, configVersion);
            requestTimeoutMs = resolveRequestTimeout(desiredConfig);
            connected = true;
            connectionStatus = "CONNECTED";
            lastConnectTime = System.currentTimeMillis();
            lastActivityTime = System.currentTimeMillis();
            reportMatchedDiscovery();
            handleAdapterReconnect(callback);
            return true;
        } catch (Exception ex) {
            lastError = ex.getMessage();
            recordException(ex, null);
            return false;
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void handleAdapterReconnect(CovCallbackBinding callback) {
        synchronized (covOwnershipLock) {
            if (!isCurrentCovIdentity(callback)) return;
            Object session = callback.adapter() instanceof BacnetIpConnectionAdapter ipAdapter
                    ? ipAdapter.captureSessionIdentity() : null;
            if (session != callback.session()) {
                clearCovOwnership();
                callback = ensureNotificationListenerRegistered();
            }
        }
        if (!isCurrentCovCallback(callback)
                || !shouldResubscribeOnReconnect() || subscribedPointMap.isEmpty() || !isCovEnabled()) {
            return;
        }
        List<DataPoint> points = filterAutoSubscriptionPoints(new ArrayList<>(subscribedPointMap.values()));
        if (points.isEmpty()) {
            return;
        }
        try {
            doSubscribe(points, callback);
            covResubscribeCount.incrementAndGet();
        } catch (Exception ex) {
            covResubscribeFailureCount.incrementAndGet();
            log.warn("{} 重新订阅 on 重连 失败, 设备={}, 点位数量={}",
                    protocolDisplayName(),
                    deviceInfo != null ? deviceInfo.getDeviceId() : null,
                    points.size(),
                    ex);
        }
    }
    /**
     * 解析或转换业务数据。
     */
    private String resolveWriteValueType(DataPoint point, BacnetAddress address, Map<String, Object> params) {
        if (params != null && params.containsKey("valueType") && params.get("valueType") != null) {
            return String.valueOf(params.get("valueType"));
        }
        Object pointSpecific = point != null ? point.getAdditionalConfig("bacnetWriteType", null) : null;
        if (pointSpecific == null && point != null) {
            pointSpecific = point.getAdditionalConfig("driverDataType", null);
        }
        if (pointSpecific != null) {
            return String.valueOf(pointSpecific);
        }
        if (address != null && address.getDriverDataType() != null && !"AUTO".equalsIgnoreCase(address.getDriverDataType())) {
            return address.getDriverDataType();
        }
        if (point != null && point.getDataType() != null && !point.getDataType().isBlank()) {
            return point.getDataType();
        }
        return "AUTO";
    }

    /**
     * 解析或转换业务数据。
     */
    private Integer resolveWritePriority(DataPoint point, Map<String, Object> params) {
        Object candidate = null;
        if (params != null) {
            candidate = firstPresent(params, "priority", "writePriority", "bacnetPriority");
        }
        if (candidate == null && point != null) {
            candidate = firstPresent(point.getAdditionalConfig(), "priority", "writePriority", "bacnetPriority");
        }
        if (candidate == null) {
            return null;
        }
        if (candidate instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(candidate));
    }

    /**
     * 处理当前业务流程。
     */
    private Map<String, Object> executeReadPropertyCommand(Map<String, Object> params) throws Exception {
        BacnetAddress address = parseCommandAddress(params);
        BacnetReadPropertyResponse response = exchange(readPropertyRequest(address));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(CommonMapKeys.ADDRESS, address.getCanonicalAddress());
        result.put(CommonMapKeys.VALUE, response.getValue());
        result.put("valueType", response.getValueType());
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Map<String, Object> executeReadPropertyMultipleCommand(Map<String, Object> params) throws Exception {
        List<String> addresses = parseCommandAddresses(params);
        Map<String, Object> values = new LinkedHashMap<>();
        for (String addressText : addresses) {
            BacnetAddress address = BacnetAddressParser.parse(addressText);
            BacnetReadPropertyResponse response = exchange(readPropertyRequest(address));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(CommonMapKeys.VALUE, response.getValue());
            item.put("valueType", response.getValueType());
            values.put(address.getCanonicalAddress(), item);
        }
        return values;
    }

    /**
     * 处理当前业务流程。
     */
    private Map<String, Object> executeWritePropertyCommand(Map<String, Object> params) throws Exception {
        BacnetAddress address = parseCommandAddress(params);
        if (!params.containsKey(CommonMapKeys.VALUE)) {
            throw new IllegalArgumentException("BACnet write_property command requires value");
        }
        BacnetWritePropertyRequest request = BacnetWritePropertyRequest.builder()
                .objectType(BacnetObjectType.fromId(address.getObjectTypeId()))
                .objectInstance(address.getInstanceNumber())
                .propertyIdentifier(BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()))
                .arrayIndex(address.getArrayIndex())
                .value(params.get(CommonMapKeys.VALUE))
                .valueType(resolveWriteValueType(null, address, params))
                .priority(resolveWritePriority(null, params))
                .invokeId(nextInvokeId())
                .remoteDeviceInstance(requireRemoteDeviceInstance())
                .build();
        exchange(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(CommonMapKeys.ADDRESS, address.getCanonicalAddress());
        result.put(CommonMapKeys.SUCCESS, true);
        result.put("valueType", request.getValueType());
        result.put("priority", request.getPriority());
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Map<String, Object> executeDeviceInfoCommand() {
        return captureDeviceSnapshot().getDeviceInfo();
    }

    /**
     * 处理当前业务流程。
     */
    private Map<String, Object> executeWhoIsCommand() {
        Map<String, Object> result = new LinkedHashMap<>();
        BacnetRemoteDevice remoteDevice = connectionAdapter != null ? connectionAdapter.getRemoteDevice() : null;
        result.put("remoteDeviceInstance", remoteDevice != null ? remoteDevice.getDeviceInstance() : requireRemoteDeviceInstance());
        result.put("remoteAddress", remoteDevice != null && remoteDevice.getSocketAddress() != null ? remoteDevice.getSocketAddress().toString() : null);
        result.put("vendorId", remoteDevice != null ? remoteDevice.getVendorId() : null);
        result.put("maxApduLengthAccepted", remoteDevice != null ? remoteDevice.getMaxApduLengthAccepted() : null);
        result.put("segmentationSupported", remoteDevice != null ? remoteDevice.getSegmentationSupported() : null);
        result.put("discoveredByWhoIs", remoteDevice != null && remoteDevice.isDiscoveredByWhoIs());
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private List<String> executeDiscoverObjectsCommand() {
        return captureDeviceSnapshot().getObjectList();
    }

    /**
     * 执行当前业务逻辑。
     */
    private Object safeReadDeviceProperty(BacnetPropertyIdentifier propertyIdentifier, Integer arrayIndex) {
        try {
            BacnetReadPropertyRequest request = BacnetReadPropertyRequest.builder()
                    .objectType(BacnetObjectType.DEVICE)
                    .objectInstance(requireRemoteDeviceInstance())
                    .propertyIdentifier(propertyIdentifier)
                    .arrayIndex(arrayIndex)
                    .invokeId(nextInvokeId())
                    .remoteDeviceInstance(requireRemoteDeviceInstance())
                    .build();
            return exchange(request).getValue();
        } catch (Exception ex) {
            log.debug("读取 BACnet 设备 property 失败, 设备={}, property={}, arrayIndex={}, 错误={}",
                    deviceInfo != null ? deviceInfo.getDeviceId() : null,
                    propertyIdentifier.getName(),
                    arrayIndex,
                    ex.getMessage());
            return null;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    public Object safeReadDevicePropertyForSnapshot(BacnetPropertyIdentifier propertyIdentifier, Integer arrayIndex) {
        return safeReadDeviceProperty(propertyIdentifier, arrayIndex);
    }

    /**
     * 校验业务条件和参数边界。
     */
    public int requireRemoteDeviceInstanceForSnapshot() {
        return requireRemoteDeviceInstance();
    }

    /**
     * 执行当前业务逻辑。
     */
    private BacnetDeviceSnapshot captureDeviceSnapshot() {
        return deviceSnapshotService.capture(this);
    }

    /**
     * 查询并返回业务数据。
     */
    private BacnetReadPropertyRequest readPropertyRequest(BacnetAddress address) {
        return BacnetReadPropertyRequest.builder()
                .objectType(BacnetObjectType.fromId(address.getObjectTypeId()))
                .objectInstance(address.getInstanceNumber())
                .propertyIdentifier(BacnetPropertyIdentifier.fromId(address.getPropertyIdentifierId()))
                .arrayIndex(address.getArrayIndex())
                .invokeId(nextInvokeId())
                .remoteDeviceInstance(requireRemoteDeviceInstance())
                .build();
    }

    /**
     * 解析或转换业务数据。
     */
    private BacnetAddress parseCommandAddress(Map<String, Object> params) {
        Object address = firstPresent(params, "address", "bacnetAddress");
        if (address == null) {
            throw new IllegalArgumentException("BACnet command requires address");
        }
        return BacnetAddressParser.parse(String.valueOf(address));
    }

    /**
     * 解析或转换业务数据。
     */
    private List<String> parseCommandAddresses(Map<String, Object> params) {
        Object addresses = firstPresent(params, "addresses", "bacnetAddresses");
        if (addresses instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).collect(Collectors.toList());
        }
        if (addresses instanceof String text && !text.isBlank()) {
            String[] items = text.split(",");
            List<String> result = new ArrayList<>(items.length);
            for (String item : items) {
                if (item != null && !item.isBlank()) {
                    result.add(item.trim());
                }
            }
            return result;
        }
        if (params.containsKey(CommonMapKeys.ADDRESS)) {
            return List.of(String.valueOf(params.get(CommonMapKeys.ADDRESS)));
        }
        throw new IllegalArgumentException("BACnet read_property_multiple command requires addresses");
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeCommand(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        return command.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    /**
     * 执行当前业务逻辑。
     */
    private DeviceConnection currentOrRequiredConnectionConfig() {
        DeviceConnection runtimeConfig = getCurrentConnectionConfig();
        return runtimeConfig != null ? runtimeConfig : requireConnectionConfig();
    }

    /**
     * 执行当前业务逻辑。
     */
    private DeviceConnection safeCurrentConnectionConfig() {
        DeviceConnection runtimeConfig = getCurrentConnectionConfig();
        if (runtimeConfig != null) {
            return runtimeConfig;
        }
        try {
            return requireConnectionConfig();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isCovEnabled(DeviceConnection connectionConfig) {
        return connectionConfig != null && Boolean.TRUE.equals(connectionConfig.getBoolConfig("covEnabled", false));
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean shouldResubscribeOnReconnect(DeviceConnection connectionConfig) {
        return connectionConfig == null || !Boolean.FALSE.equals(connectionConfig.getBoolConfig("resubscribeOnReconnect", true));
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveRequestTimeout(DeviceConnection connection) {
        Integer apduTimeout = connection.getIntConfig("apduTimeout", null);
        if (apduTimeout == null || apduTimeout <= 0) {
            apduTimeout = connection.getIntConfig("apduTimeoutMs", null);
        }
        if (apduTimeout != null && apduTimeout > 0) {
            return apduTimeout;
        }
        Integer timeout = connection.getReadTimeout() != null ? connection.getReadTimeout() : connection.getTimeout();
        return timeout != null && timeout > 0 ? timeout : 5000;
    }

    private boolean isStringType(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("STRING") || normalized.equals("CHAR") || normalized.equals("WCHAR");
    }

    private boolean isBooleanType(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("BOOL") || normalized.equals("BOOLEAN") || normalized.equals("BINARY");
    }

    /**
     * 解析或转换业务数据。
     */
    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "on".equals(text) || "active".equals(text);
    }

    /**
     * 执行当前业务逻辑。
     */
    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }


    /**
     * 执行当前业务逻辑。
     */
    protected String protocolCode() {
        return "BACNET_IP";
    }

    /**
     * 执行当前业务逻辑。
     */
    protected String protocolDisplayName() {
        return "BACnet/IP";
    }

    /**
     * 执行当前业务逻辑。
     */
    protected String defaultTransportName() {
        return "UDP";
    }

    /**
     * 执行当前业务逻辑。
     */
    protected String driverName() {
        return "SELF_IMPLEMENTED_MINIMAL_PLUS";
    }

    /**
     * 执行当前业务逻辑。
     */
    protected boolean supportsForeignDeviceRegistration() {
        return true;
    }

    /**
     * 解析或转换业务数据。
     */
    protected String resolveTransportName() {
        return connectionAdapter != null ? connectionAdapter.getTransportName() : defaultTransportName();
    }

    /**
     * 执行当前业务逻辑。
     */
    protected String capabilityMessage() {
        StringBuilder message = new StringBuilder(protocolDisplayName())
                .append(" supports polling, segmented APDU assembly, WriteProperty and COV subscriptions over ")
                .append(resolveTransportName());
        if (supportsForeignDeviceRegistration()) {
            message.append(" with optional BBMD/Foreign Device registration");
        }
        return message.toString();
    }

    /** 仅匹配目标设备实例的 I-Am 可证明 BACnet/IP 协议已响应。 */
    private void reportMatchedDiscovery() {
        if (!(connectionAdapter instanceof BacnetIpConnectionAdapter)) {
            return;
        }
        BacnetRemoteDevice remote = connectionAdapter.getRemoteDevice();
        DeviceConnection config = getCurrentConnectionConfig();
        Integer expectedInstance = config != null ? config.getIntConfig("remoteDeviceInstance", null) : null;
        if (remote != null && remote.isDiscoveredByWhoIs() && expectedInstance != null
                && remote.getDeviceInstance() == expectedInstance) {
            reportProtocolReady();
        }
    }

    /** 创建并连接 BACnet 适配器。 */
    protected BacnetConnectionAdapter createBacnetConnectionAdapter(DeviceConnection desiredConfig) throws Exception {
        ConnectionAdapter<?> adapter = createManagedConnection(desiredConfig);
        if (!(adapter instanceof BacnetConnectionAdapter bacnetAdapter)) {
            removeManagedConnection(protocolDisplayName());
            throw new IllegalStateException(protocolDisplayName() + " connection adapter type mismatch");
        }
        try {
            connectManagedConnection();
            return bacnetAdapter;
        } catch (Exception ex) {
            removeManagedConnection(protocolDisplayName());
            throw ex;
        }
    }
    /**
     * 执行当前业务逻辑。
     */
    protected UnsupportedOperationException unsupported(String operation, String reason) {
        String message = protocolDisplayName() + " collector does not implement " + operation;
        if (reason != null && !reason.isBlank()) {
            message = message + ": " + reason;
        }
        log.warn(message);
        return new UnsupportedOperationException(message);
    }

}

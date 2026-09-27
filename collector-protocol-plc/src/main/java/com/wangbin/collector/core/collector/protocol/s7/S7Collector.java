package com.wangbin.collector.core.collector.protocol.s7;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.enums.DataQuality;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7Address;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7PlcType;
import com.wangbin.collector.core.collector.protocol.s7.plan.S7ReadPlan;
import com.wangbin.collector.core.collector.protocol.s7.plan.S7ReadPlanBuilder;
import com.wangbin.collector.core.collector.protocol.s7.plan.S7ReadPlanItem;
import com.wangbin.collector.core.collector.protocol.s7.util.S7AddressParser;
import com.wangbin.collector.core.collector.protocol.s7.util.S7PlcTypeResolver;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import com.wangbin.collector.core.connection.adapter.S7ConnectionAdapter;
import com.wangbin.collector.core.processor.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.exceptions.PlcIoException;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcSubscriptionEvent;
import org.apache.plc4x.java.api.messages.PlcSubscriptionResponse;
import org.apache.plc4x.java.api.messages.PlcTagResponse;
import org.apache.plc4x.java.api.messages.PlcUnsubscriptionRequest;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.model.PlcSubscriptionHandle;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.s7.events.S7Event;
import org.apache.plc4x.java.s7.events.S7EventBase;
import org.apache.plc4x.java.s7.readwrite.ControllerType;
import org.apache.plc4x.java.s7.readwrite.DataItem;
import org.apache.plc4x.java.s7.readwrite.MemoryArea;
import org.apache.plc4x.java.s7.readwrite.TransportSize;
import org.apache.plc4x.java.s7.readwrite.tag.S7Tag;
import org.apache.plc4x.java.spi.generation.ReadBufferByteBased;
import org.apache.plc4x.java.spi.values.DefaultPlcValueHandler;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Array;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实现当前协议或设备的采集能力。
 */
@Slf4j
public class S7Collector extends ConnectionBackedCollector {

    private static final long DEFAULT_SUBSCRIPTION_INTERVAL_MS = 2000L;
    private static final String READ_PLAN_BLOCK_FIELD_NAME = "__s7_block__";
    private static final List<String> SUPPORTED_COMMANDS = List.of("read", "write", "status", "diagnostic", "connection_info");
    private static final List<String> SUPPORTED_SUBSCRIPTION_MODES = List.of("CYCLIC", "MODE", "SYS", "USR", "ALM");
    private DevicePointResolver devicePointResolver;

    /**
     * 注入点位解析辅助组件。
     */
    @Autowired(required = false)
    public void setDevicePointResolver(DevicePointResolver devicePointResolver) {
        this.devicePointResolver = devicePointResolver;
    }

    private final S7ReadPlanBuilder readPlanBuilder = new S7ReadPlanBuilder();
    private final Map<String, S7Address> configuredAddresses = new ConcurrentHashMap<>();
    private final Map<String, String> configuredSubscriptionModes = new ConcurrentHashMap<>();
    private final Map<String, String> configuredSubscriptionAddresses = new ConcurrentHashMap<>();
    private final Map<String, PlcSubscriptionHandle> subscriptionHandles = new ConcurrentHashMap<>();
    private final Map<String, Object> subscriptionTokens = new ConcurrentHashMap<>();
    private final Object subscriptionLifecycleLock = new Object();
    private final Map<String, AtomicLong> responseCodeStats = new ConcurrentHashMap<>();
    private final AtomicLong batchRequestCount = new AtomicLong();
    private final AtomicLong blockFallbackCount = new AtomicLong();
    private final AtomicLong batchTimeoutCount = new AtomicLong();
    private final AtomicLong batchResponseTimeMs = new AtomicLong();
    private final AtomicLong subscriptionEventCount = new AtomicLong();
    private final AtomicLong subscriptionRegisterFailureCount = new AtomicLong();
    private final AtomicLong subscriptionEventErrorCount = new AtomicLong();

    private volatile S7ConnectionAdapter connectionAdapter;
    private volatile List<S7ReadPlan> configuredReadPlans = Collections.emptyList();
    private int timeout = 5000;
    private int maxFieldsPerRequest = 64;
    private boolean subscriptionSupported;
    private volatile Long lastSubscriptionEventTs;
    private volatile String lastSubscriptionPointId;
    private volatile String lastSubscriptionPointCode;
    private volatile String lastSubscriptionError;
    private volatile String lastFailedPointId;
    private volatile String lastFailedAddress;
    private volatile String lastFailedOperation;
    private volatile String lastFailedResponseCode;
    private volatile Long lastFailureTs;

    @Override
    public String getCollectorType() {
        return "SIEMENS_S7";
    }

    @Override
    public String getProtocolType() {
        return "SIEMENS_S7";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        DeviceConnection desiredConfig = requireConnectionConfig();
        int configuredMaxFields = desiredConfig.getInt("maxFieldsPerRequest", 64);
        if (configuredMaxFields <= 0) {
            throw new IllegalArgumentException("S7 maxFieldsPerRequest must be positive");
        }
        synchronized (subscriptionLifecycleLock) {
            this.connectionAdapter = createAndConnectAdapter(desiredConfig, S7ConnectionAdapter.class, "S7");
        }

        DeviceConnection currentConfig = getCurrentConnectionConfig();
        if (currentConfig == null) {
            currentConfig = desiredConfig;
        }

        Integer configuredTimeout = currentConfig.getReadTimeout() != null
                ? currentConfig.getReadTimeout()
                : currentConfig.getTimeout();
        this.timeout = configuredTimeout != null && configuredTimeout > 0 ? configuredTimeout : 5000;
        this.maxFieldsPerRequest = currentConfig.getInt("maxFieldsPerRequest", 64);
        if (this.maxFieldsPerRequest <= 0) {
            removeManagedConnection("S7");
            connectionAdapter = null;
            throw new IllegalArgumentException("S7 maxFieldsPerRequest must be positive");
        }
        this.subscriptionSupported = currentConfig.getBool("subscriptionEnabled",
                requireConnection().getClient().getMetadata().isSubscribeSupported());
        resetProtocolMetrics();
        log.info("PLC4X S7 采集器 已连接, 设备={}, 超时={}, 单次最大字段数={}",
                deviceInfo.getDeviceId(), timeout, maxFieldsPerRequest);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        synchronized (subscriptionLifecycleLock) {
            subscriptionTokens.clear();
            subscriptionHandles.clear();
            connectionAdapter = null;
            removeManagedConnection("S7");
        }
        configuredAddresses.clear();
        configuredSubscriptionModes.clear();
        configuredSubscriptionAddresses.clear();
        configuredReadPlans = Collections.emptyList();
        subscriptionHandles.clear();
        subscriptionSupported = false;
        resetProtocolMetrics();
        log.info("PLC4X S7 采集器 已断开, 设备={}", deviceInfo.getDeviceId());
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public Object readPoint(DataPoint point) throws CollectorException {
        checkConnection();
        if (isSubscriptionPoint(point)) {
            lastActivityTime = System.currentTimeMillis();
            ProcessResult processResult = point != null ? getLatestProcessResult(point.getPointId()) : null;
            return processResult != null ? processResult.getFinalValue() : null;
        }
        if (!isArrayPoint(point)) {
            return super.readPoint(point);
        }

        long startTime = System.currentTimeMillis();
        try {
            S7Address address = requireAddress(point);
            validateArrayPointConfiguration(point, address, "read");

            Object rawValue = doReadPoint(point);
            ProcessResult processResult = buildArrayProcessResult(point, address, rawValue, "array pass-through read");
            lastProcessResults.put(point.getPointId(), processResult);

            totalReadCount.incrementAndGet();
            totalReadTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return processResult.getFinalValue();
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, point);
            log.error("PLC4X S7 array 点位 读取 失败, 设备={}, 点位={}",
                    deviceInfo.getDeviceId(), point != null ? point.getPointId() : null, e);
            throw new CollectorException("Array point read failed", deviceInfo.getDeviceId(),
                    point != null ? point.getPointId() : null, e);
        }
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public Map<String, Object> readPoints(List<DataPoint> points) throws CollectorException {
        checkConnection();
        if (!containsSubscriptionPoint(points) && !containsArrayPoint(points)) {
            return super.readPoints(points);
        }

        Map<String, Object> results = new LinkedHashMap<>();
        List<DataPoint> scalarPollPoints = new ArrayList<>();
        List<DataPoint> arrayPollPoints = new ArrayList<>();
        List<DataPoint> subscriptionPoints = new ArrayList<>();
        partitionReadPoints(points, scalarPollPoints, arrayPollPoints, subscriptionPoints);

        if (!scalarPollPoints.isEmpty()) {
            results.putAll(super.readPoints(scalarPollPoints));
        }
        if (!arrayPollPoints.isEmpty()) {
            results.putAll(readArrayPoints(arrayPollPoints));
        }
        for (DataPoint point : subscriptionPoints) {
            if (point == null || point.getPointId() == null) {
                continue;
            }
            ProcessResult processResult = getLatestProcessResult(point.getPointId());
            results.put(point.getPointId(), processResult != null ? processResult.getFinalValue() : null);
        }
        lastActivityTime = System.currentTimeMillis();
        return results;
    }


    /**
     * 写入或持久化业务数据。
     */
    @Override
    public boolean writePoint(DataPoint point, Object value) throws CollectorException {
        if (!isArrayPoint(point)) {
            return super.writePoint(point, value);
        }
        checkConnection();

        long startTime = System.currentTimeMillis();
        try {
            if (!"W".equals(point.getReadWrite()) && !"RW".equals(point.getReadWrite())) {
                throw new CollectorException("Point is not writable", deviceInfo.getDeviceId(), point.getPointId());
            }

            S7Address address = requireAddress(point);
            validateArrayPointConfiguration(point, address, "write");
            boolean result = doWritePoint(point, value);

            totalWriteCount.incrementAndGet();
            totalWriteTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return result;
        } catch (CollectorException e) {
            throw e;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, point);
            log.error("PLC4X S7 array 点位 写入 失败, 设备={}, 点位={}",
                    deviceInfo.getDeviceId(), point != null ? point.getPointId() : null, e);
            throw new CollectorException("Array point write failed", deviceInfo.getDeviceId(),
                    point != null ? point.getPointId() : null, e);
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    @Override
    public Map<String, Boolean> writePoints(Map<DataPoint, Object> points) throws CollectorException {
        if (!containsArrayPoint(points != null ? points.keySet() : null)) {
            return super.writePoints(points);
        }
        checkConnection();

        Map<String, Boolean> results = new LinkedHashMap<>();
        Map<DataPoint, Object> scalarPoints = new LinkedHashMap<>();
        Map<DataPoint, Object> arrayPoints = new LinkedHashMap<>();
        partitionPointValues(points, scalarPoints, arrayPoints);

        if (!scalarPoints.isEmpty()) {
            results.putAll(super.writePoints(scalarPoints));
        }
        if (arrayPoints.isEmpty()) {
            return results;
        }

        long arrayStartTime = System.currentTimeMillis();
        try {
            for (Map.Entry<DataPoint, Object> entry : arrayPoints.entrySet()) {
                DataPoint point = entry.getKey();
                if (point == null) {
                    continue;
                }
                if (!"W".equals(point.getReadWrite()) && !"RW".equals(point.getReadWrite())) {
                    results.put(point.getPointId(), false);
                    continue;
                }
                try {
                    S7Address address = requireAddress(point);
                    validateArrayPointConfiguration(point, address, "write");
                    results.put(point.getPointId(), doWritePoint(point, entry.getValue()));
                } catch (Exception e) {
                    log.error("PLC4X S7 array 点位 批量 写入 item 失败, 点位={}", point.getPointId(), e);
                    recordException(e, point);
                    results.put(point.getPointId(), false);
                }
            }

            totalWriteCount.addAndGet(arrayPoints.size());
            totalWriteTime.addAndGet(System.currentTimeMillis() - arrayStartTime);
            lastActivityTime = System.currentTimeMillis();
            return results;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, null);
            log.error("PLC4X S7 批量 array 写入 失败, 设备={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("Batch array point write failed", deviceInfo.getDeviceId(), null, e);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        S7Address address = requireAddress(point);
        String fieldName = resolvePointTagName(point);

        S7ConnectionAdapter session = requireConnection();
        try {
            PlcReadResponse response = await(session.getClient()
                    .readRequestBuilder()
                    .addTagAddress(fieldName, address.getPlc4xAddress())
                    .build()
                    .execute(), session);
            recordResponseCode("read", point, address.getRawAddress(), response != null ? response.getResponseCode(fieldName) : null);
            ensureResponseOk(response, fieldName, "read");
            return extractValue(response, fieldName, point, address);
        } catch (Exception ex) {
            if (isTransportFailure(ex)) {
                invalidateTransport(session);
            }
            throw ex;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
        Map<String, Object> results = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }

        Map<String, DataQuality> failureQualities = new HashMap<>();
        for (S7ReadPlan readPlan : readPlanBuilder.buildIsolatingInvalidPoints(points, maxFieldsPerRequest, point -> {
            if (point.getPointId() != null) {
                results.put(point.getPointId(), null);
                failureQualities.put(point.getPointId(), DataQuality.CONFIG_ERROR);
            }
            recordFailureSnapshot("read", point, point.getAddress(), "INVALID_ADDRESS");
            log.warn("PLC4X S7 点位规划失败, 设备={}, 点位={}", deviceInfo.getDeviceId(), point.getPointId());
        })) {
            executeReadPlan(readPlan, results, failureQualities);
        }
        for (DataPoint point : points) {
            if (point != null && point.getPointId() != null && results.containsKey(point.getPointId())
                    && results.get(point.getPointId()) == null) {
                publishReadFailure(point, failureQualities.getOrDefault(point.getPointId(), DataQuality.BAD));
            }
        }
        return results;
    }
    /**
     * 执行当前业务逻辑。
     */
    private void publishReadFailure(DataPoint point, DataQuality quality) {
        ProcessResult failure = ProcessResult.error(null, "S7 read failed", quality);
        enrichTelemetryMetadata(failure, null, null, System.currentTimeMillis(), "POLLING");
        lastProcessResults.put(point.getPointId(), failure);
        if (telemetryIngressService != null) {
            telemetryIngressService.append(deviceInfo.getDeviceId(), point, failure);
        }
    }

    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        S7Address address = requireAddress(point);
        String fieldName = resolvePointTagName(point);

        S7ConnectionAdapter session = requireConnection();
        try {
            PlcWriteResponse response = await(session.getClient()
                    .writeRequestBuilder()
                    .addTagAddress(fieldName, address.getPlc4xAddress(), coerceWriteValue(value, address, point))
                    .build()
                    .execute(), session);
            recordResponseCode("write", point, address.getRawAddress(), response != null ? response.getResponseCode(fieldName) : null);
            ensureResponseOk(response, fieldName, "write");
            return true;
        } catch (Exception ex) {
            if (isTransportFailure(ex)) {
                invalidateTransport(session);
            }
            throw ex;
        }
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

        S7ConnectionAdapter session = requireConnection();
        try {
            PlcWriteRequest.Builder builder = session.getClient().writeRequestBuilder();
            List<DataPoint> orderedPoints = new ArrayList<>();

            for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
                DataPoint point = entry.getKey();
                if (point == null) {
                    continue;
                }
                S7Address address = requireAddress(point);
                builder.addTagAddress(resolvePointTagName(point), address.getPlc4xAddress(), coerceWriteValue(entry.getValue(), address, point));
                orderedPoints.add(point);
            }

            PlcWriteResponse response = await(builder.build().execute(), session);
            for (DataPoint point : orderedPoints) {
                String fieldName = resolvePointTagName(point);
                PlcResponseCode responseCode = response != null ? response.getResponseCode(fieldName) : null;
                recordResponseCode("write", point, point.getAddress(), responseCode);
                results.put(point.getPointId(), responseCode == PlcResponseCode.OK);
            }
            return results;
        } catch (Exception ex) {
            if (isTransportFailure(ex)) {
                invalidateTransport(session);
            }
            // 写入 Future 失败时部分字段可能已提交，禁止逐点重放不确定的写入。
            log.warn("PLC4X S7 批量写入未确认, 设备={}, 字段数={}, 错误={}",
                    deviceInfo.getDeviceId(), points.size(), ex.getClass().getSimpleName());
            for (DataPoint point : points.keySet()) {
                if (point != null) {
                    recordFailureSnapshot("write", point, point.getAddress(), "UNCONFIRMED");
                    results.put(point.getPointId(), false);
                }
            }
            return results;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doSubscribe(List<DataPoint> points) throws Exception {
        if (points == null || points.isEmpty()) {
            return;
        }
        ensureSubscriptionSupported();
        unsubscribeExisting(points);

        S7ConnectionAdapter subscriptionSession = requireConnection();
        var builder = subscriptionSession.getClient().subscriptionRequestBuilder();
        List<SubscriptionRegistration> orderedPoints = new ArrayList<>();
        try {
            for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            String subscriptionMode = requireSupportedSubscriptionMode(point);
            String fieldName = resolvePointTagName(point);
            String cacheKey = resolvePointCacheKey(point);

            if (isEventSubscriptionMode(subscriptionMode)) {
                String subscriptionAddress = resolveEventSubscriptionAddress(point, subscriptionMode);
                Object token = new Object();
                builder.addEventTagAddress(fieldName, subscriptionAddress,
                        event -> handleSubscriptionEvent(point, fieldName, null, subscriptionMode,
                                subscriptionAddress, subscriptionSession, token, event));
                synchronized (subscriptionLifecycleLock) {
                    subscriptionTokens.put(cacheKey, token);
                }
                orderedPoints.add(new SubscriptionRegistration(point, fieldName, subscriptionMode, subscriptionAddress, token));
                continue;
            }

            S7Address address = requireAddress(point);
            validateArrayPointConfiguration(point, address, "subscribe");
            Object token = new Object();
            builder.addCyclicTagAddress(
                    fieldName,
                    address.getPlc4xAddress(),
                    resolveSubscriptionInterval(point),
                    event -> handleSubscriptionEvent(point, fieldName, address, subscriptionMode,
                            address.getRawAddress(), subscriptionSession, token, event));
            synchronized (subscriptionLifecycleLock) {
                subscriptionTokens.put(cacheKey, token);
            }
            orderedPoints.add(new SubscriptionRegistration(point, fieldName, subscriptionMode, address.getRawAddress(), token));
            }
        } catch (Exception ex) {
            for (SubscriptionRegistration registration : orderedPoints) {
                clearRegistrationToken(registration);
            }
            throw ex;
        }
        if (orderedPoints.isEmpty()) {
            throw new IllegalArgumentException("PLC4X S7 subscribe requires at least one valid point");
        }

        PlcSubscriptionResponse response;
        try {
            response = await(builder.build().execute(), subscriptionSession);
        } catch (Exception ex) {
            for (SubscriptionRegistration registration : orderedPoints) {
                clearRegistrationToken(registration);
            }
            throw ex;
        }
        Map<String, PlcSubscriptionHandle> attemptHandles = new LinkedHashMap<>();
        try {
            IllegalStateException failure = response == null
                    ? new IllegalStateException("PLC4X S7 subscribe returned no response") : null;
            for (SubscriptionRegistration registration : orderedPoints) {
                String cacheKey = resolvePointCacheKey(registration.point());
                PlcResponseCode responseCode = response == null ? null : response.getResponseCode(registration.fieldName());
                recordResponseCode("subscribe", registration.point(), registration.displayAddress(), responseCode);
                PlcSubscriptionHandle handle = response == null ? null : response.getSubscriptionHandle(registration.fieldName());
                if (handle != null) {
                    attemptHandles.put(cacheKey, handle);
                }
                if (responseCode != PlcResponseCode.OK || handle == null) {
                    if (responseCode == PlcResponseCode.OK) {
                        recordFailureSnapshot("subscribe", registration.point(), registration.displayAddress(), "NULL_HANDLE");
                    }
                    if (failure == null) {
                        failure = new IllegalStateException("PLC4X S7 subscribe failed for point "
                                + registration.point().getPointId() + ": "
                                + (responseCode != PlcResponseCode.OK ? responseCode : "NULL_HANDLE"));
                    }
                }
            }
            synchronized (subscriptionLifecycleLock) {
                if (failure == null) {
                    for (SubscriptionRegistration registration : orderedPoints) {
                        String cacheKey = resolvePointCacheKey(registration.point());
                        if (connectionAdapter != subscriptionSession || !subscriptionSession.isConnected()
                                || subscriptionTokens.get(cacheKey) != registration.token()) {
                            failure = new IllegalStateException("PLC4X S7 subscription session changed during registration");
                            break;
                        }
                    }
                }
                if (failure == null) {
                    subscriptionHandles.putAll(attemptHandles);
                    for (SubscriptionRegistration registration : orderedPoints) {
                        String cacheKey = resolvePointCacheKey(registration.point());
                        configuredSubscriptionModes.put(cacheKey, registration.subscriptionMode());
                        configuredSubscriptionAddresses.put(cacheKey, registration.displayAddress());
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        } catch (Exception failure) {
            subscriptionRegisterFailureCount.incrementAndGet();
            lastSubscriptionError = failure.getMessage();
            for (SubscriptionRegistration registration : orderedPoints) {
                clearRegistrationToken(registration);
            }
            if (!attemptHandles.isEmpty()) {
                try {
                    unsubscribeSessionHandles(subscriptionSession, attemptHandles.values());
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    log.warn("PLC4X S7 本次订阅句柄回滚失败, 设备={}, 数量={}",
                            deviceInfo.getDeviceId(), attemptHandles.size(), cleanupFailure);
                }
            }
            throw failure;
        }
        log.info("PLC4X S7 订阅已注册, 设备={}, 数量={}",
                deviceInfo.getDeviceId(), attemptHandles.size());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) throws Exception {
        if (points == null || points.isEmpty()) {
            List<PlcSubscriptionHandle> handlesToRemove;
            synchronized (subscriptionLifecycleLock) {
                subscriptionTokens.clear();
                configuredSubscriptionModes.clear();
                configuredSubscriptionAddresses.clear();
                handlesToRemove = new ArrayList<>(subscriptionHandles.values());
                subscriptionHandles.clear();
            }
            unsubscribeHandles(handlesToRemove);
            return;
        }
        List<PlcSubscriptionHandle> handlesToRemove = new ArrayList<>();
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            synchronized (subscriptionLifecycleLock) {
                String cacheKey = resolvePointCacheKey(point);
                subscriptionTokens.remove(cacheKey);
                configuredSubscriptionModes.remove(cacheKey);
                configuredSubscriptionAddresses.remove(cacheKey);
                PlcSubscriptionHandle handle = subscriptionHandles.remove(cacheKey);
                if (handle != null) {
                    handlesToRemove.add(handle);
                }
            }
        }
        unsubscribeHandles(handlesToRemove);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        DeviceConnection connection = getCurrentConnectionConfig();
        Map<String, Object> pointSummary = buildPointSummary();
        List<Map<String, Object>> deploymentChecks = buildDeploymentChecks(connection, pointSummary);

        int configuredSubscriptionPointCount = configuredSubscriptionModes.size();
        int configuredEventPointCount = countConfiguredEventPoints();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put(CommonMapKeys.PROTOCOL, getProtocolType());
        status.put(CommonMapKeys.DRIVER, "PLC4X");
        status.put("implemented", true);
        status.put(CommonMapKeys.WRITABLE, true);
        status.put("browseable", false);
        status.put(CommonMapKeys.SUBSCRIBABLE, isRuntimeSubscriptionSupported());
        status.put("subscriptionMode", configuredSubscriptionPointCount == 0
                ? "DISABLED"
                : configuredEventPointCount > 0 ? "MIXED" : "CYCLIC");
        status.put("supportedSubscriptionModes", isRuntimeSubscriptionSupported()
                ? SUPPORTED_SUBSCRIPTION_MODES
                : Collections.emptyList());
        status.put("addressingMode", "ABSOLUTE_ONLY");
        status.put("symbolicAddressingSupported", false);
        status.put(CommonMapKeys.IS_CONNECTED, isConnected());
        status.put(CommonMapKeys.CONFIGURED_POINT_COUNT, intValue(pointSummary.get("totalPoints")));
        status.put("configuredAddressCacheSize", configuredAddresses.size());
        status.put("configuredSubscriptionPoints", configuredSubscriptionPointCount);
        status.put("configuredEventPoints", configuredEventPointCount);
        status.put("invalidConfiguredPoints", intValue(pointSummary.get("invalidPointCount")));
        status.put("arrayConfiguredPoints", intValue(pointSummary.get("arrayPointCount")));
        status.put("maxFieldsPerRequest", maxFieldsPerRequest);
        status.put(CommonMapKeys.PLANNED_READ_BATCH_COUNT, configuredReadPlans.size());
        status.put("batchRequestCount", batchRequestCount.get());
        status.put("batchResponseTimeMs", batchResponseTimeMs.get());
        status.put("batchTimeoutCount", batchTimeoutCount.get());
        status.put("blockFallbackCount", blockFallbackCount.get());
        status.put("activeSubscriptions", subscriptionHandles.size());
        status.put("subscriptionEventCount", subscriptionEventCount.get());
        status.put("subscriptionRegisterFailureCount", subscriptionRegisterFailureCount.get());
        status.put("subscriptionEventErrorCount", subscriptionEventErrorCount.get());
        status.put("lastSubscriptionEventTs", lastSubscriptionEventTs);
        status.put("lastSubscriptionPointId", lastSubscriptionPointId);
        status.put("lastSubscriptionPointCode", lastSubscriptionPointCode);
        status.put("lastSubscriptionError", lastSubscriptionError);
        status.put("lastFailedPointId", lastFailedPointId);
        status.put("lastFailedAddress", lastFailedAddress);
        status.put("lastFailedOperation", lastFailedOperation);
        status.put("lastFailedResponseCode", lastFailedResponseCode);
        status.put("lastFailureTs", lastFailureTs);
        status.put("responseCodeStats", snapshotResponseCodeStats());
        status.put("deploymentReady", countChecksByStatus(deploymentChecks, "FAIL") == 0);
        status.put("deploymentWarningCount", countChecksByStatus(deploymentChecks, "WARN"));
        status.put("supportedCommands", SUPPORTED_COMMANDS);

        status.putAll(buildConnectionInfo(connection));
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
            case "read", "read_point", "readpoint" -> executeCommandRead(safeParams);
            case "write", "write_point", "writepoint" -> executeCommandWrite(safeParams);
            case "status" -> getDeviceStatus();
            case "diagnostic" -> buildDiagnosticPayload();
            case "connection_info", "connectioninfo" -> buildConnectionInfo(getCurrentConnectionConfig());
            default -> throw new IllegalArgumentException("Unsupported PLC4X S7 command: " + command);
        };
    }

    @Override
    public void rebuildReadPlans(String deviceId, List<DataPoint> points) {
        try {
            buildReadPlans(deviceId, points);
        } catch (Exception ex) {
            throw new IllegalStateException("S7 读取计划或订阅准备失败，设备=" + deviceId, ex);
        }
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) throws Exception {
        List<DataPoint> pollPoints = new ArrayList<>();
        List<DataPoint> subscriptionPoints = new ArrayList<>();
        partitionPoints(points, pollPoints, subscriptionPoints);
        List<DataPoint> validPollingPoints = cachePointDefinitions(points);
        configuredReadPlans = readPlanBuilder.buildIsolatingInvalidPoints(validPollingPoints, maxFieldsPerRequest,
                (point, reason) -> {
                    configuredAddresses.remove(resolvePointCacheKey(point));
                    recordInvalidPollingPoint(point, reason);
                });
        if (!pollPoints.isEmpty() && configuredReadPlans.isEmpty()) {
            throw new IllegalStateException("S7 polling 点全部无效，无法构建读取计划");
        }
        reconcileAutoSubscriptions(subscriptionPoints);
    }

    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>(super.getStatistics());
        Map<String, Object> protocolMetrics = new LinkedHashMap<>();
        protocolMetrics.put(CommonMapKeys.PLANNED_READ_BATCH_COUNT, configuredReadPlans.size());
        protocolMetrics.put("batchRequestCount", batchRequestCount.get());
        protocolMetrics.put("batchResponseTimeMs", batchResponseTimeMs.get());
        protocolMetrics.put("batchTimeoutCount", batchTimeoutCount.get());
        protocolMetrics.put("blockFallbackCount", blockFallbackCount.get());
        protocolMetrics.put("configuredSubscriptionPointCount", configuredSubscriptionModes.size());
        protocolMetrics.put("configuredEventPointCount", countConfiguredEventPoints());
        protocolMetrics.put("activeSubscriptionCount", subscriptionHandles.size());
        protocolMetrics.put("subscriptionEventCount", subscriptionEventCount.get());
        protocolMetrics.put("subscriptionRegisterFailureCount", subscriptionRegisterFailureCount.get());
        protocolMetrics.put("subscriptionEventErrorCount", subscriptionEventErrorCount.get());
        protocolMetrics.put("responseCodeStats", snapshotResponseCodeStats());
        protocolMetrics.put("lastFailedPointId", lastFailedPointId);
        protocolMetrics.put("lastFailedAddress", lastFailedAddress);
        protocolMetrics.put("lastFailedOperation", lastFailedOperation);
        protocolMetrics.put("lastFailedResponseCode", lastFailedResponseCode);
        protocolMetrics.put("lastFailureTs", lastFailureTs);

        stats.put(CommonMapKeys.PLANNED_READ_BATCH_COUNT, configuredReadPlans.size());
        stats.put("subscriptionEventCount", subscriptionEventCount.get());
        stats.put("subscriptionRegisterFailureCount", subscriptionRegisterFailureCount.get());
        stats.put("subscriptionEventErrorCount", subscriptionEventErrorCount.get());
        stats.put("responseCodeStats", snapshotResponseCodeStats());
        stats.put("protocolMetrics", protocolMetrics);
        return stats;
    }
    /**
     * 执行当前业务逻辑。
     */
    private List<DataPoint> cachePointDefinitions(List<DataPoint> points) {
        configuredAddresses.clear();
        configuredSubscriptionModes.clear();
        configuredSubscriptionAddresses.clear();
        List<DataPoint> validPollingPoints = new ArrayList<>();
        if (points == null) {
            return validPollingPoints;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            try {
                rememberPointDefinition(point);
                if (!isSubscriptionPoint(point)) {
                    validPollingPoints.add(point);
                }
            } catch (IllegalArgumentException ex) {
                if (isSubscriptionPoint(point)) {
                    throw ex;
                }
                recordInvalidPollingPoint(point, ex);
            }
        }
        return validPollingPoints;
    }

    private void recordInvalidPollingPoint(DataPoint point, IllegalArgumentException reason) {
        recordFailureSnapshot("read-plan", point, point.getAddress(), "INVALID_CONFIGURATION");
        log.warn("PLC4X S7 polling 点已隔离, 设备={}, 点位={}, 地址={}, 原因={}",
                deviceInfo != null ? deviceInfo.getDeviceId() : null,
                point.getPointId(), point.getAddress(), reason.getMessage());
    }

    /**
     * 执行当前业务逻辑。
     */
    private void rememberPointDefinition(DataPoint point) {
        String cacheKey = resolvePointCacheKey(point);
        if (isSubscriptionPoint(point)) {
            String subscriptionMode = requireSupportedSubscriptionMode(point);
            if (isEventSubscriptionMode(subscriptionMode)) {
                resolveEventSubscriptionAddress(point, subscriptionMode);
            }
        }
        try {
            configuredAddresses.put(cacheKey, S7AddressParser.parse(point));
        } catch (IllegalArgumentException ex) {
            if (!isEventSubscriptionPoint(point)) {
                throw ex;
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void reconcileAutoSubscriptions(List<DataPoint> desiredPoints) throws Exception {
        List<DataPoint> safeDesiredPoints = desiredPoints != null ? desiredPoints : Collections.emptyList();
        Map<String, DataPoint> desiredByKey = new LinkedHashMap<>();
        for (DataPoint point : safeDesiredPoints) {
            if (point != null) {
                desiredByKey.put(resolvePointCacheKey(point), point);
            }
        }

        List<DataPoint> obsoletePoints = new ArrayList<>();
        for (DataPoint subscribedPoint : new ArrayList<>(subscribedPointMap.values())) {
            if (subscribedPoint == null) {
                continue;
            }
            String cacheKey = resolvePointCacheKey(subscribedPoint);
            if (!desiredByKey.containsKey(cacheKey)) {
                obsoletePoints.add(subscribedPoint);
            }
        }
        if (!obsoletePoints.isEmpty()) {
            unsubscribe(obsoletePoints);
        }
        if (!safeDesiredPoints.isEmpty()) {
            subscribe(safeDesiredPoints);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean containsSubscriptionPoint(Collection<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            return false;
        }
        for (DataPoint point : points) {
            if (isSubscriptionPoint(point)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行当前业务逻辑。
     */
    private void partitionPoints(Collection<DataPoint> points,
                                 List<DataPoint> pollPoints,
                                 List<DataPoint> subscriptionPoints) {
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            if (isSubscriptionPoint(point)) {
                subscriptionPoints.add(point);
            } else {
                pollPoints.add(point);
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void partitionReadPoints(Collection<DataPoint> points,
                                     List<DataPoint> scalarPollPoints,
                                     List<DataPoint> arrayPollPoints,
                                     List<DataPoint> subscriptionPoints) {
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            if (point == null || !point.isEnabled()) {
                continue;
            }
            if (isSubscriptionPoint(point)) {
                subscriptionPoints.add(point);
            } else if (isArrayPoint(point)) {
                arrayPollPoints.add(point);
            } else {
                scalarPollPoints.add(point);
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void partitionPointValues(Map<DataPoint, Object> points,
                                      Map<DataPoint, Object> scalarPoints,
                                      Map<DataPoint, Object> arrayPoints) {
        if (points == null) {
            return;
        }
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            DataPoint point = entry.getKey();
            if (point == null) {
                continue;
            }
            if (isArrayPoint(point)) {
                arrayPoints.put(point, entry.getValue());
            } else {
                scalarPoints.put(point, entry.getValue());
            }
        }
    }

    /**
     * 查询并返回业务数据。
     */
    private Map<String, Object> readArrayPoints(List<DataPoint> points) throws CollectorException {
        Map<String, Object> results = new LinkedHashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }

        long startTime = System.currentTimeMillis();
        try {
            for (DataPoint point : points) {
                validateArrayPointConfiguration(point, requireAddress(point), "read");
            }

            Map<String, Object> rawValues = doReadPoints(points);
            for (DataPoint point : points) {
                String pointId = point.getPointId();
                Object rawValue = rawValues.get(pointId);
                if (rawValue == null) {
                    results.put(pointId, null);
                    continue;
                }
                try {
                    S7Address address = requireAddress(point);
                    ProcessResult processResult = buildArrayProcessResult(point, address, rawValue,
                            "array pass-through batch read");
                    lastProcessResults.put(pointId, processResult);
                    results.put(pointId, processResult.getFinalValue());
                } catch (Exception e) {
                    log.error("PLC4X S7 array 点位 批量 读取 item 失败, 点位={}", pointId, e);
                    recordException(e, point);
                    results.put(pointId, null);
                }
            }

            totalReadCount.addAndGet(points.size());
            totalReadTime.addAndGet(System.currentTimeMillis() - startTime);
            lastActivityTime = System.currentTimeMillis();
            return results;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, null);
            log.error("PLC4X S7 批量 array 读取 失败, 设备={}", deviceInfo.getDeviceId(), e);
            throw new CollectorException("Batch array point read failed", deviceInfo.getDeviceId(), null, e);
        }
    }


    /**
     * 执行当前业务逻辑。
     */

    /**
     * 处理当前业务流程。
     */
    private void executeReadPlan(S7ReadPlan readPlan,
                                 Map<String, Object> results,
                                 Map<String, DataQuality> failureQualities) {
        List<DataPoint> points = readPlan != null ? readPlan.getPoints() : Collections.emptyList();
        if (points.isEmpty()) {
            return;
        }
        if (!isConnected()) {
            for (DataPoint point : points) {
                if (point != null && point.getPointId() != null) {
                    results.put(point.getPointId(), null);
                    failureQualities.put(point.getPointId(), DataQuality.DEVICE_ERROR);
                }
            }
            return;
        }
        DataQuality planFailureQuality = null;
        try {
            if (canUseBlockRead(readPlan)) {
                try {
                    byte[] blockBytes = executeBlockReadBytes(readPlan);
                    if (blockBytes != null) {
                        populateBlockReadResults(readPlan, results, blockBytes);
                        return;
                    }
                    blockFallbackCount.incrementAndGet();
                    log.warn("PLC4X S7 块读取返回空载荷，降级为标签批量读取, 设备={}, 分段键={}, 块地址={}",
                            deviceInfo.getDeviceId(), readPlan.getSegmentKey(), readPlan.getBlockReadAddress());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw ex;
                } catch (Exception ex) {
                    if (isTransportFailure(ex) || isTimeout(ex)) {
                        throw ex;
                    }
                    blockFallbackCount.incrementAndGet();
                    log.warn("PLC4X S7 块读取失败，降级为标签批量读取, 设备={}, 分段键={}, 块地址={}, 错误={}",
                            deviceInfo.getDeviceId(), readPlan.getSegmentKey(), readPlan.getBlockReadAddress(), ex.getMessage());
                }
            }
            populateTagBatchReadResults(readPlan, results, failureQualities, executeTagBatchReadPlanRequest(readPlan));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            planFailureQuality = DataQuality.DEVICE_ERROR;
            recordPlanReadFailure(readPlan, points, ex);
        } catch (Exception ex) {
            planFailureQuality = isTimeout(ex) ? DataQuality.TIMEOUT : DataQuality.DEVICE_ERROR;
            boolean transportFailure = isTransportFailure(ex);
            if (!transportFailure && !isTimeout(ex) && isConnected()) {
                blockFallbackCount.incrementAndGet();
                for (DataPoint point : points) {
                    if (!isConnected()) {
                        break;
                    }
                    if (point != null && point.getPointId() != null && !results.containsKey(point.getPointId())) {
                        try {
                            results.put(point.getPointId(), doReadPoint(point));
                        } catch (Exception pointFailure) {
                            recordFailureSnapshot("read", point, point.getAddress(), "EXCEPTION");
                            results.put(point.getPointId(), null);
                            failureQualities.put(point.getPointId(), isTimeout(pointFailure)
                                    ? DataQuality.TIMEOUT : DataQuality.DEVICE_ERROR);
                            if (isTransportFailure(pointFailure) || isTimeout(pointFailure)) {
                                break;
                            }
                        }
                    }
                }
            }
            recordPlanReadFailure(readPlan, points, ex);
        }
        for (DataPoint point : points) {
            if (point != null && point.getPointId() != null) {
                results.putIfAbsent(point.getPointId(), null);
                if (results.get(point.getPointId()) == null && planFailureQuality != null) {
                    failureQualities.putIfAbsent(point.getPointId(), planFailureQuality);
                }
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean canUseBlockRead(S7ReadPlan readPlan) {
        return readPlan != null && readPlan.canUseBlockRead();
    }

    /**
     * 执行当前业务逻辑。
     */
    private void populateTagBatchReadResults(S7ReadPlan readPlan,
                                             Map<String, Object> results,
                                             Map<String, DataQuality> failureQualities,
                                             PlcReadResponse response) {
        for (DataPoint point : readPlan.getPoints()) {
            if (point == null || point.getPointId() == null) {
                continue;
            }
            String fieldName = resolvePointTagName(point);
            PlcResponseCode responseCode = response != null ? response.getResponseCode(fieldName) : null;
            recordResponseCode("read", point, point.getAddress(), responseCode);
            if (responseCode != PlcResponseCode.OK) {
                results.put(point.getPointId(), null);
                failureQualities.put(point.getPointId(), responseCode == PlcResponseCode.INVALID_ADDRESS
                        ? DataQuality.CONFIG_ERROR : DataQuality.DEVICE_ERROR);
                continue;
            }
            try {
                results.put(point.getPointId(), extractValue(response, fieldName, point, requireAddress(point)));
            } catch (Exception ex) {
                recordFailureSnapshot("read", point, point.getAddress(), "DECODE_ERROR");
                results.put(point.getPointId(), null);
                failureQualities.put(point.getPointId(), DataQuality.VALUE_INVALID);
                log.warn("PLC4X S7 batch point decode failed, device={}, point={}",
                        deviceInfo.getDeviceId(), point.getPointId(), ex);
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void populateBlockReadResults(S7ReadPlan readPlan,
                                          Map<String, Object> results,
                                          byte[] blockBytes) throws Exception {
        if (blockBytes.length < readPlan.getEstimatedByteSpan()) {
            throw new IllegalStateException("PLC4X S7 block read payload shorter than planned span: "
                    + blockBytes.length + " < " + readPlan.getEstimatedByteSpan());
        }
        for (S7ReadPlanItem item : readPlan.getItems()) {
            DataPoint point = item != null ? item.getPoint() : null;
            if (point == null || point.getPointId() == null) {
                continue;
            }
            Object value = decodeBlockReadValue(readPlan, blockBytes, item);
            recordResponseCode("read", point, point.getAddress(), PlcResponseCode.OK);
            results.put(point.getPointId(), value);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private Object decodeBlockReadValue(S7ReadPlan readPlan,
                                        byte[] blockBytes,
                                        S7ReadPlanItem item) throws Exception {
        int relativeStart = item.getByteOffset() - readPlan.getStartOffset();
        int relativeEnd = relativeStart + item.getByteLength();
        if (relativeStart < 0 || relativeEnd > blockBytes.length) {
            throw new IllegalStateException("PLC4X S7 block slice out of range for point "
                    + (item.getPoint() != null ? item.getPoint().getPointId() : "UNKNOWN"));
        }
        byte[] slice = Arrays.copyOfRange(blockBytes, relativeStart, relativeEnd);
        PlcValue plcValue = parseBlockReadPlcValue(item, slice);
        return extractValue(plcValue, item.getPoint(), item.getAddress());
    }

    /**
     * 解析或转换业务数据。
     */
    private PlcValue parseBlockReadPlcValue(S7ReadPlanItem item, byte[] rawBytes) throws Exception {
        S7Address address = item.getAddress();
        S7Tag tag = new S7Tag(
                resolveBlockReadTransportSize(address),
                resolveBlockReadMemoryArea(address),
                resolveBlockReadDbNumber(address),
                item.getByteOffset(),
                (byte) item.getBitOffset(),
                Math.max(1, address.getArraySize())
        );
        ReadBufferByteBased readBuffer = new ReadBufferByteBased(rawBytes);
        Integer stringLength = resolveBlockReadStringLength(address);
        ControllerType controllerType = resolveDriverControllerType();
        if (tag.getNumberOfElements() == 1) {
            return DataItem.staticParse(readBuffer, tag.getDataType().getDataProtocolId(), controllerType, stringLength);
        }
        PlcValue[] values = new PlcValue[tag.getNumberOfElements()];
        for (int i = 0; i < tag.getNumberOfElements(); i++) {
            values[i] = DataItem.staticParse(readBuffer, tag.getDataType().getDataProtocolId(), controllerType, stringLength);
        }
        return DefaultPlcValueHandler.of(tag, values);
    }

    /**
     * 解析或转换业务数据。
     */
    private TransportSize resolveBlockReadTransportSize(S7Address address) {
        return TransportSize.valueOf(address.getBasePlcType());
    }

    /**
     * 解析或转换业务数据。
     */
    private MemoryArea resolveBlockReadMemoryArea(S7Address address) {
        String area = address != null ? address.getArea() : null;
        return switch (area != null ? area.trim().toUpperCase(Locale.ROOT) : "") {
            case "DB" -> MemoryArea.DATA_BLOCKS;
            case "INPUT" -> MemoryArea.INPUTS;
            case "OUTPUT" -> MemoryArea.OUTPUTS;
            case "MERKER" -> MemoryArea.FLAGS_MARKERS;
            default -> throw new IllegalArgumentException("Unsupported S7 block read memory area: " + area);
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveBlockReadDbNumber(S7Address address) {
        if (address == null || address.getArea() == null || !"DB".equalsIgnoreCase(address.getArea())) {
            return 0;
        }
        String plc4xAddress = address.getPlc4xAddress();
        String normalizedAddress = plc4xAddress.startsWith("%") ? plc4xAddress.substring(1) : plc4xAddress;
        int colonIndex = normalizedAddress.indexOf(':');
        if (colonIndex <= 2) {
            throw new IllegalArgumentException("Invalid S7 DB address: " + plc4xAddress);
        }
        return Integer.parseInt(normalizedAddress.substring(2, colonIndex));
    }

    /**
     * 解析或转换业务数据。
     */
    private Integer resolveBlockReadStringLength(S7Address address) {
        String plcType = address != null ? address.getPlcType() : null;
        if (plcType == null) {
            return 254;
        }
        String normalized = plcType.trim().toUpperCase(Locale.ROOT);
        if (!normalized.startsWith("STRING(") && !normalized.startsWith("WSTRING(")) {
            return 254;
        }
        int start = normalized.indexOf('(');
        int end = normalized.indexOf(')');
        if (start < 0 || end <= start + 1) {
            return 254;
        }
        try {
            return Math.max(1, Integer.parseInt(normalized.substring(start + 1, end)));
        } catch (NumberFormatException ignored) {
            return 254;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private ControllerType resolveDriverControllerType() {
        try {
            return ControllerType.valueOf(resolveControllerType(getCurrentConnectionConfig()));
        } catch (Exception ignored) {
            return ControllerType.ANY;
        }
    }

    /**
     * 记录或统计业务状态。
     */
    private void recordPlanReadFailure(S7ReadPlan readPlan, List<DataPoint> points, Exception ex) {
        String segmentKey = readPlan != null ? readPlan.getSegmentKey() : "UNKNOWN";
        recordFailureSnapshot("read", points.isEmpty() ? null : points.get(0), segmentKey, "EXCEPTION");
        log.error("PLC4X S7 planned 批量 读取 失败, 设备={}, 分段键={}, 批量数量={}",
                deviceInfo.getDeviceId(), segmentKey, points.size(), ex);
    }

    /**
     * 处理当前业务流程。
     */
    protected byte[] executeBlockReadBytes(S7ReadPlan readPlan) throws Exception {
        if (readPlan == null || !hasText(readPlan.getBlockReadAddress())) {
            throw new IllegalArgumentException("PLC4X S7 block read address is not available");
        }
        S7ConnectionAdapter session = requireConnection();
        long startedAt = System.nanoTime();
        batchRequestCount.incrementAndGet();
        try {
            PlcReadResponse response = await(session.getClient()
                    .readRequestBuilder()
                    .addTagAddress(READ_PLAN_BLOCK_FIELD_NAME, readPlan.getBlockReadAddress())
                    .build()
                    .execute(), session);
            ensureResponseOk(response, READ_PLAN_BLOCK_FIELD_NAME, "read");
            PlcValue plcValue = response.getPlcValue(READ_PLAN_BLOCK_FIELD_NAME);
            return plcValue == null || plcValue.isNull() ? null : plcValue.getRaw();
        } catch (TimeoutException ex) {
            batchTimeoutCount.incrementAndGet();
            throw ex;
        } catch (Exception ex) {
            if (isTransportFailure(ex)) {
                invalidateTransport(session);
            }
            throw ex;
        } finally {
            batchResponseTimeMs.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        }
    }

    /**
     * 处理当前业务流程。
     */
    protected PlcReadResponse executeTagBatchReadPlanRequest(S7ReadPlan readPlan) throws Exception {
        S7ConnectionAdapter session = requireConnection();
        long startedAt = System.nanoTime();
        batchRequestCount.incrementAndGet();
        try {
            var builder = session.getClient().readRequestBuilder();
            for (S7ReadPlanItem item : readPlan.getItems()) {
                DataPoint point = item != null ? item.getPoint() : null;
                if (point != null) {
                    builder.addTagAddress(resolvePointTagName(point), item.getAddress().getPlc4xAddress());
                }
            }
            return await(builder.build().execute(), session);
        } catch (TimeoutException ex) {
            batchTimeoutCount.incrementAndGet();
            throw ex;
        } catch (Exception ex) {
            if (isTransportFailure(ex)) {
                invalidateTransport(session);
            }
            throw ex;
        } finally {
            batchResponseTimeMs.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private S7Address requireAddress(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("Point cannot be null");
        }
        return configuredAddresses.computeIfAbsent(resolvePointCacheKey(point), ignored -> S7AddressParser.parse(point));
    }

    /**
     * 执行当前业务逻辑。
     */
    private UnsupportedOperationException unsupported(String operation) {
        return unsupported(operation, null);
    }

    /**
     * 执行当前业务逻辑。
     */
    private UnsupportedOperationException unsupported(String operation, String reason) {
        String message = String.format("PLC4X S7 collector does not implement %s", operation);
        if (reason != null && !reason.isBlank()) {
            message = message + ": " + reason;
        }
        log.warn(message);
        return new UnsupportedOperationException(message);
    }

    /**
     * 处理当前业务流程。
     */
    private void handleSubscriptionEvent(DataPoint point,
                                         String fieldName,
                                         S7Address address,
                                         String subscriptionMode,
                                         String subscriptionAddress,
                                         S7ConnectionAdapter subscriptionSession,
                                         Object token,
                                         PlcSubscriptionEvent event) {
        synchronized (subscriptionLifecycleLock) {
            String cacheKey = resolvePointCacheKey(point);
            if (connectionAdapter != subscriptionSession || !subscriptionSession.isConnected()
                    || subscriptionTokens.get(cacheKey) != token || !subscriptionHandles.containsKey(cacheKey)) {
                return;
            }
            handleCurrentSubscriptionEvent(point, fieldName, address, subscriptionMode, subscriptionAddress, event);
        }
    }

    private void handleCurrentSubscriptionEvent(DataPoint point,
                                                String fieldName,
                                                S7Address address,
                                                String subscriptionMode,
                                                String subscriptionAddress,
                                                PlcSubscriptionEvent event) {
        try {
            PlcResponseCode responseCode = event != null ? event.getResponseCode(fieldName) : null;
            recordResponseCode("subscription-event", point, subscriptionAddress, responseCode);
            if (responseCode != PlcResponseCode.OK) {
                subscriptionEventErrorCount.incrementAndGet();
                lastSubscriptionError = "event responseCode=" + responseCode;
                log.warn("PLC4X S7 订阅事件 失败, 设备={}, 点位={}, subscriptionMode={}, 响应码={}",
                        deviceInfo.getDeviceId(), point.getPointId(), subscriptionMode, responseCode);
                return;
            }

            if (isEventSubscriptionMode(subscriptionMode)) {
                Map<String, Object> payload = extractEventPayload(event, fieldName, subscriptionMode, subscriptionAddress, responseCode);
                ingestEventPayload(point, payload, subscriptionMode, subscriptionAddress, responseCode);
            } else {
                Object rawValue = extractValue(event, fieldName, point, address);
                if (address != null && !address.isScalar()) {
                    ingestArrayPushedValue(point, address, rawValue, "array pass-through subscription");
                } else {
                    ingestPushedValue(point, rawValue);
                }
            }

            subscriptionEventCount.incrementAndGet();
            lastSubscriptionEventTs = System.currentTimeMillis();
            lastSubscriptionPointId = point.getPointId();
            lastSubscriptionPointCode = point.getPointCode();
            lastSubscriptionError = null;
        } catch (Exception ex) {
            subscriptionEventErrorCount.incrementAndGet();
            lastSubscriptionError = ex.getMessage();
            recordFailureSnapshot("subscription-event", point, subscriptionAddress, "EXCEPTION");
            log.warn("PLC4X S7 订阅事件处理 失败, 设备={}, 点位={}",
                    deviceInfo.getDeviceId(), point.getPointId(), ex);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private Map<String, Object> extractEventPayload(PlcSubscriptionEvent event,
                                                    String fieldName,
                                                    String subscriptionMode,
                                                    String subscriptionAddress,
                                                    PlcResponseCode responseCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subscriptionMode", subscriptionMode);
        payload.put("subscriptionAddress", subscriptionAddress);
        if (responseCode != null) {
            payload.put("responseCode", responseCode.name());
        }

        PlcValue plcValue = event != null ? event.getPlcValue(fieldName) : null;
        if (plcValue == null || plcValue.isNull()) {
            payload.put(CommonMapKeys.VALUE, null);
            return payload;
        }

        Object driverObject = plcValue.getObject();
        if (driverObject instanceof S7EventBase eventBase) {
            payload.put("eventTs", eventBase.getTimestamp());
        }
        if (driverObject instanceof S7Event s7Event) {
            payload.putAll(new LinkedHashMap<>(s7Event.getMap()));
        } else if (driverObject instanceof Map<?, ?> map) {
            payload.putAll(copyMap(map));
        } else {
            payload.put(CommonMapKeys.VALUE, driverObject);
        }
        return payload;
    }

    /**
     * 执行当前业务逻辑。
     */
    private ProcessResult ingestEventPayload(DataPoint point,
                                             Map<String, Object> payload,
                                             String subscriptionMode,
                                             String subscriptionAddress,
                                             PlcResponseCode responseCode) {
        if (point == null) {
            return null;
        }

        String resolvedDeviceId = point.getDeviceId();
        if ((resolvedDeviceId == null || resolvedDeviceId.isBlank()) && deviceInfo != null) {
            resolvedDeviceId = deviceInfo.getDeviceId();
        }

        try {
            ProcessResult processResult = ProcessResult.success(payload, payload,
                    "S7 event " + subscriptionMode + " received");
            processResult.addMetadata("eventTriggered", true);
            processResult.addMetadata(CommonMapKeys.EVENT_TYPE, "S7_" + subscriptionMode);
            processResult.addMetadata("eventLevel", "INFO");
            processResult.addMetadata("eventMessage", "S7 event " + subscriptionMode + " received");
            processResult.addMetadata("subscriptionMode", subscriptionMode);
            processResult.addMetadata("subscriptionAddress", subscriptionAddress);
            processResult.addMetadata("eventPayload", payload);
            if (responseCode != null) {
                processResult.addMetadata("responseCode", responseCode.name());
            }
            if (payload != null && payload.get("eventTs") != null) {
                processResult.addMetadata("eventTimestamp", payload.get("eventTs"));
            }
            if (point.getPointCode() != null) {
                processResult.addMetadata(CommonMapKeys.POINT_CODE, point.getPointCode());
            }

            lastProcessResults.put(point.getPointId(), processResult);
            lastActivityTime = System.currentTimeMillis();
            if (telemetryIngressService != null) {
                telemetryIngressService.append(resolvedDeviceId, point, processResult);
            }
            return processResult;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, point);
            ProcessResult error = ProcessResult.error(payload,
                    "S7 event process failed: " + e.getMessage());
            error.addMetadata("eventTriggered", true);
            error.addMetadata(CommonMapKeys.EVENT_TYPE, "S7_" + subscriptionMode);
            error.addMetadata("subscriptionMode", subscriptionMode);
            error.addMetadata("subscriptionAddress", subscriptionAddress);
            lastProcessResults.put(point.getPointId(), error);
            if (telemetryIngressService != null) {
                telemetryIngressService.append(resolvedDeviceId, point, error);
            }
            return error;
        }
    }

    /**
     * 记录或统计业务状态。
     */
    private void resetProtocolMetrics() {
        batchRequestCount.set(0L);
        batchResponseTimeMs.set(0L);
        batchTimeoutCount.set(0L);
        blockFallbackCount.set(0L);
        subscriptionEventCount.set(0L);
        subscriptionRegisterFailureCount.set(0L);
        subscriptionEventErrorCount.set(0L);
        responseCodeStats.clear();
        clearFailureSnapshot();
        lastSubscriptionEventTs = null;
        lastSubscriptionPointId = null;
        lastSubscriptionPointCode = null;
        lastSubscriptionError = null;
    }

    /**
     * 清理或删除业务数据。
     */
    private void clearFailureSnapshot() {
        lastFailedPointId = null;
        lastFailedAddress = null;
        lastFailedOperation = null;
        lastFailedResponseCode = null;
        lastFailureTs = null;
    }
    /**
     * 解析或转换业务数据。
     */
    private Object extractValue(PlcReadResponse response, String fieldName, DataPoint point, S7Address address) {
        return extractValue(response != null ? response.getPlcValue(fieldName) : null, point, address);
    }

    /**
     * 解析或转换业务数据。
     */
    private Object extractValue(PlcValue plcValue, DataPoint point, S7Address address) {
        if (plcValue == null || plcValue.isNull()) {
            return null;
        }
        if (plcValue.isList()) {
            if (address.isScalar() && plcValue.getLength() == 1) {
                plcValue = plcValue.getIndex(0);
            } else {
                return extractArrayValue(plcValue, point, address);
            }
        }

        S7PlcType plcType = resolvePlcType(point, address);
        return coerceScalarValue(plcValue, plcType);
    }

    /**
     * 执行当前业务逻辑。
     */
    private Object coerceWriteValue(Object value, S7Address address, DataPoint point) {
        if (value == null) {
            return null;
        }
        if (!address.isScalar()) {
            return coerceWriteArrayValue(value, address, point);
        }
        S7PlcType plcType = resolvePlcType(point, address);
        return coerceScalarValueForWrite(value, plcType);
    }

    /**
     * 解析或转换业务数据。
     */
    private S7PlcType resolvePlcType(DataPoint point, S7Address address) {
        return S7PlcTypeResolver.INSTANCE.resolveRequired(point, address, "S7 point type cannot be resolved");
    }

    /**
     * 执行当前业务逻辑。
     */
    private Object coerceScalarValue(PlcValue plcValue, S7PlcType plcType) {
        if (plcValue == null) {
            return null;
        }
        return plcType != null ? plcType.read(plcValue) : plcValue.getObject();
    }

    /**
     * 执行当前业务逻辑。
     */
    private Object coerceScalarValueForWrite(Object value, S7PlcType plcType) {
        if (value == null) {
            return null;
        }
        return plcType != null ? plcType.write(value) : value;
    }

    /**
     * 解析或转换业务数据。
     */
    private List<Object> extractArrayValue(PlcValue plcValue, DataPoint point, S7Address address) {
        if (address.isScalar()) {
            throw new IllegalStateException("S7 scalar point returned an unexpected array payload: " + address.getRawAddress());
        }
        List<Object> values = new ArrayList<>();
        S7PlcType plcType = resolvePlcType(point, address);
        int length = plcValue.getLength();
        for (int i = 0; i < length; i++) {
            values.add(coerceScalarValue(plcValue.getIndex(i), plcType));
        }
        return values;
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<Object> coerceWriteArrayValue(Object value, S7Address address, DataPoint point) {
        List<Object> sourceValues = toObjectList(value);
        if (sourceValues.isEmpty()) {
            throw new IllegalArgumentException("S7 array write value cannot be empty");
        }
        if (address.getArraySize() > 1 && sourceValues.size() != address.getArraySize()) {
            throw new IllegalArgumentException("S7 array write size mismatch, expected "
                    + address.getArraySize() + " but got " + sourceValues.size());
        }

        S7PlcType plcType = resolvePlcType(point, address);
        List<Object> coerced = new ArrayList<>(sourceValues.size());
        for (Object sourceValue : sourceValues) {
            coerced.add(coerceScalarValueForWrite(sourceValue, plcType));
        }
        return coerced;
    }

    /**
     * 解析或转换业务数据。
     */
    private List<Object> toObjectList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                values.add(Array.get(value, i));
            }
            return values;
        }
        throw new IllegalArgumentException("S7 array write requires collection or array value");
    }

    private boolean isArrayPoint(DataPoint point) {
        if (point == null) {
            return false;
        }
        try {
            return !requireAddress(point).isScalar();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean containsArrayPoint(Iterable<DataPoint> points) {
        if (points == null) {
            return false;
        }
        for (DataPoint point : points) {
            if (point != null && point.isEnabled() && isArrayPoint(point)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void validateArrayPointConfiguration(DataPoint point,
                                                 S7Address address,
                                                 String operation) {
        if (point == null || address == null || address.isScalar()) {
            return;
        }
        if (point.getScalingFactor() != null && point.getScalingFactor() != 0 && point.getScalingFactor() != 1.0d) {
            throw new IllegalArgumentException("S7 " + operation + " array point does not support scalingFactor: "
                    + point.getPointId());
        }
        if (point.getOffset() != null && point.getOffset() != 0.0d) {
            throw new IllegalArgumentException("S7 " + operation + " array point does not support offset: "
                    + point.getPointId());
        }
        if (point.getPrecision() != null) {
            throw new IllegalArgumentException("S7 " + operation + " array point does not support precision: "
                    + point.getPointId());
        }
        if (point.getMinValue() != null || point.getMaxValue() != null) {
            throw new IllegalArgumentException("S7 " + operation + " array point does not support min/max validation: "
                    + point.getPointId());
        }
        if (point.getAlarmEnabled() != null && point.getAlarmEnabled() == 1) {
            throw new IllegalArgumentException("S7 " + operation + " array point does not support alarm processing: "
                    + point.getPointId());
        }
    }

    /**
     * 创建并返回业务对象。
     */
    private ProcessResult buildArrayProcessResult(DataPoint point,
                                                  S7Address address,
                                                  Object rawValue,
                                                  String message) {
        if (!(rawValue instanceof Collection<?>) && !(rawValue != null && rawValue.getClass().isArray())) {
            throw new IllegalArgumentException("S7 array point did not produce collection payload: " + point.getPointId());
        }
        ProcessResult processResult = ProcessResult.success(rawValue, rawValue, message);
        processResult.addMetadata("arrayValue", true);
        processResult.addMetadata("arraySize", address.getArraySize());
        processResult.addMetadata("processingMode", "protocol_passthrough");
        if (point != null && point.getAddress() != null) {
            processResult.addMetadata(CommonMapKeys.ADDRESS, point.getAddress());
        }
        return processResult;
    }

    /**
     * 执行当前业务逻辑。
     */
    private ProcessResult ingestArrayPushedValue(DataPoint point,
                                                 S7Address address,
                                                 Object rawValue,
                                                 String message) {
        if (point == null) {
            return null;
        }
        String resolvedDeviceId = point.getDeviceId();
        if ((resolvedDeviceId == null || resolvedDeviceId.isBlank()) && deviceInfo != null) {
            resolvedDeviceId = deviceInfo.getDeviceId();
        }

        try {
            validateArrayPointConfiguration(point, address, "subscribe");
            ProcessResult processResult = buildArrayProcessResult(point, address, rawValue, message);
            lastProcessResults.put(point.getPointId(), processResult);
            lastActivityTime = System.currentTimeMillis();
            if (telemetryIngressService != null) {
                telemetryIngressService.append(resolvedDeviceId, point, processResult);
            }
            return processResult;
        } catch (Exception e) {
            totalErrorCount.incrementAndGet();
            lastError = e.getMessage();
            recordException(e, point);
            ProcessResult error = ProcessResult.error(rawValue,
                    "pushed array telemetry process failed: " + e.getMessage());
            lastProcessResults.put(point.getPointId(), error);
            if (telemetryIngressService != null) {
                telemetryIngressService.append(resolvedDeviceId, point, error);
            }
            return error;
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureResponseOk(PlcTagResponse response, String fieldName, String operation) {
        if (response == null) {
            throw new IllegalStateException("PLC4X S7 " + operation + " returned null response");
        }
        PlcResponseCode code = response.getResponseCode(fieldName);
        if (code != PlcResponseCode.OK) {
            throw new IllegalStateException("PLC4X S7 " + operation + " failed with response code: " + code);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private <T> T await(CompletableFuture<? extends T> future, S7ConnectionAdapter session) throws Exception {
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw ex;
        } catch (TimeoutException ex) {
            // 取消 Future 不保证撤销已发出的请求，超时会话不再复用；写请求也不自动重放。
            future.cancel(false);
            invalidateTransport(session);
            throw ex;
        } catch (Exception ex) {
            if (isTransportFailure(ex)) {
                invalidateTransport(session);
            }
            throw ex;
        }
    }

    private boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private boolean isTransportFailure(Throwable failure) {
        if (connectionAdapter != null && !connectionAdapter.isConnected()) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PlcConnectionException || cause instanceof PlcIoException || cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private void invalidateTransport(S7ConnectionAdapter session) {
        synchronized (subscriptionLifecycleLock) {
            if (session == null || connectionAdapter != session) {
                return;
            }
            subscriptionTokens.clear();
            subscriptionHandles.clear();
            connectionAdapter = null;
            connected = false;
            connectionStatus = "ERROR";
            subscribedPointMap.clear();
            subscribedPointsSet.clear();
            if (connectionManager == null || connectionManager.getConnection(deviceInfo.getDeviceId()) == session) {
                removeManagedConnection("S7");
            }
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureSubscriptionSupported() {
        subscriptionSupported = isRuntimeSubscriptionSupported();
        if (!subscriptionSupported) {
            throw unsupported("subscribe", "PLC4X metadata reports subscribe unsupported for the current connection");
        }
    }

    private boolean isRuntimeSubscriptionSupported() {
        if (subscriptionSupported) {
            return true;
        }
        if (connectionAdapter == null || connectionAdapter.getClient() == null) {
            return false;
        }
        return connectionAdapter.getClient().getMetadata().isSubscribeSupported();
    }

    /**
     * 维护注册或订阅关系。
     */
    private void clearRegistrationToken(SubscriptionRegistration registration) {
        synchronized (subscriptionLifecycleLock) {
            subscriptionTokens.remove(resolvePointCacheKey(registration.point()), registration.token());
        }
    }

    private void unsubscribeExisting(List<DataPoint> points) throws Exception {
        List<PlcSubscriptionHandle> existingHandles = new ArrayList<>();
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            synchronized (subscriptionLifecycleLock) {
                String cacheKey = resolvePointCacheKey(point);
                subscriptionTokens.remove(cacheKey);
                PlcSubscriptionHandle handle = subscriptionHandles.remove(cacheKey);
                configuredSubscriptionModes.remove(cacheKey);
                configuredSubscriptionAddresses.remove(cacheKey);
                subscribedPointMap.remove(point.getPointId());
                subscribedPointsSet.remove(point.getPointId());
                if (handle != null) {
                    existingHandles.add(handle);
                }
            }
        }
        unsubscribeHandles(existingHandles);
    }

    /**
     * 维护注册或订阅关系。
     */
    private void unsubscribeHandles(Collection<PlcSubscriptionHandle> handles) throws Exception {
        if (handles == null || handles.isEmpty() || connectionAdapter == null) {
            return;
        }
        unsubscribeSessionHandles(requireConnection(), handles);
    }

    private void unsubscribeSessionHandles(S7ConnectionAdapter session,
                                           Collection<PlcSubscriptionHandle> handles) throws Exception {
        PlcUnsubscriptionRequest.Builder builder = session.getClient().unsubscriptionRequestBuilder();
        builder.addHandles(handles);
        await(builder.build().execute(), session);
    }

    /**
     * 解析或转换业务数据。
     */
    private Duration resolveSubscriptionInterval(DataPoint point) {
        long intervalMs = point != null && point.getBaseCollectionInterval() != null && point.getBaseCollectionInterval() > 0
                ? point.getBaseCollectionInterval()
                : deviceInfo != null && deviceInfo.getCollectionInterval() != null && deviceInfo.getCollectionInterval() > 0
                ? deviceInfo.getCollectionInterval()
                : DEFAULT_SUBSCRIPTION_INTERVAL_MS;
        return Duration.ofMillis(Math.max(100L, intervalMs));
    }

    /**
     * 校验业务条件和参数边界。
     */
    private S7ConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException("PLC4X S7 connection has not been established");
        }
        return connectionAdapter;
    }

    /**
     * 执行当前业务逻辑。
     */
    private String tagName(DataPoint point) {
        return resolvePointTagName(point);
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeCommandRead(Map<String, Object> params) throws Exception {
        DataPoint point = resolveCommandPoint(params);
        Object value = readPoint(point);
        Map<String, Object> result = new LinkedHashMap<>();
        populatePointMetadata(result, point);
        result.put(CommonMapKeys.VALUE, value);
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeCommandWrite(Map<String, Object> params) throws Exception {
        DataPoint point = resolveCommandPoint(params);
        if (!params.containsKey(CommonMapKeys.VALUE)) {
            throw new IllegalArgumentException("value is required");
        }
        Object value = params.get(CommonMapKeys.VALUE);
        boolean success = writePoint(point, value);
        Map<String, Object> result = new LinkedHashMap<>();
        populatePointMetadata(result, point);
        result.put(CommonMapKeys.VALUE, value);
        result.put(CommonMapKeys.SUCCESS, success);
        return result;
    }

    /**
     * 解析或转换业务数据。
     */
    private DataPoint resolveCommandPoint(Map<String, Object> params) {
        List<DataPoint> points = configManager != null && deviceInfo != null
                ? configManager.getDataPoints(deviceInfo.getDeviceId())
                : Collections.emptyList();
        if (points.isEmpty()) {
            throw new IllegalArgumentException("No configured S7 points found for device: "
                    + (deviceInfo != null ? deviceInfo.getDeviceId() : "UNKNOWN"));
        }

        String pointRef = firstNonBlank(
                asText(params.get("pointRef")),
                asText(params.get(CommonMapKeys.POINT_ID)),
                asText(params.get(CommonMapKeys.POINT_CODE)),
                asText(params.get(CommonMapKeys.POINT_NAME)),
                asText(params.get(CommonMapKeys.FIELD)),
                asText(params.get("reportField"))
        );
        if (hasText(pointRef)) {
            DataPoint point = resolveConfiguredPoint(points, pointRef);
            if (point != null) {
                return point;
            }
        }

        String address = asText(params.get(CommonMapKeys.ADDRESS));
        if (hasText(address)) {
            DataPoint point = points.stream()
                    .filter(candidate -> candidate != null && hasText(candidate.getAddress())
                            && normalize(candidate.getAddress()).equals(normalize(address)))
                    .findFirst()
                    .orElse(null);
            if (point != null) {
                return point;
            }
        }

        throw new IllegalArgumentException("Unable to resolve S7 point from command params");
    }

    /**
     * 解析或转换业务数据。
     */
    private DataPoint resolveConfiguredPoint(List<DataPoint> points, String pointRef) {
        if (devicePointResolver != null) {
            return devicePointResolver.resolve(points, pointRef).orElse(null);
        }
        String normalizedRef = normalize(pointRef);
        return points.stream()
                .filter(point -> matchesPointRef(point, normalizedRef))
                .findFirst()
                .orElse(null);
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean matchesPointRef(DataPoint point, String normalizedRef) {
        return point != null
                && (normalizedRef.equals(normalize(point.getReportField()))
                || normalizedRef.equals(normalize(point.getPointAlias()))
                || normalizedRef.equals(normalize(point.getPointCode()))
                || normalizedRef.equals(normalize(point.getPointId()))
                || normalizedRef.equals(normalize(point.getPointName())));
    }

    /**
     * 执行当前业务逻辑。
     */
    private void populatePointMetadata(Map<String, Object> target, DataPoint point) {
        target.put(CommonMapKeys.POINT_ID, point.getPointId());
        target.put(CommonMapKeys.POINT_CODE, point.getPointCode());
        target.put(CommonMapKeys.POINT_NAME, point.getPointName());
        if (point.getAddress() != null) {
            target.put(CommonMapKeys.ADDRESS, point.getAddress());
        }
    }

    /**
     * 创建并返回业务对象。
     */
    private Map<String, Object> buildDiagnosticPayload() throws Exception {
        DeviceConnection connection = getCurrentConnectionConfig();
        Map<String, Object> pointSummary = buildPointSummary();
        List<Map<String, Object>> deploymentChecks = buildDeploymentChecks(connection, pointSummary);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", getDeviceStatus());
        result.put("connection", buildConnectionInfo(connection));
        result.put("capabilities", buildCapabilities());
        result.put("points", pointSummary);
        result.put("deploymentChecks", deploymentChecks);
        result.put("recommendedActions", buildRecommendedActions(connection, pointSummary, deploymentChecks));
        result.put("statistics", getStatistics());
        return result;
    }

    /**
     * 创建并返回业务对象。
     */
    private Map<String, Object> buildConnectionInfo(DeviceConnection connection) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (connection != null) {
            info.put(CommonMapKeys.HOST, firstNonBlank(connection.getHost(), deviceInfo != null ? deviceInfo.getIpAddress() : null));
            info.put(CommonMapKeys.PORT, resolveConfiguredPort(connection));
            info.put("rack", connection.getInt("rack", connection.getInt("remoteRack", 0)));
            info.put("slot", connection.getInt("slot", connection.getInt("remoteSlot", 1)));
            info.put("controllerType", resolveControllerType(connection));
            info.put("pduSize", connection.getInt("pduSize", 1024));
            info.put("maxFieldsPerRequest", connection.getInt("maxFieldsPerRequest", maxFieldsPerRequest));
            info.put("subscriptionEnabled", connection.getBool("subscriptionEnabled", null));
            info.put("localTsap", connection.getInt("localTsap", null));
            info.put("remoteTsap", connection.getInt("remoteTsap", null));
            info.put("localDeviceGroup", normalizeDeviceGroup(connection.getString("localDeviceGroup", null)));
            info.put("remoteDeviceGroup", normalizeDeviceGroup(connection.getString("remoteDeviceGroup", null)));
            info.put("remoteRack2", connection.getInt("remoteRack2", null));
            info.put("remoteSlot2", connection.getInt("remoteSlot2", null));
            info.put("remoteDeviceGroup2", normalizeDeviceGroup(connection.getString("remoteDeviceGroup2", null)));
            info.put("maxAmqCaller", connection.getInt("maxAmqCaller", null));
            info.put("maxAmqCallee", connection.getInt("maxAmqCallee", null));
            info.put("ping", connection.getBool("ping", false));
            info.put("pingTime", connection.getInt("pingTime", null));
            info.put("retryTime", connection.getInt("retryTime", null));
            info.put("connectionSource", hasRawConnectionString(connection)
                    ? "EXPLICIT_PLC4X_STRING" : "GENERATED");
            info.put("readTimeout", connection.getReadTimeout() != null ? connection.getReadTimeout() : connection.getTimeout());
        }
        return info;
    }

    /**
     * 创建并返回业务对象。
     */
    private Map<String, Object> buildCapabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("read", true);
        capabilities.put("write", true);
        capabilities.put("subscribe", isRuntimeSubscriptionSupported());
        capabilities.put("browse", false);
        capabilities.put("symbolicAddressing", false);
        capabilities.put("arrayReadWrite", true);
        capabilities.put("subscriptionModes", isRuntimeSubscriptionSupported()
                ? SUPPORTED_SUBSCRIPTION_MODES
                : Collections.emptyList());
        capabilities.put("commands", SUPPORTED_COMMANDS);
        return capabilities;
    }

    /**
     * 创建并返回业务对象。
     */
    private Map<String, Object> buildPointSummary() {
        List<DataPoint> points = configManager != null && deviceInfo != null
                ? configManager.getDataPoints(deviceInfo.getDeviceId())
                : Collections.emptyList();
        if (points == null) {
            points = Collections.emptyList();
        }

        int parsedCount = 0;
        int invalidCount = 0;
        int arrayCount = 0;
        int subscriptionPointCount = 0;
        int eventPointCount = 0;
        Map<String, Integer> areaCounts = new LinkedHashMap<>();
        Map<String, Integer> subscriptionModeCounts = new LinkedHashMap<>();
        List<Map<String, Object>> invalidPoints = new ArrayList<>();

        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            try {
                if (isSubscriptionPoint(point)) {
                    subscriptionPointCount++;
                    String subscriptionMode = requireSupportedSubscriptionMode(point);
                    subscriptionModeCounts.merge(subscriptionMode, 1, Integer::sum);
                    if (isEventSubscriptionMode(subscriptionMode)) {
                        eventPointCount++;
                        resolveEventSubscriptionAddress(point, subscriptionMode);
                        continue;
                    }
                }

                S7Address address = S7AddressParser.parse(point);
                if (!isSubscriptionPoint(point)) {
                    readPlanBuilder.build(List.of(point), maxFieldsPerRequest);
                }
                parsedCount++;
                areaCounts.merge(address.getArea(), 1, Integer::sum);
                if (!address.isScalar()) {
                    arrayCount++;
                }
            } catch (Exception ex) {
                invalidCount++;
                if (invalidPoints.size() < 10) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put(CommonMapKeys.POINT_ID, point.getPointId());
                    item.put(CommonMapKeys.POINT_CODE, point.getPointCode());
                    item.put(CommonMapKeys.ADDRESS, point.getAddress());
                    item.put(CommonMapKeys.ERROR, ex.getMessage());
                    invalidPoints.add(item);
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalPoints", points.size());
        summary.put("parsedPointCount", parsedCount);
        summary.put("invalidPointCount", invalidCount);
        summary.put("arrayPointCount", arrayCount);
        summary.put("subscriptionPointCount", subscriptionPointCount);
        summary.put("eventPointCount", eventPointCount);
        summary.put("pollPointCount", Math.max(0, points.size() - subscriptionPointCount));
        summary.put("areaCounts", areaCounts);
        summary.put("subscriptionModeCounts", subscriptionModeCounts);
        summary.put(CommonMapKeys.PLANNED_READ_BATCH_COUNT, configuredReadPlans.size());
        summary.put("plannedReadBatches", snapshotReadPlans());
        summary.put("invalidPoints", invalidPoints);
        return summary;
    }

    /**
     * 创建并返回业务对象。
     */
    private List<Map<String, Object>> buildDeploymentChecks(DeviceConnection connection,
                                                            Map<String, Object> pointSummary) {
        List<Map<String, Object>> checks = new ArrayList<>();

        if (hasRawConnectionString(connection)) {
            checks.add(check("connection-string", "INFO",
                    "plc4xConnectionString is set. Host/rack/slot/controllerType fields are not the source of truth."));
        } else if (hasText(firstNonBlank(connection != null ? connection.getHost() : null,
                deviceInfo != null ? deviceInfo.getIpAddress() : null))) {
            checks.add(check("transport", "PASS", "Host/IP is configured for generated S7 connection building."));
        } else {
            checks.add(check("transport", "FAIL", "Host or device ipAddress is required for generated S7 connections."));
        }

        int invalidPointCount = intValue(pointSummary.get("invalidPointCount"));
        checks.add(check("address-format", invalidPointCount == 0 ? "PASS" : "FAIL",
                invalidPointCount == 0
                        ? "All configured S7 polling or cyclic points are parseable absolute addresses, and event points have valid subscription modes."
                        : "Found " + invalidPointCount + " invalid or unsupported S7 point configurations."));

        int arrayPointCount = intValue(pointSummary.get("arrayPointCount"));
        checks.add(check("array-points", arrayPointCount == 0 ? "PASS" : "WARN",
                arrayPointCount == 0
                        ? "No configured S7 array points detected."
                        : "Found " + arrayPointCount + " configured S7 array points. One-dimensional full-array read/write is enabled, but scaling/offset/precision/min-max/alarm rules remain unsupported on array paths."));

        String controllerType = resolveControllerType(connection);
        if ("S7_1200".equals(controllerType) || "S7_1500".equals(controllerType)) {
            checks.add(check("optimized-block-access", "WARN",
                    controllerType + " deployments must disable optimized block access for DBs read by absolute address."));
        } else {
            checks.add(check("optimized-block-access", "INFO",
                    "Absolute DB addressing depends on PLC memory layout matching the configured offsets."));
        }

        checks.add(check("symbolic-access", "INFO",
                "Symbolic browse/import is not implemented in the current PLC4X S7 collector. Configure absolute addresses such as DB1.DBW0 / %DB1:4:REAL / I0.0."));

        int subscriptionPointCount = intValue(pointSummary.get("subscriptionPointCount"));
        int eventPointCount = intValue(pointSummary.get("eventPointCount"));
        Boolean subscriptionEnabled = connection != null ? connection.getBool("subscriptionEnabled", null) : null;
        if (subscriptionPointCount > 0 || Boolean.TRUE.equals(subscriptionEnabled)) {
            checks.add(check("subscription-mode", isRuntimeSubscriptionSupported() ? "PASS" : "WARN",
                    isRuntimeSubscriptionSupported()
                            ? "S7 collector supports CYCLIC subscriptions plus MODE/SYS/USR/ALM event subscriptions for configured subscription points."
                            : "Subscription points are configured but the current PLC4X connection metadata reports subscribe unsupported."));
        }
        if (eventPointCount > 0) {
            checks.add(check("event-subscriptions", "INFO",
                    "Configured " + eventPointCount + " S7 event subscription points using MODE/SYS/USR/ALM."));
        }

        int plannedReadBatchCount = intValue(pointSummary.get(CommonMapKeys.PLANNED_READ_BATCH_COUNT));
        if (plannedReadBatchCount > 0) {
            checks.add(check("read-plan", "INFO",
                    "Prepared " + plannedReadBatchCount + " grouped S7 polling batches from the current point layout."));
        }

        return checks;
    }

    /**
     * 创建并返回业务对象。
     */
    private List<String> buildRecommendedActions(DeviceConnection connection,
                                                 Map<String, Object> pointSummary,
                                                 List<Map<String, Object>> deploymentChecks) {
        List<String> actions = new ArrayList<>();
        if (intValue(pointSummary.get("invalidPointCount")) > 0) {
            actions.add("Normalize invalid S7 point addresses or subscription modes before enabling the device.");
        }
        if (intValue(pointSummary.get("arrayPointCount")) > 0) {
            actions.add("Keep S7 array points on full-array read/write only. Do not configure scalingFactor, offset, precision, min/max, or alarm processing on array points.");
        }
        String controllerType = resolveControllerType(connection);
        if ("S7_1200".equals(controllerType) || "S7_1500".equals(controllerType)) {
            actions.add("Verify in TIA Portal that optimized block access is disabled for every DB read by absolute address.");
        }
        if (!hasRawConnectionString(connection)) {
            actions.add("Prefer generated connection parameters unless you need a PLC4X-only compatibility override.");
        }
        if (intValue(pointSummary.get("eventPointCount")) > 0) {
            actions.add("Validate MODE/SYS/USR/ALM event payloads on a real PLC before binding strict downstream alarm or integration contracts.");
        }
        if (countChecksByStatus(deploymentChecks, "WARN") > 0
                && intValue(pointSummary.get("subscriptionPointCount")) > 0
                && !isRuntimeSubscriptionSupported()) {
            actions.add("Confirm that the target PLC4X S7 route really exposes subscribe support; otherwise subscription points will not receive live updates.");
        }
        if (actions.isEmpty()) {
            actions.add("No immediate S7 deployment blockers detected from local configuration. Validate against a real PLC before rollout.");
        }
        return actions;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private Map<String, Object> check(String name, String status, String message) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put(CommonMapKeys.NAME, name);
        check.put(CommonMapKeys.STATUS, status);
        check.put(CommonMapKeys.MESSAGE, message);
        return check;
    }

    /**
     * 记录或统计业务状态。
     */
    private int countChecksByStatus(List<Map<String, Object>> checks, String expectedStatus) {
        int count = 0;
        if (checks == null || expectedStatus == null) {
            return 0;
        }
        for (Map<String, Object> check : checks) {
            if (expectedStatus.equals(check.get(CommonMapKeys.STATUS))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 执行当前业务逻辑。
     */
    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasRawConnectionString(DeviceConnection connection) {
        return connection != null && hasText(connection.getString("plc4xConnectionString", null));
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveConfiguredPort(DeviceConnection connection) {
        if (connection != null && connection.getPort() != null && connection.getPort() > 0) {
            return connection.getPort();
        }
        if (deviceInfo != null && deviceInfo.getPort() != null && deviceInfo.getPort() > 0) {
            return deviceInfo.getPort();
        }
        return 102;
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveControllerType(DeviceConnection connection) {
        return normalizeControllerType(connection != null ? connection.getString("controllerType", "S7_1200") : "S7_1200");
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeControllerType(String controllerType) {
        return controllerType != null
                ? controllerType.trim().replace('-', '_').toUpperCase(Locale.ROOT)
                : "S7_1200";
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeDeviceGroup(String deviceGroup) {
        return hasText(deviceGroup)
                ? deviceGroup.trim().replace('-', '_').toUpperCase(Locale.ROOT)
                : null;
    }

    private boolean isSubscriptionPoint(DataPoint point) {
        if (point == null) {
            return false;
        }
        if (hasText(resolveConfiguredSubscriptionMode(point))) {
            return true;
        }
        String collectionMode = normalizeCollectionMode(point.getCollectionMode());
        return "SUBSCRIPTION".equals(collectionMode) || "EVENT".equals(collectionMode);
    }

    private boolean isEventSubscriptionPoint(DataPoint point) {
        return isSubscriptionPoint(point) && isEventSubscriptionMode(resolveSubscriptionMode(point));
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveConfiguredSubscriptionMode(DataPoint point) {
        return normalizeSubscriptionMode(firstNonBlank(
                asText(point != null ? point.getAdditionalConfig("subscriptionMode") : null),
                asText(point != null ? point.getAdditionalConfig("s7SubscriptionMode") : null)
        ));
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveSubscriptionMode(DataPoint point) {
        String configured = resolveConfiguredSubscriptionMode(point);
        if (hasText(configured)) {
            return configured;
        }
        String collectionMode = normalizeCollectionMode(point != null ? point.getCollectionMode() : null);
        if ("EVENT".equals(collectionMode)) {
            String inferredFromAddress = normalizeSubscriptionMode(firstNonBlank(
                    asText(point != null ? point.getAddress() : null),
                    asText(point != null ? point.getAdditionalConfig("subscriptionAddress") : null),
                    asText(point != null ? point.getAdditionalConfig("s7SubscriptionAddress") : null)
            ));
            if (isEventSubscriptionMode(inferredFromAddress)) {
                return inferredFromAddress;
            }
        }
        return "CYCLIC";
    }

    /**
     * 校验业务条件和参数边界。
     */
    private String requireSupportedSubscriptionMode(DataPoint point) {
        String subscriptionMode = resolveSubscriptionMode(point);
        if (!SUPPORTED_SUBSCRIPTION_MODES.contains(subscriptionMode)) {
            throw new IllegalArgumentException("Unsupported S7 subscription mode: " + subscriptionMode);
        }
        return subscriptionMode;
    }

    private boolean isEventSubscriptionMode(String subscriptionMode) {
        return "MODE".equals(subscriptionMode)
                || "SYS".equals(subscriptionMode)
                || "USR".equals(subscriptionMode)
                || "ALM".equals(subscriptionMode);
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveEventSubscriptionAddress(DataPoint point, String subscriptionMode) {
        String configuredAddress = firstNonBlank(
                asText(point != null ? point.getAdditionalConfig("subscriptionAddress") : null),
                asText(point != null ? point.getAdditionalConfig("s7SubscriptionAddress") : null)
        );
        if (hasText(configuredAddress)) {
            return configuredAddress.trim().toUpperCase(Locale.ROOT);
        }
        String pointAddress = normalizeSubscriptionMode(asText(point != null ? point.getAddress() : null));
        if (isEventSubscriptionMode(pointAddress)) {
            return pointAddress;
        }
        return subscriptionMode;
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeSubscriptionMode(String subscriptionMode) {
        if (!hasText(subscriptionMode)) {
            return null;
        }
        String normalized = subscriptionMode.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "SYSTEM" -> "SYS";
            case "USER" -> "USR";
            case "ALARM" -> "ALM";
            default -> normalized;
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeCollectionMode(String collectionMode) {
        return collectionMode != null ? collectionMode.trim().toUpperCase(Locale.ROOT).replace('-', '_') : "";
    }

    /**
     * 记录或统计业务状态。
     */
    private void recordResponseCode(String operation,
                                    DataPoint point,
                                    String address,
                                    PlcResponseCode responseCode) {
        String code = responseCode != null ? responseCode.name() : "NULL";
        responseCodeStats.computeIfAbsent(operation + "." + code, ignored -> new AtomicLong()).incrementAndGet();
        if (responseCode != PlcResponseCode.OK) {
            recordFailureSnapshot(operation, point, address, code);
        }
    }

    /**
     * 记录或统计业务状态。
     */
    private void recordFailureSnapshot(String operation,
                                       DataPoint point,
                                       String address,
                                       String responseCode) {
        lastFailedPointId = point != null ? point.getPointId() : null;
        lastFailedAddress = firstNonBlank(address, point != null ? point.getAddress() : null);
        lastFailedOperation = operation;
        lastFailedResponseCode = responseCode;
        lastFailureTs = System.currentTimeMillis();
    }

    /**
     * 查询并返回业务数据。
     */
    private Map<String, Long> snapshotResponseCodeStats() {
        if (responseCodeStats.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> keys = new ArrayList<>(responseCodeStats.keySet());
        Collections.sort(keys);
        Map<String, Long> snapshot = new LinkedHashMap<>();
        for (String key : keys) {
            AtomicLong counter = responseCodeStats.get(key);
            snapshot.put(key, counter != null ? counter.get() : 0L);
        }
        return snapshot;
    }

    /**
     * 查询并返回业务数据。
     */
    private List<Map<String, Object>> snapshotReadPlans() {
        if (configuredReadPlans.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> snapshot = new ArrayList<>();
        int limit = Math.min(10, configuredReadPlans.size());
        for (int i = 0; i < limit; i++) {
            S7ReadPlan readPlan = configuredReadPlans.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("segmentKey", readPlan.getSegmentKey());
            item.put("area", readPlan.getArea());
            item.put("dbNumber", readPlan.getDbNumber());
            item.put("startOffset", readPlan.getStartOffset());
            item.put("endOffsetExclusive", readPlan.getEndOffsetExclusive());
            item.put("estimatedByteSpan", readPlan.getEstimatedByteSpan());
            item.put(CommonMapKeys.POINT_COUNT, readPlan.getPointCount());
            item.put("blockOptimizable", readPlan.isBlockOptimizable());
            item.put("blockReadEnabled", readPlan.canUseBlockRead());
            item.put("blockReadAddress", readPlan.getBlockReadAddress());
            snapshot.add(item);
        }
        return snapshot;
    }

    /**
     * 记录或统计业务状态。
     */
    private int countConfiguredEventPoints() {
        int count = 0;
        for (String subscriptionMode : configuredSubscriptionModes.values()) {
            if (isEventSubscriptionMode(subscriptionMode)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeCommand(String command) {
        return command != null ? command.trim().toLowerCase(Locale.ROOT).replace('-', '_') : "";
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 执行当前业务逻辑。
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private String asText(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public boolean isConnected() {
        return connectionAdapter != null && connectionAdapter.isConnected();
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    private record SubscriptionRegistration(DataPoint point,
                                            String fieldName,
                                            String subscriptionMode,
                                            String displayAddress,
                                            Object token) {
    }
}

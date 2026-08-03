package com.wangbin.collector.core.collector.protocol.ads;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.ads.domain.AdsAddress;
import com.wangbin.collector.core.collector.protocol.ads.domain.AdsPlcType;
import com.wangbin.collector.core.collector.protocol.ads.util.AdsAddressParser;
import com.wangbin.collector.core.collector.protocol.ads.util.AdsPlcTypeResolver;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.plc4x.domain.Plc4xArrayValueSupport;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import com.wangbin.collector.core.connection.adapter.AdsConnectionAdapter;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 实现当前协议或设备的采集能力。
 */
@Slf4j
public class AdsCollector extends ConnectionBackedCollector {

    private static final long DEFAULT_SUBSCRIPTION_INTERVAL_MS = 2000L;
    private DevicePointResolver devicePointResolver;

    /**
     * 注入点位解析辅助组件。
     */
    @Autowired(required = false)
    public void setDevicePointResolver(DevicePointResolver devicePointResolver) {
        this.devicePointResolver = devicePointResolver;
    }

    private AdsConnectionAdapter connectionAdapter;
    private final Map<String, AdsAddress> configuredAddresses = new ConcurrentHashMap<>();
    private final Map<String, PlcSubscriptionHandle> subscriptionHandles = new ConcurrentHashMap<>();
    private int timeout = 5000;
    private int maxFieldsPerRequest = 64;
    private boolean subscriptionSupported;

    @Override
    public String getCollectorType() {
        return "ADS";
    }

    @Override
    public String getProtocolType() {
        return "ADS";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        DeviceConnection desiredConfig = requireConnectionConfig();
        this.connectionAdapter = createAndConnectAdapter(desiredConfig, AdsConnectionAdapter.class, "ADS");

        DeviceConnection currentConfig = getCurrentConnectionConfig();
        if (currentConfig == null) {
            currentConfig = desiredConfig;
        }

        Integer configuredTimeout = currentConfig.getReadTimeout() != null
                ? currentConfig.getReadTimeout()
                : currentConfig.getTimeout();
        this.timeout = configuredTimeout != null && configuredTimeout > 0 ? configuredTimeout : 5000;
        this.maxFieldsPerRequest = Math.max(1, currentConfig.getInt("maxFieldsPerRequest", 64));
        this.subscriptionSupported = currentConfig.getBool("subscriptionEnabled",
                requireConnection().getClient().getMetadata().isSubscribeSupported());
        log.info("PLC4X ADS 采集器 已连接, 设备={}, 超时={}, 单次最大字段数={}",
                deviceInfo.getDeviceId(), timeout, maxFieldsPerRequest);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        removeManagedConnection("ADS");
        connectionAdapter = null;
        configuredAddresses.clear();
        subscriptionHandles.clear();
        subscriptionSupported = false;
        log.info("PLC4X ADS 采集器 已断开, 设备={}", deviceInfo.getDeviceId());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        AdsAddress address = requireAddress(point);
        String fieldName = resolvePointTagName(point);

        PlcReadResponse response = await(requireConnection().getClient()
                .readRequestBuilder()
                .addTagAddress(fieldName, address.getPlc4xAddress())
                .build()
                .execute());
        ensureResponseOk(response, fieldName, "read");
        return extractValue(response, fieldName, point, address);
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

        List<DataPoint> batch = new ArrayList<>(Math.min(points.size(), maxFieldsPerRequest));
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            batch.add(point);
            if (batch.size() >= maxFieldsPerRequest) {
                executeReadBatch(batch, results);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            executeReadBatch(batch, results);
        }
        return results;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        AdsAddress address = requireAddress(point);
        String fieldName = resolvePointTagName(point);

        PlcWriteResponse response = await(requireConnection().getClient()
                .writeRequestBuilder()
                .addTagAddress(fieldName, address.getPlc4xAddress(), coerceWriteValue(value, address, point))
                .build()
                .execute());
        ensureResponseOk(response, fieldName, "write");
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

        try {
            PlcWriteRequest.Builder builder = requireConnection().getClient().writeRequestBuilder();
            List<DataPoint> orderedPoints = new ArrayList<>();

            for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
                DataPoint point = entry.getKey();
                if (point == null) {
                    continue;
                }
                AdsAddress address = requireAddress(point);
                builder.addTagAddress(resolvePointTagName(point), address.getPlc4xAddress(), coerceWriteValue(entry.getValue(), address, point));
                orderedPoints.add(point);
            }

            PlcWriteResponse response = await(builder.build().execute());
            for (DataPoint point : orderedPoints) {
                String fieldName = resolvePointTagName(point);
                results.put(point.getPointId(), response != null && response.getResponseCode(fieldName) == PlcResponseCode.OK);
            }
            return results;
        } catch (Exception ex) {
            log.warn("PLC4X ADS 批量 写入 失败, 降级为逐点写入:{}", ex.getMessage());
            for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
                DataPoint point = entry.getKey();
                if (point == null) {
                    continue;
                }
                try {
                    results.put(point.getPointId(), doWritePoint(point, entry.getValue()));
                } catch (Exception singleEx) {
                    log.error("PLC4X ADS 点位 写入 失败, 点位={}", point.getPointId(), singleEx);
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
        cacheAddresses(points);
        if (points == null || points.isEmpty()) {
            return;
        }
        ensureSubscriptionSupported();
        unsubscribeExisting(points);

        var builder = requireConnection().getClient().subscriptionRequestBuilder();
        List<DataPoint> orderedPoints = new ArrayList<>();
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            AdsAddress address = requireAddress(point);
            ensureScalar(address, point, "subscribe");
            builder.addCyclicTagAddress(
                    resolvePointTagName(point),
                    address.getPlc4xAddress(),
                    resolveSubscriptionInterval(point),
                    event -> handleSubscriptionEvent(point, resolvePointTagName(point), address, event));
            orderedPoints.add(point);
        }

        PlcSubscriptionResponse response = await(builder.build().execute());
        int registered = 0;
        for (DataPoint point : orderedPoints) {
            String fieldName = resolvePointTagName(point);
            PlcResponseCode responseCode = response != null ? response.getResponseCode(fieldName) : null;
            if (responseCode != PlcResponseCode.OK) {
                log.warn("PLC4X ADS 订阅失败, 设备={}, 点位={}, 响应码={}",
                        deviceInfo.getDeviceId(), point.getPointId(), responseCode);
                continue;
            }
            PlcSubscriptionHandle handle = response.getSubscriptionHandle(fieldName);
            if (handle == null) {
                log.warn("PLC4X ADS 订阅返回空句柄, 设备={}, 点位={}",
                        deviceInfo.getDeviceId(), point.getPointId());
                continue;
            }
            subscriptionHandles.put(resolvePointCacheKey(point), handle);
            registered++;
        }

        if (registered == 0) {
            throw new IllegalStateException("PLC4X ADS subscribe did not register any point");
        }
        log.info("PLC4X ADS 订阅已注册, 设备={}, 数量={}",
                deviceInfo.getDeviceId(), registered);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) throws Exception {
        if (points == null || points.isEmpty()) {
            unsubscribeHandles(new ArrayList<>(subscriptionHandles.values()));
            subscriptionHandles.clear();
            configuredAddresses.clear();
            return;
        }
        List<PlcSubscriptionHandle> handlesToRemove = new ArrayList<>();
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            configuredAddresses.remove(resolvePointCacheKey(point));
            PlcSubscriptionHandle handle = subscriptionHandles.remove(resolvePointCacheKey(point));
            if (handle != null) {
                handlesToRemove.add(handle);
            }
        }
        unsubscribeHandles(handlesToRemove);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put(CommonMapKeys.PROTOCOL, getProtocolType());
        status.put(CommonMapKeys.DRIVER, "PLC4X");
        status.put("implemented", true);
        status.put(CommonMapKeys.WRITABLE, true);
        status.put(CommonMapKeys.SUBSCRIBABLE, isRuntimeSubscriptionSupported());
        status.put(CommonMapKeys.IS_CONNECTED, isConnected());
        status.put(CommonMapKeys.CONFIGURED_POINT_COUNT, configuredAddresses.size());
        status.put("maxFieldsPerRequest", maxFieldsPerRequest);
        status.put("activeSubscriptions", subscriptionHandles.size());

        DeviceConnection connection = getCurrentConnectionConfig();
        if (connection != null) {
            status.put(CommonMapKeys.HOST, connection.getHost());
            status.put(CommonMapKeys.PORT, connection.getPort());
            status.put("targetAmsNetId", firstNonBlank(
                    connection.getString("targetAmsNetId", null),
                    connection.getString("target-ams-net-id", null)));
            status.put("targetAmsPort", firstPositive(
                    connection.getInt("targetAmsPort", null),
                    connection.getInt("target-ams-port", null)));
            status.put("sourceAmsNetId", firstNonBlank(
                    connection.getString("sourceAmsNetId", null),
                    connection.getString("source-ams-net-id", null)));
            status.put("sourceAmsPort", firstPositive(
                    connection.getInt("sourceAmsPort", null),
                    connection.getInt("source-ams-port", null)));
            status.put("loadSymbolAndDataTypeTables", firstBoolean(
                    connection.getBool("loadSymbolAndDataTypeTables", null),
                    connection.getBool("load-symbol-and-data-type-tables", null),
                    Boolean.TRUE));
            status.put(CommonMapKeys.TIMEOUT, connection.getReadTimeout() != null ? connection.getReadTimeout() : connection.getTimeout());
        }

        if (connectionAdapter != null) {
            status.put("connectionString", connectionAdapter.getConnectionString());
        }
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
            case "status", "diagnostic" -> getDeviceStatus();
            default -> throw new IllegalArgumentException("Unsupported PLC4X ADS command: " + command);
        };
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        cacheAddresses(points);
    }

    /**
     * 执行当前业务逻辑。
     */
    private void cacheAddresses(List<DataPoint> points) {
        configuredAddresses.clear();
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            configuredAddresses.put(resolvePointCacheKey(point), AdsAddressParser.parse(point));
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private AdsAddress requireAddress(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("Point cannot be null");
        }
        return configuredAddresses.computeIfAbsent(resolvePointCacheKey(point), ignored -> AdsAddressParser.parse(point));
    }

    /**
     * 执行当前业务逻辑。
     */
    private UnsupportedOperationException unsupported(String operation, String reason) {
        String message = String.format("PLC4X ADS collector does not implement %s", operation);
        if (reason != null && !reason.isBlank()) {
            message = message + ": " + reason;
        }
        log.warn(message);
        return new UnsupportedOperationException(message);
    }

    /**
     * 处理当前业务流程。
     */
    private void executeReadBatch(List<DataPoint> batch, Map<String, Object> results) {
        try {
            PlcReadResponse response = executeReadBatchRequest(batch);
            for (DataPoint point : batch) {
                if (point == null || point.getPointId() == null) {
                    continue;
                }
                String fieldName = resolvePointTagName(point);
                if (response == null || response.getResponseCode(fieldName) != PlcResponseCode.OK) {
                    results.put(point.getPointId(), null);
                    continue;
                }
                results.put(point.getPointId(), extractValue(response, fieldName, point, requireAddress(point)));
            }
        } catch (Exception ex) {
            log.error("PLC4X ADS 批量 读取 失败, 设备={}, 批量数量={}", deviceInfo.getDeviceId(), batch.size(), ex);
            for (DataPoint point : batch) {
                if (point != null && point.getPointId() != null) {
                    results.put(point.getPointId(), null);
                }
            }
        }
    }

    /**
     * 处理当前业务流程。
     */
    private PlcReadResponse executeReadBatchRequest(List<DataPoint> batch) throws Exception {
        var builder = requireConnection().getClient().readRequestBuilder();
        for (DataPoint point : batch) {
            if (point == null) {
                continue;
            }
            AdsAddress address = requireAddress(point);
            builder.addTagAddress(resolvePointTagName(point), address.getPlc4xAddress());
        }
        return await(builder.build().execute());
    }

    /**
     * 处理当前业务流程。
     */
    private void handleSubscriptionEvent(DataPoint point,
                                         String fieldName,
                                         AdsAddress address,
                                         PlcSubscriptionEvent event) {
        try {
            PlcResponseCode responseCode = event != null ? event.getResponseCode(fieldName) : null;
            if (responseCode != PlcResponseCode.OK) {
                log.warn("PLC4X ADS 订阅事件 失败, 设备={}, 点位={}, 响应码={}",
                        deviceInfo.getDeviceId(), point.getPointId(), responseCode);
                return;
            }
            Object rawValue = extractValue(event, fieldName, point, address);
            ingestPushedValue(point, rawValue);
        } catch (Exception ex) {
            log.warn("PLC4X ADS 订阅事件处理 失败, 设备={}, 点位={}",
                    deviceInfo.getDeviceId(), point.getPointId(), ex);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private Object extractValue(PlcReadResponse response, String fieldName, DataPoint point, AdsAddress address) {
        PlcValue plcValue = response.getPlcValue(fieldName);
        AdsPlcType plcType = resolvePlcType(point, address);
        return Plc4xArrayValueSupport.decode(plcValue, address.getArraySize(),
                value -> plcType != null ? plcType.read(value) : value.getObject(),
                "ADS", address.getRawAddress());
    }

    /**
     * 执行当前业务逻辑。
     */
    private Object coerceWriteValue(Object value, AdsAddress address, DataPoint point) {
        AdsPlcType plcType = resolvePlcType(point, address);
        return Plc4xArrayValueSupport.encode(value, address.getArraySize(),
                item -> plcType != null ? plcType.write(item) : item, "ADS");
    }

    /**
     * 解析或转换业务数据。
     */
    private AdsPlcType resolvePlcType(DataPoint point, AdsAddress address) {
        return AdsPlcTypeResolver.INSTANCE.resolveOrNull(point, address);
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureScalar(AdsAddress address, DataPoint point, String operation) {
        if (!address.isScalar()) {
            throw new IllegalArgumentException("ADS " + operation + " does not support array point: " + point.getPointId());
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureResponseOk(PlcTagResponse response, String fieldName, String operation) {
        if (response == null) {
            throw new IllegalStateException("PLC4X ADS " + operation + " returned null response");
        }
        PlcResponseCode code = response.getResponseCode(fieldName);
        if (code != PlcResponseCode.OK) {
            throw new IllegalStateException("PLC4X ADS " + operation + " failed with response code: " + code);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private <T> T await(CompletableFuture<? extends T> future) throws Exception {
        return future.get(timeout, TimeUnit.MILLISECONDS);
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
    private void unsubscribeExisting(List<DataPoint> points) throws Exception {
        List<PlcSubscriptionHandle> existingHandles = new ArrayList<>();
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            PlcSubscriptionHandle handle = subscriptionHandles.remove(resolvePointCacheKey(point));
            if (handle != null) {
                existingHandles.add(handle);
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
        PlcUnsubscriptionRequest.Builder builder = requireConnection().getClient().unsubscriptionRequestBuilder();
        builder.addHandles(handles);
        await(builder.build().execute());
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
    private AdsConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException("PLC4X ADS connection has not been established");
        }
        return connectionAdapter;
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
            throw new IllegalArgumentException("No configured ADS points found for device: "
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

        throw new IllegalArgumentException("Unable to resolve ADS point from command params");
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
    private Integer firstPositive(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean firstBoolean(Boolean... values) {
        if (values == null) {
            return false;
        }
        for (Boolean value : values) {
            if (value != null) {
                return value;
            }
        }
        return false;
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
}

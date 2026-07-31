package com.wangbin.collector.core.collector.protocol.knx;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.knx.domain.KnxAddress;
import com.wangbin.collector.core.collector.protocol.knx.util.KnxAddressParser;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import com.wangbin.collector.core.connection.adapter.KnxNetIpConnectionAdapter;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcSubscriptionEvent;
import org.apache.plc4x.java.api.messages.PlcSubscriptionResponse;
import org.apache.plc4x.java.api.messages.PlcTagResponse;
import org.apache.plc4x.java.api.messages.PlcUnsubscriptionRequest;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.metadata.PlcConnectionMetadata;
import org.apache.plc4x.java.api.model.PlcSubscriptionHandle;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigInteger;
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
public class KnxNetIpCollector extends ConnectionBackedCollector {
    private DevicePointResolver devicePointResolver;

    /**
     * 注入点位解析辅助组件。
     */
    @Autowired(required = false)
    public void setDevicePointResolver(DevicePointResolver devicePointResolver) {
        this.devicePointResolver = devicePointResolver;
    }

    private KnxNetIpConnectionAdapter connectionAdapter;
    private final Map<String, KnxAddress> configuredAddresses = new ConcurrentHashMap<>();
    private final Map<String, PlcSubscriptionHandle> subscriptionHandles = new ConcurrentHashMap<>();
    private int timeout = 10000;
    private int maxFieldsPerRequest = 30;
    private boolean readSupported;
    private boolean writeSupported;
    private boolean subscriptionSupported;

    @Override
    public String getCollectorType() {
        return "KNXNET_IP";
    }

    @Override
    public String getProtocolType() {
        return "KNXNET_IP";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        DeviceConnection desiredConfig = requireConnectionConfig();
        this.connectionAdapter = createAndConnectAdapter(desiredConfig, KnxNetIpConnectionAdapter.class, "KNXnet/IP");

        DeviceConnection currentConfig = getCurrentConnectionConfig();
        if (currentConfig == null) {
            currentConfig = desiredConfig;
        }

        Integer configuredTimeout = firstPositive(
                currentConfig.getInt("requestTimeout", null),
                currentConfig.getInt("request-timeout", null),
                currentConfig.getReadTimeout(),
                currentConfig.getTimeout());
        this.timeout = configuredTimeout != null && configuredTimeout > 0 ? configuredTimeout : 10000;
        this.maxFieldsPerRequest = Math.max(1, currentConfig.getInt("maxFieldsPerRequest", 30));

        PlcConnectionMetadata metadata = requireConnection().getClient().getMetadata();
        this.readSupported = currentConfig.getBool("readEnabled", metadata.isReadSupported());
        this.writeSupported = currentConfig.getBool("writeEnabled", metadata.isWriteSupported());
        this.subscriptionSupported = currentConfig.getBool("subscriptionEnabled", metadata.isSubscribeSupported());

        log.info("PLC4X KNXnet/IP 采集器 已连接, 设备={}, 超时={}, 单次最大字段数={}",
                deviceInfo.getDeviceId(), timeout, maxFieldsPerRequest);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        removeManagedConnection("KNXnet/IP");
        connectionAdapter = null;
        configuredAddresses.clear();
        subscriptionHandles.clear();
        readSupported = false;
        writeSupported = false;
        subscriptionSupported = false;
        log.info("PLC4X KNXnet/IP 采集器 已断开, 设备={}", deviceInfo.getDeviceId());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        ensureReadSupported();
        KnxAddress address = requireAddress(point);
        ensureTypedOrProjectConfigured(address, point, "read");
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

        ensureReadSupported();
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
        ensureWriteSupported();
        KnxAddress address = requireAddress(point);
        ensureTypedOrProjectConfigured(address, point, "write");
        String fieldName = resolvePointTagName(point);

        PlcWriteResponse response = await(requireConnection().getClient()
                .writeRequestBuilder()
                .addTagAddress(fieldName, address.getPlc4xAddress(), coerceWriteValue(value, point))
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

        ensureWriteSupported();
        List<Map.Entry<DataPoint, Object>> batch = new ArrayList<>(Math.min(points.size(), maxFieldsPerRequest));
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            batch.add(entry);
            if (batch.size() >= maxFieldsPerRequest) {
                executeWriteBatch(batch, results);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            executeWriteBatch(batch, results);
        }
        return results;
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
            KnxAddress address = requireAddress(point);
            ensureTypedOrProjectConfigured(address, point, "subscribe");
            String fieldName = resolvePointTagName(point);
            builder.addEventTagAddress(fieldName, address.getPlc4xAddress(),
                    event -> handleSubscriptionEvent(point, fieldName, address, event));
            orderedPoints.add(point);
        }

        PlcSubscriptionResponse response = await(builder.build().execute());
        int registered = 0;
        for (DataPoint point : orderedPoints) {
            String fieldName = resolvePointTagName(point);
            PlcResponseCode responseCode = response != null ? response.getResponseCode(fieldName) : null;
            if (responseCode != PlcResponseCode.OK) {
                log.warn("PLC4X KNXnet/IP 订阅失败, 设备={}, 点位={}, 响应码={}",
                        deviceInfo.getDeviceId(), point.getPointId(), responseCode);
                continue;
            }
            PlcSubscriptionHandle handle = response.getSubscriptionHandle(fieldName);
            if (handle == null) {
                log.warn("PLC4X KNXnet/IP 订阅返回空句柄, 设备={}, 点位={}",
                        deviceInfo.getDeviceId(), point.getPointId());
                continue;
            }
            subscriptionHandles.put(resolvePointCacheKey(point), handle);
            registered++;
        }

        if (registered == 0) {
            throw new IllegalStateException("PLC4X KNXnet/IP subscribe did not register any point");
        }
        log.info("PLC4X KNXnet/IP 订阅已注册, 设备={}, 数量={}",
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
        status.put("protocol", getProtocolType());
        status.put("driver", "PLC4X");
        status.put("implemented", true);
        status.put("readSupported", isRuntimeReadSupported());
        status.put("writable", isRuntimeWriteSupported());
        status.put("subscribable", isRuntimeSubscriptionSupported());
        status.put("isConnected", isConnected());
        status.put("configuredPointCount", configuredAddresses.size());
        status.put("maxFieldsPerRequest", maxFieldsPerRequest);
        status.put("activeSubscriptions", subscriptionHandles.size());

        DeviceConnection connection = getCurrentConnectionConfig();
        if (connection != null) {
            status.put("host", connection.getHost());
            status.put("port", connection.getPort());
            status.put("groupAddressNumLevels", resolveGroupAddressNumLevels(connection));
            status.put("knxConnectionType", resolveKnxConnectionType(connection));
            status.put("requestTimeout", timeout);
            status.put("hasKnxProject", hasKnxProjectConfiguration(connection));
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
            default -> throw new IllegalArgumentException("Unsupported PLC4X KNXnet/IP command: " + command);
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
            configuredAddresses.put(resolvePointCacheKey(point), KnxAddressParser.parse(point));
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private KnxAddress requireAddress(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("Point cannot be null");
        }
        return configuredAddresses.computeIfAbsent(resolvePointCacheKey(point), ignored -> KnxAddressParser.parse(point));
    }

    /**
     * 执行当前业务逻辑。
     */
    private UnsupportedOperationException unsupported(String operation, String reason) {
        String message = String.format("PLC4X KNXnet/IP collector does not implement %s", operation);
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
            log.error("PLC4X KNXnet/IP 批量 读取 失败, 设备={}, 批量数量={}",
                    deviceInfo.getDeviceId(), batch.size(), ex);
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
            KnxAddress address = requireAddress(point);
            ensureTypedOrProjectConfigured(address, point, "read");
            builder.addTagAddress(resolvePointTagName(point), address.getPlc4xAddress());
        }
        return await(builder.build().execute());
    }

    /**
     * 处理当前业务流程。
     */
    private void executeWriteBatch(List<Map.Entry<DataPoint, Object>> batch, Map<String, Boolean> results) {
        try {
            PlcWriteRequest.Builder builder = requireConnection().getClient().writeRequestBuilder();
            List<DataPoint> orderedPoints = new ArrayList<>();

            for (Map.Entry<DataPoint, Object> entry : batch) {
                DataPoint point = entry.getKey();
                if (point == null) {
                    continue;
                }
                KnxAddress address = requireAddress(point);
                ensureTypedOrProjectConfigured(address, point, "write");
                builder.addTagAddress(resolvePointTagName(point), address.getPlc4xAddress(), coerceWriteValue(entry.getValue(), point));
                orderedPoints.add(point);
            }

            PlcWriteResponse response = await(builder.build().execute());
            for (DataPoint point : orderedPoints) {
                String fieldName = resolvePointTagName(point);
                results.put(point.getPointId(), response != null && response.getResponseCode(fieldName) == PlcResponseCode.OK);
            }
        } catch (Exception ex) {
            log.warn("PLC4X KNXnet/IP 批量 写入 失败, 降级为逐点写入:{}", ex.getMessage());
            for (Map.Entry<DataPoint, Object> entry : batch) {
                DataPoint point = entry.getKey();
                if (point == null) {
                    continue;
                }
                try {
                    results.put(point.getPointId(), doWritePoint(point, entry.getValue()));
                } catch (Exception singleEx) {
                    log.error("PLC4X KNXnet/IP 点位 写入 失败, 点位={}", point.getPointId(), singleEx);
                    results.put(point.getPointId(), false);
                }
            }
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void handleSubscriptionEvent(DataPoint point,
                                         String fieldName,
                                         KnxAddress address,
                                         PlcSubscriptionEvent event) {
        try {
            PlcResponseCode responseCode = event != null ? event.getResponseCode(fieldName) : null;
            if (responseCode != PlcResponseCode.OK) {
                log.warn("PLC4X KNXnet/IP 订阅事件 失败, 设备={}, 点位={}, 响应码={}",
                        deviceInfo.getDeviceId(), point.getPointId(), responseCode);
                return;
            }
            Object rawValue = extractValue(event, fieldName, point, address);
            ingestPushedValue(point, rawValue);
        } catch (Exception ex) {
            log.warn("PLC4X KNXnet/IP 订阅事件处理 失败, 设备={}, 点位={}",
                    deviceInfo.getDeviceId(), point.getPointId(), ex);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private Object extractValue(PlcReadResponse response, String fieldName, DataPoint point, KnxAddress address) {
        PlcValue plcValue = response.getPlcValue(fieldName);
        if (plcValue == null || plcValue.isNull()) {
            return null;
        }
        if (plcValue.isList()) {
            if (plcValue.getLength() == 1) {
                plcValue = plcValue.getIndex(0);
            } else {
                throw new IllegalStateException("KNXnet/IP scalar point returned multiple values: " + address.getRawAddress());
            }
        }

        Object raw = plcValue.getObject();
        String pointType = point != null && hasText(point.getDataType())
                ? point.getDataType().trim().toUpperCase(Locale.ROOT)
                : null;
        if (pointType == null) {
            return raw;
        }
        return switch (pointType) {
            case "BOOLEAN", "BOOL" -> toBoolean(raw);
            case "STRING", "CHAR", "WSTRING", "WCHAR" -> Objects.toString(raw, null);
            case "BYTE", "INT8", "SINT" -> ((Number) coerceNumber(raw)).byteValue();
            case "UINT8", "USINT", "SHORT", "INT", "INT16", "UINT16", "UINT", "WORD" ->
                    ((Number) coerceNumber(raw)).intValue();
            case "LONG", "INT32", "DINT", "UINT32", "UDINT", "DWORD", "INT64", "LINT" ->
                    ((Number) coerceNumber(raw)).longValue();
            case "UINT64", "ULINT", "LWORD" -> raw instanceof BigInteger bigInteger
                    ? bigInteger
                    : BigInteger.valueOf(((Number) coerceNumber(raw)).longValue());
            case "FLOAT", "FLOAT32", "FLOAT32_SWAP", "FLOAT32_LITTLE", "REAL" ->
                    ((Number) coerceNumber(raw)).floatValue();
            case "FLOAT64", "FLOAT64_SWAP", "FLOAT64_LITTLE", "DOUBLE", "DOUBLE_SWAP", "LREAL" ->
                    ((Number) coerceNumber(raw)).doubleValue();
            default -> raw;
        };
    }

    /**
     * 执行当前业务逻辑。
     */
    private Object coerceWriteValue(Object value, DataPoint point) {
        if (value == null) {
            return null;
        }
        String pointType = point != null && hasText(point.getDataType())
                ? point.getDataType().trim().toUpperCase(Locale.ROOT)
                : null;
        if (pointType == null) {
            return value;
        }
        return switch (pointType) {
            case "BOOLEAN", "BOOL" -> toBoolean(value);
            case "STRING", "CHAR", "WSTRING", "WCHAR" -> value.toString();
            case "BYTE", "INT8", "SINT" -> ((Number) coerceNumber(value)).byteValue();
            case "UINT8", "USINT", "SHORT", "INT", "INT16", "UINT16", "UINT", "WORD" ->
                    ((Number) coerceNumber(value)).intValue();
            case "LONG", "INT32", "DINT", "UINT32", "UDINT", "DWORD", "INT64", "LINT" ->
                    ((Number) coerceNumber(value)).longValue();
            case "UINT64", "ULINT", "LWORD" -> value instanceof BigInteger bigInteger
                    ? bigInteger
                    : BigInteger.valueOf(((Number) coerceNumber(value)).longValue());
            case "FLOAT", "FLOAT32", "FLOAT32_SWAP", "FLOAT32_LITTLE", "REAL" ->
                    ((Number) coerceNumber(value)).floatValue();
            case "FLOAT64", "FLOAT64_SWAP", "FLOAT64_LITTLE", "DOUBLE", "DOUBLE_SWAP", "LREAL" ->
                    ((Number) coerceNumber(value)).doubleValue();
            default -> value;
        };
    }

    /**
     * 执行当前业务逻辑。
     */
    private Number coerceNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof PlcValue plcValue) {
            return coerceNumber(plcValue.getObject());
        }
        if (value instanceof String text) {
            return text.contains(".") ? Double.parseDouble(text) : Long.parseLong(text);
        }
        throw new IllegalArgumentException("Cannot convert KNXnet/IP value to number: " + value);
    }

    /**
     * 解析或转换业务数据。
     */
    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureTypedOrProjectConfigured(KnxAddress address, DataPoint point, String operation) {
        if (address.hasDpt() || hasKnxProjectConfiguration()) {
            return;
        }
        throw new IllegalArgumentException("KNXnet/IP " + operation
                + " requires explicit DPT in address/additionalConfig or knxprojFilePath for point: "
                + point.getPointId());
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureResponseOk(PlcTagResponse response, String fieldName, String operation) {
        if (response == null) {
            throw new IllegalStateException("PLC4X KNXnet/IP " + operation + " returned null response");
        }
        PlcResponseCode code = response.getResponseCode(fieldName);
        if (code != PlcResponseCode.OK) {
            throw new IllegalStateException("PLC4X KNXnet/IP " + operation + " failed with response code: " + code);
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
    private void ensureReadSupported() {
        readSupported = isRuntimeReadSupported();
        if (!readSupported) {
            throw unsupported("read", "PLC4X metadata reports read unsupported for the current connection");
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureWriteSupported() {
        writeSupported = isRuntimeWriteSupported();
        if (!writeSupported) {
            throw unsupported("write", "PLC4X metadata reports write unsupported for the current connection");
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

    private boolean isRuntimeReadSupported() {
        if (readSupported) {
            return true;
        }
        return connectionAdapter != null
                && connectionAdapter.getClient() != null
                && connectionAdapter.getClient().getMetadata().isReadSupported();
    }

    private boolean isRuntimeWriteSupported() {
        if (writeSupported) {
            return true;
        }
        return connectionAdapter != null
                && connectionAdapter.getClient() != null
                && connectionAdapter.getClient().getMetadata().isWriteSupported();
    }

    private boolean isRuntimeSubscriptionSupported() {
        if (subscriptionSupported) {
            return true;
        }
        return connectionAdapter != null
                && connectionAdapter.getClient() != null
                && connectionAdapter.getClient().getMetadata().isSubscribeSupported();
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
     * 校验业务条件和参数边界。
     */
    private KnxNetIpConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException("PLC4X KNXnet/IP connection has not been established");
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
        result.put("value", value);
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeCommandWrite(Map<String, Object> params) throws Exception {
        DataPoint point = resolveCommandPoint(params);
        if (!params.containsKey("value")) {
            throw new IllegalArgumentException("value is required");
        }
        Object value = params.get("value");
        boolean success = writePoint(point, value);
        Map<String, Object> result = new LinkedHashMap<>();
        populatePointMetadata(result, point);
        result.put("value", value);
        result.put("success", success);
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
            throw new IllegalArgumentException("No configured KNXnet/IP points found for device: "
                    + (deviceInfo != null ? deviceInfo.getDeviceId() : "UNKNOWN"));
        }

        String pointRef = firstNonBlank(
                asText(params.get("pointRef")),
                asText(params.get("pointId")),
                asText(params.get("pointCode")),
                asText(params.get("pointName")),
                asText(params.get("field")),
                asText(params.get("reportField"))
        );
        if (hasText(pointRef)) {
            DataPoint point = resolveConfiguredPoint(points, pointRef);
            if (point != null) {
                return point;
            }
        }

        String address = asText(params.get("address"));
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

        throw new IllegalArgumentException("Unable to resolve KNXnet/IP point from command params");
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
        target.put("pointId", point.getPointId());
        target.put("pointCode", point.getPointCode());
        target.put("pointName", point.getPointName());
        if (point.getAddress() != null) {
            target.put("address", point.getAddress());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasKnxProjectConfiguration() {
        return hasKnxProjectConfiguration(getCurrentConnectionConfig());
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasKnxProjectConfiguration(DeviceConnection connection) {
        String knxprojFilePath = connection != null
                ? firstNonBlank(
                connection.getString("knxprojFilePath", null),
                connection.getString("knxproj-file-path", null))
                : null;
        if (hasText(knxprojFilePath)) {
            return true;
        }
        String connectionString = connectionAdapter != null
                ? connectionAdapter.getConnectionString()
                : connection != null ? connection.getString("plc4xConnectionString", null) : null;
        return hasText(connectionString) && connectionString.contains("knxproj-file-path=");
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveGroupAddressNumLevels(DeviceConnection connection) {
        Integer levels = firstNonNull(
                connection.getInt("groupAddressNumLevels", null),
                connection.getInt("group-address-num-levels", null));
        return levels != null ? levels : 3;
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveKnxConnectionType(DeviceConnection connection) {
        String value = firstNonBlank(
                connection.getString("knxConnectionType", null),
                connection.getString("connection-type", null),
                connection.getString("knxnetIpConnectionType", null));
        return hasText(value) ? value.trim().replace('-', '_').toUpperCase(Locale.ROOT) : "LINK_LAYER";
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
    private Integer firstNonNull(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null) {
                return value;
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
}

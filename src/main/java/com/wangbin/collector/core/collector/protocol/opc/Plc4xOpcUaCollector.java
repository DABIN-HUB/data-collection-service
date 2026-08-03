package com.wangbin.collector.core.collector.protocol.opc;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.collector.protocol.opc.plc4x.domain.Plc4xOpcUaAddress;
import com.wangbin.collector.core.collector.protocol.opc.plc4x.domain.Plc4xOpcUaType;
import com.wangbin.collector.core.collector.protocol.opc.plc4x.util.Plc4xOpcUaAddressParser;
import com.wangbin.collector.core.collector.protocol.opc.plc4x.util.Plc4xOpcUaTypeResolver;
import com.wangbin.collector.core.collector.protocol.plc4x.domain.Plc4xArrayValueSupport;
import com.wangbin.collector.core.connection.adapter.Plc4xOpcUaConnectionAdapter;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.api.messages.PlcBrowseItem;
import org.apache.plc4x.java.api.messages.PlcBrowseResponse;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcSubscriptionEvent;
import org.apache.plc4x.java.api.messages.PlcSubscriptionResponse;
import org.apache.plc4x.java.api.messages.PlcTagResponse;
import org.apache.plc4x.java.api.messages.PlcUnsubscriptionRequest;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.model.ArrayInfo;
import org.apache.plc4x.java.api.model.PlcSubscriptionHandle;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实现当前协议或设备的采集能力。
 */
@Slf4j
public class Plc4xOpcUaCollector extends ConnectionBackedCollector {

    private static final long DEFAULT_SUBSCRIPTION_INTERVAL_MS = 1000L;
    private static final String DEFAULT_BROWSE_NODE = "ns=0;i=84";

    private Plc4xOpcUaConnectionAdapter connectionAdapter;
    private final Map<String, Plc4xOpcUaAddress> configuredAddresses = new ConcurrentHashMap<>();
    private final Map<String, PlcSubscriptionHandle> subscriptionHandles = new ConcurrentHashMap<>();
    private int timeout = 10000;
    private int maxFieldsPerRequest = 100;
    private boolean subscriptionSupported;
    private boolean browseSupported;
    private final AtomicLong subscriptionEventCount = new AtomicLong();
    private volatile Long lastSubscriptionEventTs;
    private volatile String lastSubscriptionPointId;
    private volatile String lastSubscriptionPointCode;
    private volatile String lastSubscriptionError;

    @Override
    public String getCollectorType() {
        return declaredProtocolType();
    }

    @Override
    public String getProtocolType() {
        return declaredProtocolType();
    }

    /**
     * 执行当前业务逻辑。
     */
    private String declaredProtocolType() {
        if (deviceInfo == null || deviceInfo.getProtocolType() == null) {
            return "OPC_UA";
        }
        String normalized = deviceInfo.getProtocolType().trim().toUpperCase(Locale.ROOT).replace("-", "_");
        return "OPC_UA_PLC4X".equals(normalized) || "OPCUA_PLC4X".equals(normalized)
                ? "OPC_UA_PLC4X"
                : "OPC_UA";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        DeviceConnection desiredConfig = requireConnectionConfig();
        this.connectionAdapter = createAndConnectAdapter(desiredConfig, Plc4xOpcUaConnectionAdapter.class, "PLC4X OPC UA");

        DeviceConnection currentConfig = getCurrentConnectionConfig();
        if (currentConfig == null) {
            currentConfig = desiredConfig;
        }

        Integer configuredTimeout = currentConfig.getReadTimeout() != null
                ? currentConfig.getReadTimeout()
                : currentConfig.getTimeout();
        this.timeout = configuredTimeout != null && configuredTimeout > 0 ? configuredTimeout : 10000;
        this.maxFieldsPerRequest = Math.max(1, currentConfig.getInt("maxFieldsPerRequest", 100));
        this.subscriptionSupported = currentConfig.getBool("subscriptionEnabled",
                requireConnection().getClient().getMetadata().isSubscribeSupported());
        this.browseSupported = requireConnection().getClient().getMetadata().isBrowseSupported();
        resetSubscriptionDiagnostics();
        log.info("PLC4X OPC UA 采集器 已连接, 设备={}, 超时={}, 单次最大字段数={}",
                deviceInfo.getDeviceId(), timeout, maxFieldsPerRequest);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        removeManagedConnection("PLC4X OPC UA");
        connectionAdapter = null;
        configuredAddresses.clear();
        subscriptionHandles.clear();
        subscriptionSupported = false;
        browseSupported = false;
        resetSubscriptionDiagnostics();
        log.info("PLC4X OPC UA 采集器 已断开, 设备={}", deviceInfo.getDeviceId());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        Plc4xOpcUaAddress address = requireAddress(point);
        String fieldName = resolvePointTagName(point);

        PlcReadResponse response = await(requireConnection().getClient()
                .readRequestBuilder()
                .addTagAddress(fieldName, address.getPlc4xAddress())
                .build()
                .execute());
        ensureResponseOk(response, fieldName, "read");
        return extractValue(response.getPlcValue(fieldName), point, address);
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
        Plc4xOpcUaAddress address = requireAddress(point);
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
                Plc4xOpcUaAddress address = requireAddress(point);
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
            log.warn("PLC4X OPC UA 批量 写入 失败, 降级为逐点写入:{}", ex.getMessage());
            for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
                DataPoint point = entry.getKey();
                if (point == null) {
                    continue;
                }
                try {
                    results.put(point.getPointId(), doWritePoint(point, entry.getValue()));
                } catch (Exception singleEx) {
                    log.error("PLC4X OPC UA 点位 写入 失败, 点位={}", point.getPointId(), singleEx);
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
            Plc4xOpcUaAddress address = requireAddress(point);
            ensureScalar(address, point, "subscribe");
            builder.addCyclicTagAddress(
                    resolvePointTagName(point),
                    address.getPlc4xAddress(),
                    resolveSubscriptionInterval(point, address),
                    event -> handleSubscriptionEvent(point, resolvePointTagName(point), address, event));
            orderedPoints.add(point);
        }

        PlcSubscriptionResponse response = await(builder.build().execute());
        int registered = 0;
        for (DataPoint point : orderedPoints) {
            String fieldName = resolvePointTagName(point);
            PlcResponseCode responseCode = response != null ? response.getResponseCode(fieldName) : null;
            if (responseCode != PlcResponseCode.OK) {
                log.warn("PLC4X OPC UA 订阅失败, 设备={}, 点位={}, 响应码={}",
                        deviceInfo.getDeviceId(), point.getPointId(), responseCode);
                continue;
            }
            PlcSubscriptionHandle handle = response.getSubscriptionHandle(fieldName);
            if (handle == null) {
                log.warn("PLC4X OPC UA 订阅返回空句柄, 设备={}, 点位={}",
                        deviceInfo.getDeviceId(), point.getPointId());
                continue;
            }
            subscriptionHandles.put(resolvePointCacheKey(point), handle);
            registered++;
        }

        if (registered == 0) {
            throw new IllegalStateException("PLC4X OPC UA subscribe did not register any point");
        }
        log.info("PLC4X OPC UA 订阅已注册, 设备={}, 数量={}",
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
        status.put("browseable", isRuntimeBrowseSupported());
        status.put("parallelValidation", true);
        status.put(CommonMapKeys.IS_CONNECTED, isConnected());
        status.put(CommonMapKeys.CONFIGURED_POINT_COUNT, configuredAddresses.size());
        status.put("maxFieldsPerRequest", maxFieldsPerRequest);
        status.put("activeSubscriptions", subscriptionHandles.size());
        status.put("subscriptionEventCount", subscriptionEventCount.get());
        status.put("lastSubscriptionEventTs", lastSubscriptionEventTs);
        status.put("lastSubscriptionPointId", lastSubscriptionPointId);
        status.put("lastSubscriptionPointCode", lastSubscriptionPointCode);
        status.put("lastSubscriptionError", lastSubscriptionError);

        DeviceConnection connection = getCurrentConnectionConfig();
        if (connection != null) {
            status.put(CommonMapKeys.HOST, connection.getHost());
            status.put(CommonMapKeys.PORT, connection.getPort());
            status.put("url", firstNonBlank(
                    connection.getUrl(),
                    connection.getString("endpointUrl", null),
                    connection.getString("endpoint", null)));
            status.put("discovery", connection.getBool("discovery", null));
            status.put("securityPolicy", connection.getString("securityPolicy", null));
            status.put("messageSecurity", firstNonBlank(
                    connection.getString("messageSecurity", null),
                    connection.getString("securityMode", null)));
            status.put("requestTimeout", connection.getReadTimeout() != null
                    ? connection.getReadTimeout()
                    : connection.getTimeout());
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
            case "read", "read_node", "readnode" -> executeCommandRead(safeParams);
            case "write", "write_node", "writenode" -> executeCommandWrite(safeParams);
            case "browse" -> executeCommandBrowse(safeParams);
            case "status", "diagnostic" -> getDeviceStatus();
            default -> throw new IllegalArgumentException("Unsupported PLC4X OPC UA command: " + command);
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
            configuredAddresses.put(resolvePointCacheKey(point), Plc4xOpcUaAddressParser.parse(point));
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private Plc4xOpcUaAddress requireAddress(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("Point cannot be null");
        }
        return configuredAddresses.computeIfAbsent(resolvePointCacheKey(point), ignored -> Plc4xOpcUaAddressParser.parse(point));
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
                results.put(point.getPointId(), extractValue(response.getPlcValue(resolvePointTagName(point)), point, requireAddress(point)));
            }
        } catch (Exception ex) {
            log.warn("PLC4X OPC UA 批量读取失败，降级为逐点读取：{}", ex.getMessage());
            for (DataPoint point : batch) {
                if (point == null || point.getPointId() == null) {
                    continue;
                }
                try {
                    results.put(point.getPointId(), doReadPoint(point));
                } catch (Exception singleEx) {
                    log.error("PLC4X OPC UA 点位 读取 失败, 点位={}", point.getPointId(), singleEx);
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
            Plc4xOpcUaAddress address = requireAddress(point);
            builder.addTagAddress(resolvePointTagName(point), address.getPlc4xAddress());
        }
        PlcReadResponse response = await(builder.build().execute());
        for (DataPoint point : batch) {
            if (point == null) {
                continue;
            }
            ensureResponseOk(response, resolvePointTagName(point), "read");
        }
        return response;
    }

    /**
     * 处理当前业务流程。
     */
    private void handleSubscriptionEvent(DataPoint point,
                                         String fieldName,
                                         Plc4xOpcUaAddress address,
                                         PlcSubscriptionEvent event) {
        try {
            PlcResponseCode responseCode = event.getResponseCode(fieldName);
            if (responseCode != PlcResponseCode.OK) {
                lastSubscriptionError = "responseCode=" + responseCode;
                log.warn("PLC4X OPC UA 订阅事件 失败, 设备={}, 点位={}, 响应码={}",
                        deviceInfo.getDeviceId(), point.getPointId(), responseCode);
                return;
            }
            Object rawValue = extractValue(event.getPlcValue(fieldName), point, address);
            ingestPushedValue(point, rawValue);
            subscriptionEventCount.incrementAndGet();
            lastSubscriptionEventTs = System.currentTimeMillis();
            lastSubscriptionPointId = point.getPointId();
            lastSubscriptionPointCode = point.getPointCode();
            lastSubscriptionError = null;
        } catch (Exception ex) {
            lastSubscriptionError = ex.getMessage();
            log.warn("PLC4X OPC UA 订阅事件处理 失败, 设备={}, 点位={}",
                    deviceInfo.getDeviceId(), point.getPointId(), ex);
        }
    }

    /**
     * 记录或统计业务状态。
     */
    private void resetSubscriptionDiagnostics() {
        subscriptionEventCount.set(0L);
        lastSubscriptionEventTs = null;
        lastSubscriptionPointId = null;
        lastSubscriptionPointCode = null;
        lastSubscriptionError = null;
    }

    /**
     * 解析或转换业务数据。
     */
    private Object extractValue(PlcValue plcValue, DataPoint point, Plc4xOpcUaAddress address) {
        Plc4xOpcUaType pointType = resolvePointType(point, address);
        return Plc4xArrayValueSupport.decode(plcValue, address.getArraySize(),
                value -> pointType != null ? pointType.read(value) : extractDefaultValue(value),
                "OPC UA", address.getRawAddress());
    }

    /**
     * 解析或转换业务数据。
     */
    private Object extractDefaultValue(PlcValue plcValue) {
        if (plcValue.isBoolean()) {
            return plcValue.getBoolean();
        }
        if (plcValue.isByte()) {
            return plcValue.getByte();
        }
        if (plcValue.isInteger()) {
            return plcValue.getInteger();
        }
        if (plcValue.isLong()) {
            return plcValue.getLong();
        }
        if (plcValue.isBigInteger()) {
            return plcValue.getBigInteger();
        }
        if (plcValue.isFloat()) {
            return plcValue.getFloat();
        }
        if (plcValue.isDouble()) {
            return plcValue.getDouble();
        }
        if (plcValue.isString()) {
            return plcValue.getString();
        }
        if (plcValue.isDuration()) {
            return plcValue.getDuration();
        }
        if (plcValue.isDate()) {
            return plcValue.getDate();
        }
        if (plcValue.isDateTime()) {
            return plcValue.getDateTime();
        }
        return plcValue.getObject();
    }

    /**
     * 执行当前业务逻辑。
     */
    private Object coerceWriteValue(Object value, Plc4xOpcUaAddress address, DataPoint point) {
        Plc4xOpcUaType pointType = resolvePointType(point, address);
        return Plc4xArrayValueSupport.encode(value, address.getArraySize(),
                item -> pointType != null ? pointType.write(item) : item, "OPC UA");
    }

    /**
     * 解析或转换业务数据。
     */
    private Plc4xOpcUaType resolvePointType(DataPoint point, Plc4xOpcUaAddress address) {
        return Plc4xOpcUaTypeResolver.INSTANCE.resolveOrNull(point, address);
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeCommandRead(Map<String, Object> params) throws Exception {
        List<String> nodeIds = extractNodeIds(params);
        if (nodeIds.isEmpty()) {
            throw new IllegalArgumentException("nodeId or nodeIds is required");
        }

        String dataType = firstNonBlank(
                Objects.toString(params.get("dataType"), null),
                Objects.toString(params.get("opcUaType"), null));
        var builder = requireConnection().getClient().readRequestBuilder();
        List<Plc4xOpcUaAddress> addresses = new ArrayList<>(nodeIds.size());
        for (int i = 0; i < nodeIds.size(); i++) {
            Plc4xOpcUaAddress address = Plc4xOpcUaAddressParser.parse(nodeIds.get(i), dataType);
            String fieldName = "node" + i;
            builder.addTagAddress(fieldName, address.getPlc4xAddress());
            addresses.add(address);
        }

        PlcReadResponse response = await(builder.build().execute());
        List<Map<String, Object>> results = new ArrayList<>(nodeIds.size());
        for (int i = 0; i < nodeIds.size(); i++) {
            String fieldName = "node" + i;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("nodeId", nodeIds.get(i));
            entry.put(CommonMapKeys.VALUE, extractValue(response.getPlcValue(fieldName), null, addresses.get(i)));
            entry.put(CommonMapKeys.STATUS, response.getResponseCode(fieldName) != null
                    ? response.getResponseCode(fieldName).name()
                    : null);
            results.add(entry);
        }
        return results;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeCommandWrite(Map<String, Object> params) throws Exception {
        String nodeId = firstNonBlank(
                Objects.toString(params.get("nodeId"), null),
                Objects.toString(params.get(CommonMapKeys.ADDRESS), null));
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId is required");
        }
        if (!params.containsKey(CommonMapKeys.VALUE)) {
            throw new IllegalArgumentException("value is required");
        }

        String dataType = firstNonBlank(
                Objects.toString(params.get("dataType"), null),
                Objects.toString(params.get("opcUaType"), null));
        Plc4xOpcUaAddress address = Plc4xOpcUaAddressParser.parse(nodeId, dataType);
        PlcWriteResponse response = await(requireConnection().getClient()
                .writeRequestBuilder()
                .addTagAddress("node", address.getPlc4xAddress(), coerceWriteValue(params.get(CommonMapKeys.VALUE), address, null))
                .build()
                .execute());
        ensureResponseOk(response, "node", "write");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", nodeId);
        result.put(CommonMapKeys.STATUS, "success");
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeCommandBrowse(Map<String, Object> params) throws Exception {
        ensureBrowseSupported();
        String query = firstNonBlank(
                Objects.toString(params.get("query"), null),
                Objects.toString(params.get("nodeId"), null),
                Objects.toString(params.get(CommonMapKeys.ADDRESS), null),
                DEFAULT_BROWSE_NODE);
        String normalizedQuery = Plc4xOpcUaAddressParser.parse(query).getPlc4xAddress();

        PlcBrowseResponse response = await(requireConnection().getClient()
                .browseRequestBuilder()
                .addQuery("browse", normalizedQuery)
                .build()
                .execute());
        PlcResponseCode responseCode = response.getResponseCode("browse");
        if (responseCode != PlcResponseCode.OK) {
            throw new IllegalStateException("PLC4X OPC UA browse failed with response code: " + responseCode);
        }

        List<PlcBrowseItem> browseItems = response.getValues("browse");
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (browseItems == null) {
            return nodes;
        }
        for (PlcBrowseItem item : browseItems) {
            nodes.add(toBrowseNode(item));
        }
        return nodes;
    }

    /**
     * 解析或转换业务数据。
     */
    private Map<String, Object> toBrowseNode(PlcBrowseItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(CommonMapKeys.NAME, item.getName());
        result.put("tagAddress", item.getTag() != null ? item.getTag().toString() : null);
        result.put("readable", item.isReadable());
        result.put(CommonMapKeys.WRITABLE, item.isWritable());
        result.put(CommonMapKeys.SUBSCRIBABLE, item.isSubscribable());
        result.put("publishable", item.isPublishable());
        result.put("array", item.isArray());
        result.put("childrenCount", item.getChildren() != null ? item.getChildren().size() : 0);

        if (item.isArray() && item.getArrayInformation() != null) {
            List<Map<String, Object>> arrayBounds = new ArrayList<>();
            for (ArrayInfo info : item.getArrayInformation()) {
                Map<String, Object> bounds = new LinkedHashMap<>();
                bounds.put("lowerBound", info.getLowerBound());
                bounds.put("upperBound", info.getUpperBound());
                arrayBounds.add(bounds);
            }
            result.put("arrayBounds", arrayBounds);
        }

        if (item.getChildren() != null && !item.getChildren().isEmpty()) {
            result.put("children", new ArrayList<>(item.getChildren().keySet()));
        }
        if (item.getOptions() != null && !item.getOptions().isEmpty()) {
            Map<String, Object> options = new LinkedHashMap<>();
            item.getOptions().forEach((key, value) -> options.put(key, value != null ? value.getObject() : null));
            result.put("options", options);
        }
        return result;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureScalar(Plc4xOpcUaAddress address, DataPoint point, String operation) {
        if (!address.isScalar()) {
            throw new IllegalArgumentException("PLC4X OPC UA " + operation + " does not support array point: " + point.getPointId());
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureResponseOk(PlcTagResponse response, String fieldName, String operation) {
        if (response == null) {
            throw new IllegalStateException("PLC4X OPC UA " + operation + " returned null response");
        }
        PlcResponseCode code = response.getResponseCode(fieldName);
        if (code != PlcResponseCode.OK) {
            throw new IllegalStateException("PLC4X OPC UA " + operation + " failed with response code: " + code);
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
            throw new UnsupportedOperationException(
                    "PLC4X OPC UA subscribe is unsupported for the current connection metadata");
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureBrowseSupported() {
        browseSupported = isRuntimeBrowseSupported();
        if (!browseSupported) {
            throw new UnsupportedOperationException(
                    "PLC4X OPC UA browse is unsupported for the current connection metadata");
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

    private boolean isRuntimeBrowseSupported() {
        if (browseSupported) {
            return true;
        }
        if (connectionAdapter == null || connectionAdapter.getClient() == null) {
            return false;
        }
        return connectionAdapter.getClient().getMetadata().isBrowseSupported();
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
        CompletableFuture<?> future = builder.build().execute();
        if (future == null) {
            log.debug("PLC4X OPC UA 取消订阅返回空 Future，设备={}，数量={}",
                    deviceInfo != null ? deviceInfo.getDeviceId() : null, handles.size());
            return;
        }
        await(future);
    }

    /**
     * 解析或转换业务数据。
     */
    private Duration resolveSubscriptionInterval(DataPoint point, Plc4xOpcUaAddress address) {
        long intervalMs = address != null && address.getSamplingInterval() > 0
                ? Math.round(address.getSamplingInterval())
                : point != null && point.getBaseCollectionInterval() != null && point.getBaseCollectionInterval() > 0
                ? point.getBaseCollectionInterval()
                : deviceInfo != null && deviceInfo.getCollectionInterval() != null && deviceInfo.getCollectionInterval() > 0
                ? deviceInfo.getCollectionInterval()
                : DEFAULT_SUBSCRIPTION_INTERVAL_MS;
        return Duration.ofMillis(Math.max(100L, intervalMs));
    }

    /**
     * 校验业务条件和参数边界。
     */
    private Plc4xOpcUaConnectionAdapter requireConnection() {
        if (connectionAdapter == null) {
            throw new IllegalStateException("PLC4X OPC UA connection has not been established");
        }
        return connectionAdapter;
    }

    /**
     * 解析或转换业务数据。
     */
    private List<String> extractNodeIds(Map<String, Object> params) {
        Object multi = params.get("nodeIds");
        if (multi instanceof Collection<?> collection && !collection.isEmpty()) {
            List<String> values = new ArrayList<>(collection.size());
            for (Object entry : collection) {
                if (entry != null) {
                    values.add(entry.toString());
                }
            }
            if (!values.isEmpty()) {
                return values;
            }
        }
        Object addresses = params.get("addresses");
        if (addresses instanceof Collection<?> collection && !collection.isEmpty()) {
            List<String> values = new ArrayList<>(collection.size());
            for (Object entry : collection) {
                if (entry != null) {
                    values.add(entry.toString());
                }
            }
            if (!values.isEmpty()) {
                return values;
            }
        }
        String single = firstNonBlank(
                Objects.toString(params.get("nodeId"), null),
                Objects.toString(params.get(CommonMapKeys.ADDRESS), null));
        return single != null ? List.of(single) : Collections.emptyList();
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeCommand(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        return command.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }


    /**
     * 执行当前业务逻辑。
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

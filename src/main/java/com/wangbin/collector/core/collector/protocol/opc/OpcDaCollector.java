package com.wangbin.collector.core.collector.protocol.opc;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.BaseCollector;
import com.wangbin.collector.core.collector.protocol.opc.da.InMemoryOpcDaBridge;
import com.wangbin.collector.core.collector.protocol.opc.da.OpcDaBridgeMode;
import com.wangbin.collector.core.collector.protocol.opc.da.OpcDaBridge;
import com.wangbin.collector.core.collector.protocol.opc.da.OpcDaConfig;
import com.wangbin.collector.core.collector.protocol.opc.da.RemoteOpcDaBridge;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OPC DA 采集器.
 *
 * 说明：
 * - Follows the same 采集器 lifecycle and method layout as OpcUaCollector.
 * - Current implementation uses an in-memory bridge to keep architecture 超级表
 * 不额外引入 OPC DA 运行时依赖。
 * - Real OPC DA integration can replace com.wangbin.采集器.core.采集器.协议.opc.da.OpcDaBridge implementation
 * without changing 采集器-级别 behavior.
 */
@Slf4j
public class OpcDaCollector extends BaseCollector {

    private final Map<String, String> addressCache = new ConcurrentHashMap<>();
    private final Map<String, String> subscribedItems = new ConcurrentHashMap<>();
    private final Map<String, Object> latestValues = new ConcurrentHashMap<>();

    private OpcDaBridge bridge = new InMemoryOpcDaBridge();

    private String serverProgId;
    private String host;
    private String endpoint;
    private String username;
    private String password;
    private String domain;
    private int requestTimeout = 5000;
    private int updateRate = 1000;
    private OpcDaBridgeMode bridgeMode = OpcDaBridgeMode.INMEMORY;
    private String bridgeBaseUrl;
    private String bridgeToken;
    private int bridgeRetryCount = 1;
    private long bridgeRetryBackoffMs = 200L;

    @Override
    public String getCollectorType() {
        return "OPC_DA";
    }

    @Override
    public String getProtocolType() {
        return "OPC_DA";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        DeviceConnection connection = requireConnectionConfig();
        initOpcDaConfig(connection);
        bridge.connect(new OpcDaConfig(
                serverProgId,
                host,
                endpoint,
                username,
                password,
                domain,
                requestTimeout,
                updateRate,
                bridgeMode.name(),
                bridgeBaseUrl,
                bridgeToken,
                bridgeRetryCount,
                bridgeRetryBackoffMs
        ));
        log.info("OPC DA连接建立成功: 设备={} serverProgId={} host={}",
                deviceInfo.getDeviceId(), serverProgId, host);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        try {
            bridge.disconnect();
        } finally {
            subscribedItems.clear();
            latestValues.clear();
            addressCache.clear();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        String itemId = resolveItemId(point);
        Object value = bridge.read(itemId);
        if (value != null) {
            latestValues.put(point.getPointId(), value);
        }
        return value;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) throws Exception {
        if (points == null || points.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> pointItemMap = new LinkedHashMap<>();
        List<String> itemIds = new ArrayList<>(points.size());
        for (DataPoint point : points) {
            String itemId = resolveItemId(point);
            itemIds.add(itemId);
            pointItemMap.put(point.getPointId(), itemId);
        }

        Map<String, Object> itemValues = bridge.readBatch(itemIds);
        Map<String, Object> result = new HashMap<>(pointItemMap.size());
        for (Map.Entry<String, String> entry : pointItemMap.entrySet()) {
            Object value = itemValues.get(entry.getValue());
            result.put(entry.getKey(), value);
            if (value != null) {
                latestValues.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
        String itemId = resolveItemId(point);
        boolean success = bridge.write(itemId, value);
        if (success) {
            latestValues.put(point.getPointId(), value);
        }
        return success;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) throws Exception {
        if (points == null || points.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Boolean> results = new HashMap<>(points.size());
        for (Map.Entry<DataPoint, Object> entry : points.entrySet()) {
            boolean success = doWritePoint(entry.getKey(), entry.getValue());
            results.put(entry.getKey().getPointId(), success);
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
        List<String> itemIds = new ArrayList<>();
        for (DataPoint point : points) {
            String itemId = resolveItemId(point);
            subscribedItems.put(point.getPointId(), itemId);
            itemIds.add(itemId);
        }
        bridge.subscribe(itemIds);
        log.info("OPC DA订阅完成: 设备={} subscribedItems={}", deviceInfo.getDeviceId(), subscribedItems.size());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) throws Exception {
        if (points == null || points.isEmpty()) {
            bridge.unsubscribe(new ArrayList<>(subscribedItems.values()));
            subscribedItems.clear();
            return;
        }
        List<String> itemIds = new ArrayList<>();
        for (DataPoint point : points) {
            String removed = subscribedItems.remove(point.getPointId());
            if (removed != null) {
                itemIds.add(removed);
            }
        }
        if (!itemIds.isEmpty()) {
            bridge.unsubscribe(itemIds);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() throws Exception {
        Map<String, Object> status = new HashMap<>();
        status.put("protocol", getProtocolType());
        status.put("connected", bridge.isConnected());
        status.put("serverProgId", serverProgId);
        status.put("host", host);
        status.put("endpoint", endpoint);
        status.put("requestTimeout", requestTimeout);
        status.put("updateRate", updateRate);
        status.put("bridgeMode", bridgeMode.name());
        status.put("bridgeBaseUrl", bridgeBaseUrl);
        status.put("cachedPoints", addressCache.size());
        status.put("subscribedItems", subscribedItems.size());
        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) throws Exception {
        String normalized = command != null ? command.toLowerCase(Locale.ROOT) : "";
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        return switch (normalized) {
            case "read" -> executeReadCommand(safeParams);
            case "write" -> executeWriteCommand(safeParams);
            case "browse" -> executeBrowseCommand(safeParams);
            default -> throw new IllegalArgumentException("Unsupported OPC DA command: " + command);
        };
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        addressCache.clear();
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            addressCache.put(point.getPointId(), normalizeItemId(point));
        }
        log.info("OPC DA点位缓存完成: 设备={} 数量={}", deviceId, addressCache.size());
    }

    /**
     * 处理组件生命周期。
     */
    private void initOpcDaConfig(DeviceConnection connection) {
        endpoint = connection.getUrl();
        host = firstNonBlank(connection.getHost(), connection.getString("host", null), "localhost");
        serverProgId = firstNonBlank(
                connection.getString("serverProgId", null),
                connection.getString("progId", null),
                connection.getString("clsid", null),
                "Matrikon.OPC.Simulation.1"
        );
        username = connection.getUsername();
        password = connection.getPassword();
        domain = connection.getString("domain", null);
        requestTimeout = connection.getInt("requestTimeout", 5000);
        if (requestTimeout <= 0) {
            requestTimeout = 5000;
        }
        updateRate = connection.getInt("updateRate", 1000);
        if (updateRate <= 0) {
            updateRate = 1000;
        }
        bridgeMode = OpcDaBridgeMode.from(firstNonBlank(
                connection.getString("bridgeMode", null),
                connection.getString("bridge-mode", null),
                connection.getString("opcDaBridgeMode", null)
        ));
        bridgeBaseUrl = firstNonBlank(
                connection.getString("bridgeBaseUrl", null),
                connection.getString("bridge-url", null),
                connection.getString("opcDaBridgeUrl", null),
                endpoint
        );
        bridgeToken = firstNonBlank(
                connection.getString("bridgeToken", null),
                connection.getString("bridge-token", null),
                connection.getString("opcDaBridgeToken", null)
        );
        bridgeRetryCount = connection.getInt("bridgeRetryCount", 1);
        if (bridgeRetryCount < 0) {
            bridgeRetryCount = 1;
        }
        Long retryBackoff = connection.getLong("bridgeRetryBackoffMs", 200L);
        bridgeRetryBackoffMs = retryBackoff == null || retryBackoff < 0 ? 200L : retryBackoff;
        bridge = createBridge(bridgeMode);
    }

    /**
     * 创建并返回业务对象。
     */
    private OpcDaBridge createBridge(OpcDaBridgeMode mode) {
        if (mode == OpcDaBridgeMode.HTTP) {
            return new RemoteOpcDaBridge();
        }
        return new InMemoryOpcDaBridge();
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveItemId(DataPoint point) {
        return addressCache.computeIfAbsent(point.getPointId(), key -> normalizeItemId(point));
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeItemId(DataPoint point) {
        String itemId = point.getAddress();
        if (itemId == null || itemId.isBlank()) {
            itemId = firstNonBlank(point.getPointCode(), point.getPointName(), point.getPointId());
        }
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("OPC DA 点位项为空，点位=" + point.getPointId());
        }
        return itemId.trim();
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeReadCommand(Map<String, Object> params) throws Exception {
        List<String> itemIds = extractItemIds(params);
        if (itemIds.isEmpty()) {
            throw new IllegalArgumentException("itemId or itemIds is required");
        }
        Map<String, Object> values = bridge.readBatch(itemIds);
        List<Map<String, Object>> result = new ArrayList<>(itemIds.size());
        for (String itemId : itemIds) {
            result.add(Map.of(
                    "itemId", itemId,
                    "value", values.get(itemId)
            ));
        }
        return result;
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeWriteCommand(Map<String, Object> params) throws Exception {
        String itemId = Objects.toString(params.get("itemId"), "").trim();
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId is required");
        }
        if (!params.containsKey("value")) {
            throw new IllegalArgumentException("value is required");
        }
        Object value = params.get("value");
        boolean success = bridge.write(itemId, value);
        return Map.of("itemId", itemId, "status", success ? "success" : "failed");
    }

    /**
     * 处理当前业务流程。
     */
    private Object executeBrowseCommand(Map<String, Object> params) throws Exception {
        String branch = Objects.toString(params.getOrDefault("branch", ""), "");
        return bridge.browse(branch);
    }

    /**
     * 解析或转换业务数据。
     */
    private List<String> extractItemIds(Map<String, Object> params) {
        Object multi = params.get("itemIds");
        if (multi instanceof Collection<?> collection && !collection.isEmpty()) {
            List<String> ids = new ArrayList<>(collection.size());
            for (Object item : collection) {
                if (item != null) {
                    ids.add(item.toString());
                }
            }
            return ids;
        }
        Object single = params.get("itemId");
        if (single != null) {
            return List.of(single.toString());
        }
        return Collections.emptyList();
    }

    /**
     * 执行当前业务逻辑。
     */
    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

}

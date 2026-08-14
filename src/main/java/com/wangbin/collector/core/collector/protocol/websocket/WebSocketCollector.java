package com.wangbin.collector.core.collector.protocol.websocket;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.connection.adapter.WebSocketConnectionAdapter;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实现当前协议或设备的采集能力。
 */
@Slf4j
public class WebSocketCollector extends ConnectionBackedCollector {

    private WebSocketConnectionAdapter webSocketConnection;

    private final Map<String, DataPoint> pointDefinitions = new ConcurrentHashMap<>();
    private final Map<String, Object> latestValues = new ConcurrentHashMap<>();
    private final Map<String, Long> latestTimestamps = new ConcurrentHashMap<>();

    @Override
    public String getCollectorType() {
        return "WEBSOCKET";
    }

    @Override
    public String getProtocolType() {
        return "WEBSOCKET";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        DeviceConnection connectionConfig = prepareConnectionConfig();
        this.webSocketConnection = createAndConnectAdapter(connectionConfig, WebSocketConnectionAdapter.class, "WebSocket");
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        removeManagedConnection("WebSocket");

        webSocketConnection = null;
        latestValues.clear();
        latestTimestamps.clear();
        pointDefinitions.clear();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) {
        try {
            drainInboundMessages();
            Object cached = latestValues.get(point.getPointId());
            if (cached != null) {
                return cached;
            }

            JSONObject request = new JSONObject(new LinkedHashMap<>());
            request.put("action", "read");
            request.put(CommonMapKeys.DEVICE_ID, deviceInfo != null ? deviceInfo.getDeviceId() : null);
            request.put(CommonMapKeys.POINT_ID, point.getPointId());
            request.put(CommonMapKeys.POINT_CODE, point.getPointCode());
            request.put(CommonMapKeys.ADDRESS, point.getAddress());
            request.put(CommonMapKeys.TIMESTAMP, System.currentTimeMillis());
            webSocketConnection.send(request.toJSONString().getBytes(StandardCharsets.UTF_8));

            byte[] response = receiveOnce();
            applyInboundPayload(response);
            return latestValues.get(point.getPointId());
        } catch (Exception e) {
            log.error("WebSocket 读取 点位 失败, 点位={}", point.getPointId(), e);
            return latestValues.get(point.getPointId());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
        Map<String, Object> results = new HashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }

        try {
            drainInboundMessages();

            JSONObject request = new JSONObject(new LinkedHashMap<>());
            request.put("action", "batchRead");
            request.put(CommonMapKeys.DEVICE_ID, deviceInfo != null ? deviceInfo.getDeviceId() : null);
            request.put(CommonMapKeys.TIMESTAMP, System.currentTimeMillis());

            JSONArray pointArray = new JSONArray();
            for (DataPoint point : points) {
                JSONObject p = new JSONObject(new LinkedHashMap<>());
                p.put(CommonMapKeys.POINT_ID, point.getPointId());
                p.put(CommonMapKeys.POINT_CODE, point.getPointCode());
                p.put(CommonMapKeys.ADDRESS, point.getAddress());
                pointArray.add(p);
            }
            request.put("points", pointArray);

            webSocketConnection.send(request.toJSONString().getBytes(StandardCharsets.UTF_8));
            applyInboundPayload(receiveOnce());
            drainInboundMessages();

            for (DataPoint point : points) {
                results.put(point.getPointId(), latestValues.get(point.getPointId()));
            }
            return results;
        } catch (Exception e) {
            log.error("WebSocket 批量 读取 失败, 数量={}", points.size(), e);
            for (DataPoint point : points) {
                results.put(point.getPointId(), latestValues.get(point.getPointId()));
            }
            return results;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) {
        try {
            String message = buildWriteMessage(point, value);
            webSocketConnection.send(message.getBytes(StandardCharsets.UTF_8));
            latestValues.put(point.getPointId(), value);
            latestTimestamps.put(point.getPointId(), System.currentTimeMillis());

            byte[] ack = receiveOnce();
            if (ack == null || ack.length == 0) {
                return true;
            }
            return parseAck(ack);
        } catch (Exception e) {
            log.error("WebSocket 写入 点位 失败, 点位={}", point.getPointId(), e);
            return false;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
        Map<String, Boolean> results = new HashMap<>();
        if (points == null || points.isEmpty()) {
            return results;
        }

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
    protected void doSubscribe(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }

        for (DataPoint point : points) {
            pointDefinitions.put(point.getPointId(), point);
            try {
                String subscribeMessage = buildSubscribeMessage(point);
                webSocketConnection.send(subscribeMessage.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.error("WebSocket 订阅失败，点位={}", point.getPointId(), e);
            }
        }
        drainInboundSilently();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            pointDefinitions.clear();
            latestValues.clear();
            latestTimestamps.clear();
            return;
        }

        for (DataPoint point : points) {
            pointDefinitions.remove(point.getPointId());
            try {
                String unsubscribeMessage = buildUnsubscribeMessage(point);
                webSocketConnection.send(unsubscribeMessage.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.error("WebSocket 取消订阅失败, 点位={}", point.getPointId(), e);
            }
        }
        drainInboundSilently();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put(CommonMapKeys.IS_CONNECTED, isConnected());
        status.put("protocolType", getProtocolType());
        status.put(CommonMapKeys.POINT_COUNT, pointDefinitions.size());
        status.put("cachedValueCount", latestValues.size());
        status.put("lastTimestamps", latestTimestamps);
        status.put("connectionStats", webSocketConnection != null ? webSocketConnection.getStatistics() : Map.of());
        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) {
        try {
            String commandMessage = buildCommandMessage(command, params);
            webSocketConnection.send(commandMessage.getBytes(StandardCharsets.UTF_8));
            byte[] response = receiveOnce();
            if (response == null || response.length == 0) {
                return Map.of("status", "sent");
            }
            return parseAnyPayload(response);
        } catch (Exception e) {
            log.error("WebSocket 执行命令失败, 命令={}", command, e);
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    /**
     * 创建并返回业务对象。
     */
    @Override
    protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        pointDefinitions.clear();
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            pointDefinitions.put(point.getPointId(), point);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void drainInboundMessages() {
        if (webSocketConnection == null || !webSocketConnection.isConnected()) {
            return;
        }
        int max = 50;
        int drained = 0;
        while (drained < max && webSocketConnection.getPendingMessageCount() > 0) {
            try {
                byte[] payload = webSocketConnection.receive(1);
                if (payload == null || payload.length == 0) {
                    break;
                }
                applyInboundPayload(payload);
                drained++;
            } catch (Exception e) {
                break;
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void drainInboundSilently() {
        try {
            drainInboundMessages();
        } catch (Exception ignore) {
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private byte[] receiveOnce() {
        if (webSocketConnection == null || !webSocketConnection.isConnected()) {
            return null;
        }
        int timeout = 1000;
        DeviceConnection config = webSocketConnection.getConnectionConfig();
        if (config != null && config.getReadTimeout() != null && config.getReadTimeout() > 0) {
            timeout = config.getReadTimeout();
        }
        try {
            return webSocketConnection.receive(timeout);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void applyInboundPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }
        String message = new String(payload, StandardCharsets.UTF_8);
        handleWebSocketMessage(message);
    }

    /**
     * 处理当前业务流程。
     */
    private void handleWebSocketMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        try {
            Object parsed = JSON.parse(message);

            if (parsed instanceof JSONObject obj) {
                if (obj.containsKey(CommonMapKeys.POINT_ID) && obj.containsKey(CommonMapKeys.VALUE)) {
                    String pointId = Objects.toString(obj.get(CommonMapKeys.POINT_ID), null);
                    if (pointId != null) {
                        recordInboundValue(pointId, obj.get(CommonMapKeys.VALUE));
                    }
                    return;
                }

                Object valuesObj = obj.get("values");
                if (valuesObj instanceof JSONObject values) {
                    updateFromValueMap(values);
                    return;
                }

                updateFromValueMap(obj);
                return;
            }

            if (parsed instanceof JSONArray array) {
                for (Object item : array) {
                    if (!(item instanceof JSONObject itemObj)) {
                        continue;
                    }
                    if (itemObj.containsKey(CommonMapKeys.POINT_ID) && itemObj.containsKey(CommonMapKeys.VALUE)) {
                        String pointId = Objects.toString(itemObj.get(CommonMapKeys.POINT_ID), null);
                        if (pointId != null) {
                            recordInboundValue(pointId, itemObj.get(CommonMapKeys.VALUE));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("WebSocket 消息 parse 失败, 载荷={}", message, e);
        }
    }

    /**
     * 更新或刷新业务状态。
     */
    private void updateFromValueMap(JSONObject source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if ("action".equals(key) || "status".equals(key) || "timestamp".equals(key) || "deviceId".equals(key)) {
                continue;
            }

            String pointId = resolvePointIdByKey(key);
            if (pointId != null) {
                recordInboundValue(pointId, entry.getValue());
            }
        }
    }

    /**
     * 记录或统计业务状态。
     */
    private void recordInboundValue(String pointId, Object value) {
        latestValues.put(pointId, value);
        latestTimestamps.put(pointId, System.currentTimeMillis());

        DataPoint point = pointDefinitions.get(pointId);
        if (point != null) {
            ingestPushedValue(point, value);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolvePointIdByKey(String key) {
        if (pointDefinitions.containsKey(key)) {
            return key;
        }
        for (DataPoint point : pointDefinitions.values()) {
            if (Objects.equals(point.getPointCode(), key)) {
                return point.getPointId();
            }
        }
        return null;
    }

    /**
     * 解析或转换业务数据。
     */
    private boolean parseAck(byte[] payload) {
        try {
            Object parsed = parseAnyPayload(payload);
            if (parsed instanceof JSONObject obj) {
                Object success = obj.get(CommonMapKeys.SUCCESS);
                if (success instanceof Boolean bool) {
                    return bool;
                }
                Object status = obj.get(CommonMapKeys.STATUS);
                if (status != null) {
                    String text = status.toString().toLowerCase();
                    return Objects.equals(text, "ok") || Objects.equals(text, "success");
                }
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private Object parseAnyPayload(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return Map.of("status", "empty");
        }
        try {
            return JSON.parse(text);
        } catch (Exception e) {
            return Map.of("status", "raw", "payload", text);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private DeviceConnection prepareConnectionConfig() {
        DeviceConnection config = requireConnectionConfig();
        if (config.getConnectionType() == null || config.getConnectionType().isBlank()) {
            config.setConnectionType("WEBSOCKET");
        }
        if (config.getHost() == null && deviceInfo.getIpAddress() != null) {
            config.setHost(deviceInfo.getIpAddress());
        }
        if (config.getPort() == null && deviceInfo.getPort() != null) {
            config.setPort(deviceInfo.getPort());
        }
        if (config.getUrl() == null && config.getHost() != null && config.getPort() != null) {
            String scheme = Boolean.TRUE.equals(config.getSslEnabled()) ? "wss" : "ws";
            config.setUrl(scheme + "://" + config.getHost() + ":" + config.getPort());
        }
        return config;
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildSubscribeMessage(DataPoint point) {
        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("action", "subscribe");
        payload.put(CommonMapKeys.DEVICE_ID, deviceInfo != null ? deviceInfo.getDeviceId() : null);
        payload.put(CommonMapKeys.POINT_ID, point.getPointId());
        payload.put(CommonMapKeys.POINT_CODE, point.getPointCode());
        payload.put(CommonMapKeys.ADDRESS, point.getAddress());
        payload.put(CommonMapKeys.TIMESTAMP, System.currentTimeMillis());
        return payload.toJSONString();
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildUnsubscribeMessage(DataPoint point) {
        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("action", "unsubscribe");
        payload.put(CommonMapKeys.DEVICE_ID, deviceInfo != null ? deviceInfo.getDeviceId() : null);
        payload.put(CommonMapKeys.POINT_ID, point.getPointId());
        payload.put(CommonMapKeys.POINT_CODE, point.getPointCode());
        payload.put(CommonMapKeys.ADDRESS, point.getAddress());
        payload.put(CommonMapKeys.TIMESTAMP, System.currentTimeMillis());
        return payload.toJSONString();
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildWriteMessage(DataPoint point, Object value) {
        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("action", "write");
        payload.put(CommonMapKeys.DEVICE_ID, deviceInfo != null ? deviceInfo.getDeviceId() : null);
        payload.put(CommonMapKeys.POINT_ID, point.getPointId());
        payload.put(CommonMapKeys.POINT_CODE, point.getPointCode());
        payload.put(CommonMapKeys.ADDRESS, point.getAddress());
        payload.put(CommonMapKeys.VALUE, value);
        payload.put(CommonMapKeys.TIMESTAMP, System.currentTimeMillis());
        return payload.toJSONString();
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildCommandMessage(String command, Map<String, Object> params) {
        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("action", "command");
        payload.put(CommonMapKeys.DEVICE_ID, deviceInfo != null ? deviceInfo.getDeviceId() : null);
        payload.put(CommonMapKeys.COMMAND, command);
        payload.put("params", params != null ? params : Map.of());
        payload.put(CommonMapKeys.TIMESTAMP, System.currentTimeMillis());
        return payload.toJSONString();
    }
}

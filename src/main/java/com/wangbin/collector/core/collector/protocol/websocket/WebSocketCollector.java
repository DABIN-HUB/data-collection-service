package com.wangbin.collector.core.collector.protocol.websocket;

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

    @Override
    protected void doConnect() throws Exception {
        DeviceConnection connectionConfig = prepareConnectionConfig();
        this.webSocketConnection = createAndConnectAdapter(connectionConfig, WebSocketConnectionAdapter.class, "WebSocket");
    }

    @Override
    protected void doDisconnect() throws Exception {
        removeManagedConnection("WebSocket");

        webSocketConnection = null;
        latestValues.clear();
        latestTimestamps.clear();
        pointDefinitions.clear();
    }

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
            request.put("deviceId", deviceInfo != null ? deviceInfo.getDeviceId() : null);
            request.put("pointId", point.getPointId());
            request.put("pointCode", point.getPointCode());
            request.put("address", point.getAddress());
            request.put("timestamp", System.currentTimeMillis());
            webSocketConnection.send(request.toJSONString().getBytes(StandardCharsets.UTF_8));

            byte[] response = receiveOnce();
            applyInboundPayload(response);
            return latestValues.get(point.getPointId());
        } catch (Exception e) {
            log.error("WebSocket read point failed, pointId={}", point.getPointId(), e);
            return latestValues.get(point.getPointId());
        }
    }

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
            request.put("deviceId", deviceInfo != null ? deviceInfo.getDeviceId() : null);
            request.put("timestamp", System.currentTimeMillis());

            JSONArray pointArray = new JSONArray();
            for (DataPoint point : points) {
                JSONObject p = new JSONObject(new LinkedHashMap<>());
                p.put("pointId", point.getPointId());
                p.put("pointCode", point.getPointCode());
                p.put("address", point.getAddress());
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
            log.error("WebSocket batch read failed, size={}", points.size(), e);
            for (DataPoint point : points) {
                results.put(point.getPointId(), latestValues.get(point.getPointId()));
            }
            return results;
        }
    }

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
            log.error("WebSocket write point failed, pointId={}", point.getPointId(), e);
            return false;
        }
    }

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
                log.error("WebSocket subscribe failed, pointId={}", point.getPointId(), e);
            }
        }
        drainInboundSilently();
    }

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
                log.error("WebSocket unsubscribe failed, pointId={}", point.getPointId(), e);
            }
        }
        drainInboundSilently();
    }

    @Override
    protected Map<String, Object> doGetDeviceStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("isConnected", isConnected());
        status.put("protocolType", getProtocolType());
        status.put("pointCount", pointDefinitions.size());
        status.put("cachedValueCount", latestValues.size());
        status.put("lastTimestamps", latestTimestamps);
        status.put("connectionStats", webSocketConnection != null ? webSocketConnection.getStatistics() : Map.of());
        return status;
    }

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
            log.error("WebSocket execute command failed, command={}", command, e);
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

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

    private void drainInboundSilently() {
        try {
            drainInboundMessages();
        } catch (Exception ignore) {
        }
    }

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

    private void applyInboundPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }
        String message = new String(payload, StandardCharsets.UTF_8);
        handleWebSocketMessage(message);
    }

    private void handleWebSocketMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        try {
            Object parsed = JSON.parse(message);

            if (parsed instanceof JSONObject obj) {
                if (obj.containsKey("pointId") && obj.containsKey("value")) {
                    String pointId = Objects.toString(obj.get("pointId"), null);
                    if (pointId != null) {
                        recordInboundValue(pointId, obj.get("value"));
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
                    if (itemObj.containsKey("pointId") && itemObj.containsKey("value")) {
                        String pointId = Objects.toString(itemObj.get("pointId"), null);
                        if (pointId != null) {
                            recordInboundValue(pointId, itemObj.get("value"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("WebSocket message parse failed, payload={}", message, e);
        }
    }

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

    private void recordInboundValue(String pointId, Object value) {
        latestValues.put(pointId, value);
        latestTimestamps.put(pointId, System.currentTimeMillis());

        DataPoint point = pointDefinitions.get(pointId);
        if (point != null) {
            ingestPushedValue(point, value);
        }
    }

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

    private boolean parseAck(byte[] payload) {
        try {
            Object parsed = parseAnyPayload(payload);
            if (parsed instanceof JSONObject obj) {
                Object success = obj.get("success");
                if (success instanceof Boolean bool) {
                    return bool;
                }
                Object status = obj.get("status");
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

    private String buildSubscribeMessage(DataPoint point) {
        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("action", "subscribe");
        payload.put("deviceId", deviceInfo != null ? deviceInfo.getDeviceId() : null);
        payload.put("pointId", point.getPointId());
        payload.put("pointCode", point.getPointCode());
        payload.put("address", point.getAddress());
        payload.put("timestamp", System.currentTimeMillis());
        return payload.toJSONString();
    }

    private String buildUnsubscribeMessage(DataPoint point) {
        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("action", "unsubscribe");
        payload.put("deviceId", deviceInfo != null ? deviceInfo.getDeviceId() : null);
        payload.put("pointId", point.getPointId());
        payload.put("pointCode", point.getPointCode());
        payload.put("address", point.getAddress());
        payload.put("timestamp", System.currentTimeMillis());
        return payload.toJSONString();
    }

    private String buildWriteMessage(DataPoint point, Object value) {
        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("action", "write");
        payload.put("deviceId", deviceInfo != null ? deviceInfo.getDeviceId() : null);
        payload.put("pointId", point.getPointId());
        payload.put("pointCode", point.getPointCode());
        payload.put("address", point.getAddress());
        payload.put("value", value);
        payload.put("timestamp", System.currentTimeMillis());
        return payload.toJSONString();
    }

    private String buildCommandMessage(String command, Map<String, Object> params) {
        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("action", "command");
        payload.put("deviceId", deviceInfo != null ? deviceInfo.getDeviceId() : null);
        payload.put("command", command);
        payload.put("params", params != null ? params : Map.of());
        payload.put("timestamp", System.currentTimeMillis());
        return payload.toJSONString();
    }
}

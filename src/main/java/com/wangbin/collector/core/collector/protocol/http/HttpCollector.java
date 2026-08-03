package com.wangbin.collector.core.collector.protocol.http;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.connection.adapter.HttpConnectionAdapter;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
public class HttpCollector extends ConnectionBackedCollector {

    private HttpConnectionAdapter httpConnection;

    private final Map<String, DataPoint> pointDefinitions = new ConcurrentHashMap<>();
    private final Map<String, Object> latestValues = new ConcurrentHashMap<>();

    @Override
    public String getCollectorType() {
        return "HTTP";
    }

    @Override
    public String getProtocolType() {
        return "HTTP";
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        DeviceConnection connectionConfig = prepareConnectionConfig();
        this.httpConnection = createAndConnectAdapter(connectionConfig, HttpConnectionAdapter.class, "HTTP");
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        removeManagedConnection("HTTP");
        httpConnection = null;
        latestValues.clear();
        pointDefinitions.clear();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) {
        try {
            Map<String, Object> values = requestRead(List.of(point));
            Object value = values.get(point.getPointId());
            if (value != null) {
                latestValues.put(point.getPointId(), value);
            }
            return value;
        } catch (Exception e) {
            log.error("HTTP 读取 点位 失败, 点位={}", point.getPointId(), e);
            return latestValues.get(point.getPointId());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) {
        Map<String, Object> result = new HashMap<>();
        if (points == null || points.isEmpty()) {
            return result;
        }

        try {
            Map<String, Object> values = requestRead(points);
            for (DataPoint point : points) {
                String pointId = point.getPointId();
                Object value = values.get(pointId);
                if (value != null) {
                    latestValues.put(pointId, value);
                }
                result.put(pointId, value != null ? value : latestValues.get(pointId));
            }
            return result;
        } catch (Exception e) {
            log.error("HTTP 批量 读取 失败, 数量={}", points.size(), e);
            for (DataPoint point : points) {
                result.put(point.getPointId(), latestValues.get(point.getPointId()));
            }
            return result;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doWritePoint(DataPoint point, Object value) {
        try {
            JSONObject payload = new JSONObject(new LinkedHashMap<>());
            payload.put("action", "write");
            payload.put(CommonMapKeys.DEVICE_ID, deviceInfo != null ? deviceInfo.getDeviceId() : null);
            payload.put(CommonMapKeys.POINT_ID, point.getPointId());
            payload.put(CommonMapKeys.POINT_CODE, point.getPointCode());
            payload.put(CommonMapKeys.ADDRESS, point.getAddress());
            payload.put(CommonMapKeys.VALUE, value);
            payload.put(CommonMapKeys.TIMESTAMP, System.currentTimeMillis());

            httpConnection.send(payload.toJSONString().getBytes(StandardCharsets.UTF_8));

            byte[] response = tryReceiveResponse();
            boolean success = parseWriteAck(response);
            if (success) {
                latestValues.put(point.getPointId(), value);
            }
            return success;
        } catch (Exception e) {
            log.error("HTTP 写入 点位 失败, 点位={}", point.getPointId(), e);
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
            boolean ok = doWritePoint(entry.getKey(), entry.getValue());
            results.put(entry.getKey().getPointId(), ok);
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
        }
        log.debug("HTTP 仅注册订阅, 数量={}", points.size());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doUnsubscribe(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            pointDefinitions.clear();
            return;
        }
        for (DataPoint point : points) {
            pointDefinitions.remove(point.getPointId());
        }
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
        status.put("connectionStats", httpConnection != null ? httpConnection.getStatistics() : Map.of());
        return status;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) {
        try {
            JSONObject payload = new JSONObject(new LinkedHashMap<>());
            payload.put("action", "command");
            payload.put(CommonMapKeys.COMMAND, command);
            payload.put(CommonMapKeys.DEVICE_ID, deviceInfo != null ? deviceInfo.getDeviceId() : null);
            payload.put("params", params != null ? params : Map.of());
            payload.put(CommonMapKeys.TIMESTAMP, System.currentTimeMillis());

            httpConnection.send(payload.toJSONString().getBytes(StandardCharsets.UTF_8));
            byte[] response = tryReceiveResponse();
            if (response == null || response.length == 0) {
                return Map.of("status", "sent");
            }
            return parseCommandResponse(response);
        } catch (Exception e) {
            log.error("HTTP 执行命令失败, 命令={}", command, e);
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
    private Map<String, Object> requestRead(List<DataPoint> points) throws Exception {
        JSONObject payload = new JSONObject(new LinkedHashMap<>());
        payload.put("action", points.size() == 1 ? "read" : "batchRead");
        payload.put(CommonMapKeys.DEVICE_ID, deviceInfo != null ? deviceInfo.getDeviceId() : null);
        payload.put(CommonMapKeys.TIMESTAMP, System.currentTimeMillis());

        JSONArray pointArray = new JSONArray();
        for (DataPoint point : points) {
            JSONObject pointJson = new JSONObject(new LinkedHashMap<>());
            pointJson.put(CommonMapKeys.POINT_ID, point.getPointId());
            pointJson.put(CommonMapKeys.POINT_CODE, point.getPointCode());
            pointJson.put(CommonMapKeys.ADDRESS, point.getAddress());
            pointJson.put("dataType", point.getDataType());
            pointArray.add(pointJson);
        }
        payload.put("points", pointArray);

        httpConnection.send(payload.toJSONString().getBytes(StandardCharsets.UTF_8));
        byte[] response = tryReceiveResponse();
        return parseReadResponse(points, response);
    }

    /**
     * 执行当前业务逻辑。
     */
    private byte[] tryReceiveResponse() throws Exception {
        if (httpConnection == null || !httpConnection.isConnected()) {
            return null;
        }
        int timeout = 1000;
        DeviceConnection config = httpConnection.getConnectionConfig();
        if (config != null && config.getReadTimeout() != null && config.getReadTimeout() > 0) {
            timeout = config.getReadTimeout();
        }
        return httpConnection.receive(timeout);
    }

    /**
     * 解析或转换业务数据。
     */
    private Map<String, Object> parseReadResponse(List<DataPoint> points, byte[] responseBytes) {
        Map<String, Object> result = new HashMap<>();
        if (responseBytes == null || responseBytes.length == 0) {
            return result;
        }

        String text = new String(responseBytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return result;
        }

        try {
            Object parsed = JSON.parse(text);

            if (parsed instanceof JSONObject obj) {
                Object valuesObj = obj.get("values");
                if (valuesObj instanceof JSONObject values) {
                    putValueMap(points, result, values);
                    return result;
                }

                Object pointId = obj.get(CommonMapKeys.POINT_ID);
                if (pointId != null && obj.containsKey(CommonMapKeys.VALUE)) {
                    result.put(pointId.toString(), obj.get(CommonMapKeys.VALUE));
                    return result;
                }

                putValueMap(points, result, obj);
                return result;
            }

            if (parsed instanceof JSONArray array) {
                for (Object item : array) {
                    if (!(item instanceof JSONObject itemObj)) {
                        continue;
                    }
                    Object pointId = itemObj.get(CommonMapKeys.POINT_ID);
                    if (pointId != null && itemObj.containsKey(CommonMapKeys.VALUE)) {
                        result.put(pointId.toString(), itemObj.get(CommonMapKeys.VALUE));
                    }
                }
                return result;
            }

            if (points.size() == 1) {
                result.put(points.get(0).getPointId(), parsed);
            }
            return result;
        } catch (Exception e) {
            if (points.size() == 1) {
                result.put(points.get(0).getPointId(), text);
            }
            return result;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void putValueMap(List<DataPoint> points, Map<String, Object> result, JSONObject source) {
        for (DataPoint point : points) {
            String pointId = point.getPointId();
            if (source.containsKey(pointId)) {
                result.put(pointId, source.get(pointId));
                continue;
            }
            String pointCode = point.getPointCode();
            if (pointCode != null && source.containsKey(pointCode)) {
                result.put(pointId, source.get(pointCode));
            }
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private boolean parseWriteAck(byte[] responseBytes) {
        if (responseBytes == null || responseBytes.length == 0) {
            return true;
        }
        String text = new String(responseBytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return true;
        }
        try {
            Object parsed = JSON.parse(text);
            if (parsed instanceof JSONObject obj) {
                Object success = obj.get(CommonMapKeys.SUCCESS);
                if (success instanceof Boolean bool) {
                    return bool;
                }
                Object status = obj.get(CommonMapKeys.STATUS);
                if (status != null) {
                    String statusText = status.toString().toLowerCase();
                    return Objects.equals(statusText, "success") || Objects.equals(statusText, "ok");
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
    private Object parseCommandResponse(byte[] responseBytes) {
        String text = new String(responseBytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return Map.of("status", "empty");
        }
        try {
            return JSON.parse(text);
        } catch (Exception e) {
            Map<String, Object> plain = new HashMap<>();
            plain.put(CommonMapKeys.STATUS, "raw");
            plain.put("payload", text);
            return plain;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private DeviceConnection prepareConnectionConfig() {
        DeviceConnection config = requireConnectionConfig();
        if (config.getConnectionType() == null || config.getConnectionType().isBlank()) {
            config.setConnectionType("HTTP");
        }
        if (config.getHost() == null && deviceInfo.getIpAddress() != null) {
            config.setHost(deviceInfo.getIpAddress());
        }
        if (config.getPort() == null && deviceInfo.getPort() != null) {
            config.setPort(deviceInfo.getPort());
        }
        if (config.getUrl() == null && config.getHost() != null && config.getPort() != null) {
            String scheme = Boolean.TRUE.equals(config.getSslEnabled()) ? "https" : "http";
            config.setUrl(scheme + "://" + config.getHost() + ":" + config.getPort());
        }
        return config;
    }
}

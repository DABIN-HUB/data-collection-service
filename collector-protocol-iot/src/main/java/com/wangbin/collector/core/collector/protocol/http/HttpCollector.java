package com.wangbin.collector.core.collector.protocol.http;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.base.ConnectionBackedCollector;
import com.wangbin.collector.core.connection.adapter.HttpConnectionAdapter;
import com.wangbin.collector.core.config.validator.HttpConfigurationContract;
import com.wangbin.collector.core.collector.protocol.http.extractor.HttpResponseExtractor;
import com.wangbin.collector.core.collector.protocol.http.extractor.JsonPathHttpResponseExtractor;
import com.wangbin.collector.core.collector.protocol.http.extractor.PointArrayHttpResponseExtractor;
import com.wangbin.collector.core.collector.protocol.http.extractor.RawHttpResponseExtractor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实现当前协议或设备的采集能力。
 */
@Slf4j
public class HttpCollector extends ConnectionBackedCollector {

    private HttpConnectionAdapter httpConnection;

    private final Map<String, DataPoint> pointDefinitions = new ConcurrentHashMap<>();

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
        pointDefinitions.clear();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Object doReadPoint(DataPoint point) throws Exception {
        return requestRead(List.of(point)).get(point.getPointId());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Map<String, Object> doReadPoints(List<DataPoint> points) throws Exception {
        if (points == null || points.isEmpty()) {
            return Map.of();
        }
        return requestRead(points);
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

            DeviceConnection config = httpConnection.getConnectionConfig();
            boolean direct = "DIRECT".equals(resolveRequestMode(config, List.of(point)));
            String endpoint = direct ? config.getString("writeEndpoint", "")
                    : config.getString("sendEndpoint", "/api/data");
            if (direct && endpoint.isBlank()) throw new IllegalArgumentException("HTTP DIRECT write requires writeEndpoint");
            String method = direct ? config.getString("writeMethod", "POST") : config.getString("method", "POST");
            byte[] response = httpConnection.request(method, endpoint, payload.toJSONString().getBytes(StandardCharsets.UTF_8));
            String ackMode = config.getString("writeAckMode", "RESPONSE");
            if (!"RESPONSE".equalsIgnoreCase(ackMode) && !"HTTP_2XX".equalsIgnoreCase(ackMode)) {
                throw new IllegalArgumentException("HTTP invalid writeAckMode");
            }
            if ((response == null || response.length == 0) && "HTTP_2XX".equalsIgnoreCase(ackMode)) return true;
            if ((response == null || response.length == 0) && !direct) response = tryReceiveResponse();
            return parseWriteAck(response);
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

        DeviceConnection config = httpConnection.getConnectionConfig();
        String requestMode = resolveRequestMode(config, points);
        byte[] response;
        if ("DIRECT".equals(requestMode)) {
            String endpoint = HttpConfigurationContract.path(config);
            response = httpConnection.request(config.getString("method", "GET"), endpoint);
        } else {
            httpConnection.send(payload.toJSONString().getBytes(StandardCharsets.UTF_8));
            response = tryReceiveResponse();
        }
        Map<String, Object> values = selectResponseExtractor(config).extract(response, points,
                config.getExtJson() != null ? config.getExtJson() : Map.of());
        if (values.isEmpty()) throw new IllegalStateException("HTTP POINT_NOT_FOUND: no requested points mapped");
        return values;
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

    /** 根据显式配置决定 HTTP 请求模式；AUTO_COMPAT 仅保留旧配置兼容规则。 */
    private String resolveRequestMode(DeviceConnection config, List<DataPoint> points) {
        String configured = HttpConfigurationContract.requestMode(config);
        if ("DIRECT".equals(configured) || "ENVELOPE".equals(configured)) {
            return configured;
        }
        String path = HttpConfigurationContract.path(config);
        boolean directCompatible = !path.isBlank()
                && points.stream().allMatch(point -> point.getAddress() != null && point.getAddress().startsWith("/"));
        return directCompatible ? "DIRECT" : "ENVELOPE";
    }

    private HttpResponseExtractor selectResponseExtractor(DeviceConnection config) {
        return switch (HttpConfigurationContract.responseMode(config)) {
            case "JSON_PATH" -> new JsonPathHttpResponseExtractor();
            case "POINT_ARRAY" -> new PointArrayHttpResponseExtractor();
            case "RAW" -> new RawHttpResponseExtractor();
            default -> throw new IllegalArgumentException("HTTP unsupported responseMode");
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private boolean parseWriteAck(byte[] responseBytes) {
        if (responseBytes == null || responseBytes.length == 0) {
            return false;
        }
        String text = new String(responseBytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return false;
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
                    return "success".equals(statusText) || "ok".equals(statusText);
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("HTTP WRITE_ACK_ERROR: malformed acknowledgment", e);
            return false;
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

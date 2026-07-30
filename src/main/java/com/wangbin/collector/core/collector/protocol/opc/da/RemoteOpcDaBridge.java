package com.wangbin.collector.core.collector.protocol.opc.da;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RemoteOpcDaBridge implements OpcDaBridge {

    private static final String DEFAULT_BASE_PATH = "/api/v1/opcda";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile boolean connected;
    private String bridgeBaseUrl;
    private String bridgeToken;
    private String sessionId;
    private int requestTimeout;
    private int retryCount;
    private long retryBackoffMs;
    private HttpClient httpClient;

    @Override
    public void connect(OpcDaConfig config) throws Exception {
        this.bridgeBaseUrl = resolveBaseUrl(config);
        this.bridgeToken = config.bridgeToken();
        this.requestTimeout = Math.max(config.requestTimeout(), 1000);
        this.retryCount = Math.max(config.bridgeRetryCount(), 0);
        this.retryBackoffMs = Math.max(config.bridgeRetryBackoffMs(), 0L);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.requestTimeout))
                .build();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("serverProgId", config.serverProgId());
        request.put("host", config.host());
        request.put("endpoint", config.endpoint());
        request.put("username", config.username());
        request.put("password", config.password());
        request.put("domain", config.domain());
        request.put("requestTimeout", config.requestTimeout());
        request.put("updateRate", config.updateRate());

        JsonNode response = post("/open", request);
        JsonNode data = readDataNode(response);
        this.sessionId = readText(data, "sessionId");
        if (this.sessionId == null || this.sessionId.isBlank()) {
            this.sessionId = readText(response, "sessionId");
        }
        if (this.sessionId == null || this.sessionId.isBlank()) {
            throw new IllegalStateException("OPC DA bridge open response missing sessionId");
        }
        this.connected = true;
    }

    @Override
    public void disconnect() throws Exception {
        if (!connected) {
            return;
        }
        try {
            post("/close", Map.of("sessionId", sessionId));
        } finally {
            connected = false;
            sessionId = null;
        }
    }

    @Override
    public Object read(String itemId) throws Exception {
        ensureConnected();
        JsonNode response = post("/read", Map.of("sessionId", sessionId, "itemId", itemId));
        JsonNode data = readDataNode(response);
        JsonNode valueNode = data.get("value");
        if (valueNode != null) {
            return objectMapper.convertValue(valueNode, Object.class);
        }
        if (data.has(itemId)) {
            return objectMapper.convertValue(data.get(itemId), Object.class);
        }
        return objectMapper.convertValue(data, Object.class);
    }

    @Override
    public Map<String, Object> readBatch(List<String> itemIds) throws Exception {
        ensureConnected();
        if (itemIds == null || itemIds.isEmpty()) {
            return Collections.emptyMap();
        }
        JsonNode response = post("/read-batch", Map.of("sessionId", sessionId, "itemIds", itemIds));
        JsonNode data = readDataNode(response);
        JsonNode valuesNode = data.has("values") ? data.get("values") : data;
        if (valuesNode == null || !valuesNode.isObject()) {
            return Collections.emptyMap();
        }
        Map<String, Object> values = new HashMap<>();
        valuesNode.fields().forEachRemaining(e -> values.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class)));
        return values;
    }

    @Override
    public boolean write(String itemId, Object value) throws Exception {
        ensureConnected();
        JsonNode response = post("/write", Map.of("sessionId", sessionId, "itemId", itemId, "value", value));
        JsonNode data = readDataNode(response);
        if (data.has("success")) {
            return data.get("success").asBoolean(false);
        }
        if (data.has("status")) {
            String status = data.get("status").asText("");
            return "success".equalsIgnoreCase(status) || "ok".equalsIgnoreCase(status);
        }
        return true;
    }

    @Override
    public void subscribe(List<String> itemIds) throws Exception {
        ensureConnected();
        if (itemIds == null || itemIds.isEmpty()) {
            return;
        }
        post("/subscribe", Map.of("sessionId", sessionId, "itemIds", itemIds));
    }

    @Override
    public void unsubscribe(List<String> itemIds) throws Exception {
        ensureConnected();
        if (itemIds == null || itemIds.isEmpty()) {
            return;
        }
        post("/unsubscribe", Map.of("sessionId", sessionId, "itemIds", itemIds));
    }

    @Override
    public List<Map<String, Object>> browse(String branch) throws Exception {
        ensureConnected();
        JsonNode response = post("/browse", Map.of("sessionId", sessionId, "branch", Objects.toString(branch, "")));
        JsonNode data = readDataNode(response);
        JsonNode nodes = data.has("nodes") ? data.get("nodes") : data;
        if (nodes == null || !nodes.isArray()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (node.isObject()) {
                result.add(objectMapper.convertValue(node, Map.class));
            }
        }
        return result;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    private JsonNode post(String path, Object payload) throws Exception {
        Exception lastError = null;
        int attempts = retryCount + 1;
        for (int i = 0; i < attempts; i++) {
            try {
                String requestBody = objectMapper.writeValueAsString(payload);
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(bridgeBaseUrl + path))
                        .timeout(Duration.ofMillis(requestTimeout))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody));
                if (bridgeToken != null && !bridgeToken.isBlank()) {
                    requestBuilder.header("Authorization", "Bearer " + bridgeToken.trim());
                }
                HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("HTTP status=" + response.statusCode() + ", body=" + response.body());
                }
                JsonNode root = objectMapper.readTree(response.body());
                validateBridgeResponse(root);
                return root;
            } catch (Exception ex) {
                lastError = ex;
                if (i < attempts - 1 && retryBackoffMs > 0) {
                    Thread.sleep(retryBackoffMs * (i + 1));
                }
            }
        }
        throw lastError;
    }

    private void validateBridgeResponse(JsonNode response) {
        if (response == null || response.isNull()) {
            throw new IllegalStateException("Bridge response is empty");
        }
        if (response.has("success")) {
            if (!response.get("success").asBoolean(false)) {
                throw new IllegalStateException("Bridge request failed: " + readText(response, "message"));
            }
            return;
        }
        if (response.has("code")) {
            String code = readText(response, "code");
            if (!"0".equals(code) && !"200".equals(code) && !"SUCCESS".equalsIgnoreCase(code) && !"OK".equalsIgnoreCase(code)) {
                throw new IllegalStateException("Bridge request failed: code=" + code + ", message=" + readText(response, "message"));
            }
        }
    }

    private JsonNode readDataNode(JsonNode response) {
        if (response != null && response.has("data")) {
            JsonNode data = response.get("data");
            if (data != null && !data.isNull()) {
                return data;
            }
        }
        return objectMapper.createObjectNode();
    }

    private String readText(JsonNode node, String field) {
        if (node == null || field == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText(null);
    }

    private String resolveBaseUrl(OpcDaConfig config) {
        String candidate = firstNonBlank(config.bridgeBaseUrl(), config.endpoint());
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("bridgeBaseUrl or endpoint is required for HTTP OPC DA bridge");
        }
        String normalized = candidate.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.endsWith(DEFAULT_BASE_PATH)) {
            normalized = normalized + DEFAULT_BASE_PATH;
        }
        return normalized;
    }

    private void ensureConnected() {
        if (!connected || sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("OPC DA bridge is not connected");
        }
    }

    private String firstNonBlank(String... values) {
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

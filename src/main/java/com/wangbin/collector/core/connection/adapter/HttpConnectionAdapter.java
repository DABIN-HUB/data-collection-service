package com.wangbin.collector.core.connection.adapter;

import com.alibaba.fastjson2.JSON;
import com.wangbin.collector.common.config.ThreadPoolFallbacks;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.enums.ConnectionStatus;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP连接适配器（基于 Java 11+ HttpClient）
 */
@Slf4j
public class HttpConnectionAdapter extends AbstractConnectionAdapter<HttpClient> {

    private static final ExecutorService DEFAULT_HTTP_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            runnable -> {
                Thread thread = new Thread(runnable, "http-connection-io-shared");
                thread.setDaemon(true);
                return thread;
            });

    private HttpClient httpClient;
    private String baseUrl;
    private Map<String, String> customHeaders;
    private Executor httpExecutor;

    public HttpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        this(deviceInfo, config, null);
    }

    public HttpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config, Executor httpExecutor) {
        super(deviceInfo, config);
        this.httpExecutor = httpExecutor;
        initialize();
    }

    private void initialize() {
        this.baseUrl = buildBaseUrl();
        this.customHeaders = getCustomHeaders();
        this.httpExecutor = resolveHttpExecutor();
        this.httpClient = createHttpClient();
    }

    Executor resolveHttpExecutor() {
        return ThreadPoolFallbacks.preferExecutor(
                httpExecutor,
                DEFAULT_HTTP_EXECUTOR,
                "HttpConnectionAdapter",
                "http-connection-io-shared");
    }

    private String buildBaseUrl() {
        // 优先使用配置的完整 URL
        if (config.getUrl() != null && !config.getUrl().isEmpty()) {
            return config.getUrl();
        }

        // 根据 host/port 构建基础 URL
        String protocol = Boolean.TRUE.equals(config.getSslEnabled()) ? "https" : "http";
        String path = config.getStringConfig("path", "");

        // 确保 path 以 / 开头
        if (!path.startsWith("/") && !path.isEmpty()) {
            path = "/" + path;
        }

        return String.format("%s://%s:%d%s",
                protocol,
                config.getHost(),
                config.getPort(),
                path);
    }

    private Map<String, String> getCustomHeaders() {
        Map<String, Object> headersMap = config.getMapConfig("headers");
        if (headersMap != null) {
            // 转换为 String,String 类型的 Map
            Map<String, String> result = new java.util.HashMap<>();
            for (Map.Entry<String, Object> entry : headersMap.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), entry.getValue().toString());
                }
            }
            return result;
        }
        return new java.util.HashMap<>();
    }

    private HttpClient createHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getConnectTimeout()))
                .executor(httpExecutor);

        // 配置 SSL
        if (Boolean.TRUE.equals(config.getSslEnabled())) {
            builder.sslContext(createTrustAllSSLContext());
        }

        // 配置代理
        String proxyHost = config.getStringConfig("proxyHost", null);
        if (proxyHost != null) {
            int proxyPort = config.getIntConfig("proxyPort", 8080);
            builder.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(proxyHost, proxyPort)));
        }

        return builder.build();
    }

    private SSLContext createTrustAllSSLContext() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                        public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            log.error("创建 SSL 上下文失败", e);
            return null;
        }
    }

    @Override
    protected void doConnect() throws Exception {
        // HTTP 连接健康检查
        try {
            String healthPath = config.getStringConfig("healthCheckPath", "/health");
            HttpRequest request = buildRequest("GET", healthPath, null);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("HTTP 连接成功: {} (状态码: {})", baseUrl, response.statusCode());
            } else {
                throw new Exception("HTTP 连接失败，状态码: " + response.statusCode());
            }
        } catch (Exception e) {
            throw new Exception("HTTP 连接异常: " + e.getMessage(), e);
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        log.info("HTTP 连接资源清理完成: {}", connectionId);
    }

    @Override
    protected void doSend(byte[] data) throws UnsupportedOperationException {
        try {
            String method = config.getStringConfig("method", "POST");
            String endpoint = config.getStringConfig("sendEndpoint", "/api/data");

            HttpRequest request = buildRequest(method, endpoint, data);
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.debug("HTTP 发送数据成功: {} -> {} ({} bytes)",
                        baseUrl + endpoint, response.statusCode(), data.length);
            } else {
                throw new Exception("HTTP 发送数据失败，状态码: " + response.statusCode());
            }
        } catch (Exception e) {
            throw new UnsupportedOperationException("HTTP 发送数据异常: " + e.getMessage(), e);
        }
    }

    @Override
    protected byte[] doReceive() throws UnsupportedOperationException {
        try {
            return doReceive(config.getReadTimeout());
        } catch (Exception e) {
            throw new UnsupportedOperationException("HTTP 接收数据失败: " + e.getMessage(), e);
        }
    }

    @Override
    protected byte[] doReceive(long timeout) throws UnsupportedOperationException {
        try {
            String endpoint = config.getStringConfig("receiveEndpoint", "/api/receive");
            String method = config.getStringConfig("receiveMethod", "GET");

            HttpRequest request = buildRequest(method, endpoint, null);

            // 设置读取超时
            HttpClient tempClient = httpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeout))
                    .build();

            HttpResponse<byte[]> response = tempClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                throw new Exception("HTTP 接收数据失败，状态码: " + response.statusCode());
            }
        } catch (Exception e) {
            throw new UnsupportedOperationException("HTTP 接收数据异常: " + e.getMessage(), e);
        }
    }

    @Override
    public HttpClient getClient() {
        return httpClient;
    }

    @Override
    protected void doHeartbeat() throws Exception {
        String heartbeatEndpoint = config.getStringConfig("heartbeatEndpoint", "/health");

        HttpRequest request = buildRequest("GET", heartbeatEndpoint, null);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.debug("HTTP 心跳检查成功: {}", connectionId);
        } else {
            throw new Exception("HTTP 心跳检查失败，状态码: " + response.statusCode());
        }
    }

    @Override
    protected void doAuthenticate() throws Exception {
        String authEndpoint = config.getStringConfig("authEndpoint", "/api/auth");
        String authMethod = config.getStringConfig("authMethod", "POST");

        // 构建认证请求体
        String authBody = buildAuthRequestBody();

        HttpRequest request = buildRequest(authMethod, authEndpoint, authBody.getBytes(StandardCharsets.UTF_8));
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            // 提取认证 Token
            String authToken = extractAuthToken(response);
            if (authToken != null) {
                customHeaders.put("Authorization", "Bearer " + authToken);
            }
            log.info("HTTP 认证成功: {}", deviceInfo != null ? deviceInfo.getDeviceId() : "UNKNOWN");
        } else {
            throw new Exception("HTTP 认证失败，状态码: " + response.statusCode());
        }
    }

    private HttpRequest buildRequest(String method, String endpoint, byte[] body) {
        String url = buildFullUrl(endpoint);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(config.getReadTimeout()));

        // 设置请求方法
        switch (method.toUpperCase()) {
            case "GET":
                builder.GET();
                break;
            case "POST":
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(body != null ? body : new byte[0]));
                break;
            case "PUT":
                builder.PUT(HttpRequest.BodyPublishers.ofByteArray(body != null ? body : new byte[0]));
                break;
            case "DELETE":
                builder.DELETE();
                break;
            case "HEAD":
                builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                break;
            default:
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body != null ? body : new byte[0]));
        }

        // 设置基本认证
        if (config.getUsername() != null && config.getPassword() != null) {
            String auth = config.getUsername() + ":" + config.getPassword();
            String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        }

        // 设置 Bearer Token
        if (config.getAuthToken() != null) {
            builder.header("Authorization", "Bearer " + config.getAuthToken());
        }

        // 添加自定义请求头
        customHeaders.forEach(builder::header);

        // 设置默认请求头
        builder.header("Content-Type", "application/json");
        builder.header("User-Agent", "DataCollector/1.0");

        return builder.build();
    }

    private String buildFullUrl(String endpoint) {
        // 确保 endpoint 以 / 开头
        if (!endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }

        // 添加查询参数
        String queryString = buildQueryString();
        if (!queryString.isEmpty()) {
            if (baseUrl.contains("?")) {
                return baseUrl + endpoint + "&" + queryString;
            } else {
                return baseUrl + endpoint + "?" + queryString;
            }
        }

        return baseUrl + endpoint;
    }

    private String buildQueryString() {
        Map<String, Object> queryParamsMap = config.getMapConfig("queryParams");
        if (queryParamsMap == null || queryParamsMap.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : queryParamsMap.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append("=")
                    .append(URLEncoder.encode(entry.getValue().toString(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private String buildAuthRequestBody() {
        Map<String, Object> authParams = new java.util.HashMap<>();

        // 基本认证参数
        if (config.getUsername() != null && config.getPassword() != null) {
            authParams.put("username", config.getUsername());
            authParams.put("password", config.getPassword());
        }

        // 添加设备标识
        if (deviceInfo != null && deviceInfo.getDeviceId() != null) {
            authParams.put("deviceId", deviceInfo.getDeviceId());
        }
        if (deviceInfo != null && deviceInfo.getProductKey() != null) {
            authParams.put("productKey", deviceInfo.getProductKey());
        }
        if (config.getDeviceSecret() != null) {
            authParams.put("deviceSecret", config.getDeviceSecret());
        }

        // 合并自定义认证参数
        if (config.getAuthParams() != null) {
            authParams.putAll(config.getAuthParams());
        }

        // 转换为 JSON 字符串
        try {
            return JSON.toJSONString(authParams);
        } catch (Exception e) {
            // JSON 转换失败时使用简单拼接
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> entry : authParams.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
            }
            return "{" + sb.toString() + "}";
        }
    }

    private String extractAuthToken(HttpResponse<byte[]> response) {
        try {
            String responseBody = new String(response.body(), StandardCharsets.UTF_8);
            // 尝试从 JSON 响应中提取 token
            Map<String, Object> jsonResponse = com.alibaba.fastjson2.JSON.parseObject(responseBody, Map.class);
            if (jsonResponse.containsKey("token")) {
                return jsonResponse.get("token").toString();
            }
            if (jsonResponse.containsKey("access_token")) {
                return jsonResponse.get("access_token").toString();
            }

            // 尝试从 Header 中提取
            return response.headers().firstValue("Authorization").orElse(null);
        } catch (Exception e) {
            log.debug("提取认证 Token 失败", e);
            return null;
        }
    }

    @Override
    public void setConnectionParam(String key, Object value) {
        super.setConnectionParam(key, value);
        if (value instanceof String) {
            customHeaders.put(key, (String) value);
        }
    }
}
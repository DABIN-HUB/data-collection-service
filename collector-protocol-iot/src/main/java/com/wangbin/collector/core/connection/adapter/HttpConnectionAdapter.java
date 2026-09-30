package com.wangbin.collector.core.connection.adapter;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONPath;
import com.wangbin.collector.common.config.ThreadPoolFallbacks;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.validator.HttpConfigurationContract;
import com.wangbin.collector.common.domain.enums.ConnectionStatus;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
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

    /**
     * 创建当前组件实例。
     */
    public HttpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        this(deviceInfo, config, null);
    }

    /**
     * 创建当前组件实例。
     */
    public HttpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config, Executor httpExecutor) {
        super(deviceInfo, config);
        this.httpExecutor = httpExecutor;
        initialize();
    }

    /**
     * 处理组件生命周期。
     */
    private void initialize() {
        HttpConfigurationContract.validate(config);
        if ("JSON_PATH".equals(HttpConfigurationContract.responseMode(config))) {
            JSONPath.of(config.getString("responsePath", "$"));
        } else if ("POINT_ARRAY".equals(HttpConfigurationContract.responseMode(config))) {
            JSONPath.of(config.getString("responseArrayPath", "$.points"));
        }
        this.baseUrl = buildBaseUrl();
        this.customHeaders = getCustomHeaders();
        this.httpExecutor = resolveHttpExecutor();
        this.httpClient = createHttpClient();
    }

    /**
     * 解析或转换业务数据。
     */
    Executor resolveHttpExecutor() {
        return ThreadPoolFallbacks.preferExecutor(
                httpExecutor,
                DEFAULT_HTTP_EXECUTOR,
                "HttpConnectionAdapter",
                "http-connection-io-shared");
    }

    public byte[] request(String method, String endpoint) throws Exception {
        return request(method, endpoint, null);
    }

    public byte[] request(String method, String endpoint, byte[] body) throws Exception {
        HttpRequest request = buildRequest(method, endpoint, body);
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP 请求失败，状态码: " + response.statusCode());
        }
        return response.body();
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildBaseUrl() {
        // 优先使用配置的完整 URL
        if (config.getUrl() != null && !config.getUrl().isEmpty()) {
            return config.getUrl();
        }

        // 根据 host/port 构建基础 URL
        String protocol = Boolean.TRUE.equals(config.getSslEnabled()) ? "https" : "http";
        return String.format("%s://%s:%d",
                protocol,
                config.getHost(),
                config.getPort());
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

    /**
     * 创建并返回业务对象。
     */
    private HttpClient createHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getConnectTimeout()))
                .executor(httpExecutor);

        // 配置 SSL
        if (Boolean.TRUE.equals(config.getBool("insecureSkipVerify", false))) {
            builder.sslContext(createTrustAllSSLContext());
        }

        // 配置代理
        String proxyHost = config.getStringConfig("proxyHost", null);
        if (proxyHost != null && !proxyHost.isBlank()) {
            int proxyPort = config.getIntConfig("proxyPort", 8080);
            builder.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(proxyHost, proxyPort)));
        }

        return builder.build();
    }

    /**
     * 创建并返回业务对象。
     */
    private SSLContext createTrustAllSSLContext() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return null; }
                        /**
                         * 校验业务条件和参数边界。
                         */
                        public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                        /**
                         * 校验业务条件和参数边界。
                         */
                        public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new IllegalStateException("HTTP TLS context initialization failed", e);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        String healthPath = config.getStringConfig("healthCheckPath", "");
        if (healthPath == null || healthPath.isBlank()) {
            return;
        }
        try {
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

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        log.info("HTTP 连接资源清理完成: {}", connectionId);
    }

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected byte[] doReceive() throws UnsupportedOperationException {
        try {
            return doReceive(config.getReadTimeout());
        } catch (Exception e) {
            throw new UnsupportedOperationException("HTTP 接收数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected byte[] doReceive(long timeout) throws UnsupportedOperationException {
        try {
            String endpoint = config.getStringConfig("receiveEndpoint", "/api/receive");
            String method = config.getStringConfig("receiveMethod", "GET");

            HttpRequest request = buildRequest(method, endpoint, null, timeout);
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

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

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() throws Exception {
        String heartbeatEndpoint = config.getStringConfig("heartbeatEndpoint", "");
        if (heartbeatEndpoint == null || heartbeatEndpoint.isBlank()) {
            return;
        }

        HttpRequest request = buildRequest("GET", heartbeatEndpoint, null);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.debug("HTTP 心跳检查成功: {}", connectionId);
        } else {
            throw new Exception("HTTP 心跳检查失败，状态码: " + response.statusCode());
        }
    }

    /**
     * 执行当前业务逻辑。
     */
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
            if (authToken != null && !authToken.isBlank()) {
                customHeaders.keySet().removeIf(k -> "Authorization".equalsIgnoreCase(k));
                customHeaders.put("Authorization", authToken.regionMatches(true, 0, "Bearer ", 0, 7)
                        ? authToken : "Bearer " + authToken);
            } else {
                throw new IllegalStateException("HTTP authentication response has no token");
            }
            log.info("HTTP 认证成功: {}", deviceInfo != null ? deviceInfo.getDeviceId() : "UNKNOWN");
        } else {
            throw new Exception("HTTP 认证失败，状态码: " + response.statusCode());
        }
    }

    /**
     * 创建并返回业务对象。
     */
    private HttpRequest buildRequest(String method, String endpoint, byte[] body) {
        return buildRequest(method, endpoint, body, config.getReadTimeout());
    }

    private HttpRequest buildRequest(String method, String endpoint, byte[] body, long timeout) {
        String url = buildFullUrl(endpoint);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeout));

        // 设置请求方法
        switch (HttpConfigurationContract.method(method, "GET", "method")) {
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
            default: throw new IllegalArgumentException("HTTP unsupported method");
        }

        // A dynamically acquired token overrides configured credentials after authentication.
        boolean dynamicAuth = customHeaders.keySet().stream().anyMatch(k -> "Authorization".equalsIgnoreCase(k));
        if (!dynamicAuth && config.getUsername() != null && !config.getUsername().isBlank()
                && config.getPassword() != null && !config.getPassword().isBlank()) {
            String auth = config.getUsername() + ":" + config.getPassword();
            String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        }

        // 设置 Bearer Token
        if (!dynamicAuth && config.getAuthToken() != null && !config.getAuthToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.getAuthToken());
        }

        // 添加自定义请求头
        customHeaders.forEach((key, value) -> {
            if ("Authorization".equalsIgnoreCase(key) || "Content-Type".equalsIgnoreCase(key)
                    || "User-Agent".equalsIgnoreCase(key)) {
                builder.setHeader(key, value);
            } else {
                builder.header(key, value);
            }
        });

        // 设置默认请求头
        if (customHeaders.keySet().stream().noneMatch(k -> "Content-Type".equalsIgnoreCase(k))) {
            builder.header("Content-Type", "application/json");
        }
        if (customHeaders.keySet().stream().noneMatch(k -> "User-Agent".equalsIgnoreCase(k))) {
            builder.header("User-Agent", "DataCollector/1.0");
        }

        return builder.build();
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildFullUrl(String endpoint) {
        return HttpUrlResolver.resolve(baseUrl, endpoint, config.getMapConfig("queryParams")).toString();
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildAuthRequestBody() {
        Map<String, Object> authParams = new java.util.HashMap<>();

        // 基本认证参数
        if (config.getUsername() != null && config.getPassword() != null) {
            authParams.put("username", config.getUsername());
            authParams.put("password", config.getPassword());
        }

        // 添加设备标识
        if (deviceInfo != null && deviceInfo.getDeviceId() != null) {
            authParams.put(CommonMapKeys.DEVICE_ID, deviceInfo.getDeviceId());
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

    /**
     * 解析或转换业务数据。
     */
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
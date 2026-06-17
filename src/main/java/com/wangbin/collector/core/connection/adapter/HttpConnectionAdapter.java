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
 * HTTP鏉╃偞甯撮柅鍌炲帳閸ｎ煉绱欐担璺ㄦ暏Java 11+ HttpClient閿?
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
        // 娴兼ê鍘涙担璺ㄦ暏url鐎涙顔?
        if (config.getUrl() != null && !config.getUrl().isEmpty()) {
            return config.getUrl();
        }

        // 閸氾箑鍨担璺ㄦ暏host閸滃ort閺嬪嫬缂?
        String protocol = Boolean.TRUE.equals(config.getSslEnabled()) ? "https" : "http";
        String path = config.getStringConfig("path", "");

        // 绾喕绻歱ath娴?瀵偓婢?
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
            // 鏉烆剚宕叉稉绡爐ring,String Map
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

        // 闁板秶鐤哠SL
        if (Boolean.TRUE.equals(config.getSslEnabled())) {
            builder.sslContext(createTrustAllSSLContext());
        }

        // 闁板秶鐤嗘禒锝囨倞閿涘牆顩ч弸婊堟付鐟曚緤绱?
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
            log.error("Failed to create SSL context", e);
            return null;
        }
    }

    @Override
    protected void doConnect() throws Exception {
        // HTTP鏉╃偞甯撮弰顖滅叚鏉╃偞甯撮敍宀冪箹闁插本顥呴弻銉︽箛閸斺剝妲搁崥锕€褰叉潏?
        try {
            String healthPath = config.getStringConfig("healthCheckPath", "/health");
            HttpRequest request = buildRequest("GET", healthPath, null);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("HTTP閺堝秴濮熼崣顖濇彧: {} (閻樿埖鈧胶鐖? {})", baseUrl, response.statusCode());
            } else {
                throw new Exception("HTTP閺堝秴濮熸稉宥呭讲鏉? 閻樿埖鈧胶鐖?" + response.statusCode());
            }
        } catch (Exception e) {
            throw new Exception("HTTP鏉╃偞甯村ù瀣槸婢惰精瑙? " + e.getMessage(), e);
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        log.info("HTTP杩炴帴璧勬簮娓呯悊瀹屾垚: {}", connectionId);
    }

    @Override
    protected void doSend(byte[] data) throws UnsupportedOperationException {
        try {
            String method = config.getStringConfig("method", "POST");
            String endpoint = config.getStringConfig("sendEndpoint", "/api/data");

            HttpRequest request = buildRequest(method, endpoint, data);
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.debug("HTTP閸欐垿鈧焦鍨氶崝? {} -> {} ({} bytes)",
                        baseUrl + endpoint, response.statusCode(), data.length);
            } else {
                throw new Exception("HTTP閸欐垿鈧礁銇戠拹? 閻樿埖鈧胶鐖?" + response.statusCode());
            }
        } catch (Exception e) {
            throw new UnsupportedOperationException("HTTP閸欐垿鈧焦鎼锋担婊冦亼鐠? " + e.getMessage(), e);
        }
    }

    @Override
    protected byte[] doReceive() throws UnsupportedOperationException {
        try {
            return doReceive(config.getReadTimeout());
        } catch (Exception e) {
            throw new UnsupportedOperationException("HTTP閹恒儲鏁归幙宥勭稊婢惰精瑙? " + e.getMessage(), e);
        }
    }

    @Override
    protected byte[] doReceive(long timeout) throws UnsupportedOperationException {
        try {
            String endpoint = config.getStringConfig("receiveEndpoint", "/api/receive");
            String method = config.getStringConfig("receiveMethod", "GET");

            HttpRequest request = buildRequest(method, endpoint, null);

            // 鐠佸墽鐤嗛懛顏勭暰娑斿绉撮弮?
            HttpClient tempClient = httpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeout))
                    .build();

            HttpResponse<byte[]> response = tempClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                throw new Exception("HTTP閹恒儲鏁规径杈Е: 閻樿埖鈧胶鐖?" + response.statusCode());
            }
        } catch (Exception e) {
            throw new UnsupportedOperationException("HTTP閹恒儲鏁归幙宥勭稊婢惰精瑙? " + e.getMessage(), e);
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
            log.debug("HTTP韫囧啳鐑﹀Λ鈧ù瀣灇閸? {}", connectionId);
        } else {
            throw new Exception("HTTP韫囧啳鐑﹀Λ鈧ù瀣亼鐠? 閻樿埖鈧胶鐖?" + response.statusCode());
        }
    }

    @Override
    protected void doAuthenticate() throws Exception {
        String authEndpoint = config.getStringConfig("authEndpoint", "/api/auth");
        String authMethod = config.getStringConfig("authMethod", "POST");

        // 閺嬪嫬缂撶拋銈堢槈鐠囬攱鐪版担?
        String authBody = buildAuthRequestBody();

        HttpRequest request = buildRequest(authMethod, authEndpoint, authBody.getBytes(StandardCharsets.UTF_8));
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            // 閹绘劕褰囩拋銈堢槈娴犮倗澧濋敍鍫濐洤閺嬫粍婀侀敍?
            String authToken = extractAuthToken(response);
            if (authToken != null) {
                customHeaders.put("Authorization", "Bearer " + authToken);
            }
            log.info("HTTP鐠併倛鐦夐幋鎰: {}", deviceInfo != null ? deviceInfo.getDeviceId() : "UNKNOWN");
        } else {
            throw new Exception("HTTP鐠併倛鐦夋径杈Е: 閻樿埖鈧胶鐖?" + response.statusCode());
        }
    }

    private HttpRequest buildRequest(String method, String endpoint, byte[] body) {
        String url = buildFullUrl(endpoint);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(config.getReadTimeout()));

        // 鐠佸墽鐤嗙拠閿嬬湴閺傝纭?
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

        // 鐠佸墽鐤嗛崺鐑樻拱鐠併倛鐦夋径?
        if (config.getUsername() != null && config.getPassword() != null) {
            String auth = config.getUsername() + ":" + config.getPassword();
            String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        }

        // 鐠佸墽鐤咮earer娴犮倗澧濋敍鍫濐洤閺嬫粍婀侀敍?
        if (config.getAuthToken() != null) {
            builder.header("Authorization", "Bearer " + config.getAuthToken());
        }

        // 鐠佸墽鐤嗛懛顏勭暰娑斿銇?
        customHeaders.forEach(builder::header);

        // 鐠佸墽鐤嗛崘鍛啇缁鐎?
        builder.header("Content-Type", "application/json");
        builder.header("User-Agent", "DataCollector/1.0");

        return builder.build();
    }

    private String buildFullUrl(String endpoint) {
        // 绾喕绻歟ndpoint娴?瀵偓婢?
        if (!endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }

        // 濞ｈ濮為弻銉嚄閸欏倹鏆?
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

        // 濞ｈ濮為崺鐑樻拱鐠併倛鐦夋穱鈩冧紖
        if (config.getUsername() != null && config.getPassword() != null) {
            authParams.put("username", config.getUsername());
            authParams.put("password", config.getPassword());
        }

        // 濞ｈ濮炵拋鎯ь槵娣団剝浼?
        if (deviceInfo != null && deviceInfo.getDeviceId() != null) {
            authParams.put("deviceId", deviceInfo.getDeviceId());
        }
        if (deviceInfo != null && deviceInfo.getProductKey() != null) {
            authParams.put("productKey", deviceInfo.getProductKey());
        }
        if (config.getDeviceSecret() != null) {
            authParams.put("deviceSecret", config.getDeviceSecret());
        }

        // 濞ｈ濮炴０婵嗩樆閻ㄥ嫯顓荤拠浣稿棘閺?
        if (config.getAuthParams() != null) {
            authParams.putAll(config.getAuthParams());
        }

        // 鏉烆剚宕叉稉绡擲ON鐎涙顑佹稉?
        try {
            return JSON.toJSONString(authParams);
        } catch (Exception e) {
            // 婵″倹鐏塉SON鏉烆剚宕叉径杈Е閿涘奔濞囬悽銊х暆閸楁洘鐗稿?
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
            // 鐏忔繆鐦禒宥玈ON閸濆秴绨叉稉顓熷絹閸欐潰oken
            Map<String, Object> jsonResponse = com.alibaba.fastjson2.JSON.parseObject(responseBody, Map.class);
            if (jsonResponse.containsKey("token")) {
                return jsonResponse.get("token").toString();
            }
            if (jsonResponse.containsKey("access_token")) {
                return jsonResponse.get("access_token").toString();
            }

            // 鐏忔繆鐦禒宥〆ader娑擃厽褰侀崣?
            return response.headers().firstValue("Authorization").orElse(null);
        } catch (Exception e) {
            log.debug("閹绘劕褰囩拋銈堢槈娴犮倗澧濇径杈Е", e);
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


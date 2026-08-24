package com.wangbin.collector.api.filter;

import com.wangbin.collector.api.filter.config.AuthProperties;
import com.wangbin.collector.api.filter.config.AuthScope;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 简单鉴权过滤器，支持运维 Token 及服务签名。
 */
@Slf4j
public class AuthFilter extends OncePerRequestFilter {

    public static final String ATTR_PRINCIPAL = AuthFilter.class.getName() + ".principal";
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final String REAL_IP_HEADER = "X-Real-IP";
    private static final String NONCE_KEY_PREFIX = "collector:auth:nonce:";
    private static final String AUTH_METRIC_NAME = "collector.auth.requests";
    private static final String UNKNOWN_AUTH_TYPE = "UNKNOWN";

    private final AuthProperties properties;
    private final Clock clock;
    private final StringRedisTemplate stringRedisTemplate;
    private final MeterRegistry meterRegistry;
    private final AntPathMatcher matcher = new AntPathMatcher();
    private final ConcurrentMap<String, Long> localNonces = new ConcurrentHashMap<>();

    /**
     * 创建当前组件实例。
     */
    public AuthFilter(AuthProperties properties) {
        this(properties, Clock.systemUTC(), null, null);
    }

    /**
     * 创建当前组件实例。
     */
    public AuthFilter(AuthProperties properties, StringRedisTemplate stringRedisTemplate) {
        this(properties, Clock.systemUTC(), stringRedisTemplate, null);
    }

    /**
     * 创建当前组件实例。
     */
    public AuthFilter(AuthProperties properties,
                      StringRedisTemplate stringRedisTemplate,
                      MeterRegistry meterRegistry) {
        this(properties, Clock.systemUTC(), stringRedisTemplate, meterRegistry);
    }

    /**
     * 创建当前组件实例。
     */
    AuthFilter(AuthProperties properties, Clock clock) {
        this(properties, clock, null, null);
    }

    /**
     * 创建当前组件实例。
     */
    AuthFilter(AuthProperties properties, Clock clock, StringRedisTemplate stringRedisTemplate) {
        this(properties, clock, stringRedisTemplate, null);
    }

    /**
     * 创建当前组件实例。
     */
    AuthFilter(AuthProperties properties,
               Clock clock,
               StringRedisTemplate stringRedisTemplate,
               MeterRegistry meterRegistry) {
        this.properties = properties;
        this.clock = clock;
        this.stringRedisTemplate = stringRedisTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = resolveApplicationPath(request);
        return matches(path, properties.getPermitAllPaths());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpServletRequest securedRequest = prepareSignedRequest(request, response);
        if (securedRequest == null) {
            return;
        }
        AuthDecision decision = authorize(securedRequest);
        recordAuthentication(decision);
        if (!decision.isAllowed()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter()
                    .append("{\"status\":\"error\",\"message\":\"")
                    .append(decision.getMessage())
                    .append("\"}");
            return;
        }
        AuthScope requiredScope = resolveRequiredScope(securedRequest);
        if (requiredScope != null && (decision.getPrincipal() == null
                || !decision.getPrincipal().getScopes().contains(requiredScope))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            String quote = Character.toString((char) 34);
            response.getWriter()
                    .append("{").append(quote).append("status").append(quote)
                    .append(":").append(quote).append("error").append(quote)
                    .append(",").append(quote).append("message").append(quote)
                    .append(":").append(quote).append("权限不足").append(quote).append("}");
            return;
        }
        if (decision.getPrincipal() != null) {
            securedRequest.setAttribute(ATTR_PRINCIPAL, decision.getPrincipal());
        }
        filterChain.doFilter(securedRequest, response);
    }

    /**
     * 记录或统计业务状态。
     */
    private void recordAuthentication(AuthDecision decision) {
        if (meterRegistry == null) {
            return;
        }
        String type = decision.getPrincipal() == null
                ? UNKNOWN_AUTH_TYPE : decision.getPrincipal().getType();
        meterRegistry.counter(AUTH_METRIC_NAME,
                        "result", decision.isAllowed() ? "allowed" : "denied",
                        "type", type)
                .increment();
    }

    /**
     * 执行当前业务逻辑。
     */
    private HttpServletRequest prepareSignedRequest(HttpServletRequest request,
                                                    HttpServletResponse response) throws IOException {
        if (!StringUtils.hasText(request.getHeader(properties.getServiceHeader()))) {
            return request;
        }
        int maximumBodyBytes = Math.max(0, properties.getMaxSignedBodyBytes());
        if (maximumBodyBytes > 0 && request.getContentLengthLong() > maximumBodyBytes) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "signed request body too large");
            return null;
        }
        CachedBodyRequest cachedRequest = new CachedBodyRequest(request);
        if (maximumBodyBytes > 0 && cachedRequest.bodyLength() > maximumBodyBytes) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "signed request body too large");
            return null;
        }
        return cachedRequest;
    }

    /**
     * 执行当前业务逻辑。
     */
    private AuthDecision authorize(HttpServletRequest request) {
        String serviceId = request.getHeader(properties.getServiceHeader());
        if (StringUtils.hasText(serviceId)) {
            return authorizeService(request, serviceId);
        }
        String token = request.getHeader(properties.getTokenHeader());
        if (StringUtils.hasText(token)) {
            return authorizeOps(request, token);
        }
        if (properties.isAllowIpAuthentication()
                && ipAllowed(resolveClientIp(request), properties.getIpAllowList())) {
            return AuthDecision.allow(AuthPrincipal.ops(
                    "ip-allowlist", "global", Set.copyOf(properties.getDefaultOpsScopes())));
        }
        return AuthDecision.deny("missing credential");
    }

    /**
     * 执行当前业务逻辑。
     */
    private AuthDecision authorizeOps(HttpServletRequest request, String token) {
        Map<String, String> opsTokens = properties.getOpsTokens();
        if (CollectionUtils.isEmpty(opsTokens)) {
            return AuthDecision.deny("ops tokens not configured");
        }
        String label = opsTokens.get(token);
        if (!StringUtils.hasText(label)) {
            return AuthDecision.deny("invalid ops token");
        }
        String ip = resolveClientIp(request);
        return AuthDecision.allow(AuthPrincipal.ops(
                label, ip, Set.copyOf(properties.resolveOpsScopes(label))));
    }

    /**
     * 执行当前业务逻辑。
     */
    private AuthDecision authorizeService(HttpServletRequest request, String serviceId) {
        AuthProperties.ServiceClient client = properties.getServiceClients().get(serviceId);
        if (client == null || !client.isEnabled()) {
            return AuthDecision.deny("service client disabled or not found");
        }
        String clientIp = resolveClientIp(request);
        boolean ipTrusted = ipAllowed(clientIp, combine(client.getAllowIps(), properties.getIpAllowList()));
        String signatureHeader = request.getHeader(properties.getSignatureHeader());
        if (!StringUtils.hasText(signatureHeader)) {
            if (client.isAllowIpFallback() && ipTrusted) {
                return AuthDecision.allow(AuthPrincipal.service(
                        serviceId, clientIp, "ip-fallback", Set.copyOf(client.getScopes())));
            }
            if (!client.isRequireSignature()) {
                return AuthDecision.allow(AuthPrincipal.service(
                        serviceId, clientIp, "header-only", Set.copyOf(client.getScopes())));
            }
            return AuthDecision.deny("signature required");
        }
        String timestampHeader = request.getHeader(properties.getTimestampHeader());
        if (!StringUtils.hasText(timestampHeader)) {
            return AuthDecision.deny("timestamp required");
        }
        String nonce = request.getHeader(properties.getNonceHeader());
        if (!StringUtils.hasText(nonce)) {
            return AuthDecision.deny("nonce required");
        }
        long timestampMillis;
        try {
            timestampMillis = Long.parseLong(timestampHeader);
        } catch (NumberFormatException ex) {
            return AuthDecision.deny("invalid timestamp");
        }
        long nowMillis = Instant.now(clock).toEpochMilli();
        long skewSeconds = client.resolveMaxSkew(properties.getMaxSkewSeconds());
        if (skewSeconds > 0) {
            long skew = Math.abs(nowMillis - timestampMillis);
            if (skew > skewSeconds * 1000) {
                return AuthDecision.deny("timestamp skew too large");
            }
        }
        String canonical = buildCanonicalRequest(request, timestampHeader, nonce);
        String keyVersion = request.getHeader(properties.getKeyVersionHeader());
        String secret = client.resolveSecret(keyVersion);
        if (!StringUtils.hasText(secret)) {
            return AuthDecision.deny("secret not configured");
        }
        String expectedSignature = hmacSha256(secret, canonical);
        if (!constantTimeEquals(expectedSignature, signatureHeader)) {
            if (client.isAllowIpFallback() && ipTrusted) {
                log.warn("服务签名校验失败，但来源 IP 已受信任，降级放行，服务={}，来源IP={}", serviceId, clientIp);
                return AuthDecision.allow(AuthPrincipal.service(
                        serviceId, clientIp, "ip-fallback", Set.copyOf(client.getScopes())));
            }
            return AuthDecision.deny("signature mismatch");
        }
        if (!registerNonce(serviceId, nonce, skewSeconds)) {
            return AuthDecision.deny("request replayed");
        }
        return AuthDecision.allow(AuthPrincipal.service(
                serviceId,
                clientIp,
                StringUtils.hasText(keyVersion) ? keyVersion : "default",
                Set.copyOf(client.getScopes())));
    }

    /**
     * 解析或转换业务数据。
     */
    private AuthScope resolveRequiredScope(HttpServletRequest request) {
        String path = resolveApplicationPath(request);
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        for (AuthProperties.AccessRule rule : properties.getAccessRules()) {
            boolean methodMatches = CollectionUtils.isEmpty(rule.getMethods())
                    || rule.getMethods().stream().anyMatch(method::equalsIgnoreCase);
            if (methodMatches && matches(path, rule.getPaths())) {
                return rule.getRequiredScope();
            }
        }
        return properties.getDefaultRequiredScope();
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean matches(String path, List<String> patterns) {
        if (CollectionUtils.isEmpty(patterns)) {
            return false;
        }
        for (String pattern : patterns) {
            if (StringUtils.hasText(pattern) && matcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveApplicationPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && path.startsWith(contextPath)) {
            String applicationPath = path.substring(contextPath.length());
            return StringUtils.hasText(applicationPath) ? applicationPath : "/";
        }
        return path;
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<String> combine(List<String> preferred, List<String> fallback) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (!CollectionUtils.isEmpty(preferred)) {
            merged.addAll(preferred);
        }
        if (!CollectionUtils.isEmpty(fallback)) {
            merged.addAll(fallback);
        }
        return merged.isEmpty() ? List.of() : List.copyOf(merged);
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildCanonicalRequest(HttpServletRequest request, String timestamp, String nonce) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String path = request.getRequestURI();
        String query = request.getQueryString();
        return String.join("\n",
                timestamp,
                nonce,
                method,
                path,
                query == null ? "" : query,
                sha256Base64(resolveBody(request)));
    }

    /**
     * 解析或转换业务数据。
     */
    private byte[] resolveBody(HttpServletRequest request) {
        if (request instanceof CachedBodyRequest cachedBodyRequest) {
            return cachedBodyRequest.body();
        }
        return new byte[0];
    }

    /**
     * 执行当前业务逻辑。
     */
    private String sha256Base64(byte[] content) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(messageDigest.digest(content));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法计算请求体摘要", exception);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("unable to compute signature", e);
        }
    }

    /**
     * 维护注册或订阅关系。
     */
    private boolean registerNonce(String serviceId, String nonce, long skewSeconds) {
        long effectiveSkewSeconds = Math.max(1L, skewSeconds);
        String nonceKey = NONCE_KEY_PREFIX + sha256Base64(
                (serviceId + ":" + nonce).getBytes(StandardCharsets.UTF_8));
        if (stringRedisTemplate != null) {
            try {
                return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                        .setIfAbsent(nonceKey, "1", Duration.ofSeconds(effectiveSkewSeconds)));
            } catch (RuntimeException exception) {
                log.error("防重放存储不可用，拒绝服务签名请求，serviceId={}", serviceId, exception);
                return false;
            }
        }

        long nowMillis = Instant.now(clock).toEpochMilli();
        long expireAt = nowMillis + effectiveSkewSeconds * 1000L;
        Long existing = localNonces.putIfAbsent(nonceKey, expireAt);
        if (existing == null) {
            return true;
        }
        if (existing < nowMillis && localNonces.replace(nonceKey, existing, expireAt)) {
            return true;
        }
        return false;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean constantTimeEquals(String expected, String actual) {
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(actual)) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        if (expectedBytes.length != actualBytes.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < expectedBytes.length; i++) {
            result |= expectedBytes[i] ^ actualBytes[i];
        }
        return result == 0;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean ipAllowed(String clientIp, List<String> allowList) {
        if (!StringUtils.hasText(clientIp) || CollectionUtils.isEmpty(allowList)) {
            return false;
        }
        for (String rule : allowList) {
            if (!StringUtils.hasText(rule)) {
                continue;
            }
            if ("*".equals(rule)) {
                return true;
            }
            if (rule.contains("/")) {
                if (cidrMatch(clientIp, rule)) {
                    return true;
                }
            } else if (rule.equalsIgnoreCase(clientIp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean cidrMatch(String clientIp, String cidr) {
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            return false;
        }
        try {
            java.net.InetAddress target = java.net.InetAddress.getByName(clientIp);
            java.net.InetAddress network = java.net.InetAddress.getByName(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            byte[] targetBytes = target.getAddress();
            byte[] networkBytes = network.getAddress();
            if (targetBytes.length != networkBytes.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (targetBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = (-1) << (8 - remainingBits);
            return (targetBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
        } catch (Exception e) {
            log.warn("无效 cidr 规则 {}:{}", cidr, e.getMessage());
            return false;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!ipAllowed(remoteAddress, properties.getTrustedProxyRanges())) {
            return remoteAddress;
        }
        String forwarded = request.getHeader(FORWARDED_FOR_HEADER);
        if (StringUtils.hasText(forwarded)) {
            int idx = forwarded.indexOf(',');
            return idx > 0 ? forwarded.substring(0, idx).trim() : forwarded.trim();
        }
        String realIp = request.getHeader(REAL_IP_HEADER);
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return remoteAddress;
    }

    /**
     * 可重复读取请求体的签名请求包装器。
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        /**
         * 创建当前组件实例。
         */
        private CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        /**
         * 执行当前业务逻辑。
         */
        private int bodyLength() {
            return body.length;
        }

        /**
         * 执行当前业务逻辑。
         */
        private byte[] body() {
            return body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return inputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // 当前管理接口使用同步请求读取，不注册异步读取监听器。
                }

                /**
                 * 查询并返回业务数据。
                 */
                @Override
                public int read() {
                    return inputStream.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /**
     * 定义当前模块的业务组件。
     */
    @RequiredArgsConstructor
    @Getter
    static class AuthDecision {
        private final boolean allowed;
        private final String message;
        private final AuthPrincipal principal;

        /**
         * 构造标准业务结果。
         */
        static AuthDecision allow(AuthPrincipal principal) {
            return new AuthDecision(true, "OK", principal);
        }

        /**
         * 构造标准业务结果。
         */
        static AuthDecision deny(String message) {
            return new AuthDecision(false, message, null);
        }
    }

    /**
     * 定义当前模块的业务组件。
     */
    @RequiredArgsConstructor
    @Getter
    public static class AuthPrincipal {
        private final String type;
        private final String id;
        private final String source;
        private final Set<AuthScope> scopes;

        /**
         * 执行当前业务逻辑。
         */
        static AuthPrincipal ops(String label, String source, Set<AuthScope> scopes) {
            return new AuthPrincipal("OPS_TOKEN", label, source, scopes);
        }

        /**
         * 执行当前业务逻辑。
         */
        static AuthPrincipal service(String serviceId,
                                     String source,
                                     String credential,
                                     Set<AuthScope> scopes) {
            return new AuthPrincipal("SERVICE", serviceId + ":" + credential, source, scopes);
        }
    }
}

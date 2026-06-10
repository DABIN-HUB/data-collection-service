package com.wangbin.collector.api.filter;

import com.wangbin.collector.api.filter.config.AuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 简单鉴权过滤器，支持运维 Token 及服务签名。
 */
@Slf4j
public class AuthFilter extends OncePerRequestFilter {

    public static final String ATTR_PRINCIPAL = AuthFilter.class.getName() + ".principal";

    private final AuthProperties properties;
    private final Clock clock;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public AuthFilter(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AuthFilter(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = resolveApplicationPath(request);
        return matches(path, properties.getPermitAllPaths());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        AuthDecision decision = authorize(request);
        if (!decision.isAllowed()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter()
                    .append("{\"status\":\"error\",\"message\":\"")
                    .append(decision.getMessage())
                    .append("\"}");
            return;
        }
        if (decision.getPrincipal() != null) {
            request.setAttribute(ATTR_PRINCIPAL, decision.getPrincipal());
        }
        filterChain.doFilter(request, response);
    }

    private AuthDecision authorize(HttpServletRequest request) {
        String serviceId = request.getHeader(properties.getServiceHeader());
        if (StringUtils.hasText(serviceId)) {
            return authorizeService(request, serviceId);
        }
        String token = request.getHeader(properties.getTokenHeader());
        if (StringUtils.hasText(token)) {
            return authorizeOps(request, token);
        }
        if (ipAllowed(resolveClientIp(request), properties.getIpAllowList())) {
            return AuthDecision.allow(AuthPrincipal.ops("ip-allowlist", "global"));
        }
        return AuthDecision.deny("missing credential");
    }

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
        return AuthDecision.allow(AuthPrincipal.ops(label, ip));
    }

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
                return AuthDecision.allow(AuthPrincipal.service(serviceId, clientIp, "ip-fallback"));
            }
            if (!client.isRequireSignature()) {
                return AuthDecision.allow(AuthPrincipal.service(serviceId, clientIp, "header-only"));
            }
            return AuthDecision.deny("signature required");
        }
        String timestampHeader = request.getHeader(properties.getTimestampHeader());
        if (!StringUtils.hasText(timestampHeader)) {
            return AuthDecision.deny("timestamp required");
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
        String canonical = buildCanonicalRequest(request, timestampHeader);
        String keyVersion = request.getHeader(properties.getKeyVersionHeader());
        String secret = client.resolveSecret(keyVersion);
        if (!StringUtils.hasText(secret)) {
            return AuthDecision.deny("secret not configured");
        }
        String expectedSignature = hmacSha256(secret, canonical);
        if (!constantTimeEquals(expectedSignature, signatureHeader)) {
            if (client.isAllowIpFallback() && ipTrusted) {
                log.warn("signature check failed for service {} but IP {} is trusted, fallback allowed", serviceId, clientIp);
                return AuthDecision.allow(AuthPrincipal.service(serviceId, clientIp, "ip-fallback"));
            }
            return AuthDecision.deny("signature mismatch");
        }
        return AuthDecision.allow(AuthPrincipal.service(serviceId, clientIp,
                StringUtils.hasText(keyVersion) ? keyVersion : "default"));
    }

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

    private String resolveApplicationPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && path.startsWith(contextPath)) {
            String applicationPath = path.substring(contextPath.length());
            return StringUtils.hasText(applicationPath) ? applicationPath : "/";
        }
        return path;
    }

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

    private String buildCanonicalRequest(HttpServletRequest request, String timestamp) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String path = request.getRequestURI();
        String query = request.getQueryString();
        return timestamp + '\n' + method + '\n' + path + '\n' + (query == null ? "" : query);
    }

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
            log.warn("invalid cidr rule {}: {}", cidr, e.getMessage());
            return false;
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int idx = forwarded.indexOf(',');
            return idx > 0 ? forwarded.substring(0, idx).trim() : forwarded.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    @RequiredArgsConstructor
    @Getter
    static class AuthDecision {
        private final boolean allowed;
        private final String message;
        private final AuthPrincipal principal;

        static AuthDecision allow(AuthPrincipal principal) {
            return new AuthDecision(true, "OK", principal);
        }

        static AuthDecision deny(String message) {
            return new AuthDecision(false, message, null);
        }
    }

    @RequiredArgsConstructor
    @Getter
    public static class AuthPrincipal {
        private final String type;
        private final String id;
        private final String source;

        static AuthPrincipal ops(String label, String source) {
            return new AuthPrincipal("OPS_TOKEN", label, source);
        }

        static AuthPrincipal service(String serviceId, String source, String credential) {
            return new AuthPrincipal("SERVICE", serviceId + ":" + credential, source);
        }
    }
}

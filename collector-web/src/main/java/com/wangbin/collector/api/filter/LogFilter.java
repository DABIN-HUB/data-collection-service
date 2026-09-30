package com.wangbin.collector.api.filter;

import com.wangbin.collector.api.filter.config.AccessLogProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 访问日志过滤器。
 */
public class LogFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(LogFilter.class);
    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("collector.access");
    private static final String DEFAULT_REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MASKED_VALUE = "***";
    private static final Set<String> SENSITIVE_QUERY_KEYS = Set.of(
            "password", "passwd", "pwd", "token", "secret", "authorization",
            "accesskey", "devicekey", "credential", "signature");
    private static final List<String> SENSITIVE_HEADER_TOKENS = List.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "token",
            "secret", "signature", "credential", "access-key", "device-key", "api-key");

    private final AccessLogProperties properties;
    private final AntPathMatcher matcher = new AntPathMatcher();

    /**
     * 创建当前组件实例。
     */
    public LogFilter(AccessLogProperties properties) {
        this.properties = properties;
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
        return !matches(path, properties.getIncludePaths()) || matches(path, properties.getExcludePaths());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, responseWrapper);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            try {
                logAccess(request, responseWrapper, durationMs);
            } catch (Exception e) {
                LOG.warn("访问日志记录异常: {}", e.getMessage(), e);
            }
            responseWrapper.copyBodyToResponse();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void logAccess(HttpServletRequest request,
                           ContentCachingResponseWrapper response,
                           long durationMs) {
        String requestId = resolveRequestId(request);
        String path = resolveApplicationPath(request);
        String method = request.getMethod();
        String query = truncate(sanitizeQuery(request.getQueryString()), properties.getMaxQueryLength());
        String clientIp = resolveClientIp(request);
        Object principal = request.getAttribute(AuthFilter.ATTR_PRINCIPAL);
        String deviceId = resolveDeviceId(request, path);
        boolean highRisk = isHighRisk(method, path);
        boolean success = response.getStatus() < 400;

        StringBuilder builder = new StringBuilder(256)
                .append("config_access")
                .append(" requestId=").append(requestId)
                .append(" 方法=").append(method)
                .append(" 路径=").append(path)
                .append(" 查询=").append(StringUtils.hasText(query) ? query : "-")
                .append(" 状态=").append(response.getStatus())
                .append(" 成功=").append(success)
                .append(" 耗时毫秒=").append(durationMs)
                .append(" 客户端IP=").append(clientIp)
                .append(" 主体=").append(resolvePrincipal(principal));

        if (properties.isLogBodySize()) {
            builder.append(" 请求字节=").append(Math.max(request.getContentLengthLong(), 0))
                    .append(" 响应字节=").append(response.getContentSize());
        }

        if (StringUtils.hasText(deviceId)) {
            builder.append(" 设备=").append(deviceId);
        }

        List<String> extraHeaders = properties.getAdditionalHeaders();
        if (!CollectionUtils.isEmpty(extraHeaders)) {
            for (String header : extraHeaders) {
                if (!StringUtils.hasText(header)) {
                    continue;
                }
                String value = request.getHeader(header);
                if (StringUtils.hasText(value)) {
                    builder.append(' ').append(header).append('=').append(sanitizeHeader(header, value));
                }
            }
        }

        if (highRisk) {
            builder.append(" risk=HIGH");
            ACCESS_LOG.warn(builder.toString());
        } else {
            ACCESS_LOG.info(builder.toString());
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolvePrincipal(Object principal) {
        if (principal instanceof AuthFilter.AuthPrincipal authPrincipal) {
            return authPrincipal.getType() + ":" + authPrincipal.getId();
        }
        return "-";
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveRequestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID);
        if (attribute instanceof String requestId && StringUtils.hasText(requestId)) {
            return requestId;
        }
        String incoming = request.getHeader(resolveRequestIdHeader());
        if (StringUtils.hasText(incoming)) {
            return incoming;
        }
        String generated = UUID.randomUUID().toString();
        request.setAttribute(RequestCorrelationFilter.ATTR_REQUEST_ID, generated);
        return generated;
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveRequestIdHeader() {
        return StringUtils.hasText(properties.getRequestIdHeader())
                ? properties.getRequestIdHeader()
                : DEFAULT_REQUEST_ID_HEADER;
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveApplicationPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && StringUtils.hasText(path) && path.startsWith(contextPath)) {
            String applicationPath = path.substring(contextPath.length());
            return StringUtils.hasText(applicationPath) ? applicationPath : "/";
        }
        return path;
    }

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 执行当前业务逻辑。
     */
    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveDeviceId(HttpServletRequest request, String applicationPath) {
        String param = request.getParameter("deviceId");
        if (StringUtils.hasText(param)) {
            return param;
        }
        if (!StringUtils.hasText(applicationPath)) {
            return null;
        }
        String[] segments = applicationPath.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if ("device".equalsIgnoreCase(segments[i]) && StringUtils.hasText(segments[i + 1])) {
                return segments[i + 1];
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean isHighRisk(String method, String path) {
        List<AccessLogProperties.RiskRule> rules = properties.getHighRiskRules();
        if (CollectionUtils.isEmpty(rules)) {
            return false;
        }
        for (AccessLogProperties.RiskRule rule : rules) {
            if (!StringUtils.hasText(rule.getPattern())) {
                continue;
            }
            String configuredMethod = StringUtils.hasText(rule.getMethod())
                    ? rule.getMethod()
                    : "*";
            boolean methodMatches = "*".equals(configuredMethod)
                    || configuredMethod.equalsIgnoreCase(method);
            if (methodMatches && matcher.match(rule.getPattern(), path)) {
                return true;
            }
        }
        return false;
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
    private String sanitizeQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return query;
        }
        String[] parts = query.split("&", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = sanitizeQueryPart(parts[i]);
        }
        return String.join("&", parts);
    }

    /**
     * 解析或转换业务数据。
     */
    private String sanitizeQueryPart(String part) {
        int separator = part.indexOf('=');
        String key = separator >= 0 ? part.substring(0, separator) : part;
        if (SENSITIVE_QUERY_KEYS.contains(normalizeKey(key))) {
            return key + "=" + MASKED_VALUE;
        }
        return part;
    }

    /**
     * 解析或转换业务数据。
     */
    private String sanitizeHeader(String header, String value) {
        String normalized = normalizeHeader(header);
        for (String token : SENSITIVE_HEADER_TOKENS) {
            if (normalized.equals(token) || normalized.contains(token)) {
                return MASKED_VALUE;
            }
        }
        return value;
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeKey(String key) {
        return safeText(key).replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeHeader(String header) {
        return safeText(header).toLowerCase(Locale.ROOT);
    }

    /**
     * 解析或转换业务数据。
     */
    private String safeText(String value) {
        return value == null ? "" : value;
    }
}

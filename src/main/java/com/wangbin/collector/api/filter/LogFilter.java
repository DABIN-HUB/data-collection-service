package com.wangbin.collector.api.filter;

import com.wangbin.collector.api.filter.config.AccessLogProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 访问日志过滤器
 */
@Slf4j
public class LogFilter extends OncePerRequestFilter {

    private static final String DEFAULT_REQUEST_ID_HEADER = "X-Request-Id";

    private final AccessLogProperties properties;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public LogFilter(AccessLogProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return !matches(path, properties.getIncludePaths()) || matches(path, properties.getExcludePaths());
    }

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
                log.warn("访问日志记录异常: {}", e.getMessage(), e);
            }
            responseWrapper.copyBodyToResponse();
        }
    }

    private void logAccess(HttpServletRequest request,
                           ContentCachingResponseWrapper response,
                           long durationMs) {
        String requestId = resolveRequestId(request);
        String path = request.getRequestURI();
        String method = request.getMethod();
        String query = truncate(request.getQueryString(), properties.getMaxQueryLength());
        String clientIp = resolveClientIp(request);
        Object principal = request.getAttribute(AuthFilter.ATTR_PRINCIPAL);
        String deviceId = resolveDeviceId(request);
        boolean highRisk = isHighRisk(method, path);
        boolean success = response.getStatus() < 400;

        StringBuilder builder = new StringBuilder(256)
                .append("config_access")
                .append(" requestId=").append(requestId)
                .append(" method=").append(method)
                .append(" uri=").append(path)
                .append(" query=").append(StringUtils.hasText(query) ? query : "-")
                .append(" status=").append(response.getStatus())
                .append(" success=").append(success)
                .append(" latencyMs=").append(durationMs)
                .append(" ip=").append(clientIp)
                .append(" principal=").append(resolvePrincipal(principal));

        if (properties.isLogBodySize()) {
            builder.append(" reqSize=").append(Math.max(request.getContentLengthLong(), 0))
                    .append(" respSize=").append(response.getContentSize());
        }

        if (StringUtils.hasText(deviceId)) {
            builder.append(" deviceId=").append(deviceId);
        }

        List<String> extraHeaders = properties.getAdditionalHeaders();
        if (!CollectionUtils.isEmpty(extraHeaders)) {
            for (String header : extraHeaders) {
                if (!StringUtils.hasText(header)) {
                    continue;
                }
                String value = request.getHeader(header);
                if (StringUtils.hasText(value)) {
                    builder.append(' ').append(header).append('=').append(value);
                }
            }
        }

        if (highRisk) {
            builder.append(" risk=HIGH");
            log.warn(builder.toString());
        } else {
            log.info(builder.toString());
        }
    }

    private String resolvePrincipal(Object principal) {
        if (principal instanceof AuthFilter.AuthPrincipal authPrincipal) {
            return authPrincipal.getType() + ":" + authPrincipal.getId();
        }
        return "-";
    }

    private String resolveRequestId(HttpServletRequest request) {
        String headerName = StringUtils.hasText(properties.getRequestIdHeader())
                ? properties.getRequestIdHeader()
                : DEFAULT_REQUEST_ID_HEADER;
        String incoming = request.getHeader(headerName);
        if (StringUtils.hasText(incoming)) {
            return incoming;
        }
        String generated = UUID.randomUUID().toString();
        request.setAttribute(headerName, generated);
        return generated;
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

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String resolveDeviceId(HttpServletRequest request) {
        String param = request.getParameter("deviceId");
        if (StringUtils.hasText(param)) {
            return param;
        }
        String path = request.getRequestURI();
        if (!StringUtils.hasText(path)) {
            return null;
        }
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if ("device".equalsIgnoreCase(segments[i]) && StringUtils.hasText(segments[i + 1])) {
                return segments[i + 1];
            }
        }
        return null;
    }

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
            if (!methodMatches) {
                continue;
            }
            if (matcher.match(rule.getPattern(), path)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(String path, List<String> patterns) {
        if (CollectionUtils.isEmpty(patterns)) {
            return false;
        }
        for (String pattern : patterns) {
            if (!StringUtils.hasText(pattern)) {
                continue;
            }
            if (matcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}

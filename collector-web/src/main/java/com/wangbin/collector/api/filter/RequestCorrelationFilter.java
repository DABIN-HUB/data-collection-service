package com.wangbin.collector.api.filter;

import com.wangbin.collector.api.filter.config.AccessLogProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * HTTP 请求关联标识过滤器。
 */
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String ATTR_REQUEST_ID = RequestCorrelationFilter.class.getName() + ".requestId";
    public static final String MDC_REQUEST_ID = "requestId";
    private static final String DEFAULT_REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final AccessLogProperties properties;

    /**
     * 创建当前组件实例。
     */
    public RequestCorrelationFilter(AccessLogProperties properties) {
        this.properties = properties;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        String headerName = resolveHeaderName();
        String previousRequestId = MDC.get(MDC_REQUEST_ID);
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        response.setHeader(headerName, requestId);
        MDC.put(MDC_REQUEST_ID, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previousRequestId != null) {
                MDC.put(MDC_REQUEST_ID, previousRequestId);
            } else {
                MDC.remove(MDC_REQUEST_ID);
            }
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(resolveHeaderName());
        if (isSafeRequestId(incoming)) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 解析或转换业务数据。
     */
    private boolean isSafeRequestId(String value) {
        return StringUtils.hasText(value) && SAFE_REQUEST_ID.matcher(value).matches();
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveHeaderName() {
        return StringUtils.hasText(properties.getRequestIdHeader())
                ? properties.getRequestIdHeader()
                : DEFAULT_REQUEST_ID_HEADER;
    }
}

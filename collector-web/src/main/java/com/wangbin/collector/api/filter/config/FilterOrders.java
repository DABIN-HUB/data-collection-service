package com.wangbin.collector.api.filter.config;

import org.springframework.core.Ordered;

/**
 * Web 过滤器顺序定义。
 */
public final class FilterOrders {

    public static final int REQUEST_CORRELATION = Ordered.HIGHEST_PRECEDENCE;
    public static final int ACCESS_LOG = Ordered.HIGHEST_PRECEDENCE + 1;
    public static final int AUTH = Ordered.HIGHEST_PRECEDENCE + 2;

    private FilterOrders() {
    }
}

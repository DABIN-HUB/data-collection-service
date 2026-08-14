package com.wangbin.collector.core.collector.runtime;

import java.util.Locale;

/**
 * 订阅不可用时的处理策略。
 */
public enum SubscriptionFallbackStrategy {

    FAIL_FAST,
    FALLBACK_TO_POLLING;

    /**
     * 创建并返回业务对象。
     */
    public static SubscriptionFallbackStrategy fromValue(String value) {
        if (value == null || value.isBlank()) {
            return FAIL_FAST;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return FAIL_FAST;
        }
    }
}

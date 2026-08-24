package com.wangbin.collector.core.cloud.config;

import java.util.Locale;

/**
 * payload 可选字段输出策略。
 */
public enum CloudPayloadFieldMode {

    /**
     * 始终输出。
     */
    ALWAYS,

    /**
     * 从不输出。
     */
    NEVER,

    /**
     * 仅异常时输出。
     */
    ON_ERROR;

    /**
     * 创建并返回业务对象。
     */
    public static CloudPayloadFieldMode from(String value) {
        if (value == null || value.isBlank()) {
            return ON_ERROR;
        }
        try {
            return CloudPayloadFieldMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ON_ERROR;
        }
    }
}

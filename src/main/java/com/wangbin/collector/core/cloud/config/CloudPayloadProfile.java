package com.wangbin.collector.core.cloud.config;

import java.util.Locale;

/**
 * 云端 payload 输出档位。
 */
public enum CloudPayloadProfile {

    /**
     * 极简模式：只保留云平台必需字段，适合高频属性上报。
     */
    COMPACT,

    /**
     * 标准模式：保留排障常用字段。
     */
    STANDARD,

    /**
     * 诊断模式：保留完整追踪字段，适合联调和问题排查。
     */
    DIAGNOSTIC;

    public static CloudPayloadProfile from(String value) {
        if (value == null || value.isBlank()) {
            return COMPACT;
        }
        try {
            return CloudPayloadProfile.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return COMPACT;
        }
    }
}

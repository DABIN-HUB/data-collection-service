package com.wangbin.collector.core.cloud.config;

import java.util.Locale;

/**
 * 云平台业务 ACK 处理模式。
 */
public enum CloudAckMode {

    /**
     * 发布成功后不等待平台业务 ACK，ACK 在后台闭环。
     */
    ASYNC,

    /**
     * 同步等待平台业务 ACK。
     */
    SYNC,

    /**
     * 不跟踪业务 ACK。
     */
    DISABLED;

    public static CloudAckMode from(String value) {
        if (value == null || value.isBlank()) {
            return ASYNC;
        }
        try {
            return CloudAckMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ASYNC;
        }
    }
}

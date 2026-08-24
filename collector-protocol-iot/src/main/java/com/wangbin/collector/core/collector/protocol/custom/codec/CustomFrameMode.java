package com.wangbin.collector.core.collector.protocol.custom.codec;

import java.util.Locale;

/**
 * 自定义协议帧边界模式。
 */
public enum CustomFrameMode {

    LENGTH_FIELD,
    FIXED_LENGTH,
    DELIMITER,
    DATAGRAM;

    /**
     * 创建并返回业务对象。
     */
    public static CustomFrameMode fromValue(String value, CustomFrameMode defaultMode) {
        if (value == null || value.isBlank()) {
            return defaultMode;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return defaultMode;
        }
    }
}

package com.wangbin.collector.core.collector.protocol.fins.domain;

import java.util.Locale;

/**
 * 定义当前模块的枚举值。
 */
public enum FinsWordOrder {
    BIG_ENDIAN,
    LITTLE_ENDIAN;

    /**
     * 创建并返回业务对象。
     */
    public static FinsWordOrder from(Object value, FinsWordOrder defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.toString().trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LE", "LITTLE", "LITTLE_ENDIAN", "REVERSE" -> LITTLE_ENDIAN;
            case "BE", "BIG", "BIG_ENDIAN", "NORMAL" -> BIG_ENDIAN;
            default -> defaultValue;
        };
    }
}
package com.wangbin.collector.core.collector.protocol.fins.domain;

import java.nio.ByteOrder;
import java.util.Locale;

/**
 * 定义当前模块的枚举值。
 */
public enum FinsByteOrder {
    BIG_ENDIAN,
    LITTLE_ENDIAN;

    /**
     * 创建并返回业务对象。
     */
    public static FinsByteOrder from(Object value, FinsByteOrder defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.toString().trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LE", "LITTLE", "LITTLE_ENDIAN" -> LITTLE_ENDIAN;
            case "BE", "BIG", "BIG_ENDIAN" -> BIG_ENDIAN;
            default -> defaultValue;
        };
    }

    /**
     * 解析或转换业务数据。
     */
    public ByteOrder toNioOrder() {
        return this == LITTLE_ENDIAN ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }
}
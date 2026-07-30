package com.wangbin.collector.core.collector.protocol.fins.domain;

import java.nio.ByteOrder;
import java.util.Locale;

public enum FinsByteOrder {
    BIG_ENDIAN,
    LITTLE_ENDIAN;

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

    public ByteOrder toNioOrder() {
        return this == LITTLE_ENDIAN ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }
}
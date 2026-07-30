package com.wangbin.collector.core.collector.protocol.fins.domain;

import java.util.Locale;

public enum FinsWordOrder {
    BIG_ENDIAN,
    LITTLE_ENDIAN;

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
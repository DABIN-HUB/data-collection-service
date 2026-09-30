package com.wangbin.collector.core.collector.protocol.iec.base;

/**
 * IEC104 信息体地址编码模式。
 */
public enum Iec104IoaEncodingMode {
    STANDARD,
    SHIFT8_COMPAT;

    public static Iec104IoaEncodingMode from(Object value) {
        if (value == null || value.toString().isBlank()) {
            return STANDARD;
        }
        try {
            return valueOf(value.toString().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported IEC104 ioaEncodingMode: " + value, exception);
        }
    }
}

package com.wangbin.collector.core.collector.protocol.iec.base;

/**
 * IEC104 信息体地址编码模式。
 */
public enum Iec104IoaEncodingMode {
    STANDARD,
    SHIFT8_COMPAT;

    public static Iec104IoaEncodingMode from(Object value) {
        if (value == null) {
            return STANDARD;
        }
        try {
            return valueOf(value.toString().trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return STANDARD;
        }
    }
}

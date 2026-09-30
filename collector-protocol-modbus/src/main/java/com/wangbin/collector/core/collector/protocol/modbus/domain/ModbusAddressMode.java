package com.wangbin.collector.core.collector.protocol.modbus.domain;

/** Modbus 地址解释模式。 */
public enum ModbusAddressMode {
    RAW_OFFSET,
    REFERENCE,
    AUTO_COMPAT;

    public static ModbusAddressMode from(Object value) {
        if (value == null) return AUTO_COMPAT;
        try { return valueOf(value.toString().trim().toUpperCase()); }
        catch (IllegalArgumentException ignored) { return AUTO_COMPAT; }
    }
}

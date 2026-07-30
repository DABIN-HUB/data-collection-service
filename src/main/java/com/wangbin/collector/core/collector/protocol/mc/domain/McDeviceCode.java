package com.wangbin.collector.core.collector.protocol.mc.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public enum McDeviceCode {
    M("M", 0x90, 10, true),
    X("X", 0x9C, 16, true),
    Y("Y", 0x9D, 16, true),
    B("B", 0xA0, 16, true),
    D("D", 0xA8, 10, false),
    R("R", 0xAF, 10, false),
    W("W", 0xB4, 16, false),
    ZR("ZR", 0xB0, 10, false);

    private static final Map<String, McDeviceCode> LOOKUP = buildLookup();

    private final String symbol;
    private final int code;
    private final int radix;
    private final boolean bitDevice;

    McDeviceCode(String symbol, int code, int radix, boolean bitDevice) {
        this.symbol = symbol;
        this.code = code;
        this.radix = radix;
        this.bitDevice = bitDevice;
    }

    public String getSymbol() {
        return symbol;
    }

    public int getCode() {
        return code;
    }

    public int getRadix() {
        return radix;
    }

    public boolean isBitDevice() {
        return bitDevice;
    }

    public boolean isWordDevice() {
        return !bitDevice;
    }

    public static McDeviceCode fromPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("MC device code cannot be empty");
        }
        McDeviceCode deviceCode = LOOKUP.get(prefix.trim().toUpperCase(Locale.ROOT));
        if (deviceCode != null) {
            return deviceCode;
        }
        throw new IllegalArgumentException("Unsupported MC device code: " + prefix);
    }

    private static Map<String, McDeviceCode> buildLookup() {
        Map<String, McDeviceCode> lookup = new LinkedHashMap<>();
        for (McDeviceCode value : values()) {
            lookup.put(value.symbol, value);
        }
        return Map.copyOf(lookup);
    }
}

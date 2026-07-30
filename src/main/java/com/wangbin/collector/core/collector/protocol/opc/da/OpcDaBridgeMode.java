package com.wangbin.collector.core.collector.protocol.opc.da;

import java.util.Locale;

public enum OpcDaBridgeMode {
    INMEMORY,
    HTTP;

    public static OpcDaBridgeMode from(String value) {
        if (value == null || value.isBlank()) {
            return INMEMORY;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (OpcDaBridgeMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return INMEMORY;
    }
}

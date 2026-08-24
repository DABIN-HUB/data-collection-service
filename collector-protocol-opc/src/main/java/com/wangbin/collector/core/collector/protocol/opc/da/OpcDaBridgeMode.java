package com.wangbin.collector.core.collector.protocol.opc.da;

import java.util.Locale;

/**
 * 定义当前模块的枚举值。
 */
public enum OpcDaBridgeMode {
    INMEMORY,
    HTTP;

    /**
     * 创建并返回业务对象。
     */
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

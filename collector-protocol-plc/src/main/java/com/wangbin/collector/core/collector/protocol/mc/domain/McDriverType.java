package com.wangbin.collector.core.collector.protocol.mc.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 定义当前模块的枚举值。
 */
public enum McDriverType {
    BOOL(0, true, false),
    INT16(1, false, false),
    UINT16(1, false, false),
    INT32(2, false, false),
    UINT32(2, false, false),
    FLOAT32(2, false, false),
    FLOAT64(4, false, false),
    STRING(0, false, true);

    private static final Map<String, McDriverType> DRIVER_LOOKUP = buildDriverLookup();
    private static final Map<String, McDriverType> PLATFORM_LOOKUP = buildPlatformLookup();

    private final int wordLength;
    private final boolean bitType;
    private final boolean stringType;

    /**
     * 创建当前组件实例。
     */
    McDriverType(int wordLength, boolean bitType, boolean stringType) {
        this.wordLength = wordLength;
        this.bitType = bitType;
        this.stringType = stringType;
    }

    public int getWordLength() {
        return wordLength;
    }

    public boolean isBitType() {
        return bitType;
    }

    public boolean isStringType() {
        return stringType;
    }

    public boolean isNumericType() {
        return !bitType && !stringType;
    }

    /**
     * 创建并返回业务对象。
     */
    public static McDriverType fromDriverText(String text) {
        String normalized = normalize(text);
        McDriverType driverType = DRIVER_LOOKUP.get(normalized);
        if (driverType != null) {
            return driverType;
        }
        throw new IllegalArgumentException("Unsupported MC driver type: " + text);
    }

    /**
     * 创建并返回业务对象。
     */
    public static McDriverType fromPlatformDataType(String text) {
        String normalized = normalize(text);
        McDriverType driverType = PLATFORM_LOOKUP.get(normalized);
        if (driverType != null) {
            return driverType;
        }
        throw new IllegalArgumentException("Unsupported MC platform data type mapping: " + text);
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("MC type cannot be empty");
        }
        return text.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 创建并返回业务对象。
     */
    private static Map<String, McDriverType> buildDriverLookup() {
        Map<String, McDriverType> lookup = new LinkedHashMap<>();
        register(lookup, BOOL, "BOOLEAN");
        register(lookup, INT16, "INT", "SHORT");
        register(lookup, UINT16, "WORD");
        register(lookup, INT32, "DINT", "LONG");
        register(lookup, UINT32, "DWORD");
        register(lookup, FLOAT32, "REAL", "FLOAT");
        register(lookup, FLOAT64, "DOUBLE", "LREAL");
        register(lookup, STRING);
        return Map.copyOf(lookup);
    }

    /**
     * 创建并返回业务对象。
     */
    private static Map<String, McDriverType> buildPlatformLookup() {
        Map<String, McDriverType> lookup = new LinkedHashMap<>();
        register(lookup, BOOL, "BOOLEAN");
        register(lookup, INT16, "INT", "SHORT");
        register(lookup, UINT16, "WORD");
        register(lookup, INT32, "LONG");
        register(lookup, UINT32, "DWORD");
        register(lookup, FLOAT32, "FLOAT", "REAL");
        register(lookup, FLOAT64, "DOUBLE");
        register(lookup, STRING);
        return Map.copyOf(lookup);
    }

    /**
     * 维护注册或订阅关系。
     */
    private static void register(Map<String, McDriverType> lookup, McDriverType type, String... aliases) {
        lookup.put(type.name(), type);
        if (aliases != null) {
            for (String alias : aliases) {
                lookup.put(alias.toUpperCase(Locale.ROOT), type);
            }
        }
    }
}

package com.wangbin.collector.core.collector.protocol.s7.domain;

import com.wangbin.collector.core.collector.protocol.plc4x.domain.CodecBackedPlcType;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 定义当前模块的枚举值。
 */
public enum S7PlcType implements CodecBackedPlcType<S7ValueCodec> {
    BOOL(S7ValueCodec.BOOL, "BOOLEAN"),
    SINT(S7ValueCodec.INT8_SIGNED, "INT8"),
    USINT(S7ValueCodec.INT8_UNSIGNED, "UINT8", "BYTE"),
    INT(S7ValueCodec.INT16_SIGNED, "SHORT", "INT16"),
    UINT(S7ValueCodec.INT16_UNSIGNED, "UINT16", "WORD"),
    DINT(S7ValueCodec.INT32_SIGNED, "LONG", "INT32"),
    UDINT(S7ValueCodec.INT32_UNSIGNED, "UINT32", "DWORD"),
    LINT(S7ValueCodec.INT64_SIGNED, "INT64"),
    ULINT(S7ValueCodec.INT64_UNSIGNED, "UINT64", "LWORD"),
    REAL(S7ValueCodec.FLOAT32, "FLOAT", "FLOAT32", "FLOAT32_SWAP", "FLOAT32_LITTLE"),
    LREAL(S7ValueCodec.FLOAT64, "FLOAT64", "FLOAT64_SWAP", "FLOAT64_LITTLE", "DOUBLE", "DOUBLE_SWAP"),
    CHAR(S7ValueCodec.TEXT),
    WCHAR(S7ValueCodec.TEXT),
    STRING(S7ValueCodec.TEXT),
    WSTRING(S7ValueCodec.TEXT),
    TIME(S7ValueCodec.PASSTHROUGH),
    LTIME(S7ValueCodec.PASSTHROUGH),
    DATE(S7ValueCodec.PASSTHROUGH),
    TIME_OF_DAY(S7ValueCodec.PASSTHROUGH),
    DATE_AND_TIME(S7ValueCodec.PASSTHROUGH),
    S5TIME(S7ValueCodec.PASSTHROUGH);

    private static final Map<String, S7PlcType> LOOKUP = buildLookup();

    private final S7ValueCodec codec;
    private final String[] aliases;

    /**
     * 创建当前组件实例。
     */
    S7PlcType(S7ValueCodec codec, String... aliases) {
        this.codec = codec;
        this.aliases = aliases;
    }

    public S7ValueCodec getCodec() {
        return codec;
    }

    /**
     * 解析或转换业务数据。
     */
    public String toTypeExpression() {
        return name();
    }

    /**
     * 创建并返回业务对象。
     */
    public static S7PlcType fromText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("S7 PLC type cannot be empty");
        }
        String normalized = normalize(text);
        S7PlcType type = LOOKUP.get(normalized);
        if (type != null) {
            return type;
        }
        throw new IllegalArgumentException("Unsupported S7 PLC type: " + text);
    }

    /**
     * 创建并返回业务对象。
     */
    private static Map<String, S7PlcType> buildLookup() {
        Map<String, S7PlcType> lookup = new LinkedHashMap<>();
        for (S7PlcType type : values()) {
            lookup.put(type.name(), type);
            for (String alias : type.aliases) {
                lookup.put(normalize(alias), type);
            }
        }
        return lookup;
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalize(String text) {
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("STRING(")) {
            return STRING.name();
        }
        if (normalized.startsWith("WSTRING(")) {
            return WSTRING.name();
        }
        return normalized;
    }
}

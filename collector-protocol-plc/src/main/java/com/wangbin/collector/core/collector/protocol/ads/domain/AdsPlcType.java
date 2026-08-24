package com.wangbin.collector.core.collector.protocol.ads.domain;

import com.wangbin.collector.core.collector.protocol.plc4x.domain.CodecBackedPlcType;
import com.wangbin.collector.core.collector.protocol.plc4x.domain.Plc4xValueCodec;
import com.wangbin.collector.core.collector.protocol.plc4x.domain.PlcTypeAliasLookup;

import java.util.Locale;

/**
 * 定义当前模块的枚举值。
 */
public enum AdsPlcType implements CodecBackedPlcType<Plc4xValueCodec> {
    BOOL(Plc4xValueCodec.BOOL),
    BYTE(Plc4xValueCodec.BYTE_SIGNED),
    SINT(Plc4xValueCodec.BYTE_SIGNED),
    USINT(Plc4xValueCodec.INT32),
    INT(Plc4xValueCodec.INT32),
    UINT(Plc4xValueCodec.INT32),
    DINT(Plc4xValueCodec.INT32),
    UDINT(Plc4xValueCodec.INT64),
    LINT(Plc4xValueCodec.INT64),
    ULINT(Plc4xValueCodec.UINT64_BIGINT),
    REAL(Plc4xValueCodec.FLOAT32),
    LREAL(Plc4xValueCodec.FLOAT64),
    STRING(Plc4xValueCodec.STRING),
    WSTRING(Plc4xValueCodec.STRING);

    private static final PlcTypeAliasLookup<AdsPlcType> DRIVER_LOOKUP = PlcTypeAliasLookup.<AdsPlcType>builder()
            .register(BOOL, "BOOLEAN")
            .register(BYTE, "CHAR")
            .register(SINT, "INT8")
            .register(USINT, "UINT8")
            .register(INT, "SHORT", "INT16")
            .register(UINT, "UINT16", "WORD")
            .register(DINT, "LONG", "INT32")
            .register(UDINT, "UINT32", "DWORD")
            .register(LINT, "INT64")
            .register(ULINT, "UINT64", "LWORD")
            .register(REAL, "FLOAT", "FLOAT32", "FLOAT32_SWAP", "FLOAT32_LITTLE")
            .register(LREAL, "FLOAT64", "FLOAT64_SWAP", "FLOAT64_LITTLE", "DOUBLE", "DOUBLE_SWAP")
            .register(STRING)
            .register(WSTRING)
            .build();

    private static final PlcTypeAliasLookup<AdsPlcType> PLATFORM_LOOKUP = PlcTypeAliasLookup.<AdsPlcType>builder()
            .register(BOOL, "BOOLEAN")
            .alias(SINT, "BYTE", "INT8")
            .register(USINT, "UINT8")
            .alias(BYTE, "CHAR")
            .register(INT, "SHORT", "INT16")
            .register(UINT, "UINT16", "WORD")
            .register(DINT, "LONG", "INT32")
            .register(UDINT, "UINT32", "DWORD")
            .register(LINT, "INT64")
            .register(ULINT, "UINT64", "LWORD")
            .register(REAL, "FLOAT", "FLOAT32", "FLOAT32_SWAP", "FLOAT32_LITTLE")
            .register(LREAL, "FLOAT64", "FLOAT64_SWAP", "FLOAT64_LITTLE", "DOUBLE", "DOUBLE_SWAP")
            .register(STRING)
            .register(WSTRING)
            .build();

    private final Plc4xValueCodec codec;

    /**
     * 创建当前组件实例。
     */
    AdsPlcType(Plc4xValueCodec codec) {
        this.codec = codec;
    }

    public Plc4xValueCodec getCodec() {
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
    public static AdsPlcType fromDriverText(String text) {
        String normalized = normalize(text);
        return DRIVER_LOOKUP.require(normalized, "Unsupported ADS PLC type: " + text);
    }

    /**
     * 创建并返回业务对象。
     */
    public static AdsPlcType fromPlatformDataType(String text) {
        String normalized = normalize(text);
        return PLATFORM_LOOKUP.require(normalized, "Unsupported ADS data type mapping: " + text);
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("ADS PLC type cannot be empty");
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("STRING(")) {
            return "STRING";
        }
        if (normalized.startsWith("WSTRING(")) {
            return "WSTRING";
        }
        return normalized;
    }
}
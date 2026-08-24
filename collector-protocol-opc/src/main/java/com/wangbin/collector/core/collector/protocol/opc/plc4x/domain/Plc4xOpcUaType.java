package com.wangbin.collector.core.collector.protocol.opc.plc4x.domain;

import com.wangbin.collector.core.collector.protocol.plc4x.domain.CodecBackedPlcType;
import com.wangbin.collector.core.collector.protocol.plc4x.domain.Plc4xValueCodec;
import com.wangbin.collector.core.collector.protocol.plc4x.domain.PlcTypeAliasLookup;

import java.util.Locale;

/**
 * 定义当前模块的枚举值。
 */
public enum Plc4xOpcUaType implements CodecBackedPlcType<Plc4xValueCodec> {
    BOOL(Plc4xValueCodec.BOOL),
    BYTE(Plc4xValueCodec.BYTE_SIGNED),
    SINT(Plc4xValueCodec.BYTE_SIGNED),
    USINT(Plc4xValueCodec.INT32),
    INT(Plc4xValueCodec.INT32),
    UINT(Plc4xValueCodec.INT32),
    DINT(Plc4xValueCodec.INT64),
    UDINT(Plc4xValueCodec.INT64),
    LINT(Plc4xValueCodec.INT64),
    ULINT(Plc4xValueCodec.UINT64_BIGINT),
    REAL(Plc4xValueCodec.FLOAT32),
    LREAL(Plc4xValueCodec.FLOAT64),
    CHAR(Plc4xValueCodec.STRING),
    WCHAR(Plc4xValueCodec.STRING),
    STRING(Plc4xValueCodec.STRING),
    TIME(Plc4xValueCodec.DURATION),
    DATE(Plc4xValueCodec.DATE),
    DATE_AND_TIME(Plc4xValueCodec.DATETIME),
    BYTESTRING(Plc4xValueCodec.RAW_BYTES);

    private static final PlcTypeAliasLookup<Plc4xOpcUaType> LOOKUP = PlcTypeAliasLookup.<Plc4xOpcUaType>builder()
            .register(BOOL, "BOOLEAN")
            .register(BYTE)
            .register(SINT, "SBYTE", "INT8")
            .register(USINT, "UINT8")
            .register(INT, "SHORT", "INT16")
            .register(UINT, "UINT16", "WORD")
            .register(DINT, "LONG", "INT32")
            .register(UDINT, "UINT32", "DWORD")
            .register(LINT, "INT64")
            .register(ULINT, "UINT64", "LWORD")
            .register(REAL, "FLOAT", "FLOAT32", "FLOAT32_SWAP", "FLOAT32_LITTLE")
            .register(LREAL, "FLOAT64", "FLOAT64_SWAP", "FLOAT64_LITTLE", "DOUBLE", "DOUBLE_SWAP")
            .register(TIME, "LTIME")
            .register(DATE, "LDATE")
            .register(DATE_AND_TIME, "DATETIME", "DATE_TIME", "LDATE_AND_TIME")
            .register(CHAR)
            .register(WCHAR)
            .register(STRING)
            .register(BYTESTRING, "BYTE_ARRAY", "BINARY")
            .build();

    private final Plc4xValueCodec codec;

    /**
     * 创建当前组件实例。
     */
    Plc4xOpcUaType(Plc4xValueCodec codec) {
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
    public static Plc4xOpcUaType fromDriverTextOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = normalize(text);
        return LOOKUP.resolveOrNull(normalized);
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalize(String text) {
        return text.trim().toUpperCase(Locale.ROOT);
    }
}
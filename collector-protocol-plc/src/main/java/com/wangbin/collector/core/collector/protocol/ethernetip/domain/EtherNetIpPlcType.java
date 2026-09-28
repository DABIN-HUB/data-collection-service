package com.wangbin.collector.core.collector.protocol.ethernetip.domain;

import com.wangbin.collector.core.collector.protocol.plc4x.domain.CodecBackedPlcType;
import com.wangbin.collector.core.collector.protocol.plc4x.domain.Plc4xValueCodec;
import com.wangbin.collector.core.collector.protocol.plc4x.domain.PlcTypeAliasLookup;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import org.apache.plc4x.java.api.value.PlcValue;

/**
 * 定义当前模块的枚举值。
 */
public enum EtherNetIpPlcType implements CodecBackedPlcType<Plc4xValueCodec> {
    BOOL(Plc4xValueCodec.BOOL),
    BYTE(Plc4xValueCodec.BYTE_SIGNED),
    SINT(Plc4xValueCodec.BYTE_SIGNED),
    USINT(Plc4xValueCodec.INT32),
    INT(Plc4xValueCodec.INT32),
    UINT(Plc4xValueCodec.INT32),
    WORD(Plc4xValueCodec.INT32),
    DINT(Plc4xValueCodec.INT32),
    UDINT(Plc4xValueCodec.INT64),
    DWORD(Plc4xValueCodec.INT64),
    LINT(Plc4xValueCodec.INT64),
    ULINT(Plc4xValueCodec.UINT64_BIGINT),
    LWORD(Plc4xValueCodec.UINT64_BIGINT),
    REAL(Plc4xValueCodec.FLOAT32),
    LREAL(Plc4xValueCodec.FLOAT64),
    STRING(Plc4xValueCodec.STRING);

    private static final PlcTypeAliasLookup<EtherNetIpPlcType> DRIVER_LOOKUP =
            PlcTypeAliasLookup.<EtherNetIpPlcType>builder()
                    .register(BOOL, "BOOLEAN")
                    .register(BYTE)
                    .register(SINT, "INT8")
                    .register(USINT, "UINT8")
                    .register(INT, "SHORT", "INT16")
                    .register(UINT, "UINT16")
                    .register(WORD)
                    .register(DINT, "LONG", "INT32")
                    .register(UDINT, "UINT32")
                    .register(DWORD)
                    .register(LINT, "INT64")
                    .register(ULINT, "UINT64")
                    .register(LWORD)
                    .register(REAL, "FLOAT", "FLOAT32", "FLOAT32_SWAP", "FLOAT32_LITTLE")
                    .register(LREAL, "FLOAT64", "FLOAT64_SWAP", "FLOAT64_LITTLE", "DOUBLE", "DOUBLE_SWAP")
                    .register(STRING, "CHAR", "WCHAR")
                    .build();

    private static final PlcTypeAliasLookup<EtherNetIpPlcType> PLATFORM_LOOKUP =
            PlcTypeAliasLookup.<EtherNetIpPlcType>builder()
                    .register(BOOL, "BOOLEAN")
                    .register(BYTE)
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
                    .register(STRING, "CHAR", "WCHAR")
                    .build();

    private final Plc4xValueCodec codec;

    /**
     * 创建当前组件实例。
     */
    EtherNetIpPlcType(Plc4xValueCodec codec) {
        this.codec = codec;
    }

    public Plc4xValueCodec getCodec() {
        return codec;
    }

    /**
     * 整数类型按 CIP 原生位宽转换，拒绝溢出而不是静默截断。
     */
    @Override
    public Object write(Object value) {
        return switch (this) {
            case BYTE, USINT -> checkedInteger(value, 8, false).shortValue();
            case SINT -> checkedInteger(value, 8, true).byteValue();
            case INT -> checkedInteger(value, 16, true).shortValue();
            case WORD, UINT -> checkedInteger(value, 16, false).intValue();
            case DINT -> checkedInteger(value, 32, true).intValue();
            case DWORD, UDINT -> checkedInteger(value, 32, false).longValue();
            case LINT -> checkedInteger(value, 64, true).longValue();
            case LWORD, ULINT -> checkedInteger(value, 64, false);
            default -> codec.write(value);
        };
    }

    /**
     * 读取时保留无符号宽度，并对驱动异常负值及超范围值直接报错。
     */
    @Override
    public Object read(PlcValue value) {
        if (value == null) {
            return null;
        }
        Object rawValue = value.getObject();
        if (rawValue == null) {
            if (value.isBigInteger()) {
                rawValue = value.getBigInteger();
            } else if (value.isLong()) {
                rawValue = value.getLong();
            } else if (value.isInteger()) {
                rawValue = value.getInteger();
            } else if (value.isShort()) {
                rawValue = value.getShort();
            } else if (value.isByte()) {
                rawValue = value.getByte();
            }
        }
        Object numericValue = rawValue;
        return switch (this) {
            case BYTE, USINT -> checkedInteger(numericValue, 8, false).shortValue();
            case SINT -> checkedInteger(numericValue, 8, true).byteValue();
            case INT -> checkedInteger(numericValue, 16, true).shortValue();
            case WORD, UINT -> checkedInteger(numericValue, 16, false).intValue();
            case DINT -> checkedInteger(numericValue, 32, true).intValue();
            case DWORD, UDINT -> checkedInteger(numericValue, 32, false).longValue();
            case LINT -> checkedInteger(numericValue, 64, true).longValue();
            case LWORD, ULINT -> checkedInteger(numericValue, 64, false);
            default -> codec.read(value);
        };
    }

    private static BigInteger checkedInteger(Object input, int bits, boolean signed) {
        Object value = input instanceof PlcValue plcValue ? plcValue.getObject() : input;
        BigInteger integer;
        try {
            if (value instanceof BigInteger bigInteger) {
                integer = bigInteger;
            } else if (value instanceof Number number) {
                integer = new BigDecimal(number.toString()).toBigIntegerExact();
            } else {
                integer = new BigDecimal(String.valueOf(value).trim()).toBigIntegerExact();
            }
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid EtherNet/IP integer value: " + value, exception);
        }
        BigInteger minimum = signed ? BigInteger.ONE.shiftLeft(bits - 1).negate() : BigInteger.ZERO;
        BigInteger maximum = BigInteger.ONE.shiftLeft(signed ? bits - 1 : bits).subtract(BigInteger.ONE);
        if (integer.compareTo(minimum) < 0 || integer.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("EtherNet/IP integer out of range (" + bits + " bits): " + value);
        }
        return integer;
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
    public static EtherNetIpPlcType fromDriverText(String text) {
        String normalized = normalize(text);
        return DRIVER_LOOKUP.require(normalized, "Unsupported EtherNet/IP PLC type: " + text);
    }

    /**
     * 创建并返回业务对象。
     */
    public static EtherNetIpPlcType fromPlatformDataType(String text) {
        String normalized = normalize(text);
        return PLATFORM_LOOKUP.require(normalized, "Unsupported EtherNet/IP data type mapping: " + text);
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("EtherNet/IP PLC type cannot be empty");
        }
        return text.trim().toUpperCase(Locale.ROOT);
    }
}
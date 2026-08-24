package com.wangbin.collector.core.collector.protocol.dlt645.codec;

import com.wangbin.collector.core.collector.protocol.dlt645.Dlt645ProtocolException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

/**
 * DL/T 645 数据标识和数据值编解码器。
 */
public final class Dlt645DataCodec {

    /**
     * 创建当前组件实例。
     */
    private Dlt645DataCodec() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static String normalizeDataIdentifier(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("DL/T 645 数据标识不能为空");
        }
        String normalized = address.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("DI:")) {
            normalized = normalized.substring(3);
        }
        normalized = normalized.replace(" ", "").replace("-", "");
        if (!normalized.matches("[0-9A-F]{8}")) {
            throw new IllegalArgumentException("DL/T 645 数据标识必须是 8 位十六进制字符");
        }
        return normalized;
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encodeDataIdentifier(String identifier) {
        String normalized = normalizeDataIdentifier(identifier);
        byte[] result = new byte[4];
        for (int index = 0; index < result.length; index++) {
            int source = normalized.length() - (index + 1) * 2;
            result[index] = (byte) Integer.parseInt(normalized.substring(source, source + 2), 16);
        }
        return result;
    }

    /**
     * 解析或转换业务数据。
     */
    public static String decodeDataIdentifier(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            throw new IllegalArgumentException("DL/T 645 数据标识至少需要 4 字节");
        }
        StringBuilder builder = new StringBuilder(8);
        for (int index = 3; index >= 0; index--) {
            builder.append(String.format("%02X", bytes[index] & 0xFF));
        }
        return builder.toString();
    }

    /**
     * 解析或转换业务数据。
     */
    public static Object decodeValue(byte[] payload,
                                     String valueType,
                                     String dataFormat,
                                     int valueIndex) throws Dlt645ProtocolException {
        String type = valueType == null || valueType.isBlank()
                ? "BCD" : valueType.trim().toUpperCase(Locale.ROOT);
        byte[] selected = selectValue(payload, dataFormat, valueIndex);
        return switch (type) {
            case "BCD", "DECIMAL" -> decodeBcd(selected, decimalPlaces(dataFormat));
            case "UINT_LE" -> decodeUnsignedLittleEndian(selected);
            case "INT_LE" -> decodeSignedLittleEndian(selected);
            case "FLOAT_LE" -> decodeFloatLittleEndian(selected);
            case "ASCII" -> new String(selected, StandardCharsets.US_ASCII).trim();
            case "DATETIME" -> decodeDateTime(selected);
            case "HEX", "RAW" -> toHex(selected);
            default -> throw new Dlt645ProtocolException("不支持的 DL/T 645 数据类型: " + valueType);
        };
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encodeValue(Object value,
                                     String valueType,
                                     String dataFormat) throws Dlt645ProtocolException {
        String type = valueType == null || valueType.isBlank()
                ? "BCD" : valueType.trim().toUpperCase(Locale.ROOT);
        int length = bytesPerValue(dataFormat);
        return switch (type) {
            case "BCD", "DECIMAL" -> encodeBcd(value, length, decimalPlaces(dataFormat));
            case "UINT_LE", "INT_LE" -> encodeInteger(value, length);
            case "FLOAT_LE" -> encodeFloat(value);
            case "ASCII" -> encodeAscii(value, length);
            case "HEX", "RAW" -> parseHex(String.valueOf(value));
            default -> throw new Dlt645ProtocolException("不支持写入的数据类型: " + valueType);
        };
    }

    /**
     * 执行当前业务逻辑。
     */
    private static byte[] selectValue(byte[] payload, String format, int valueIndex)
            throws Dlt645ProtocolException {
        if (payload == null) {
            throw new Dlt645ProtocolException("DL/T 645 响应数据为空");
        }
        int length = bytesPerValue(format);
        if (length <= 0) {
            return Arrays.copyOf(payload, payload.length);
        }
        int offset = Math.max(0, valueIndex) * length;
        if (offset + length > payload.length) {
            throw new Dlt645ProtocolException("DL/T 645 响应长度不足，无法读取指定值索引");
        }
        return Arrays.copyOfRange(payload, offset, offset + length);
    }

    /**
     * 解析或转换业务数据。
     */
    private static BigDecimal decodeBcd(byte[] bytes, int decimalPlaces) throws Dlt645ProtocolException {
        StringBuilder digits = new StringBuilder(bytes.length * 2);
        for (int index = bytes.length - 1; index >= 0; index--) {
            int high = (bytes[index] >>> 4) & 0x0F;
            int low = bytes[index] & 0x0F;
            if (high > 9 || low > 9) {
                throw new Dlt645ProtocolException("DL/T 645 BCD 数据包含非法数字");
            }
            digits.append(high).append(low);
        }
        BigDecimal value = new BigDecimal(digits.toString());
        return decimalPlaces > 0 ? value.movePointLeft(decimalPlaces) : value;
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeBcd(Object value, int length, int decimalPlaces)
            throws Dlt645ProtocolException {
        if (length <= 0) {
            throw new Dlt645ProtocolException("写入 BCD 数据必须配置 dataFormat");
        }
        BigDecimal decimal = new BigDecimal(String.valueOf(value))
                .movePointRight(decimalPlaces)
                .setScale(0, RoundingMode.HALF_UP);
        String digits = decimal.toPlainString();
        if (digits.startsWith("-") || digits.length() > length * 2) {
            throw new Dlt645ProtocolException("BCD 写入值超出配置长度");
        }
        digits = "0".repeat(length * 2 - digits.length()) + digits;
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            int source = digits.length() - (index + 1) * 2;
            result[index] = (byte) Integer.parseInt(digits.substring(source, source + 2), 16);
        }
        return result;
    }

    /**
     * 解析或转换业务数据。
     */
    private static long decodeUnsignedLittleEndian(byte[] bytes) {
        long value = 0;
        for (int index = 0; index < bytes.length; index++) {
            value |= (long) (bytes[index] & 0xFF) << (index * 8);
        }
        return value;
    }

    /**
     * 解析或转换业务数据。
     */
    private static long decodeSignedLittleEndian(byte[] bytes) {
        long value = decodeUnsignedLittleEndian(bytes);
        int bits = bytes.length * 8;
        if (bits < Long.SIZE && (value & (1L << (bits - 1))) != 0) {
            value |= -1L << bits;
        }
        return value;
    }

    /**
     * 解析或转换业务数据。
     */
    private static float decodeFloatLittleEndian(byte[] bytes) throws Dlt645ProtocolException {
        if (bytes.length != Float.BYTES) {
            throw new Dlt645ProtocolException("FLOAT_LE 数据必须是 4 字节");
        }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    /**
     * 解析或转换业务数据。
     */
    private static String decodeDateTime(byte[] bytes) throws Dlt645ProtocolException {
        if (bytes.length != 6) {
            throw new Dlt645ProtocolException("DATETIME 数据必须是 6 字节");
        }
        int second = bcdByte(bytes[0]);
        int minute = bcdByte(bytes[1]);
        int hour = bcdByte(bytes[2]);
        int day = bcdByte(bytes[3]);
        int month = bcdByte(bytes[4]);
        int year = 2000 + bcdByte(bytes[5]);
        return LocalDateTime.of(year, month, day, hour, minute, second)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * 执行当前业务逻辑。
     */
    private static int bcdByte(byte value) throws Dlt645ProtocolException {
        int high = (value >>> 4) & 0x0F;
        int low = value & 0x0F;
        if (high > 9 || low > 9) {
            throw new Dlt645ProtocolException("日期时间包含非法 BCD 数据");
        }
        return high * 10 + low;
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeInteger(Object value, int length) throws Dlt645ProtocolException {
        if (length <= 0 || length > Long.BYTES) {
            throw new Dlt645ProtocolException("整数写入长度必须在 1 到 8 字节之间");
        }
        long number = new BigDecimal(String.valueOf(value)).longValueExact();
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            result[index] = (byte) ((number >>> (index * 8)) & 0xFF);
        }
        return result;
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeFloat(Object value) {
        return ByteBuffer.allocate(Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(Float.parseFloat(String.valueOf(value)))
                .array();
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeAscii(Object value, int length) throws Dlt645ProtocolException {
        byte[] source = String.valueOf(value).getBytes(StandardCharsets.US_ASCII);
        if (length <= 0) {
            return source;
        }
        if (source.length > length) {
            throw new Dlt645ProtocolException("ASCII 写入值超过配置长度");
        }
        return Arrays.copyOf(source, length);
    }

    /**
     * 执行当前业务逻辑。
     */
    private static int bytesPerValue(String format) {
        if (format == null || format.isBlank()) {
            return 0;
        }
        long digits = format.toUpperCase(Locale.ROOT).chars().filter(ch -> ch == 'X' || ch == '9').count();
        return (int) ((digits + 1) / 2);
    }

    /**
     * 执行当前业务逻辑。
     */
    private static int decimalPlaces(String format) {
        if (format == null) {
            return 0;
        }
        int point = format.indexOf('.');
        if (point < 0) {
            return 0;
        }
        return (int) format.substring(point + 1).chars().filter(ch -> ch == 'X' || ch == '9').count();
    }

    /**
     * 解析或转换业务数据。
     */
    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02X", value & 0xFF));
        }
        return builder.toString();
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] parseHex(String value) throws Dlt645ProtocolException {
        String normalized = value == null ? "" : value.replace(" ", "").replace("-", "");
        if (normalized.length() % 2 != 0 || !normalized.matches("[0-9a-fA-F]*")) {
            throw new Dlt645ProtocolException("十六进制数据格式无效");
        }
        byte[] result = new byte[normalized.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(normalized.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }
}

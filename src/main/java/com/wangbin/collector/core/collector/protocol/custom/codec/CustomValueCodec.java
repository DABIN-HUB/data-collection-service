package com.wangbin.collector.core.collector.protocol.custom.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * 自定义协议点位值编解码器。
 */
public final class CustomValueCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 创建当前组件实例。
     */
    private CustomValueCodec() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static Object decode(byte[] response, DataPoint point) throws Exception {
        CustomPointAddress address = CustomPointAddress.parse(point.getAddress());
        if (address.mode() == CustomPointAddress.AddressMode.JSON) {
            return decodeJson(response, address.jsonPath(), resolveCharset(point));
        }
        if (address.mode() == CustomPointAddress.AddressMode.BIT) {
            ensureRange(response, address.byteOffset(), 1);
            return ((response[address.byteOffset()] >> address.bitOffset()) & 0x01) == 1;
        }

        String dataType = normalizeDataType(point.getDataType());
        int length = resolveLength(address, point, dataType, response.length);
        ensureRange(response, address.byteOffset(), length);
        byte[] valueBytes = Arrays.copyOfRange(response, address.byteOffset(), address.byteOffset() + length);
        ByteBuffer buffer = ByteBuffer.wrap(valueBytes).order(resolveByteOrder(point));
        return switch (dataType) {
            case "BOOLEAN", "BOOL" -> valueBytes[0] != 0;
            case "BYTE", "UINT8" -> valueBytes[0] & 0xFF;
            case "INT8" -> valueBytes[0];
            case "SHORT", "INT16" -> buffer.getShort();
            case "UINT16" -> buffer.getShort() & 0xFFFF;
            case "INT", "INT32" -> buffer.getInt();
            case "UINT32" -> buffer.getInt() & 0xFFFFFFFFL;
            case "LONG", "INT64" -> buffer.getLong();
            case "UINT64" -> new BigInteger(1, orderedUnsignedBytes(valueBytes, buffer.order()));
            case "FLOAT", "FLOAT32" -> buffer.getFloat();
            case "DOUBLE", "FLOAT64" -> buffer.getDouble();
            case "STRING" -> trimTrailingZero(new String(valueBytes, resolveCharset(point)));
            default -> throw new IllegalArgumentException("不支持的自定义协议数据类型: " + dataType);
        };
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encode(Object value, DataPoint point) {
        String dataType = normalizeDataType(point.getDataType());
        ByteOrder order = resolveByteOrder(point);
        return switch (dataType) {
            case "BOOLEAN", "BOOL" -> new byte[]{(byte) (toBoolean(value) ? 1 : 0)};
            case "BYTE", "UINT8", "INT8" -> new byte[]{(byte) toLong(value)};
            case "SHORT", "INT16", "UINT16" -> ByteBuffer.allocate(2).order(order)
                    .putShort((short) toLong(value)).array();
            case "INT", "INT32", "UINT32" -> ByteBuffer.allocate(4).order(order)
                    .putInt((int) toLong(value)).array();
            case "LONG", "INT64", "UINT64" -> ByteBuffer.allocate(8).order(order)
                    .putLong(toLong(value)).array();
            case "FLOAT", "FLOAT32" -> ByteBuffer.allocate(4).order(order)
                    .putFloat((float) toDouble(value)).array();
            case "DOUBLE", "FLOAT64" -> ByteBuffer.allocate(8).order(order)
                    .putDouble(toDouble(value)).array();
            case "STRING" -> String.valueOf(value).getBytes(resolveCharset(point));
            default -> throw new IllegalArgumentException("不支持的自定义协议数据类型: " + dataType);
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private static Object decodeJson(byte[] response, String path, Charset charset) throws Exception {
        JsonNode current = OBJECT_MAPPER.readTree(new String(response, charset));
        String normalized = path.trim();
        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.isBlank()) {
            for (String segment : normalized.split("\\.")) {
                current = resolveJsonSegment(current, segment);
                if (current == null || current.isMissingNode()) {
                    throw new IllegalArgumentException("JSON路径不存在: " + path);
                }
            }
        }
        if (current == null || current.isNull()) {
            return null;
        }
        if (current.isBoolean()) {
            return current.booleanValue();
        }
        if (current.isIntegralNumber()) {
            return current.longValue();
        }
        if (current.isFloatingPointNumber()) {
            return current.doubleValue();
        }
        if (current.isTextual()) {
            return current.textValue();
        }
        return OBJECT_MAPPER.convertValue(current, Object.class);
    }

    /**
     * 解析或转换业务数据。
     */
    private static JsonNode resolveJsonSegment(JsonNode current, String segment) {
        int bracketIndex = segment.indexOf('[');
        if (bracketIndex < 0) {
            return current.path(segment);
        }
        String fieldName = segment.substring(0, bracketIndex);
        JsonNode array = fieldName.isEmpty() ? current : current.path(fieldName);
        int endIndex = segment.indexOf(']', bracketIndex);
        if (endIndex < 0) {
            throw new IllegalArgumentException("JSON数组路径格式错误: " + segment);
        }
        int arrayIndex = Integer.parseInt(segment.substring(bracketIndex + 1, endIndex));
        return array.path(arrayIndex);
    }

    /**
     * 解析或转换业务数据。
     */
    private static int resolveLength(CustomPointAddress address,
                                     DataPoint point,
                                     String dataType,
                                     int responseLength) {
        if (address.length() != null) {
            return address.length();
        }
        Integer configuredLength = point.getAdditionalConfig("length", null);
        if (configuredLength != null && configuredLength > 0) {
            return configuredLength;
        }
        return switch (dataType) {
            case "BOOLEAN", "BOOL", "BYTE", "UINT8", "INT8" -> 1;
            case "SHORT", "INT16", "UINT16" -> 2;
            case "INT", "INT32", "UINT32", "FLOAT", "FLOAT32" -> 4;
            case "LONG", "INT64", "UINT64", "DOUBLE", "FLOAT64" -> 8;
            case "STRING" -> responseLength - address.byteOffset();
            default -> throw new IllegalArgumentException("不支持的自定义协议数据类型: " + dataType);
        };
    }

    /**
     * 校验业务条件和参数边界。
     */
    private static void ensureRange(byte[] response, int offset, int length) {
        if (response == null || offset < 0 || length <= 0 || offset + length > response.length) {
            throw new IllegalArgumentException(
                    String.format("响应数据长度不足: offset=%d, length=%d, frameLength=%d",
                            offset, length, response == null ? 0 : response.length));
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private static ByteOrder resolveByteOrder(DataPoint point) {
        String configured = point.getAdditionalConfig("byteOrder", "BIG_ENDIAN");
        return "LITTLE_ENDIAN".equalsIgnoreCase(configured)
                ? ByteOrder.LITTLE_ENDIAN
                : ByteOrder.BIG_ENDIAN;
    }

    /**
     * 解析或转换业务数据。
     */
    private static Charset resolveCharset(DataPoint point) {
        String configured = point.getAdditionalConfig("charset", StandardCharsets.UTF_8.name());
        return Charset.forName(configured);
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalizeDataType(String value) {
        return value == null || value.isBlank() ? "INT16" : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 执行当前业务逻辑。
     */
    private static byte[] orderedUnsignedBytes(byte[] value, ByteOrder order) {
        if (order == ByteOrder.BIG_ENDIAN) {
            return value;
        }
        byte[] reversed = value.clone();
        for (int left = 0, right = reversed.length - 1; left < right; left++, right--) {
            byte current = reversed[left];
            reversed[left] = reversed[right];
            reversed[right] = current;
        }
        return reversed;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static String trimTrailingZero(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '\0') {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * 解析或转换业务数据。
     */
    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0D;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 解析或转换业务数据。
     */
    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value).trim());
    }

    /**
     * 解析或转换业务数据。
     */
    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value).trim());
    }
}

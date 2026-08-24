package com.wangbin.collector.core.collector.protocol.fins.codec;

import com.wangbin.collector.core.collector.protocol.fins.domain.FinsAddress;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsByteOrder;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsWordOrder;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 定义当前模块的业务组件。
 */
public final class FinsDataCodec {

    /**
     * 创建当前组件实例。
     */
    private FinsDataCodec() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static Object decode(byte[] payload, FinsAddress address) {
        if (address.isBitUnit()) {
            if (address.isArrayType()) {
                List<Boolean> values = new ArrayList<>(address.getElementCount());
                for (int index = 0; index < address.getElementCount(); index++) {
                    values.add((payload[index] & 0x01) != 0);
                }
                return values;
            }
            return payload.length > 0 && (payload[0] & 0x01) != 0;
        }
        if (address.isStringType()) {
            return decodeString(payload, address);
        }
        int scalarWordCount = switch (address.getDataType()) {
            case "BOOLEAN", "INT8", "UINT8", "INT16", "UINT16" -> 1;
            case "INT32", "UINT32", "FLOAT" -> 2;
            case "INT64", "UINT64", "DOUBLE" -> 4;
            default -> throw new IllegalArgumentException("Unsupported FINS decode type: " + address.getDataType());
        };
        if (!address.isArrayType()) {
            return decodeScalar(payload, address.getDataType(), scalarWordCount, address.getByteOrder(), address.getWordOrder());
        }
        List<Object> values = new ArrayList<>(address.getElementCount());
        int bytesPerElement = scalarWordCount * 2;
        for (int index = 0; index < address.getElementCount(); index++) {
            byte[] slice = new byte[bytesPerElement];
            System.arraycopy(payload, index * bytesPerElement, slice, 0, bytesPerElement);
            values.add(decodeScalar(slice, address.getDataType(), scalarWordCount, address.getByteOrder(), address.getWordOrder()));
        }
        return values;
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encode(Object value, FinsAddress address) {
        if (address.isBitUnit()) {
            if (address.isArrayType()) {
                List<?> values = asList(value, address.getElementCount());
                byte[] payload = new byte[values.size()];
                for (int index = 0; index < values.size(); index++) {
                    payload[index] = (byte) (coerceBoolean(values.get(index)) ? 0x01 : 0x00);
                }
                return payload;
            }
            return new byte[]{(byte) (coerceBoolean(value) ? 0x01 : 0x00)};
        }
        if (address.isStringType()) {
            return encodeString(value, address);
        }
        int scalarWordCount = switch (address.getDataType()) {
            case "BOOLEAN", "INT8", "UINT8", "INT16", "UINT16" -> 1;
            case "INT32", "UINT32", "FLOAT" -> 2;
            case "INT64", "UINT64", "DOUBLE" -> 4;
            default -> throw new IllegalArgumentException("Unsupported FINS encode type: " + address.getDataType());
        };
        if (!address.isArrayType()) {
            return encodeScalar(value, address.getDataType(), scalarWordCount, address.getByteOrder(), address.getWordOrder());
        }
        List<?> values = asList(value, address.getElementCount());
        byte[] payload = new byte[address.readUnitCount() * 2];
        int offset = 0;
        for (Object item : values) {
            byte[] encoded = encodeScalar(item, address.getDataType(), scalarWordCount, address.getByteOrder(), address.getWordOrder());
            System.arraycopy(encoded, 0, payload, offset, encoded.length);
            offset += encoded.length;
        }
        return payload;
    }

    /**
     * 解析或转换业务数据。
     */
    private static Object decodeScalar(byte[] payload,
                                       String dataType,
                                       int scalarWordCount,
                                       FinsByteOrder byteOrder,
                                       FinsWordOrder wordOrder) {
        byte[] normalized = normalizeWordBytes(payload, scalarWordCount, byteOrder, wordOrder);
        ByteBuffer buffer = ByteBuffer.wrap(normalized);
        return switch (dataType) {
            case "BOOLEAN" -> buffer.getShort() != 0;
            case "INT8" -> (int) buffer.get(1);
            case "UINT8" -> buffer.get(1) & 0xFF;
            case "INT16" -> (int) buffer.getShort();
            case "UINT16" -> buffer.getShort() & 0xFFFF;
            case "INT32" -> buffer.getInt();
            case "UINT32" -> buffer.getInt() & 0xFFFFFFFFL;
            case "INT64" -> buffer.getLong();
            case "UINT64" -> new BigInteger(1, normalized);
            case "FLOAT" -> buffer.getFloat();
            case "DOUBLE" -> buffer.getDouble();
            default -> throw new IllegalArgumentException("Unsupported FINS decode type: " + dataType);
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeScalar(Object value,
                                       String dataType,
                                       int scalarWordCount,
                                       FinsByteOrder byteOrder,
                                       FinsWordOrder wordOrder) {
        ByteBuffer buffer = ByteBuffer.allocate(scalarWordCount * 2);
        switch (dataType) {
            case "BOOLEAN" -> buffer.putShort((short) (coerceBoolean(value) ? 1 : 0));
            case "INT8" -> {
                buffer.put((byte) 0x00);
                buffer.put(coerceNumber(value).byteValue());
            }
            case "UINT8" -> {
                buffer.put((byte) 0x00);
                buffer.put((byte) (coerceNumber(value).intValue() & 0xFF));
            }
            case "INT16" -> buffer.putShort(coerceNumber(value).shortValue());
            case "UINT16" -> buffer.putShort((short) (coerceNumber(value).intValue() & 0xFFFF));
            case "INT32" -> buffer.putInt(coerceNumber(value).intValue());
            case "UINT32" -> buffer.putInt((int) (coerceNumber(value).longValue() & 0xFFFFFFFFL));
            case "INT64" -> buffer.putLong(coerceNumber(value).longValue());
            case "UINT64" -> {
                BigInteger bigInteger = toBigInteger(value);
                byte[] src = bigInteger.toByteArray();
                byte[] dst = new byte[8];
                int copyLength = Math.min(src.length, 8);
                System.arraycopy(src, src.length - copyLength, dst, 8 - copyLength, copyLength);
                buffer.put(dst);
            }
            case "FLOAT" -> buffer.putFloat(coerceNumber(value).floatValue());
            case "DOUBLE" -> buffer.putDouble(coerceNumber(value).doubleValue());
            default -> throw new IllegalArgumentException("Unsupported FINS encode type: " + dataType);
        }
        return denormalizeWordBytes(buffer.array(), scalarWordCount, byteOrder, wordOrder);
    }

    /**
     * 解析或转换业务数据。
     */
    private static String decodeString(byte[] payload, FinsAddress address) {
        byte[] normalized = normalizeWordBytes(payload, address.requiredStringWordCount(), address.getByteOrder(), address.getWordOrder());
        int limit = Math.min(normalized.length, address.getStringLength());
        int end = 0;
        while (end < limit && normalized[end] != 0) {
            end++;
        }
        return new String(normalized, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeString(Object value, FinsAddress address) {
        byte[] raw = value == null ? new byte[0] : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        int byteLength = address.requiredStringWordCount() * 2;
        byte[] normalized = new byte[byteLength];
        System.arraycopy(raw, 0, normalized, 0, Math.min(raw.length, Math.min(byteLength, address.getStringLength())));
        return denormalizeWordBytes(normalized, address.requiredStringWordCount(), address.getByteOrder(), address.getWordOrder());
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] normalizeWordBytes(byte[] payload,
                                             int wordCount,
                                             FinsByteOrder byteOrder,
                                             FinsWordOrder wordOrder) {
        byte[] result = payload.clone();
        if (wordCount > 1 && wordOrder == FinsWordOrder.LITTLE_ENDIAN) {
            reverseWordBlocks(result, wordCount);
        }
        if (byteOrder == FinsByteOrder.LITTLE_ENDIAN) {
            swapBytesInWords(result);
        }
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static byte[] denormalizeWordBytes(byte[] payload,
                                               int wordCount,
                                               FinsByteOrder byteOrder,
                                               FinsWordOrder wordOrder) {
        byte[] result = payload.clone();
        if (byteOrder == FinsByteOrder.LITTLE_ENDIAN) {
            swapBytesInWords(result);
        }
        if (wordCount > 1 && wordOrder == FinsWordOrder.LITTLE_ENDIAN) {
            reverseWordBlocks(result, wordCount);
        }
        return result;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static void swapBytesInWords(byte[] payload) {
        for (int index = 0; index + 1 < payload.length; index += 2) {
            byte first = payload[index];
            payload[index] = payload[index + 1];
            payload[index + 1] = first;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private static void reverseWordBlocks(byte[] payload, int wordCount) {
        for (int left = 0, right = wordCount - 1; left < right; left++, right--) {
            int leftOffset = left * 2;
            int rightOffset = right * 2;
            byte left0 = payload[leftOffset];
            byte left1 = payload[leftOffset + 1];
            payload[leftOffset] = payload[rightOffset];
            payload[leftOffset + 1] = payload[rightOffset + 1];
            payload[rightOffset] = left0;
            payload[rightOffset + 1] = left1;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private static List<?> asList(Object value, int expectedSize) {
        if (value instanceof List<?> list) {
            if (list.size() != expectedSize) {
                throw new IllegalArgumentException("FINS array value size mismatch, expected=" + expectedSize + ", actual=" + list.size());
            }
            return list;
        }
        throw new IllegalArgumentException("FINS array value must be a List");
    }

    /**
     * 执行当前业务逻辑。
     */
    private static Number coerceNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Double.parseDouble(text.trim());
        }
        throw new IllegalArgumentException("FINS value cannot be converted to number: " + value);
    }

    /**
     * 执行当前业务逻辑。
     */
    private static boolean coerceBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            String normalized = text.trim();
            return "true".equalsIgnoreCase(normalized) || "1".equals(normalized) || "on".equalsIgnoreCase(normalized);
        }
        throw new IllegalArgumentException("FINS value cannot be converted to boolean: " + value);
    }

    /**
     * 解析或转换业务数据。
     */
    private static BigInteger toBigInteger(Object value) {
        if (value instanceof BigInteger bigInteger) {
            return bigInteger;
        }
        if (value instanceof Number number) {
            return BigInteger.valueOf(number.longValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            return new BigInteger(text.trim());
        }
        throw new IllegalArgumentException("FINS value cannot be converted to BigInteger: " + value);
    }
}
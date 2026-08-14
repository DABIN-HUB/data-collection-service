package com.wangbin.collector.core.collector.protocol.mc.util;

import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDriverType;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 定义当前模块的业务组件。
 */
public final class McByteCodec {

    /**
     * 创建当前组件实例。
     */
    private McByteCodec() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static Object decode(McAddress address, byte[] payload) {
        if (address == null) {
            throw new IllegalArgumentException("MC address cannot be null");
        }
        byte[] effectivePayload = payload != null ? payload : new byte[0];
        if (address.isBitDevice()) {
            List<Boolean> values = decodeBitValues(effectivePayload, address.getElementCount());
            return address.isScalar() ? values.get(0) : values;
        }
        if (address.getDriverType() == McDriverType.STRING) {
            return decodeString(effectivePayload, address.getStringLength());
        }
        validateWordPayloadLength(address, effectivePayload);
        if (address.isScalar()) {
            return decodeScalar(address.getDriverType(), effectivePayload, 0);
        }
        List<Object> values = new ArrayList<>(address.getElementCount());
        int elementWidth = address.getDriverType().getWordLength() * 2;
        for (int i = 0; i < address.getElementCount(); i++) {
            values.add(decodeScalar(address.getDriverType(), effectivePayload, i * elementWidth));
        }
        return values;
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encode(McAddress address, Object value) {
        if (address == null) {
            throw new IllegalArgumentException("MC address cannot be null");
        }
        if (address.isBitDevice()) {
            return encodeBitValues(address, value);
        }
        if (address.getDriverType() == McDriverType.STRING) {
            return encodeString(address, value);
        }
        if (address.isScalar()) {
            return encodeScalar(address.getDriverType(), value);
        }
        List<?> values = toValueList(value, address.getElementCount());
        ByteBuffer buffer = ByteBuffer.allocate(address.getWordCount() * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (Object item : values) {
            buffer.put(encodeScalar(address.getDriverType(), item));
        }
        return buffer.array();
    }

    /**
     * 校验业务条件和参数边界。
     */
    private static void validateWordPayloadLength(McAddress address, byte[] payload) {
        int expected = address.getWordCount() * 2;
        if (payload.length < expected) {
            throw new IllegalArgumentException("MC payload is shorter than expected: expected="
                    + expected + ", actual=" + payload.length);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private static Object decodeScalar(McDriverType driverType, byte[] payload, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        return switch (driverType) {
            case INT16 -> buffer.getShort(offset);
            case UINT16 -> Short.toUnsignedInt(buffer.getShort(offset));
            case INT32 -> buffer.getInt(offset);
            case UINT32 -> Integer.toUnsignedLong(buffer.getInt(offset));
            case FLOAT32 -> buffer.getFloat(offset);
            case FLOAT64 -> buffer.getDouble(offset);
            default -> throw new IllegalArgumentException("Unsupported MC scalar type: " + driverType);
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeScalar(McDriverType driverType, Object value) {
        ByteBuffer buffer = ByteBuffer.allocate(driverType.getWordLength() * 2).order(ByteOrder.LITTLE_ENDIAN);
        switch (driverType) {
            case INT16 -> buffer.putShort((short) coerceLong(value));
            case UINT16 -> buffer.putShort((short) (coerceLong(value) & 0xFFFF));
            case INT32 -> buffer.putInt((int) coerceLong(value));
            case UINT32 -> buffer.putInt((int) (coerceLong(value) & 0xFFFFFFFFL));
            case FLOAT32 -> buffer.putFloat((float) coerceDouble(value));
            case FLOAT64 -> buffer.putDouble(coerceDouble(value));
            default -> throw new IllegalArgumentException("Unsupported MC scalar type: " + driverType);
        }
        return buffer.array();
    }

    /**
     * 解析或转换业务数据。
     */
    private static List<Boolean> decodeBitValues(byte[] payload, int count) {
        List<Boolean> values = new ArrayList<>(count);
        for (byte current : payload) {
            if (values.size() < count) {
                values.add((current & 0x0F) != 0);
            }
            if (values.size() < count) {
                values.add(((current >> 4) & 0x0F) != 0);
            }
            if (values.size() >= count) {
                break;
            }
        }
        if (values.size() < count) {
            throw new IllegalArgumentException("MC bit payload is shorter than expected: expected="
                    + count + ", actual=" + values.size());
        }
        return values;
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeBitValues(McAddress address, Object value) {
        List<?> rawValues = address.isScalar() ? List.of(value) : toValueList(value, address.getElementCount());
        int byteCount = (rawValues.size() + 1) / 2;
        byte[] payload = new byte[byteCount];
        for (int i = 0; i < rawValues.size(); i++) {
            boolean bit = toBoolean(rawValues.get(i));
            int targetIndex = i / 2;
            int encoded = bit ? 0x01 : 0x00;
            if ((i & 1) == 0) {
                payload[targetIndex] = (byte) ((payload[targetIndex] & 0xF0) | encoded);
            } else {
                payload[targetIndex] = (byte) ((payload[targetIndex] & 0x0F) | (encoded << 4));
            }
        }
        return payload;
    }

    /**
     * 解析或转换业务数据。
     */
    private static String decodeString(byte[] payload, Integer stringLength) {
        int length = stringLength != null && stringLength > 0 ? stringLength : payload.length;
        int safeLength = Math.min(length, payload.length);
        int end = 0;
        while (end < safeLength && payload[end] != 0) {
            end++;
        }
        return new String(payload, 0, end, StandardCharsets.US_ASCII);
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeString(McAddress address, Object value) {
        int length = address.getStringLength() != null ? address.getStringLength() : 0;
        if (length <= 0) {
            throw new IllegalArgumentException("MC STRING requires stringLength");
        }
        byte[] target = new byte[address.getWordCount() * 2];
        byte[] source = value == null
                ? new byte[0]
                : String.valueOf(value).getBytes(StandardCharsets.US_ASCII);
        int copyLength = Math.min(length, source.length);
        System.arraycopy(source, 0, target, 0, copyLength);
        return target;
    }

    /**
     * 解析或转换业务数据。
     */
    private static List<?> toValueList(Object value, int expectedSize) {
        if (value == null) {
            throw new IllegalArgumentException("MC array write requires a collection or array value");
        }
        List<Object> values = new ArrayList<>();
        if (value instanceof List<?> list) {
            values.addAll(list);
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                values.add(Array.get(value, i));
            }
        } else {
            throw new IllegalArgumentException("MC array write requires a collection or array value");
        }
        if (values.size() != expectedSize) {
            throw new IllegalArgumentException("MC array size mismatch: expected="
                    + expectedSize + ", actual=" + values.size());
        }
        return values;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static long coerceLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1L : 0L;
        }
        return Long.parseLong(String.valueOf(value).trim());
    }

    /**
     * 执行当前业务逻辑。
     */
    private static double coerceDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1.0d : 0.0d;
        }
        return Double.parseDouble(String.valueOf(value).trim());
    }

    /**
     * 解析或转换业务数据。
     */
    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String normalized = String.valueOf(value).trim().toLowerCase();
        return "true".equals(normalized) || "1".equals(normalized) || "on".equals(normalized);
    }
}

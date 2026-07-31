package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 定义当前模块的业务组件。
 */
final class BacnetValueEncodingSupport {

    /**
     * 创建当前组件实例。
     */
    private BacnetValueEncodingSupport() {
    }

    /**
     * 写入或持久化业务数据。
     */
    static void writeApplicationValue(ByteArrayOutputStream out, Object value, String typeHint) {
        String normalized = normalizeType(typeHint);
        if (value == null || "NULL".equals(normalized)) {
            BacnetTagSupport.writeTag(out, 0, false, 0);
            return;
        }
        if (value instanceof Boolean || "BOOLEAN".equals(normalized)) {
            boolean result = value instanceof Boolean boolValue ? boolValue : coerceBoolean(value);
            BacnetTagSupport.writeTag(out, 1, false, result ? 1 : 0);
            return;
        }
        if (value instanceof String || normalized.contains("STRING")) {
            byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            BacnetTagSupport.writeTag(out, 7, false, bytes.length + 1L);
            out.write(0);
            out.writeBytes(bytes);
            return;
        }
        if ("BIT_STRING".equals(normalized) && value instanceof boolean[] bits) {
            byte[] payload = encodeBitString(bits);
            BacnetTagSupport.writeTag(out, 8, false, payload.length);
            out.writeBytes(payload);
            return;
        }
        if (normalized.startsWith("ENUM")) {
            BacnetTagSupport.writeEnumerated(out, (int) coerceLong(value));
            return;
        }
        if (normalized.startsWith("UNSIGNED")) {
            BacnetTagSupport.writeUnsignedInteger(out, Math.max(0L, coerceLong(value)));
            return;
        }
        if (normalized.startsWith("SIGNED") || normalized.startsWith("INT") || normalized.startsWith("LONG")) {
            writeSignedInteger(out, coerceLong(value));
            return;
        }
        if (normalized.startsWith("DOUBLE") || normalized.startsWith("FLOAT64")) {
            BacnetTagSupport.writeTag(out, 5, false, 8);
            out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(coerceDouble(value)).array());
            return;
        }
        if (normalized.startsWith("REAL") || normalized.startsWith("FLOAT") || normalized.startsWith("FLOAT32")) {
            BacnetTagSupport.writeTag(out, 4, false, 4);
            out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat((float) coerceDouble(value)).array());
            return;
        }
        if (value instanceof Float || value instanceof Double) {
            BacnetTagSupport.writeTag(out, 4, false, 4);
            out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat((float) coerceDouble(value)).array());
            return;
        }
        if (value instanceof Number) {
            long numeric = coerceLong(value);
            if (numeric >= 0L) {
                BacnetTagSupport.writeUnsignedInteger(out, numeric);
            } else {
                writeSignedInteger(out, numeric);
            }
            return;
        }
        throw new IllegalArgumentException("Unsupported BACnet write value: " + value.getClass().getName());
    }

    /**
     * 写入或持久化业务数据。
     */
    static void writeContextReal(ByteArrayOutputStream out, int contextId, double value) {
        BacnetTagSupport.writeTag(out, contextId, true, 4);
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat((float) value).array());
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalizeType(String typeHint) {
        if (typeHint == null || typeHint.isBlank()) {
            return "AUTO";
        }
        return typeHint.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * 执行当前业务逻辑。
     */
    private static boolean coerceBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "on".equals(text) || "active".equals(text);
    }

    /**
     * 执行当前业务逻辑。
     */
    private static double coerceDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value).trim());
    }

    /**
     * 执行当前业务逻辑。
     */
    private static long coerceLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value).trim());
    }

    /**
     * 写入或持久化业务数据。
     */
    private static void writeSignedInteger(ByteArrayOutputStream out, long value) {
        byte[] payload = encodeSigned(value);
        BacnetTagSupport.writeTag(out, 3, false, payload.length);
        out.writeBytes(payload);
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeSigned(long value) {
        for (int length = 1; length <= 8; length++) {
            long min = -(1L << (length * 8 - 1));
            long max = (1L << (length * 8 - 1)) - 1;
            if (value >= min && value <= max) {
                byte[] payload = new byte[length];
                for (int i = 0; i < length; i++) {
                    payload[length - i - 1] = (byte) ((value >> (i * 8)) & 0xFF);
                }
                return payload;
            }
        }
        throw new IllegalArgumentException("Signed integer is too large: " + value);
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeBitString(boolean[] bits) {
        int bitLength = bits != null ? bits.length : 0;
        int byteCount = (bitLength + 7) / 8;
        byte[] payload = new byte[byteCount + 1];
        payload[0] = (byte) ((byteCount * 8) - bitLength);
        for (int i = 0; i < bitLength; i++) {
            if (bits[i]) {
                payload[1 + (i / 8)] |= (byte) (1 << (7 - (i % 8)));
            }
        }
        return payload;
    }
}

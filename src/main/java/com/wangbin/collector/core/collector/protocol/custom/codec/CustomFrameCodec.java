package com.wangbin.collector.core.collector.protocol.custom.codec;

import com.wangbin.collector.common.domain.entity.DeviceConnection;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * 自定义 TCP 协议的受控帧编解码器。
 */
public final class CustomFrameCodec {

    private static final int DEFAULT_MAX_FRAME_LENGTH = 65_535;
    private static final int DEFAULT_LENGTH_FIELD_SIZE = 4;

    /**
     * 创建当前组件实例。
     */
    private CustomFrameCodec() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encode(byte[] payload, DeviceConnection config) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        CustomFrameMode mode = resolveMode(config);
        if (mode == CustomFrameMode.LENGTH_FIELD
                && config.getBool("prependLengthField", true)) {
            int fieldLength = config.getInt("lengthFieldLength", DEFAULT_LENGTH_FIELD_SIZE);
            ByteOrder order = resolveByteOrder(config);
            byte[] header = encodeUnsigned(safePayload.length, fieldLength, order);
            byte[] framed = new byte[header.length + safePayload.length];
            System.arraycopy(header, 0, framed, 0, header.length);
            System.arraycopy(safePayload, 0, framed, header.length, safePayload.length);
            return framed;
        }
        if (mode == CustomFrameMode.DELIMITER
                && config.getBool("appendDelimiter", true)) {
            byte[] delimiter = decodeHex(config.getString("delimiterHex", "0A"));
            byte[] framed = Arrays.copyOf(safePayload, safePayload.length + delimiter.length);
            System.arraycopy(delimiter, 0, framed, safePayload.length, delimiter.length);
            return framed;
        }
        return safePayload;
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] decode(InputStream inputStream, DeviceConnection config) throws IOException {
        return switch (resolveMode(config)) {
            case FIXED_LENGTH -> readFixed(inputStream, requiredPositive(
                    config.getInt("fixedFrameLength", 0), "fixedFrameLength"));
            case DELIMITER -> readDelimited(inputStream, config);
            case LENGTH_FIELD -> readLengthFieldFrame(inputStream, config);
            case DATAGRAM -> throw new IllegalArgumentException("TCP连接不能使用DATAGRAM帧模式");
        };
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] decodeHex(String value) {
        if (value == null) {
            return new byte[0];
        }
        String normalized = value.replaceAll("[^0-9A-Fa-f]", "");
        if ((normalized.length() & 1) != 0) {
            throw new IllegalArgumentException("十六进制内容必须包含偶数个字符");
        }
        byte[] bytes = new byte[normalized.length() / 2];
        for (int index = 0; index < normalized.length(); index += 2) {
            bytes[index / 2] = (byte) Integer.parseInt(normalized.substring(index, index + 2), 16);
        }
        return bytes;
    }

    /**
     * 解析或转换业务数据。
     */
    public static String encodeHex(byte[] value) {
        if (value == null || value.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte item : value) {
            builder.append(String.format("%02X", item & 0xFF));
        }
        return builder.toString();
    }

    /**
     * 查询并返回业务数据。
     */
    private static byte[] readLengthFieldFrame(InputStream inputStream, DeviceConnection config) throws IOException {
        int fieldOffset = Math.max(0, config.getInt("lengthFieldOffset", 0));
        int fieldLength = config.getInt("lengthFieldLength", DEFAULT_LENGTH_FIELD_SIZE);
        if (!SetHolder.SUPPORTED_LENGTHS.contains(fieldLength)) {
            throw new IllegalArgumentException("lengthFieldLength只支持1、2、4或8字节");
        }
        int headerLength = fieldOffset + fieldLength;
        byte[] header = readFixed(inputStream, headerLength);
        long payloadLength = decodeUnsigned(header, fieldOffset, fieldLength, resolveByteOrder(config));
        long frameLength = headerLength + payloadLength + config.getInt("lengthAdjustment", 0);
        int maxFrameLength = resolveMaxFrameLength(config);
        if (frameLength < headerLength || frameLength > maxFrameLength) {
            throw new IOException("自定义协议帧长度越界: " + frameLength);
        }
        byte[] frame = Arrays.copyOf(header, (int) frameLength);
        byte[] remainder = readFixed(inputStream, (int) frameLength - headerLength);
        System.arraycopy(remainder, 0, frame, headerLength, remainder.length);
        int stripLength = Math.max(0, config.getInt("initialBytesToStrip", headerLength));
        if (stripLength > frame.length) {
            throw new IOException("initialBytesToStrip超过完整帧长度");
        }
        return Arrays.copyOfRange(frame, stripLength, frame.length);
    }

    /**
     * 查询并返回业务数据。
     */
    private static byte[] readDelimited(InputStream inputStream, DeviceConnection config) throws IOException {
        byte[] delimiter = decodeHex(config.getString("delimiterHex", "0A"));
        if (delimiter.length == 0) {
            throw new IllegalArgumentException("delimiterHex不能为空");
        }
        int maxFrameLength = resolveMaxFrameLength(config);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        while (output.size() < maxFrameLength) {
            int current = inputStream.read();
            if (current < 0) {
                throw new EOFException("读取分隔符帧时连接已关闭");
            }
            output.write(current);
            if ((byte) current == delimiter[matched]) {
                matched++;
                if (matched == delimiter.length) {
                    byte[] frame = output.toByteArray();
                    return config.getBool("stripDelimiter", true)
                            ? Arrays.copyOf(frame, frame.length - delimiter.length)
                            : frame;
                }
            } else {
                matched = (byte) current == delimiter[0] ? 1 : 0;
            }
        }
        throw new IOException("分隔符帧超过最大长度: " + maxFrameLength);
    }

    /**
     * 查询并返回业务数据。
     */
    private static byte[] readFixed(InputStream inputStream, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = inputStream.read(data, offset, length - offset);
            if (count < 0) {
                throw new EOFException("读取固定长度帧时连接已关闭");
            }
            offset += count;
        }
        return data;
    }

    /**
     * 解析或转换业务数据。
     */
    private static long decodeUnsigned(byte[] source, int offset, int length, ByteOrder order) {
        ByteBuffer buffer = ByteBuffer.wrap(source, offset, length).order(order);
        return switch (length) {
            case 1 -> buffer.get() & 0xFFL;
            case 2 -> buffer.getShort() & 0xFFFFL;
            case 4 -> buffer.getInt() & 0xFFFFFFFFL;
            case 8 -> {
                long value = buffer.getLong();
                if (value < 0) {
                    throw new IllegalArgumentException("8字节长度字段不能超过Long最大值");
                }
                yield value;
            }
            default -> throw new IllegalArgumentException("不支持的长度字段字节数: " + length);
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeUnsigned(long value, int length, ByteOrder order) {
        if (value < 0) {
            throw new IllegalArgumentException("长度字段不能为负数");
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).order(order);
        switch (length) {
            case 1 -> buffer.put((byte) value);
            case 2 -> buffer.putShort((short) value);
            case 4 -> buffer.putInt((int) value);
            case 8 -> buffer.putLong(value);
            default -> throw new IllegalArgumentException("lengthFieldLength只支持1、2、4或8字节");
        }
        return buffer.array();
    }

    /**
     * 解析或转换业务数据。
     */
    private static CustomFrameMode resolveMode(DeviceConnection config) {
        return CustomFrameMode.fromValue(config.getString("frameMode", "LENGTH_FIELD"),
                CustomFrameMode.LENGTH_FIELD);
    }

    /**
     * 解析或转换业务数据。
     */
    private static ByteOrder resolveByteOrder(DeviceConnection config) {
        return "LITTLE_ENDIAN".equalsIgnoreCase(config.getString("lengthFieldByteOrder", "BIG_ENDIAN"))
                ? ByteOrder.LITTLE_ENDIAN
                : ByteOrder.BIG_ENDIAN;
    }

    /**
     * 解析或转换业务数据。
     */
    private static int resolveMaxFrameLength(DeviceConnection config) {
        Integer configured = config.getMaxFrameLength();
        return configured != null && configured > 0 ? configured : DEFAULT_MAX_FRAME_LENGTH;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private static int requiredPositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "必须大于0");
        }
        return value;
    }

    /**
     * 定义当前模块的业务组件。
     */
    private static final class SetHolder {

        private static final java.util.Set<Integer> SUPPORTED_LENGTHS = java.util.Set.of(1, 2, 4, 8);

        /**
         * 创建当前组件实例。
         */
        private SetHolder() {
        }
    }
}

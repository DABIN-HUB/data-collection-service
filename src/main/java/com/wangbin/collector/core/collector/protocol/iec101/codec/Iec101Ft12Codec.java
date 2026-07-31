package com.wangbin.collector.core.collector.protocol.iec101.codec;

import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101Frame;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101FrameType;
import com.wangbin.collector.core.connection.serial.SerialChannel;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * IEC101 FT1.2 链路帧编解码器。
 */
public final class Iec101Ft12Codec {

    private static final int FIXED_START = 0x10;
    private static final int VARIABLE_START = 0x68;
    private static final int SINGLE_ACK = 0xE5;
    private static final int END = 0x16;

    /**
     * 创建当前组件实例。
     */
    private Iec101Ft12Codec() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encode(Iec101Frame frame, int linkAddressSize) {
        return switch (frame.type()) {
            case SINGLE_ACK -> new byte[]{(byte) SINGLE_ACK};
            case FIXED -> encodeFixed(frame, linkAddressSize);
            case VARIABLE -> encodeVariable(frame, linkAddressSize);
        };
    }

    /**
     * 解析或转换业务数据。
     */
    public static Iec101Frame decode(byte[] bytes, int linkAddressSize) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("IEC101 帧不能为空");
        }
        int start = bytes[0] & 0xFF;
        if (start == SINGLE_ACK) {
            if (bytes.length != 1) {
                throw new IllegalArgumentException("IEC101 单字节确认帧长度无效");
            }
            return Iec101Frame.singleAck();
        }
        if (start == FIXED_START) {
            return decodeFixed(bytes, linkAddressSize);
        }
        if (start == VARIABLE_START) {
            return decodeVariable(bytes, linkAddressSize);
        }
        throw new IllegalArgumentException("不支持的 IEC101 帧启动字符");
    }

    /**
     * 查询并返回业务数据。
     */
    public static Iec101Frame read(SerialChannel channel,
                                   int linkAddressSize,
                                   long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            Integer start = readByte(channel, deadline);
            if (start == null) {
                continue;
            }
            if (start == SINGLE_ACK) {
                return Iec101Frame.singleAck();
            }
            if (start == FIXED_START) {
                byte[] tail = readExact(channel, linkAddressSize + 3, deadline);
                byte[] frame = new byte[tail.length + 1];
                frame[0] = (byte) start.intValue();
                System.arraycopy(tail, 0, frame, 1, tail.length);
                return decode(frame, linkAddressSize);
            }
            if (start == VARIABLE_START) {
                byte[] header = readExact(channel, 3, deadline);
                int length = header[0] & 0xFF;
                if (length != (header[1] & 0xFF) || (header[2] & 0xFF) != VARIABLE_START) {
                    continue;
                }
                byte[] tail = readExact(channel, length + 2, deadline);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                output.write(start);
                output.writeBytes(header);
                output.writeBytes(tail);
                return decode(output.toByteArray(), linkAddressSize);
            }
        }
        throw new IllegalStateException("等待 IEC101 链路响应超时");
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeFixed(Iec101Frame frame, int addressSize) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(frame.control());
        writeLittleEndian(body, frame.linkAddress(), addressSize);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(FIXED_START);
        output.writeBytes(body.toByteArray());
        output.write(checksum(body.toByteArray()));
        output.write(END);
        return output.toByteArray();
    }

    /**
     * 解析或转换业务数据。
     */
    private static byte[] encodeVariable(Iec101Frame frame, int addressSize) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(frame.control());
        writeLittleEndian(body, frame.linkAddress(), addressSize);
        body.writeBytes(frame.userData());
        byte[] payload = body.toByteArray();
        if (payload.length > 255) {
            throw new IllegalArgumentException("IEC101 可变帧长度超过 255 字节");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(VARIABLE_START);
        output.write(payload.length);
        output.write(payload.length);
        output.write(VARIABLE_START);
        output.writeBytes(payload);
        output.write(checksum(payload));
        output.write(END);
        return output.toByteArray();
    }

    /**
     * 解析或转换业务数据。
     */
    private static Iec101Frame decodeFixed(byte[] bytes, int addressSize) {
        int expectedLength = addressSize + 4;
        if (bytes.length != expectedLength || (bytes[bytes.length - 1] & 0xFF) != END) {
            throw new IllegalArgumentException("IEC101 固定帧长度或结束字符无效");
        }
        byte[] body = Arrays.copyOfRange(bytes, 1, 2 + addressSize);
        validateChecksum(body, bytes[bytes.length - 2]);
        return new Iec101Frame(Iec101FrameType.FIXED,
                body[0] & 0xFF, readLittleEndian(body, 1, addressSize), new byte[0]);
    }

    /**
     * 解析或转换业务数据。
     */
    private static Iec101Frame decodeVariable(byte[] bytes, int addressSize) {
        if (bytes.length < 7 || (bytes[3] & 0xFF) != VARIABLE_START) {
            throw new IllegalArgumentException("IEC101 可变帧头无效");
        }
        int length = bytes[1] & 0xFF;
        if (length != (bytes[2] & 0xFF) || bytes.length != length + 6
                || (bytes[bytes.length - 1] & 0xFF) != END) {
            throw new IllegalArgumentException("IEC101 可变帧长度或结束字符无效");
        }
        byte[] body = Arrays.copyOfRange(bytes, 4, 4 + length);
        validateChecksum(body, bytes[bytes.length - 2]);
        int userDataOffset = 1 + addressSize;
        return new Iec101Frame(Iec101FrameType.VARIABLE,
                body[0] & 0xFF,
                readLittleEndian(body, 1, addressSize),
                Arrays.copyOfRange(body, userDataOffset, body.length));
    }

    /**
     * 校验业务条件和参数边界。
     */
    private static void validateChecksum(byte[] body, byte actual) {
        if (checksum(body) != (actual & 0xFF)) {
            throw new IllegalArgumentException("IEC101 链路帧校验和错误");
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private static int checksum(byte[] body) {
        int value = 0;
        for (byte item : body) {
            value = (value + (item & 0xFF)) & 0xFF;
        }
        return value;
    }

    /**
     * 写入或持久化业务数据。
     */
    private static void writeLittleEndian(ByteArrayOutputStream output, int value, int length) {
        for (int index = 0; index < length; index++) {
            output.write((value >>> (index * 8)) & 0xFF);
        }
    }

    /**
     * 查询并返回业务数据。
     */
    private static int readLittleEndian(byte[] bytes, int offset, int length) {
        int value = 0;
        for (int index = 0; index < length; index++) {
            value |= (bytes[offset + index] & 0xFF) << (index * 8);
        }
        return value;
    }

    /**
     * 查询并返回业务数据。
     */
    private static byte[] readExact(SerialChannel channel, int length, long deadline) throws Exception {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length && System.currentTimeMillis() < deadline) {
            int count = channel.read(result, offset, length - offset,
                    Math.max(1, deadline - System.currentTimeMillis()));
            if (count > 0) {
                offset += count;
            }
        }
        if (offset != length) {
            throw new IllegalStateException("IEC101 链路帧接收不完整");
        }
        return result;
    }

    /**
     * 查询并返回业务数据。
     */
    private static Integer readByte(SerialChannel channel, long deadline) throws Exception {
        byte[] one = new byte[1];
        return channel.read(one, 0, 1, Math.max(1, deadline - System.currentTimeMillis())) == 1
                ? one[0] & 0xFF : null;
    }
}

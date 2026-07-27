package com.wangbin.collector.core.collector.protocol.dlt645.codec;

import com.wangbin.collector.core.collector.protocol.dlt645.Dlt645ProtocolException;
import com.wangbin.collector.core.collector.protocol.dlt645.domain.Dlt645Address;
import com.wangbin.collector.core.collector.protocol.dlt645.domain.Dlt645Frame;
import com.wangbin.collector.core.connection.serial.SerialChannel;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * DL/T 645-2007 帧编解码器。
 */
public final class Dlt645FrameCodec {

    private static final int START = 0x68;
    private static final int END = 0x16;
    private static final int WAKE_UP = 0xFE;

    private Dlt645FrameCodec() {
    }

    public static byte[] encode(Dlt645Frame frame, int wakeUpByteCount) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int index = 0; index < Math.max(0, wakeUpByteCount); index++) {
            output.write(WAKE_UP);
        }
        int checksumStart = output.size();
        output.write(START);
        output.writeBytes(frame.address().toWireBytes());
        output.write(START);
        output.write(frame.control());
        byte[] data = frame.data();
        output.write(data.length);
        for (byte value : data) {
            output.write((value + 0x33) & 0xFF);
        }
        byte[] withoutChecksum = output.toByteArray();
        int checksum = checksum(Arrays.copyOfRange(withoutChecksum, checksumStart, withoutChecksum.length));
        output.write(checksum);
        output.write(END);
        return output.toByteArray();
    }

    public static Dlt645Frame decode(byte[] bytes) throws Dlt645ProtocolException {
        if (bytes == null || bytes.length < 12) {
            throw new Dlt645ProtocolException("DL/T 645 帧长度不足");
        }
        int start = findStart(bytes);
        if (start < 0 || start + 11 >= bytes.length) {
            throw new Dlt645ProtocolException("未找到完整的 DL/T 645 帧");
        }
        if ((bytes[start + 7] & 0xFF) != START) {
            throw new Dlt645ProtocolException("DL/T 645 第二个启动字符无效");
        }
        int length = bytes[start + 9] & 0xFF;
        int endIndex = start + 12 + length - 1;
        if (endIndex >= bytes.length || (bytes[endIndex] & 0xFF) != END) {
            throw new Dlt645ProtocolException("DL/T 645 帧结束字符或长度无效");
        }
        int expected = checksum(Arrays.copyOfRange(bytes, start, start + 10 + length));
        int actual = bytes[start + 10 + length] & 0xFF;
        if (expected != actual) {
            throw new Dlt645ProtocolException("DL/T 645 校验和错误");
        }
        byte[] address = Arrays.copyOfRange(bytes, start + 1, start + 7);
        byte[] data = Arrays.copyOfRange(bytes, start + 10, start + 10 + length);
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) ((data[index] - 0x33) & 0xFF);
        }
        return new Dlt645Frame(Dlt645Address.fromWireBytes(address), bytes[start + 8] & 0xFF, data);
    }

    public static Dlt645Frame read(SerialChannel channel, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        while (System.currentTimeMillis() < deadline) {
            Integer value = readByte(channel, deadline);
            if (value == null) {
                continue;
            }
            if (value != START) {
                continue;
            }
            frame.reset();
            frame.write(value);
            byte[] addressAndStart = readExact(channel, 7, deadline);
            if ((addressAndStart[6] & 0xFF) != START) {
                continue;
            }
            frame.writeBytes(addressAndStart);
            byte[] controlAndLength = readExact(channel, 2, deadline);
            frame.writeBytes(controlAndLength);
            int length = controlAndLength[1] & 0xFF;
            frame.writeBytes(readExact(channel, length + 2, deadline));
            return decode(frame.toByteArray());
        }
        throw new Dlt645ProtocolException("等待 DL/T 645 响应超时");
    }

    private static byte[] readExact(SerialChannel channel, int length, long deadline) throws Exception {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length && System.currentTimeMillis() < deadline) {
            long remaining = Math.max(1, deadline - System.currentTimeMillis());
            int count = channel.read(result, offset, length - offset, remaining);
            if (count > 0) {
                offset += count;
            }
        }
        if (offset != length) {
            throw new Dlt645ProtocolException("DL/T 645 响应不完整");
        }
        return result;
    }

    private static Integer readByte(SerialChannel channel, long deadline) throws Exception {
        byte[] one = new byte[1];
        long remaining = Math.max(1, deadline - System.currentTimeMillis());
        return channel.read(one, 0, 1, remaining) == 1 ? one[0] & 0xFF : null;
    }

    private static int findStart(byte[] bytes) {
        for (int index = 0; index < bytes.length; index++) {
            if ((bytes[index] & 0xFF) == START) {
                return index;
            }
        }
        return -1;
    }

    private static int checksum(byte[] bytes) {
        int result = 0;
        for (byte value : bytes) {
            result = (result + (value & 0xFF)) & 0xFF;
        }
        return result;
    }
}

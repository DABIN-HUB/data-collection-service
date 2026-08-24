package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.transport.BacnetSerialChannel;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * 定义当前模块的业务组件。
 */
public final class BacnetMstpFrameCodec {

    public static final int PREAMBLE_FIRST = 0x55;
    public static final int PREAMBLE_SECOND = 0xFF;

    /**
     * 创建当前组件实例。
     */
    private BacnetMstpFrameCodec() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encode(BacnetMstpFrame frame) {
        byte[] payload = frame.data();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PREAMBLE_FIRST);
        out.write(PREAMBLE_SECOND);
        out.write(frame.frameType().getCode() & 0xFF);
        out.write(frame.destinationAddress() & 0xFF);
        out.write(frame.sourceAddress() & 0xFF);
        out.write((payload.length >> 8) & 0xFF);
        out.write(payload.length & 0xFF);

        byte[] headerBytes = new byte[]{
                (byte) frame.frameType().getCode(),
                (byte) frame.destinationAddress(),
                (byte) frame.sourceAddress(),
                (byte) ((payload.length >> 8) & 0xFF),
                (byte) (payload.length & 0xFF)
        };
        out.write(BacnetMstpCrc.headerCrc(headerBytes) & 0xFF);

        if (payload.length > 0) {
            out.writeBytes(payload);
            int dataCrc = BacnetMstpCrc.dataCrc(payload);
            out.write(dataCrc & 0xFF);
            out.write((dataCrc >> 8) & 0xFF);
        }
        return out.toByteArray();
    }

    /**
     * 解析或转换业务数据。
     */
    public static BacnetMstpFrame decode(byte[] rawFrame) {
        if (rawFrame == null || rawFrame.length < 8) {
            throw new IllegalArgumentException("BACnet MS/TP frame is too short");
        }
        if (Byte.toUnsignedInt(rawFrame[0]) != PREAMBLE_FIRST || Byte.toUnsignedInt(rawFrame[1]) != PREAMBLE_SECOND) {
            throw new IllegalArgumentException("BACnet MS/TP preamble mismatch");
        }
        byte[] headerBytes = Arrays.copyOfRange(rawFrame, 2, 7);
        int expectedHeaderCrc = BacnetMstpCrc.headerCrc(headerBytes);
        int actualHeaderCrc = Byte.toUnsignedInt(rawFrame[7]);
        if (expectedHeaderCrc != actualHeaderCrc) {
            throw new CrcException("BACnet MS/TP header CRC mismatch: expected="
                    + expectedHeaderCrc + ", actual=" + actualHeaderCrc);
        }
        BacnetMstpFrameType frameType = BacnetMstpFrameType.fromCode(Byte.toUnsignedInt(rawFrame[2]));
        int destinationAddress = Byte.toUnsignedInt(rawFrame[3]);
        int sourceAddress = Byte.toUnsignedInt(rawFrame[4]);
        int dataLength = (Byte.toUnsignedInt(rawFrame[5]) << 8) | Byte.toUnsignedInt(rawFrame[6]);
        int expectedLength = dataLength > 0 ? 8 + dataLength + 2 : 8;
        if (rawFrame.length != expectedLength) {
            throw new IllegalArgumentException("BACnet MS/TP frame length mismatch: expected="
                    + expectedLength + ", actual=" + rawFrame.length);
        }
        byte[] payload = dataLength > 0 ? Arrays.copyOfRange(rawFrame, 8, 8 + dataLength) : new byte[0];
        if (dataLength > 0) {
            int expectedDataCrc = BacnetMstpCrc.dataCrc(payload);
            int actualDataCrc = Byte.toUnsignedInt(rawFrame[8 + dataLength])
                    | (Byte.toUnsignedInt(rawFrame[8 + dataLength + 1]) << 8);
            if (expectedDataCrc != actualDataCrc) {
                throw new CrcException("BACnet MS/TP data CRC mismatch: expected="
                        + expectedDataCrc + ", actual=" + actualDataCrc);
            }
        }
        return new BacnetMstpFrame(frameType, destinationAddress, sourceAddress, payload);
    }

    /**
     * 查询并返回业务数据。
     */
    public static BacnetMstpFrame read(BacnetSerialChannel channel, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMs);
        int state = 0;
        while (System.currentTimeMillis() < deadline) {
            Integer value = readByte(channel, deadline - System.currentTimeMillis());
            if (value == null) {
                return null;
            }
            if (state == 0) {
                state = value == PREAMBLE_FIRST ? 1 : 0;
                continue;
            }
            if (value == PREAMBLE_SECOND) {
                byte[] headerAndCrc = readExact(channel, 6, Math.max(1L, deadline - System.currentTimeMillis()));
                if (headerAndCrc == null) {
                    return null;
                }
                int dataLength = (Byte.toUnsignedInt(headerAndCrc[3]) << 8) | Byte.toUnsignedInt(headerAndCrc[4]);
                byte[] dataAndCrc = dataLength > 0
                        ? readExact(channel, dataLength + 2, Math.max(1L, deadline - System.currentTimeMillis()))
                        : new byte[0];
                if (dataLength > 0 && dataAndCrc == null) {
                    return null;
                }
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.write(PREAMBLE_FIRST);
                frame.write(PREAMBLE_SECOND);
                frame.writeBytes(headerAndCrc);
                if (dataLength > 0) {
                    frame.writeBytes(dataAndCrc);
                }
                return decode(frame.toByteArray());
            }
            state = value == PREAMBLE_FIRST ? 1 : 0;
        }
        return null;
    }

    /**
     * 查询并返回业务数据。
     */
    private static byte[] readExact(BacnetSerialChannel channel, int length, long timeoutMs) throws Exception {
        byte[] buffer = new byte[length];
        int offset = 0;
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMs);
        while (offset < length && System.currentTimeMillis() < deadline) {
            long remaining = Math.max(1L, deadline - System.currentTimeMillis());
            int count = channel.read(buffer, offset, length - offset, remaining);
            if (count <= 0) {
                continue;
            }
            offset += count;
        }
        return offset == length ? buffer : null;
    }

    /**
     * 查询并返回业务数据。
     */
    private static Integer readByte(BacnetSerialChannel channel, long timeoutMs) throws Exception {
        byte[] buffer = new byte[1];
        int count = channel.read(buffer, 0, 1, Math.max(1L, timeoutMs));
        if (count <= 0) {
            return null;
        }
        return Byte.toUnsignedInt(buffer[0]);
    }

    /**
     * 表示当前模块的异常语义。
     */
    public static final class CrcException extends IllegalArgumentException {

        /**
         * 创建当前组件实例。
         */
        public CrcException(String message) {
            super(message);
        }
    }
}
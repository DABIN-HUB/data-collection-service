package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class BacnetBvlcCodec {

    public static final int BVLC_RESULT_CODE_SUCCESSFUL_COMPLETION = 0x0000;

    private BacnetBvlcCodec() {
    }

    public static byte[] encodeRegisterForeignDevice(int ttlSeconds) {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(BacnetReadPropertyCodec.BVLC_TYPE_IP);
        frame.write(BacnetReadPropertyCodec.BVLC_REGISTER_FOREIGN_DEVICE);
        frame.write(0);
        frame.write(6);
        frame.write((ttlSeconds >> 8) & 0xFF);
        frame.write(ttlSeconds & 0xFF);
        return frame.toByteArray();
    }

    public static void verifyResult(byte[] frame, int expectedResultCode) {
        if (frame == null || frame.length < 6) {
            throw new IllegalArgumentException("BACnet BVLC result frame is too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int type = Byte.toUnsignedInt(buffer.get());
        int function = Byte.toUnsignedInt(buffer.get());
        int length = Short.toUnsignedInt(buffer.getShort());
        if (type != BacnetReadPropertyCodec.BVLC_TYPE_IP) {
            throw new IllegalArgumentException("Unexpected BACnet BVLC type: 0x" + Integer.toHexString(type));
        }
        if (function != BacnetReadPropertyCodec.BVLC_RESULT) {
            throw new IllegalArgumentException("Unexpected BACnet BVLC function: 0x" + Integer.toHexString(function));
        }
        if (length != frame.length) {
            throw new IllegalArgumentException("BACnet BVLC result length mismatch: declared=" + length + ", actual=" + frame.length);
        }
        int resultCode = Short.toUnsignedInt(buffer.getShort());
        if (resultCode != expectedResultCode) {
            throw new IllegalStateException("BACnet BVLC result code mismatch: expected=" + expectedResultCode + ", actual=" + resultCode);
        }
    }

    public static InetSocketAddress resolveMessageSource(byte[] frame, InetSocketAddress transportSource) {
        if (frame == null || frame.length < 10) {
            return transportSource;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
            int type = Byte.toUnsignedInt(buffer.get());
            int function = Byte.toUnsignedInt(buffer.get());
            if (type != BacnetReadPropertyCodec.BVLC_TYPE_IP
                    || function != BacnetReadPropertyCodec.BVLC_FORWARDED_NPDU) {
                return transportSource;
            }
            int declaredLength = Short.toUnsignedInt(buffer.getShort());
            if (declaredLength != frame.length) {
                return transportSource;
            }
            byte[] ipBytes = new byte[4];
            buffer.get(ipBytes);
            int port = Short.toUnsignedInt(buffer.getShort());
            return new InetSocketAddress(InetAddress.getByAddress(ipBytes), port);
        } catch (Exception ex) {
            return transportSource;
        }
    }

    public static byte[] wrapWithFunction(byte[] originalFrame, int bvlcFunction) {
        if (originalFrame == null || originalFrame.length < 4) {
            throw new IllegalArgumentException("BACnet frame is too short");
        }
        byte[] copy = Arrays.copyOf(originalFrame, originalFrame.length);
        copy[1] = (byte) (bvlcFunction & 0xFF);
        return copy;
    }
}
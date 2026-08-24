package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * 定义当前模块的业务组件。
 */
public final class BacnetTransportFrameSupport {

    /**
     * 创建当前组件实例。
     */
    private BacnetTransportFrameSupport() {
    }

    /**
     * 执行当前业务逻辑。
     */
    public static byte[] unwrapBvlc(byte[] frame) {
        if (frame == null || frame.length < 4) {
            throw new IllegalArgumentException("BACnet frame is too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int bvlcType = Byte.toUnsignedInt(buffer.get());
        if (bvlcType != BacnetReadPropertyCodec.BVLC_TYPE_IP) {
            throw new IllegalArgumentException("Unexpected BACnet BVLC type: 0x" + Integer.toHexString(bvlcType));
        }
        int function = Byte.toUnsignedInt(buffer.get());
        int declaredLength = Short.toUnsignedInt(buffer.getShort());
        if (declaredLength != frame.length) {
            throw new IllegalArgumentException("BACnet BVLC frame length mismatch: declared="
                    + declaredLength + ", actual=" + frame.length);
        }
        int offset = function == BacnetReadPropertyCodec.BVLC_FORWARDED_NPDU ? 10 : 4;
        if (frame.length < offset) {
            throw new IllegalArgumentException("BACnet BVLC frame is truncated");
        }
        return Arrays.copyOfRange(frame, offset, frame.length);
    }

    /**
     * 执行当前业务逻辑。
     */
    public static byte[] wrapNpdu(byte[] npdu) {
        if (npdu == null || npdu.length < 2) {
            throw new IllegalArgumentException("BACnet NPDU is too short");
        }
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(BacnetReadPropertyCodec.BVLC_TYPE_IP);
        frame.write(BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU);
        int totalLength = npdu.length + 4;
        frame.write((totalLength >> 8) & 0xFF);
        frame.write(totalLength & 0xFF);
        frame.writeBytes(npdu);
        return frame.toByteArray();
    }
}
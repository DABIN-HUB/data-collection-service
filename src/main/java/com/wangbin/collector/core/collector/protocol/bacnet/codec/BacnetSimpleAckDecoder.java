package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class BacnetSimpleAckDecoder {

    private BacnetSimpleAckDecoder() {
    }

    public static void verify(byte[] frame, int expectedInvokeId, int expectedServiceChoice) {
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        BacnetReadPropertyResponseDecoder.BacnetFrameHeader header =
                BacnetReadPropertyResponseDecoder.readFrameHeader(buffer);
        switch (header.pduType()) {
            case BacnetReadPropertyCodec.APDU_TYPE_SIMPLE_ACK -> decodeSimpleAck(buffer, expectedInvokeId, expectedServiceChoice);
            case BacnetReadPropertyCodec.APDU_TYPE_ERROR -> throw BacnetReadPropertyResponseDecoder.decodeError(buffer);
            case BacnetReadPropertyCodec.APDU_TYPE_REJECT -> throw BacnetReadPropertyResponseDecoder.decodeReject(buffer, header.pduHeader());
            case BacnetReadPropertyCodec.APDU_TYPE_ABORT -> throw BacnetReadPropertyResponseDecoder.decodeAbort(buffer, header.pduHeader());
            default -> throw new IllegalArgumentException("Unsupported BACnet APDU type for SimpleACK: " + header.pduType());
        }
    }

    private static void decodeSimpleAck(ByteBuffer buffer, int expectedInvokeId, int expectedServiceChoice) {
        int invokeId = Byte.toUnsignedInt(buffer.get());
        if ((expectedInvokeId & 0xFF) != invokeId) {
            throw new IllegalStateException("BACnet invokeId mismatch: expected="
                    + (expectedInvokeId & 0xFF) + ", actual=" + invokeId);
        }
        int serviceChoice = Byte.toUnsignedInt(buffer.get());
        if (serviceChoice != expectedServiceChoice) {
            throw new IllegalStateException("Unexpected BACnet SimpleACK service choice: " + serviceChoice);
        }
    }
}

package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 定义当前模块的业务组件。
 */
public final class BacnetSegmentSupport {

    /**
     * 创建当前组件实例。
     */
    private BacnetSegmentSupport() {
    }

    public static boolean isSegmentedComplexAck(byte[] frame) {
        try {
            BacnetReadPropertyResponseDecoder.BacnetFrameHeader header = BacnetReadPropertyResponseDecoder.readFrameHeader(frame);
            return header.pduType() == BacnetReadPropertyCodec.APDU_TYPE_COMPLEX_ACK
                    && (header.pduHeader() & 0x08) != 0;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 解析或转换业务数据。
     */
    public static SegmentedComplexAckSegment decodeSegmentedComplexAck(byte[] frame) {
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        BacnetReadPropertyResponseDecoder.BacnetFrameHeader header = BacnetReadPropertyResponseDecoder.readFrameHeader(buffer);
        if (header.pduType() != BacnetReadPropertyCodec.APDU_TYPE_COMPLEX_ACK) {
            throw new IllegalArgumentException("Unsupported APDU type for segmented ComplexACK: " + header.pduType());
        }
        boolean segmented = (header.pduHeader() & 0x08) != 0;
        if (!segmented) {
            throw new IllegalArgumentException("BACnet ComplexACK is not segmented");
        }
        boolean moreFollows = (header.pduHeader() & 0x04) != 0;
        int invokeId = Byte.toUnsignedInt(buffer.get());
        int sequenceNumber = Byte.toUnsignedInt(buffer.get());
        int proposedWindowSize = Byte.toUnsignedInt(buffer.get());
        int serviceChoice = Byte.toUnsignedInt(buffer.get());
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        return new SegmentedComplexAckSegment(invokeId,
                sequenceNumber,
                proposedWindowSize,
                serviceChoice,
                moreFollows,
                payload);
    }

    /**
     * 执行当前业务逻辑。
     */
    public static byte[] assembleComplexAckFrame(List<SegmentedComplexAckSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("BACnet segmented ComplexACK list cannot be empty");
        }
        List<SegmentedComplexAckSegment> orderedSegments = new ArrayList<>(segments);
        orderedSegments.sort((left, right) -> Integer.compare(left.sequenceNumber(), right.sequenceNumber()));
        SegmentedComplexAckSegment first = orderedSegments.get(0);
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_COMPLEX_ACK << 4);
        apdu.write(first.invokeId() & 0xFF);
        apdu.write(first.serviceChoice() & 0xFF);
        int expectedSequence = first.sequenceNumber();
        for (SegmentedComplexAckSegment segment : orderedSegments) {
            if (segment.invokeId() != first.invokeId()) {
                throw new IllegalArgumentException("BACnet segmented ComplexACK invokeId is inconsistent");
            }
            if (segment.serviceChoice() != first.serviceChoice()) {
                throw new IllegalArgumentException("BACnet segmented ComplexACK service choice is inconsistent");
            }
            if (segment.sequenceNumber() != expectedSequence) {
                throw new IllegalArgumentException("BACnet segmented ComplexACK sequence mismatch: expected="
                        + expectedSequence + ", actual=" + segment.sequenceNumber());
            }
            apdu.writeBytes(segment.payload());
            expectedSequence = (expectedSequence + 1) & 0xFF;
        }
        return BacnetFrameSupport.wrapApdu(apdu.toByteArray(), 0x00, BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU);
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encodeSegmentAck(int invokeId, int sequenceNumber, int actualWindowSize) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_SEGMENT_ACK << 4);
        apdu.write(invokeId & 0xFF);
        apdu.write(sequenceNumber & 0xFF);
        apdu.write(actualWindowSize & 0xFF);
        return BacnetFrameSupport.wrapApdu(apdu.toByteArray(), 0x04, BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU);
    }

    /**
     * 定义当前模块的不可变数据记录。
     */
    public record SegmentedComplexAckSegment(int invokeId,
                                             int sequenceNumber,
                                             int proposedWindowSize,
                                             int serviceChoice,
                                             boolean moreFollows,
                                             byte[] payload) {
    }
}

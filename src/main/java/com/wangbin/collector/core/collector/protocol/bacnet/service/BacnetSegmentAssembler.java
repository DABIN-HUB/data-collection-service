package com.wangbin.collector.core.collector.protocol.bacnet.service;

import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSegmentSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class BacnetSegmentAssembler {

    public byte[] collect(byte[] firstFrame,
                          int segmentTimeoutMs,
                          SegmentFramePoller poller,
                          SegmentAckSender ackSender) throws Exception {
        List<BacnetSegmentSupport.SegmentedComplexAckSegment> segments = new ArrayList<>();
        BacnetSegmentSupport.SegmentedComplexAckSegment current =
                BacnetSegmentSupport.decodeSegmentedComplexAck(firstFrame);
        segments.add(current);
        ackSender.send(current.invokeId(), current.sequenceNumber(), Math.max(1, current.proposedWindowSize()));

        while (current.moreFollows()) {
            byte[] nextFrame = poller.poll(segmentTimeoutMs, TimeUnit.MILLISECONDS);
            if (nextFrame == null) {
                throw new java.net.SocketTimeoutException(
                        "BACnet segmented response timed out after " + segmentTimeoutMs + "ms");
            }
            BacnetSegmentSupport.SegmentedComplexAckSegment nextSegment =
                    BacnetSegmentSupport.decodeSegmentedComplexAck(nextFrame);
            if (nextSegment.invokeId() != current.invokeId()) {
                throw new IllegalStateException("BACnet segmented invokeId mismatch: expected="
                        + current.invokeId() + ", actual=" + nextSegment.invokeId());
            }
            if (nextSegment.sequenceNumber() != ((current.sequenceNumber() + 1) & 0xFF)) {
                throw new IllegalStateException("BACnet segmented sequence mismatch: expected="
                        + (((current.sequenceNumber() + 1) & 0xFF)) + ", actual=" + nextSegment.sequenceNumber());
            }
            segments.add(nextSegment);
            current = nextSegment;
            ackSender.send(current.invokeId(), current.sequenceNumber(), Math.max(1, current.proposedWindowSize()));
        }
        return BacnetSegmentSupport.assembleComplexAckFrame(segments);
    }

    @FunctionalInterface
    public interface SegmentFramePoller {
        byte[] poll(long timeout, TimeUnit unit) throws Exception;
    }

    @FunctionalInterface
    public interface SegmentAckSender {
        void send(int invokeId, int sequenceNumber, int proposedWindowSize) throws Exception;
    }
}

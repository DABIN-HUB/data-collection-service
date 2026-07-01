package com.wangbin.collector.core.collector.protocol.bacnet.client;

import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetConfirmedCovNotificationCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetCovNotificationDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetMstpFrame;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyMultipleCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyMultipleResponseDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyResponseDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSegmentSupport;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSimpleAckDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSubscribeCovCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSubscribeCovPropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetTransportFrameSupport;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetWritePropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetWritePropertyMultipleCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetCovNotification;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.transport.BacnetMstpTokenManager;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
public class BacnetMstpClient implements AutoCloseable {

    private final BacnetMstpTokenManager tokenManager;
    private final int remoteMacAddress;
    private final AtomicLong requestRetryCount = new AtomicLong(0);
    private final AtomicLong requestTimeoutCount = new AtomicLong(0);
    private final AtomicLong invokeIdMismatchCount = new AtomicLong(0);
    private final AtomicLong covNotificationCount = new AtomicLong(0);
    private final AtomicLong segmentedResponseCount = new AtomicLong(0);

    private volatile Consumer<BacnetCovNotification> covNotificationHandler;

    public BacnetMstpClient(BacnetMstpTokenManager tokenManager, int remoteMacAddress) {
        this.tokenManager = tokenManager;
        this.remoteMacAddress = remoteMacAddress;
        this.tokenManager.setIncomingFrameHandler(this::handleIncomingFrame);
    }

    public void setCovNotificationHandler(Consumer<BacnetCovNotification> handler) {
        this.covNotificationHandler = handler;
    }

    public BacnetReadPropertyResponse readProperty(BacnetReadPropertyRequest request,
                                                   long timeoutMs,
                                                   long segmentTimeoutMs,
                                                   int retries) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetReadPropertyCodec.encode(request));
        return exchangeConfirmed(npdu, timeoutMs, segmentTimeoutMs, retries,
                frame -> BacnetReadPropertyResponseDecoder.decode(frame, request.getInvokeId()));
    }

    public BacnetReadPropertyMultipleResponse readPropertyMultiple(BacnetReadPropertyMultipleRequest request,
                                                                   long timeoutMs,
                                                                   long segmentTimeoutMs,
                                                                   int retries) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetReadPropertyMultipleCodec.encode(request));
        return exchangeConfirmed(npdu, timeoutMs, segmentTimeoutMs, retries,
                frame -> BacnetReadPropertyMultipleResponseDecoder.decode(frame, request.getInvokeId()));
    }

    public void writeProperty(BacnetWritePropertyRequest request, long timeoutMs, int retries) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetWritePropertyCodec.encode(request));
        exchangeConfirmed(npdu, timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetWritePropertyCodec.SERVICE_CHOICE_WRITE_PROPERTY);
            return null;
        });
    }

    public void writePropertyMultiple(BacnetWritePropertyMultipleRequest request, long timeoutMs, int retries) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetWritePropertyMultipleCodec.encode(request));
        exchangeConfirmed(npdu, timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetWritePropertyMultipleCodec.SERVICE_CHOICE_WRITE_PROPERTY_MULTIPLE);
            return null;
        });
    }

    public void subscribeCov(BacnetSubscribeCovRequest request, long timeoutMs, int retries) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetSubscribeCovCodec.encode(request));
        exchangeConfirmed(npdu, timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetSubscribeCovCodec.SERVICE_CHOICE_SUBSCRIBE_COV);
            return null;
        });
    }

    public void subscribeCovProperty(BacnetSubscribeCovPropertyRequest request, long timeoutMs, int retries) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetSubscribeCovPropertyCodec.encode(request));
        exchangeConfirmed(npdu, timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetSubscribeCovPropertyCodec.SERVICE_CHOICE_SUBSCRIBE_COV_PROPERTY);
            return null;
        });
    }

    public void acknowledgeConfirmedCovNotification(int invokeId) throws Exception {
        byte[] npdu = BacnetTransportFrameSupport.unwrapBvlc(BacnetConfirmedCovNotificationCodec.encodeAck(invokeId));
        tokenManager.sendUnconfirmed(remoteMacAddress, npdu, 1000);
    }

    public long getRequestRetryCount() { return requestRetryCount.get(); }
    public long getRequestTimeoutCount() { return requestTimeoutCount.get(); }
    public long getInvokeIdMismatchCount() { return invokeIdMismatchCount.get(); }
    public long getCovNotificationCount() { return covNotificationCount.get(); }
    public long getSegmentedResponseCount() { return segmentedResponseCount.get(); }

    @Override
    public void close() {
        tokenManager.setIncomingFrameHandler(null);
    }

    private <T> T exchangeConfirmed(byte[] requestNpdu,
                                    long timeoutMs,
                                    long segmentTimeoutMs,
                                    int retries,
                                    FrameDecoder<T> decoder) throws Exception {
        int attempts = Math.max(0, retries) + 1;
        int resolvedTimeout = resolveTimeout(timeoutMs);
        int resolvedSegmentTimeout = resolveTimeout(segmentTimeoutMs > 0 ? segmentTimeoutMs : timeoutMs);
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            long deadline = System.currentTimeMillis() + resolvedTimeout;
            tokenManager.beginConfirmedRequest(remoteMacAddress, requestNpdu, resolvedTimeout);
            try {
                while (System.currentTimeMillis() <= deadline) {
                    BacnetMstpFrame frame = tokenManager.awaitConversationFrame(Math.max(1L, deadline - System.currentTimeMillis()));
                    if (frame.frameType() == com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetMstpFrameType.REPLY_POSTPONED) {
                        continue;
                    }
                    byte[] wrapped = BacnetTransportFrameSupport.wrapNpdu(frame.data());
                    if (!BacnetSegmentSupport.isSegmentedComplexAck(wrapped)) {
                        try {
                            return decoder.decode(wrapped);
                        } catch (Exception ex) {
                            if (isInvokeIdMismatch(ex)) {
                                invokeIdMismatchCount.incrementAndGet();
                                lastFailure = ex;
                                continue;
                            }
                            throw ex;
                        }
                    }
                    segmentedResponseCount.incrementAndGet();
                    byte[] assembled = collectSegmentedComplexAck(frame, resolvedSegmentTimeout);
                    return decoder.decode(assembled);
                }
            } catch (SocketTimeoutException ex) {
                lastFailure = ex;
            } finally {
                tokenManager.finishConversation();
            }

            lastFailure = new SocketTimeoutException("BACnet MS/TP receive timed out after " + resolvedTimeout + "ms");
            requestTimeoutCount.incrementAndGet();
            if (attempt < attempts) {
                requestRetryCount.incrementAndGet();
                log.debug("Retry BACnet MS/TP request after timeout, remoteMac={}, attempt={}/{}",
                        remoteMacAddress, attempt + 1, attempts);
            }
        }
        throw lastFailure;
    }

    private byte[] collectSegmentedComplexAck(BacnetMstpFrame firstFrame, int segmentTimeoutMs) throws Exception {
        List<BacnetSegmentSupport.SegmentedComplexAckSegment> segments = new ArrayList<>();
        BacnetSegmentSupport.SegmentedComplexAckSegment current =
                BacnetSegmentSupport.decodeSegmentedComplexAck(BacnetTransportFrameSupport.wrapNpdu(firstFrame.data()));
        segments.add(current);
        tokenManager.sendConversationFrame(remoteMacAddress,
                BacnetTransportFrameSupport.unwrapBvlc(BacnetSegmentSupport.encodeSegmentAck(
                        current.invokeId(),
                        current.sequenceNumber(),
                        Math.max(1, current.proposedWindowSize()))),
                true);

        while (current.moreFollows()) {
            BacnetMstpFrame nextFrame = tokenManager.awaitConversationFrame(segmentTimeoutMs);
            BacnetSegmentSupport.SegmentedComplexAckSegment nextSegment =
                    BacnetSegmentSupport.decodeSegmentedComplexAck(BacnetTransportFrameSupport.wrapNpdu(nextFrame.data()));
            if (nextSegment.invokeId() != current.invokeId()) {
                throw new IllegalStateException("BACnet MS/TP segmented invokeId mismatch: expected="
                        + current.invokeId() + ", actual=" + nextSegment.invokeId());
            }
            if (nextSegment.sequenceNumber() != ((current.sequenceNumber() + 1) & 0xFF)) {
                throw new IllegalStateException("BACnet MS/TP segmented sequence mismatch: expected="
                        + (((current.sequenceNumber() + 1) & 0xFF)) + ", actual=" + nextSegment.sequenceNumber());
            }
            segments.add(nextSegment);
            current = nextSegment;
            if (current.moreFollows()) {
                tokenManager.sendConversationFrame(remoteMacAddress,
                        BacnetTransportFrameSupport.unwrapBvlc(BacnetSegmentSupport.encodeSegmentAck(
                                current.invokeId(),
                                current.sequenceNumber(),
                                Math.max(1, current.proposedWindowSize()))),
                        true);
            }
        }
        return BacnetSegmentSupport.assembleComplexAckFrame(segments);
    }

    private void handleIncomingFrame(BacnetMstpFrame frame) {
        if (frame == null || !frame.frameType().isDataFrame()) {
            return;
        }
        try {
            byte[] wrapped = BacnetTransportFrameSupport.wrapNpdu(frame.data());
            if (!BacnetCovNotificationDecoder.isUnconfirmedCovNotification(wrapped)
                    && !BacnetCovNotificationDecoder.isConfirmedCovNotification(wrapped)) {
                return;
            }
            BacnetCovNotification notification = BacnetCovNotificationDecoder.decode(wrapped);
            if (notification.isConfirmed() && notification.getInvokeId() != null) {
                acknowledgeConfirmedCovNotification(notification.getInvokeId());
            }
            covNotificationCount.incrementAndGet();
            Consumer<BacnetCovNotification> handler = covNotificationHandler;
            if (handler != null) {
                handler.accept(notification);
            }
        } catch (Exception ex) {
            log.warn("Decode BACnet MS/TP COV notification failed, remoteMac={}", remoteMacAddress, ex);
        }
    }

    private boolean isInvokeIdMismatch(Exception ex) {
        String message = ex.getMessage();
        return message != null && message.contains("invokeId mismatch");
    }

    private int resolveTimeout(long timeoutMs) {
        if (timeoutMs <= 0) {
            return 5000;
        }
        if (timeoutMs > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) timeoutMs;
    }

    @FunctionalInterface
    private interface FrameDecoder<T> {
        T decode(byte[] frame) throws Exception;
    }
}

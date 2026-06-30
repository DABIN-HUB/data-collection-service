package com.wangbin.collector.core.collector.protocol.bacnet.client;

import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetCovNotificationDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyMultipleCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyMultipleResponseDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyResponseDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSegmentSupport;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSimpleAckDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSubscribeCovCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetSubscribeCovPropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetWritePropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetCovNotification;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyRequest;
import com.wangbin.collector.core.connection.adapter.BacnetScConnectionAdapter;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
public class BacnetScClient implements AutoCloseable {

    private static final int RECEIVE_POLL_TIMEOUT_MS = 250;

    private final BacnetScConnectionAdapter connectionAdapter;
    private final BlockingQueue<byte[]> responseQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong requestRetryCount = new AtomicLong(0);
    private final AtomicLong requestTimeoutCount = new AtomicLong(0);
    private final AtomicLong invokeIdMismatchCount = new AtomicLong(0);
    private final AtomicLong covNotificationCount = new AtomicLong(0);
    private final AtomicLong segmentedResponseCount = new AtomicLong(0);
    private final Thread receiverThread;

    private volatile Consumer<BacnetCovNotification> covNotificationHandler;

    public BacnetScClient(BacnetScConnectionAdapter connectionAdapter) {
        this.connectionAdapter = connectionAdapter;
        this.receiverThread = new Thread(this::receiveLoop, "bacnet-sc-client-" + connectionAdapter.getDeviceId());
        this.receiverThread.setDaemon(true);
        this.receiverThread.start();
    }

    public void setCovNotificationHandler(Consumer<BacnetCovNotification> handler) {
        this.covNotificationHandler = handler;
    }

    public BacnetReadPropertyResponse readProperty(BacnetReadPropertyRequest request,
                                                   long timeoutMs,
                                                   long segmentTimeoutMs,
                                                   int retries) throws Exception {
        return exchange(BacnetReadPropertyCodec.encode(request),
                timeoutMs,
                segmentTimeoutMs,
                retries,
                frame -> BacnetReadPropertyResponseDecoder.decode(frame, request.getInvokeId()));
    }

    public BacnetReadPropertyMultipleResponse readPropertyMultiple(BacnetReadPropertyMultipleRequest request,
                                                                   long timeoutMs,
                                                                   long segmentTimeoutMs,
                                                                   int retries) throws Exception {
        return exchange(BacnetReadPropertyMultipleCodec.encode(request),
                timeoutMs,
                segmentTimeoutMs,
                retries,
                frame -> BacnetReadPropertyMultipleResponseDecoder.decode(frame, request.getInvokeId()));
    }

    public void writeProperty(BacnetWritePropertyRequest request, long timeoutMs, int retries) throws Exception {
        exchange(BacnetWritePropertyCodec.encode(request), timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetWritePropertyCodec.SERVICE_CHOICE_WRITE_PROPERTY);
            return null;
        });
    }

    public void subscribeCov(BacnetSubscribeCovRequest request, long timeoutMs, int retries) throws Exception {
        exchange(BacnetSubscribeCovCodec.encode(request), timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetSubscribeCovCodec.SERVICE_CHOICE_SUBSCRIBE_COV);
            return null;
        });
    }

    public void subscribeCovProperty(BacnetSubscribeCovPropertyRequest request, long timeoutMs, int retries) throws Exception {
        exchange(BacnetSubscribeCovPropertyCodec.encode(request), timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetSubscribeCovPropertyCodec.SERVICE_CHOICE_SUBSCRIBE_COV_PROPERTY);
            return null;
        });
    }

    public long getRequestRetryCount() { return requestRetryCount.get(); }
    public long getRequestTimeoutCount() { return requestTimeoutCount.get(); }
    public long getInvokeIdMismatchCount() { return invokeIdMismatchCount.get(); }
    public long getCovNotificationCount() { return covNotificationCount.get(); }
    public long getSegmentedResponseCount() { return segmentedResponseCount.get(); }

    @Override
    public void close() {
        running.set(false);
        receiverThread.interrupt();
    }

    private <T> T exchange(byte[] requestFrame,
                           long timeoutMs,
                           long segmentTimeoutMs,
                           int retries,
                           FrameDecoder<T> decoder) throws Exception {
        int attempts = Math.max(0, retries) + 1;
        int resolvedTimeout = resolveTimeout(timeoutMs);
        int resolvedSegmentTimeout = resolveTimeout(segmentTimeoutMs > 0 ? segmentTimeoutMs : timeoutMs);
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            responseQueue.clear();
            connectionAdapter.send(requestFrame);
            long deadline = System.currentTimeMillis() + resolvedTimeout;
            while (System.currentTimeMillis() <= deadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                byte[] response = awaitResponseFrame(remaining, resolvedSegmentTimeout);
                if (response == null) {
                    break;
                }
                try {
                    return decoder.decode(response);
                } catch (Exception ex) {
                    if (isInvokeIdMismatch(ex)) {
                        invokeIdMismatchCount.incrementAndGet();
                        lastFailure = ex;
                        continue;
                    }
                    throw ex;
                }
            }
            lastFailure = new SocketTimeoutException("BACnet/SC receive timed out after " + resolvedTimeout + "ms");
            requestTimeoutCount.incrementAndGet();
            if (attempt < attempts) {
                requestRetryCount.incrementAndGet();
                log.debug("Retry BACnet/SC request after timeout, deviceId={}, attempt={}/{}",
                        connectionAdapter.getDeviceId(), attempt + 1, attempts);
            }
        }
        throw lastFailure;
    }

    private byte[] awaitResponseFrame(long responseTimeoutMs, int segmentTimeoutMs) throws Exception {
        byte[] response = responseQueue.poll(responseTimeoutMs, TimeUnit.MILLISECONDS);
        if (response == null) {
            return null;
        }
        if (!BacnetSegmentSupport.isSegmentedComplexAck(response)) {
            return response;
        }
        segmentedResponseCount.incrementAndGet();
        return collectSegmentedComplexAck(response, segmentTimeoutMs);
    }

    private byte[] collectSegmentedComplexAck(byte[] firstFrame, int segmentTimeoutMs) throws Exception {
        List<BacnetSegmentSupport.SegmentedComplexAckSegment> segments = new ArrayList<>();
        BacnetSegmentSupport.SegmentedComplexAckSegment current = BacnetSegmentSupport.decodeSegmentedComplexAck(firstFrame);
        segments.add(current);
        connectionAdapter.send(BacnetSegmentSupport.encodeSegmentAck(current.invokeId(),
                current.sequenceNumber(),
                Math.max(1, current.proposedWindowSize())));

        while (current.moreFollows()) {
            byte[] nextFrame = responseQueue.poll(segmentTimeoutMs, TimeUnit.MILLISECONDS);
            if (nextFrame == null) {
                throw new SocketTimeoutException("BACnet/SC segmented response timed out after " + segmentTimeoutMs + "ms");
            }
            BacnetSegmentSupport.SegmentedComplexAckSegment nextSegment =
                    BacnetSegmentSupport.decodeSegmentedComplexAck(nextFrame);
            if (nextSegment.invokeId() != current.invokeId()) {
                throw new IllegalStateException("BACnet/SC segmented invokeId mismatch: expected="
                        + current.invokeId() + ", actual=" + nextSegment.invokeId());
            }
            if (nextSegment.sequenceNumber() != ((current.sequenceNumber() + 1) & 0xFF)) {
                throw new IllegalStateException("BACnet/SC segmented sequence mismatch: expected="
                        + (((current.sequenceNumber() + 1) & 0xFF)) + ", actual=" + nextSegment.sequenceNumber());
            }
            segments.add(nextSegment);
            current = nextSegment;
            connectionAdapter.send(BacnetSegmentSupport.encodeSegmentAck(current.invokeId(),
                    current.sequenceNumber(),
                    Math.max(1, current.proposedWindowSize())));
        }
        return BacnetSegmentSupport.assembleComplexAckFrame(segments);
    }

    private void receiveLoop() {
        while (running.get()) {
            try {
                byte[] data = connectionAdapter.receive(RECEIVE_POLL_TIMEOUT_MS);
                if (data == null || data.length == 0) {
                    continue;
                }
                dispatchIncoming(data);
            } catch (Exception ex) {
                if (running.get() && connectionAdapter.isConnected()) {
                    log.warn("BACnet/SC receive loop stopped unexpectedly, deviceId={}",
                            connectionAdapter.getDeviceId(), ex);
                }
            }
        }
    }

    private void dispatchIncoming(byte[] frame) {
        if (BacnetCovNotificationDecoder.isUnconfirmedCovNotification(frame)) {
            handleCovNotification(frame);
            return;
        }
        if (isOtherUnconfirmedRequest(frame)) {
            return;
        }
        responseQueue.offer(frame);
    }

    private void handleCovNotification(byte[] frame) {
        try {
            BacnetCovNotification notification = BacnetCovNotificationDecoder.decode(frame);
            covNotificationCount.incrementAndGet();
            Consumer<BacnetCovNotification> handler = covNotificationHandler;
            if (handler != null) {
                handler.accept(notification);
            }
        } catch (Exception ex) {
            log.warn("Decode BACnet/SC COV notification failed, deviceId={}", connectionAdapter.getDeviceId(), ex);
        }
    }

    private boolean isOtherUnconfirmedRequest(byte[] frame) {
        if (frame == null || frame.length < 8) {
            return false;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
            int bvlcType = Byte.toUnsignedInt(buffer.get());
            int function = Byte.toUnsignedInt(buffer.get());
            if (bvlcType != BacnetReadPropertyCodec.BVLC_TYPE_IP
                    || function != BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU) {
                return false;
            }
            int declaredLength = Short.toUnsignedInt(buffer.getShort());
            if (declaredLength != frame.length) {
                return false;
            }
            if (Byte.toUnsignedInt(buffer.get()) != BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION) {
                return false;
            }
            buffer.get();
            int pduHeader = Byte.toUnsignedInt(buffer.get());
            int pduType = (pduHeader >> 4) & 0x0F;
            return pduType == BacnetReadPropertyCodec.APDU_TYPE_UNCONFIRMED_REQUEST;
        } catch (Exception ex) {
            return false;
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
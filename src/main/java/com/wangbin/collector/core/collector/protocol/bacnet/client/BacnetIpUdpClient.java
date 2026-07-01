package com.wangbin.collector.core.collector.protocol.bacnet.client;

import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetBvlcCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetConfirmedCovNotificationCodec;
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
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetWritePropertyMultipleCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetCovNotification;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetRemoteDevice;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.service.BacnetClientSupport;
import com.wangbin.collector.core.collector.protocol.bacnet.service.BacnetRequestSession;
import com.wangbin.collector.core.collector.protocol.bacnet.service.BacnetSegmentAssembler;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
public class BacnetIpUdpClient implements AutoCloseable {

    private static final int MAX_FRAME_SIZE = 4096;
    private static final int RECEIVE_POLL_TIMEOUT_MS = 250;

    private final DatagramSocket socket;
    private final InetSocketAddress remoteAddress;
    private final BlockingQueue<byte[]> responseQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong requestRetryCount = new AtomicLong(0);
    private final AtomicLong requestTimeoutCount = new AtomicLong(0);
    private final AtomicLong invokeIdMismatchCount = new AtomicLong(0);
    private final AtomicLong covNotificationCount = new AtomicLong(0);
    private final AtomicLong segmentedResponseCount = new AtomicLong(0);
    private final BacnetClientSupport clientSupport =
            new BacnetClientSupport(invokeIdMismatchCount, covNotificationCount, segmentedResponseCount);
    private final BacnetRequestSession requestSession =
            new BacnetRequestSession(requestRetryCount, requestTimeoutCount, clientSupport);
    private final BacnetSegmentAssembler segmentAssembler = new BacnetSegmentAssembler();
    private final Thread receiverThread;

    private volatile Consumer<BacnetCovNotification> covNotificationHandler;

    public BacnetIpUdpClient(DatagramSocket socket, InetSocketAddress remoteAddress) throws Exception {
        this.socket = socket;
        this.remoteAddress = remoteAddress;
        this.socket.setSoTimeout(RECEIVE_POLL_TIMEOUT_MS);
        this.receiverThread = new Thread(this::receiveLoop, "bacnet-ip-udp-client-" + remoteAddress.getPort());
        this.receiverThread.setDaemon(true);
        this.receiverThread.start();
    }

    public void setCovNotificationHandler(Consumer<BacnetCovNotification> covNotificationHandler) {
        this.covNotificationHandler = covNotificationHandler;
    }

    public BacnetReadPropertyResponse readProperty(BacnetReadPropertyRequest request,
                                                   long timeoutMs,
                                                   long segmentTimeoutMs,
                                                   int retries) throws Exception {
        return exchange(BacnetReadPropertyCodec.encode(request),
                remoteAddress,
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
                remoteAddress,
                timeoutMs,
                segmentTimeoutMs,
                retries,
                frame -> BacnetReadPropertyMultipleResponseDecoder.decode(frame, request.getInvokeId()));
    }

    public void writeProperty(BacnetWritePropertyRequest request,
                              long timeoutMs,
                              int retries) throws Exception {
        exchange(BacnetWritePropertyCodec.encode(request), remoteAddress, timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetWritePropertyCodec.SERVICE_CHOICE_WRITE_PROPERTY);
            return null;
        });
    }

    public void writePropertyMultiple(BacnetWritePropertyMultipleRequest request,
                                      long timeoutMs,
                                      int retries) throws Exception {
        exchange(BacnetWritePropertyMultipleCodec.encode(request), remoteAddress, timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetWritePropertyMultipleCodec.SERVICE_CHOICE_WRITE_PROPERTY_MULTIPLE);
            return null;
        });
    }

    public void subscribeCov(BacnetSubscribeCovRequest request,
                             long timeoutMs,
                             int retries) throws Exception {
        exchange(BacnetSubscribeCovCodec.encode(request), remoteAddress, timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetSubscribeCovCodec.SERVICE_CHOICE_SUBSCRIBE_COV);
            return null;
        });
    }

    public void subscribeCovProperty(BacnetSubscribeCovPropertyRequest request,
                                     long timeoutMs,
                                     int retries) throws Exception {
        exchange(BacnetSubscribeCovPropertyCodec.encode(request), remoteAddress, timeoutMs, timeoutMs, retries, frame -> {
            BacnetSimpleAckDecoder.verify(frame,
                    request.getInvokeId(),
                    BacnetSubscribeCovPropertyCodec.SERVICE_CHOICE_SUBSCRIBE_COV_PROPERTY);
            return null;
        });
    }

    public void acknowledgeConfirmedCovNotification(int invokeId) throws Exception {
        send(BacnetConfirmedCovNotificationCodec.encodeAck(invokeId), remoteAddress);
    }

    public void registerForeignDevice(InetSocketAddress bbmdAddress,
                                      int ttlSeconds,
                                      long timeoutMs,
                                      int retries) throws Exception {
        exchange(BacnetBvlcCodec.encodeRegisterForeignDevice(ttlSeconds),
                bbmdAddress,
                timeoutMs,
                timeoutMs,
                retries,
                frame -> {
                    BacnetBvlcCodec.verifyResult(frame, BacnetBvlcCodec.BVLC_RESULT_CODE_SUCCESSFUL_COMPLETION);
                    return null;
                });
    }

    public BacnetRemoteDevice probeRemoteDevice(int remoteDeviceInstance, int timeoutMs) {
        return BacnetRemoteDevice.builder()
                .deviceInstance(remoteDeviceInstance)
                .socketAddress(remoteAddress)
                .build();
    }

    public long getRequestRetryCount() {
        return requestRetryCount.get();
    }

    public long getRequestTimeoutCount() {
        return requestTimeoutCount.get();
    }

    public long getInvokeIdMismatchCount() {
        return invokeIdMismatchCount.get();
    }

    public long getCovNotificationCount() {
        return covNotificationCount.get();
    }

    public long getSegmentedResponseCount() {
        return segmentedResponseCount.get();
    }

    @Override
    public void close() {
        running.set(false);
        receiverThread.interrupt();
    }

    private <T> T exchange(byte[] requestFrame,
                           InetSocketAddress targetAddress,
                           long timeoutMs,
                           long segmentTimeoutMs,
                           int retries,
                           FrameDecoder<T> decoder) throws Exception {
        String remoteLabel = targetAddress.getHostString() + ":" + targetAddress.getPort();
        return requestSession.execute(new BacnetRequestSession.RequestExchange<>() {
            @Override
            public void beforeAttempt() {
                responseQueue.clear();
            }

            @Override
            public void sendRequest() throws Exception {
                send(requestFrame, targetAddress);
            }

            @Override
            public byte[] pollResponse(long timeout, TimeUnit unit, int resolvedSegmentTimeoutMs) throws Exception {
                byte[] response = responseQueue.poll(timeout, unit);
                if (response == null) {
                    return null;
                }
                if (!clientSupport.isSegmentedComplexAck(response)) {
                    return response;
                }
                clientSupport.recordSegmentedResponse();
                return segmentAssembler.collect(response,
                        resolvedSegmentTimeoutMs,
                        (segmentPollTimeout, pollUnit) -> responseQueue.poll(segmentPollTimeout, pollUnit),
                        (invokeId, sequenceNumber, proposedWindowSize) ->
                                send(BacnetSegmentSupport.encodeSegmentAck(invokeId, sequenceNumber, proposedWindowSize), targetAddress));
            }

            @Override
            public T decode(byte[] response) throws Exception {
                return decoder.decode(response);
            }

            @Override
            public String timeoutMessage(int resolvedTimeoutMs) {
                return "BACnet/IP receive timed out after " + resolvedTimeoutMs + "ms";
            }
        }, timeoutMs, segmentTimeoutMs, retries, "BACnet/IP request remote=" + remoteLabel);
    }

    private void send(byte[] frame) throws Exception {
        send(frame, remoteAddress);
    }

    private void send(byte[] frame, InetSocketAddress targetAddress) throws Exception {
        DatagramPacket packet = new DatagramPacket(frame, frame.length, targetAddress);
        socket.send(packet);
    }

    private void receiveLoop() {
        while (running.get()) {
            DatagramPacket packet = new DatagramPacket(new byte[MAX_FRAME_SIZE], MAX_FRAME_SIZE);
            try {
                socket.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                dispatchIncoming(data);
            } catch (SocketTimeoutException ignored) {
                // Poll loop.
            } catch (Exception ex) {
                if (running.get() && !socket.isClosed()) {
                    log.warn("BACnet/IP UDP receive loop stopped unexpectedly, remote={}:{}",
                            remoteAddress.getHostString(), remoteAddress.getPort(), ex);
                }
                if (socket.isClosed()) {
                    return;
                }
            }
        }
    }

    private void dispatchIncoming(byte[] frame) {
        if (frame == null || frame.length == 0) {
            return;
        }
        if (BacnetCovNotificationDecoder.isUnconfirmedCovNotification(frame)
                || BacnetCovNotificationDecoder.isConfirmedCovNotification(frame)) {
            handleCovNotification(frame);
            return;
        }
        if (isOtherUnconfirmedRequest(frame)) {
            log.debug("Ignore unrelated BACnet unconfirmed frame, remote={}:{}",
                    remoteAddress.getHostString(), remoteAddress.getPort());
            return;
        }
        responseQueue.offer(frame);
    }

    private void handleCovNotification(byte[] frame) {
        clientSupport.handleCovNotification(
                frame,
                remoteAddress.getHostString() + ":" + remoteAddress.getPort(),
                invokeId -> {
                    try {
                        acknowledgeConfirmedCovNotification(invokeId);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                },
                covNotificationHandler);
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
                    || (function != BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU
                    && function != BacnetReadPropertyCodec.BVLC_ORIGINAL_BROADCAST_NPDU
                    && function != BacnetReadPropertyCodec.BVLC_FORWARDED_NPDU)) {
                return false;
            }
            int declaredLength = Short.toUnsignedInt(buffer.getShort());
            if (declaredLength != frame.length) {
                return false;
            }
            if (function == BacnetReadPropertyCodec.BVLC_FORWARDED_NPDU) {
                buffer.position(buffer.position() + 6);
            }
            if (Byte.toUnsignedInt(buffer.get()) != BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION) {
                return false;
            }
            int npduControl = Byte.toUnsignedInt(buffer.get());
            skipNpduAddresses(buffer, npduControl);
            int pduHeader = Byte.toUnsignedInt(buffer.get());
            int pduType = (pduHeader >> 4) & 0x0F;
            return pduType == BacnetReadPropertyCodec.APDU_TYPE_UNCONFIRMED_REQUEST;
        } catch (Exception ex) {
            return false;
        }
    }

    private void skipNpduAddresses(ByteBuffer buffer, int control) {
        boolean destinationSpecified = (control & 0x20) != 0;
        boolean sourceSpecified = (control & 0x08) != 0;
        boolean networkMessage = (control & 0x80) != 0;

        if (destinationSpecified) {
            buffer.getShort();
            int len = Byte.toUnsignedInt(buffer.get());
            buffer.position(buffer.position() + len);
        }
        if (sourceSpecified) {
            buffer.getShort();
            int len = Byte.toUnsignedInt(buffer.get());
            buffer.position(buffer.position() + len);
        }
        if (destinationSpecified) {
            buffer.get();
        }
        if (networkMessage) {
            int messageType = Byte.toUnsignedInt(buffer.get());
            if (messageType >= 80) {
                buffer.getShort();
            }
        }
    }
    @FunctionalInterface
    private interface FrameDecoder<T> {
        T decode(byte[] frame) throws Exception;
    }
}

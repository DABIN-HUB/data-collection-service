package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetFrameSupport;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyResponseDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetTagReader;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SegmentedBacnetTestServer implements AutoCloseable {

    private final DatagramSocket socket;
    private final AtomicReference<RuntimeException> asyncFailure = new AtomicReference<>();
    private final CountDownLatch started = new CountDownLatch(1);

    private volatile boolean running = true;
    private volatile SocketAddress pendingEndpoint;
    private volatile byte[] pendingSecondSegment;
    private volatile int expectedInvokeId = -1;
    private Thread serverThread;

    public SegmentedBacnetTestServer() throws Exception {
        this.socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        this.socket.setSoTimeout(250);
        this.serverThread = new Thread(this::runLoop, "segmented-bacnet-test-server");
        this.serverThread.setDaemon(true);
        this.serverThread.start();
        awaitStart();
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void awaitStart() throws Exception {
        if (!started.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Segmented BACnet test server failed to start");
        }
    }

    private void runLoop() {
        started.countDown();
        byte[] buffer = new byte[2048];
        while (running) {
            DatagramPacket request = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(request);
                byte[] payload = new byte[request.getLength()];
                System.arraycopy(request.getData(), request.getOffset(), payload, 0, request.getLength());
                handleRequest(payload, request.getSocketAddress());
            } catch (SocketTimeoutException ignored) {
                // Poll loop.
            } catch (Exception ex) {
                if (running) {
                    asyncFailure.compareAndSet(null, new RuntimeException("Segmented BACnet test server failed", ex));
                }
            }
        }
    }

    private void handleRequest(byte[] frame, SocketAddress requester) throws Exception {
        verifyNoAsyncFailure();
        if (isSegmentAck(frame)) {
            if (pendingSecondSegment != null && requester.equals(pendingEndpoint)) {
                send(pendingSecondSegment, pendingEndpoint);
                pendingSecondSegment = null;
            }
            return;
        }
        ReadRequest request = parseReadRequest(frame);
        if (request.objectType.getId() != BacnetObjectType.DEVICE.getId()
                || request.instance != 1001
                || request.propertyIdentifier.getId() != BacnetPropertyIdentifier.OBJECT_NAME.getId()) {
            send(buildReject(request.invokeId, 5), requester);
            return;
        }
        byte[][] segments = buildSegmentedAck(request.invokeId, "SEGMENTED-AHU-01");
        expectedInvokeId = request.invokeId;
        pendingEndpoint = requester;
        pendingSecondSegment = segments[1];
        send(segments[0], requester);
    }

    private ReadRequest parseReadRequest(byte[] frame) {
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        BacnetReadPropertyResponseDecoder.BacnetFrameHeader header =
                BacnetReadPropertyResponseDecoder.readFrameHeader(buffer);
        if (header.pduType() != BacnetReadPropertyCodec.APDU_TYPE_CONFIRMED_REQUEST) {
            throw new IllegalArgumentException("Unsupported APDU type: " + header.pduType());
        }
        buffer.get();
        int invokeId = Byte.toUnsignedInt(buffer.get());
        int serviceChoice = Byte.toUnsignedInt(buffer.get());
        if (serviceChoice != BacnetReadPropertyCodec.SERVICE_CHOICE_READ_PROPERTY) {
            throw new IllegalArgumentException("Unsupported service choice: " + serviceChoice);
        }
        BacnetTagReader.TagHeader objectTag = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(objectTag, 0);
        int objectId = buffer.getInt();
        BacnetObjectType objectType = BacnetObjectType.fromId((objectId >>> 22) & 0x03FF);
        int instance = objectId & 0x3FFFFF;

        BacnetTagReader.TagHeader propertyTag = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(propertyTag, 1);
        BacnetPropertyIdentifier propertyIdentifier = BacnetPropertyIdentifier.fromId(
                BacnetReadPropertyResponseDecoder.readUnsigned(buffer, propertyTag.length()));
        return new ReadRequest(invokeId, objectType, instance, propertyIdentifier);
    }

    private boolean isSegmentAck(byte[] frame) {
        if (frame == null || frame.length < 8) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        BacnetReadPropertyResponseDecoder.BacnetFrameHeader header =
                BacnetReadPropertyResponseDecoder.readFrameHeader(buffer);
        if (header.pduType() != BacnetReadPropertyCodec.APDU_TYPE_SEGMENT_ACK) {
            return false;
        }
        int invokeId = Byte.toUnsignedInt(buffer.get());
        return expectedInvokeId >= 0 && invokeId == expectedInvokeId;
    }

    private byte[][] buildSegmentedAck(int invokeId, String value) {
        byte[] stringPayload = value.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream any = new ByteArrayOutputStream();
        any.write((7 << 4) | 0x05);
        any.write(stringPayload.length + 1);
        any.write(0);
        any.writeBytes(stringPayload);

        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_COMPLEX_ACK << 4);
        apdu.write(invokeId & 0xFF);
        apdu.write(BacnetReadPropertyCodec.SERVICE_CHOICE_READ_PROPERTY);
        apdu.write(0x0C);
        int objectIdentifier = ((BacnetObjectType.DEVICE.getId() & 0x03FF) << 22) | 1001;
        apdu.write((objectIdentifier >> 24) & 0xFF);
        apdu.write((objectIdentifier >> 16) & 0xFF);
        apdu.write((objectIdentifier >> 8) & 0xFF);
        apdu.write(objectIdentifier & 0xFF);
        apdu.write(0x19);
        apdu.write(BacnetPropertyIdentifier.OBJECT_NAME.getId());
        apdu.write(0x3E);
        apdu.writeBytes(any.toByteArray());
        apdu.write(0x3F);

        byte[] fullApdu = apdu.toByteArray();
        byte[] payload = Arrays.copyOfRange(fullApdu, 3, fullApdu.length);
        int splitIndex = Math.max(1, payload.length / 2);
        byte[] firstPayload = Arrays.copyOfRange(payload, 0, splitIndex);
        byte[] secondPayload = Arrays.copyOfRange(payload, splitIndex, payload.length);
        return new byte[][]{
                buildSegmentFrame(invokeId, 0, true, firstPayload),
                buildSegmentFrame(invokeId, 1, false, secondPayload)
        };
    }

    private byte[] buildSegmentFrame(int invokeId, int sequenceNumber, boolean moreFollows, byte[] payload) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        int header = (BacnetReadPropertyCodec.APDU_TYPE_COMPLEX_ACK << 4) | 0x08;
        if (moreFollows) {
            header |= 0x04;
        }
        apdu.write(header);
        apdu.write(invokeId & 0xFF);
        apdu.write(sequenceNumber & 0xFF);
        apdu.write(1);
        apdu.write(BacnetReadPropertyCodec.SERVICE_CHOICE_READ_PROPERTY);
        apdu.writeBytes(payload);
        return BacnetFrameSupport.wrapApdu(apdu.toByteArray(), 0x00, BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU);
    }

    private byte[] buildReject(int invokeId, int reason) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_REJECT << 4);
        apdu.write(invokeId & 0xFF);
        apdu.write(reason & 0xFF);
        return BacnetFrameSupport.wrapApdu(apdu.toByteArray(), 0x00, BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU);
    }

    private void send(byte[] frame, SocketAddress target) throws Exception {
        DatagramPacket packet = new DatagramPacket(frame, frame.length, target);
        socket.send(packet);
    }

    private void verifyNoAsyncFailure() {
        RuntimeException failure = asyncFailure.get();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() throws Exception {
        running = false;
        try {
            socket.close();
        } finally {
            if (serverThread != null) {
                serverThread.join(2000);
            }
        }
    }

    private record ReadRequest(int invokeId,
                               BacnetObjectType objectType,
                               int instance,
                               BacnetPropertyIdentifier propertyIdentifier) {
    }
}
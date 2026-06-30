package com.wangbin.collector.core.collector.protocol.bacnet.codec;

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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class FeatureBacnetTestServer implements AutoCloseable {

    private static final int SERVICE_CHOICE_WRITE_PROPERTY = 0x0F;
    private static final int SERVICE_CHOICE_SUBSCRIBE_COV_PROPERTY = 0x1C;
    private static final int SERVICE_CHOICE_UNCONFIRMED_COV_NOTIFICATION = 0x02;

    private final DatagramSocket socket;
    private final Map<String, StoredValue> values = new ConcurrentHashMap<>();
    private final Map<Integer, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicReference<RuntimeException> asyncFailure = new AtomicReference<>();
    private final CountDownLatch started = new CountDownLatch(1);

    private volatile boolean running = true;
    private volatile int localDeviceInstance = 1001;
    private Thread serverThread;

    public FeatureBacnetTestServer() throws Exception {
        this.socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        this.socket.setSoTimeout(250);
        this.serverThread = new Thread(this::runLoop, "feature-bacnet-test-server");
        this.serverThread.setDaemon(true);
        this.serverThread.start();
        awaitStart();
    }

    public int port() {
        return socket.getLocalPort();
    }

    public void putReal(BacnetObjectType objectType, int instance, BacnetPropertyIdentifier propertyIdentifier, float value) {
        values.put(key(objectType, instance, propertyIdentifier, null), new StoredValue(value, "REAL", null));
    }

    public void putString(BacnetObjectType objectType, int instance, BacnetPropertyIdentifier propertyIdentifier, String value) {
        values.put(key(objectType, instance, propertyIdentifier, null), new StoredValue(value, "CHARACTER_STRING", null));
    }

    public void publishReal(BacnetObjectType objectType, int instance, BacnetPropertyIdentifier propertyIdentifier, float value) throws Exception {
        StoredValue storedValue = new StoredValue(value, "REAL", null);
        values.put(key(objectType, instance, propertyIdentifier, null), storedValue);
        notifySubscribers(objectType, instance, propertyIdentifier, null, storedValue);
    }

    private void awaitStart() throws Exception {
        if (!started.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Feature BACnet test server failed to start");
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
                byte[] response = handleRequest(payload, request.getSocketAddress());
                if (response != null) {
                    DatagramPacket packet = new DatagramPacket(response, response.length, request.getSocketAddress());
                    socket.send(packet);
                }
            } catch (SocketTimeoutException ignored) {
                // Poll loop.
            } catch (Exception ex) {
                if (running) {
                    asyncFailure.compareAndSet(null, new RuntimeException("Feature BACnet test server failed", ex));
                }
            }
        }
    }

    private byte[] handleRequest(byte[] frame, SocketAddress requester) {
        verifyNoAsyncFailure();
        RequestEnvelope request = parseRequest(frame);
        return switch (request.serviceChoice) {
            case BacnetReadPropertyCodec.SERVICE_CHOICE_READ_PROPERTY -> handleReadProperty(request.readRequest);
            case SERVICE_CHOICE_WRITE_PROPERTY -> handleWriteProperty(request.writeRequest);
            case SERVICE_CHOICE_SUBSCRIBE_COV_PROPERTY -> handleSubscribeCovProperty(request.subscribeRequest, requester);
            default -> buildReject(request.invokeId, 9);
        };
    }

    private RequestEnvelope parseRequest(byte[] frame) {
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        BacnetReadPropertyResponseDecoder.BacnetFrameHeader header = BacnetReadPropertyResponseDecoder.readFrameHeader(buffer);
        if (header.pduType() != BacnetReadPropertyCodec.APDU_TYPE_CONFIRMED_REQUEST) {
            throw new IllegalArgumentException("Unsupported APDU type: " + header.pduType());
        }
        ByteBuffer payload = header.payload();
        payload.get();
        int invokeId = Byte.toUnsignedInt(payload.get());
        int serviceChoice = Byte.toUnsignedInt(payload.get());
        RequestEnvelope envelope = new RequestEnvelope(invokeId, serviceChoice);
        if (serviceChoice == BacnetReadPropertyCodec.SERVICE_CHOICE_READ_PROPERTY) {
            envelope.readRequest = parseReadRequest(payload, invokeId);
        } else if (serviceChoice == SERVICE_CHOICE_WRITE_PROPERTY) {
            envelope.writeRequest = parseWriteRequest(payload, invokeId);
        } else if (serviceChoice == SERVICE_CHOICE_SUBSCRIBE_COV_PROPERTY) {
            envelope.subscribeRequest = parseSubscribeRequest(payload, invokeId);
        }
        return envelope;
    }

    private ReadRequest parseReadRequest(ByteBuffer buffer, int invokeId) {
        BacnetTagReader.TagHeader objectTag = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(objectTag, 0);
        int rawObjectIdentifier = buffer.getInt();
        BacnetObjectType objectType = BacnetObjectType.fromId((rawObjectIdentifier >>> 22) & 0x03FF);
        int instance = rawObjectIdentifier & 0x3FFFFF;

        BacnetTagReader.TagHeader propertyTag = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(propertyTag, 1);
        BacnetPropertyIdentifier propertyIdentifier = BacnetPropertyIdentifier.fromId(
                BacnetReadPropertyResponseDecoder.readUnsigned(buffer, propertyTag.length()));

        Integer arrayIndex = null;
        if (buffer.hasRemaining()) {
            int nextByte = Byte.toUnsignedInt(buffer.get(buffer.position()));
            if ((nextByte & 0x08) != 0 && ((nextByte >> 4) & 0x0F) == 2 && (nextByte & 0x07) < 5) {
                BacnetTagReader.TagHeader indexTag = BacnetTagReader.readTag(buffer);
                arrayIndex = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, indexTag.length());
            }
        }
        return new ReadRequest(invokeId, objectType, instance, propertyIdentifier, arrayIndex);
    }

    private WriteRequest parseWriteRequest(ByteBuffer buffer, int invokeId) {
        ReadRequest readRequest = parseReadRequest(buffer, invokeId);
        BacnetTagReader.TagHeader valueOpen = BacnetTagReader.readTag(buffer);
        if (!valueOpen.contextSpecific() || !valueOpen.openingTag() || valueOpen.tagNumber() != 3) {
            throw new IllegalArgumentException("WriteProperty value opening tag is missing");
        }
        BacnetReadPropertyResponseDecoder.PrimitiveValue primitiveValue =
                BacnetReadPropertyResponseDecoder.readAnyPrimitiveValue(buffer);
        BacnetTagReader.TagHeader valueClose = BacnetTagReader.readTag(buffer);
        if (!valueClose.contextSpecific() || !valueClose.closingTag() || valueClose.tagNumber() != 3) {
            throw new IllegalArgumentException("WriteProperty value closing tag is missing");
        }
        Integer priority = null;
        if (buffer.hasRemaining()) {
            BacnetTagReader.TagHeader priorityTag = BacnetTagReader.readTag(buffer);
            BacnetReadPropertyResponseDecoder.requireContextTag(priorityTag, 4);
            priority = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, priorityTag.length());
        }
        return new WriteRequest(readRequest, primitiveValue.value(), primitiveValue.type(), priority);
    }
    private SubscribeRequest parseSubscribeRequest(ByteBuffer buffer, int invokeId) {
        BacnetTagReader.TagHeader processTag = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(processTag, 0);
        int processIdentifier = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, processTag.length());

        BacnetTagReader.TagHeader objectTag = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(objectTag, 1);
        int rawObjectIdentifier = buffer.getInt();
        BacnetObjectType objectType = BacnetObjectType.fromId((rawObjectIdentifier >>> 22) & 0x03FF);
        int instance = rawObjectIdentifier & 0x3FFFFF;

        BacnetTagReader.TagHeader issueConfirmedTag = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(issueConfirmedTag, 2);
        boolean issueConfirmedNotifications = issueConfirmedTag.length() == 1;

        Integer lifetime = null;
        BacnetTagReader.TagHeader nextTag = BacnetTagReader.readTag(buffer);
        if (nextTag.contextSpecific() && !nextTag.openingTag() && !nextTag.closingTag() && nextTag.tagNumber() == 3) {
            lifetime = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, nextTag.length());
            nextTag = BacnetTagReader.readTag(buffer);
        }

        if (!nextTag.contextSpecific() || !nextTag.openingTag() || nextTag.tagNumber() != 4) {
            throw new IllegalArgumentException("SubscribeCOVProperty property reference opening tag is missing");
        }
        BacnetTagReader.TagHeader propertyTag = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(propertyTag, 0);
        BacnetPropertyIdentifier propertyIdentifier = BacnetPropertyIdentifier.fromId(
                BacnetReadPropertyResponseDecoder.readUnsigned(buffer, propertyTag.length()));
        Integer arrayIndex = null;
        BacnetTagReader.TagHeader propertyTail = BacnetTagReader.readTag(buffer);
        if (propertyTail.contextSpecific() && !propertyTail.openingTag() && !propertyTail.closingTag() && propertyTail.tagNumber() == 1) {
            arrayIndex = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, propertyTail.length());
            propertyTail = BacnetTagReader.readTag(buffer);
        }
        if (!propertyTail.contextSpecific() || !propertyTail.closingTag() || propertyTail.tagNumber() != 4) {
            throw new IllegalArgumentException("SubscribeCOVProperty property reference closing tag is missing");
        }

        if (buffer.hasRemaining()) {
            BacnetTagReader.TagHeader covIncrementTag = BacnetTagReader.readTag(buffer);
            if (covIncrementTag.contextSpecific() && !covIncrementTag.openingTag() && !covIncrementTag.closingTag()
                    && covIncrementTag.tagNumber() == 5) {
                buffer.position(buffer.position() + covIncrementTag.length());
            }
        }

        return new SubscribeRequest(invokeId,
                processIdentifier,
                objectType,
                instance,
                propertyIdentifier,
                arrayIndex,
                issueConfirmedNotifications,
                lifetime);
    }

    private byte[] handleReadProperty(ReadRequest request) {
        StoredValue storedValue = values.get(key(request.objectType, request.instance, request.propertyIdentifier, request.arrayIndex));
        if (storedValue == null) {
            return buildReject(request.invokeId, 5);
        }
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_COMPLEX_ACK << 4);
        apdu.write(request.invokeId & 0xFF);
        apdu.write(BacnetReadPropertyCodec.SERVICE_CHOICE_READ_PROPERTY);
        writeContextObjectIdentifier(apdu, 0, request.objectType, request.instance);
        writeContextEnumerated(apdu, 1, request.propertyIdentifier.getId());
        if (request.arrayIndex != null) {
            writeContextUnsigned(apdu, 2, request.arrayIndex);
        }
        BacnetTagSupport.writeContextOpeningTag(apdu, 3);
        BacnetValueEncodingSupport.writeApplicationValue(apdu, storedValue.value, storedValue.valueType);
        BacnetTagSupport.writeContextClosingTag(apdu, 3);
        return wrapApdu(apdu.toByteArray());
    }

    private byte[] handleWriteProperty(WriteRequest request) {
        values.put(key(request.readRequest.objectType,
                request.readRequest.instance,
                request.readRequest.propertyIdentifier,
                request.readRequest.arrayIndex),
                new StoredValue(request.value, request.valueType, request.priority));
        return buildSimpleAck(request.readRequest.invokeId, SERVICE_CHOICE_WRITE_PROPERTY);
    }

    private byte[] handleSubscribeCovProperty(SubscribeRequest request, SocketAddress requester) {
        if (request.lifetime != null && request.lifetime == 0) {
            subscriptions.remove(request.processIdentifier);
        } else {
            subscriptions.put(request.processIdentifier, new Subscription((InetSocketAddress) requester,
                    request.processIdentifier,
                    request.objectType,
                    request.instance,
                    request.propertyIdentifier,
                    request.arrayIndex,
                    request.issueConfirmedNotifications,
                    request.lifetime));
        }
        return buildSimpleAck(request.invokeId, SERVICE_CHOICE_SUBSCRIBE_COV_PROPERTY);
    }

    private void notifySubscribers(BacnetObjectType objectType,
                                   int instance,
                                   BacnetPropertyIdentifier propertyIdentifier,
                                   Integer arrayIndex,
                                   StoredValue storedValue) throws Exception {
        for (Subscription subscription : subscriptions.values()) {
            if (subscription.objectType != objectType
                    || subscription.instance != instance
                    || subscription.propertyIdentifier != propertyIdentifier
                    || !Objects.equals(subscription.arrayIndex, arrayIndex)) {
                continue;
            }
            byte[] notification = buildCovNotification(subscription, storedValue);
            DatagramPacket packet = new DatagramPacket(notification, notification.length, subscription.endpoint);
            socket.send(packet);
        }
    }

    private byte[] buildCovNotification(Subscription subscription, StoredValue storedValue) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_UNCONFIRMED_REQUEST << 4);
        apdu.write(SERVICE_CHOICE_UNCONFIRMED_COV_NOTIFICATION);
        writeContextUnsigned(apdu, 0, subscription.processIdentifier);
        writeContextObjectIdentifier(apdu, 1, BacnetObjectType.DEVICE, localDeviceInstance);
        writeContextObjectIdentifier(apdu, 2, subscription.objectType, subscription.instance);
        writeContextUnsigned(apdu, 3, subscription.lifetime != null ? subscription.lifetime : 60);
        BacnetTagSupport.writeContextOpeningTag(apdu, 4);
        writeContextEnumerated(apdu, 0, subscription.propertyIdentifier.getId());
        if (subscription.arrayIndex != null) {
            writeContextUnsigned(apdu, 1, subscription.arrayIndex);
        }
        BacnetTagSupport.writeContextOpeningTag(apdu, 2);
        BacnetValueEncodingSupport.writeApplicationValue(apdu, storedValue.value, storedValue.valueType);
        BacnetTagSupport.writeContextClosingTag(apdu, 2);
        BacnetTagSupport.writeContextClosingTag(apdu, 4);
        return wrapApdu(apdu.toByteArray());
    }

    private byte[] buildSimpleAck(int invokeId, int serviceChoice) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_SIMPLE_ACK << 4);
        apdu.write(invokeId & 0xFF);
        apdu.write(serviceChoice & 0xFF);
        return wrapApdu(apdu.toByteArray());
    }

    private byte[] buildReject(int invokeId, int reason) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_REJECT << 4);
        apdu.write(invokeId & 0xFF);
        apdu.write(reason & 0xFF);
        return wrapApdu(apdu.toByteArray());
    }
    private byte[] wrapApdu(byte[] apdu) {
        ByteArrayOutputStream npdu = new ByteArrayOutputStream();
        npdu.write(BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION);
        npdu.write(0x00);
        npdu.writeBytes(apdu);
        byte[] npduBytes = npdu.toByteArray();

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(BacnetReadPropertyCodec.BVLC_TYPE_IP);
        frame.write(BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU);
        int length = npduBytes.length + 4;
        frame.write((length >> 8) & 0xFF);
        frame.write(length & 0xFF);
        frame.writeBytes(npduBytes);
        return frame.toByteArray();
    }

    private void writeContextUnsigned(ByteArrayOutputStream out, int contextId, int value) {
        int length = BacnetTagSupport.unsignedLength(value);
        BacnetTagSupport.writeTag(out, contextId, true, length);
        BacnetTagSupport.writeUnsigned(out, value, length);
    }

    private void writeContextEnumerated(ByteArrayOutputStream out, int contextId, int value) {
        writeContextUnsigned(out, contextId, value);
    }

    private void writeContextObjectIdentifier(ByteArrayOutputStream out,
                                              int contextId,
                                              BacnetObjectType objectType,
                                              int instance) {
        BacnetTagSupport.writeTag(out, contextId, true, 4);
        int raw = ((objectType.getId() & 0x03FF) << 22) | (instance & 0x3FFFFF);
        BacnetTagSupport.writeUnsigned(out, raw, 4);
    }

    private String key(BacnetObjectType objectType,
                       int instance,
                       BacnetPropertyIdentifier propertyIdentifier,
                       Integer arrayIndex) {
        return objectType.getId() + ":" + instance + "." + propertyIdentifier.getId()
                + (arrayIndex != null ? "[" + arrayIndex + "]" : "");
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

    private record StoredValue(Object value, String valueType, Integer priority) {
    }

    private record Subscription(InetSocketAddress endpoint,
                                int processIdentifier,
                                BacnetObjectType objectType,
                                int instance,
                                BacnetPropertyIdentifier propertyIdentifier,
                                Integer arrayIndex,
                                boolean issueConfirmedNotifications,
                                Integer lifetime) {
    }

    private static final class RequestEnvelope {
        private final int invokeId;
        private final int serviceChoice;
        private ReadRequest readRequest;
        private WriteRequest writeRequest;
        private SubscribeRequest subscribeRequest;

        private RequestEnvelope(int invokeId, int serviceChoice) {
            this.invokeId = invokeId;
            this.serviceChoice = serviceChoice;
        }
    }

    private record ReadRequest(int invokeId,
                               BacnetObjectType objectType,
                               int instance,
                               BacnetPropertyIdentifier propertyIdentifier,
                               Integer arrayIndex) {
    }

    private record WriteRequest(ReadRequest readRequest, Object value, String valueType, Integer priority) {
    }

    private record SubscribeRequest(int invokeId,
                                    int processIdentifier,
                                    BacnetObjectType objectType,
                                    int instance,
                                    BacnetPropertyIdentifier propertyIdentifier,
                                    Integer arrayIndex,
                                    boolean issueConfirmedNotifications,
                                    Integer lifetime) {
    }
}

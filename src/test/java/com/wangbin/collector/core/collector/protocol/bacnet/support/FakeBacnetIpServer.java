package com.wangbin.collector.core.collector.protocol.bacnet.support;

import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class FakeBacnetIpServer implements AutoCloseable {

    private static final int APDU_TYPE_UNCONFIRMED_REQUEST = 0x01;
    private static final int SERVICE_CHOICE_WHO_IS = 0x08;
    private static final int SERVICE_CHOICE_READ_PROPERTY_MULTIPLE = 0x0E;

    private final DatagramSocket socket;
    private final Map<String, PropertyValue> values = new ConcurrentHashMap<>();
    private final AtomicReference<RuntimeException> asyncFailure = new AtomicReference<>();
    private final CountDownLatch started = new CountDownLatch(1);

    private volatile boolean running = true;
    private volatile long responseDelayMs;
    private volatile Integer rejectReason;
    private volatile Integer rpmRejectReason;
    private volatile int localDeviceInstance = 1001;
    private volatile int maxApduLengthAccepted = 480;
    private volatile int vendorId = 999;
    private Thread serverThread;

    public FakeBacnetIpServer() throws Exception {
        this.socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        this.socket.setSoTimeout(250);
        this.serverThread = new Thread(this::runLoop, "fake-bacnet-ip-server");
        this.serverThread.setDaemon(true);
        this.serverThread.start();
        awaitStart();
    }

    public int port() {
        return socket.getLocalPort();
    }

    public void putReal(BacnetObjectType objectType, int instance, BacnetPropertyIdentifier propertyIdentifier, float value) {
        values.put(key(objectType, instance, propertyIdentifier, null), new PropertyValue(ValueType.REAL, value, null));
    }

    public void putBoolean(BacnetObjectType objectType, int instance, BacnetPropertyIdentifier propertyIdentifier, boolean value) {
        values.put(key(objectType, instance, propertyIdentifier, null), new PropertyValue(ValueType.BOOLEAN, value, null));
    }

    public void putString(BacnetObjectType objectType, int instance, BacnetPropertyIdentifier propertyIdentifier, String value) {
        values.put(key(objectType, instance, propertyIdentifier, null), new PropertyValue(ValueType.STRING, value, null));
    }

    public void putEnumerated(BacnetObjectType objectType, int instance, BacnetPropertyIdentifier propertyIdentifier, int value) {
        values.put(key(objectType, instance, propertyIdentifier, null), new PropertyValue(ValueType.ENUMERATED, value, null));
    }

    public void setResponseDelayMs(long responseDelayMs) {
        this.responseDelayMs = responseDelayMs;
    }

    public void forceRejectReason(Integer rejectReason) {
        this.rejectReason = rejectReason;
    }

    public void forceReadPropertyMultipleRejectReason(Integer rejectReason) {
        this.rpmRejectReason = rejectReason;
    }

    public void setDeviceIdentity(int localDeviceInstance, int maxApduLengthAccepted, int vendorId) {
        this.localDeviceInstance = localDeviceInstance;
        this.maxApduLengthAccepted = maxApduLengthAccepted;
        this.vendorId = vendorId;
    }

    private void awaitStart() throws Exception {
        if (!started.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Fake BACnet/IP server failed to start");
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
                byte[] response = handleRequest(payload);
                if (responseDelayMs > 0) {
                    Thread.sleep(responseDelayMs);
                }
                DatagramPacket packet = new DatagramPacket(response, response.length, request.getSocketAddress());
                socket.send(packet);
            } catch (SocketTimeoutException ignored) {
                // poll loop
            } catch (Exception ex) {
                if (running) {
                    asyncFailure.compareAndSet(null, new RuntimeException("Fake BACnet/IP server failed", ex));
                }
            }
        }
    }

    private byte[] handleRequest(byte[] frame) {
        verifyNoAsyncFailure();
        if (isWhoIs(frame)) {
            return buildIAm();
        }
        RequestEnvelope envelope = parseRequest(frame);
        if (envelope.readPropertyMultiple) {
            if (rpmRejectReason != null) {
                return buildReject(envelope.invokeId, rpmRejectReason);
            }
            return buildReadPropertyMultipleAck(envelope);
        }
        RequestModel request = envelope.singleRequest;
        if (rejectReason != null) {
            return buildReject(request.invokeId, rejectReason);
        }
        PropertyValue propertyValue = values.get(key(request.objectType, request.instanceNumber, request.propertyIdentifier, request.arrayIndex));
        if (propertyValue == null) {
            return buildReject(request.invokeId, 5);
        }
        return buildReadPropertyAck(request, propertyValue);
    }

    private RequestEnvelope parseRequest(byte[] frame) {
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int bvlcType = Byte.toUnsignedInt(buffer.get());
        if (bvlcType != BacnetReadPropertyCodec.BVLC_TYPE_IP) {
            throw new IllegalArgumentException("Unexpected BVLC type: " + bvlcType);
        }
        int function = Byte.toUnsignedInt(buffer.get());
        if (function != BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU) {
            throw new IllegalArgumentException("Unexpected BVLC function: " + function);
        }
        int declaredLength = Short.toUnsignedInt(buffer.getShort());
        if (declaredLength != frame.length) {
            throw new IllegalArgumentException("Frame length mismatch");
        }
        int version = Byte.toUnsignedInt(buffer.get());
        if (version != BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported BACnet version");
        }
        int npduControl = Byte.toUnsignedInt(buffer.get());
        if (npduControl != 0x04) {
            throw new IllegalArgumentException("Unsupported NPDU control: 0x" + Integer.toHexString(npduControl));
        }
        int apduHeader = Byte.toUnsignedInt(buffer.get());
        int pduType = (apduHeader >> 4) & 0x0F;
        if (pduType != BacnetReadPropertyCodec.APDU_TYPE_CONFIRMED_REQUEST) {
            throw new IllegalArgumentException("Unsupported APDU type: " + pduType);
        }
        buffer.get();
        int invokeId = Byte.toUnsignedInt(buffer.get());
        int serviceChoice = Byte.toUnsignedInt(buffer.get());
        RequestEnvelope envelope = new RequestEnvelope();
        envelope.invokeId = invokeId;
        if (serviceChoice == BacnetReadPropertyCodec.SERVICE_CHOICE_READ_PROPERTY) {
            RequestModel model = parseReadPropertyRequest(buffer);
            model.invokeId = invokeId;
            envelope.singleRequest = model;
            return envelope;
        }
        if (serviceChoice == SERVICE_CHOICE_READ_PROPERTY_MULTIPLE) {
            envelope.readPropertyMultiple = true;
            envelope.multipleRequests.putAll(parseReadPropertyMultipleRequests(buffer));
            return envelope;
        }
        throw new IllegalArgumentException("Unsupported confirmed service choice: " + serviceChoice);
    }

    private RequestModel parseReadPropertyRequest(ByteBuffer buffer) {
        ContextTag objectTag = readContextTag(buffer);
        if (objectTag.tagNumber != 0 || objectTag.length != 4) {
            throw new IllegalArgumentException("Invalid object identifier tag");
        }
        int objectIdentifier = buffer.getInt();
        BacnetObjectType objectType = BacnetObjectType.fromId((objectIdentifier >>> 22) & 0x03FF);
        int instanceNumber = objectIdentifier & 0x3FFFFF;

        ContextTag propertyTag = readContextTag(buffer);
        if (propertyTag.tagNumber != 1) {
            throw new IllegalArgumentException("Invalid property identifier tag");
        }
        int propertyId = readUnsigned(buffer, propertyTag.length);
        BacnetPropertyIdentifier propertyIdentifier = BacnetPropertyIdentifier.fromId(propertyId);

        Integer arrayIndex = null;
        if (buffer.hasRemaining()) {
            ContextTag next = readContextTag(buffer);
            if (next.tagNumber == 2) {
                arrayIndex = readUnsigned(buffer, next.length);
            } else {
                throw new IllegalArgumentException("Unexpected extra BACnet request tag: " + next.tagNumber);
            }
        }

        RequestModel model = new RequestModel();
        model.objectType = objectType;
        model.instanceNumber = instanceNumber;
        model.propertyIdentifier = propertyIdentifier;
        model.arrayIndex = arrayIndex;
        return model;
    }

    private Map<String, RequestModel> parseReadPropertyMultipleRequests(ByteBuffer buffer) {
        Map<String, RequestModel> requests = new ConcurrentHashMap<>();
        while (buffer.hasRemaining()) {
            ContextTag objectOpen = readContextTag(buffer);
            if (objectOpen.tagNumber != 0 || !objectOpen.opening) {
                throw new IllegalArgumentException("Invalid RPM object opening tag");
            }
            ApplicationTag objectIdentifierTag = readApplicationTag(buffer);
            if (objectIdentifierTag.tagNumber != 12 || objectIdentifierTag.length != 4) {
                throw new IllegalArgumentException("Invalid RPM object identifier");
            }
            int objectIdentifier = buffer.getInt();
            BacnetObjectType objectType = BacnetObjectType.fromId((objectIdentifier >>> 22) & 0x03FF);
            int instanceNumber = objectIdentifier & 0x3FFFFF;
            ContextTag objectClose = readContextTag(buffer);
            if (objectClose.tagNumber != 0 || !objectClose.closing) {
                throw new IllegalArgumentException("Invalid RPM object closing tag");
            }

            ContextTag propertyListOpen = readContextTag(buffer);
            if (propertyListOpen.tagNumber != 1 || !propertyListOpen.opening) {
                throw new IllegalArgumentException("Invalid RPM property list opening tag");
            }
            while (buffer.hasRemaining()) {
                ContextTag propertyRef = readContextTag(buffer);
                if (propertyRef.tagNumber == 1 && propertyRef.closing) {
                    break;
                }
                if (propertyRef.tagNumber != 0 || !propertyRef.opening) {
                    throw new IllegalArgumentException("Invalid RPM property reference opening tag");
                }
                ApplicationTag propertyIdTag = readApplicationTag(buffer);
                if (propertyIdTag.tagNumber != 9) {
                    throw new IllegalArgumentException("Invalid RPM property identifier tag");
                }
                int propertyId = readUnsigned(buffer, propertyIdTag.length);
                BacnetPropertyIdentifier propertyIdentifier = BacnetPropertyIdentifier.fromId(propertyId);

                Integer arrayIndex = null;
                if (buffer.hasRemaining()) {
                    int nextByte = Byte.toUnsignedInt(buffer.get(buffer.position()));
                    if (isContextPrimitiveTag(nextByte)) {
                        ContextTag possibleArrayIndex = readContextTag(buffer);
                        if (possibleArrayIndex.tagNumber == 2) {
                            arrayIndex = readUnsigned(buffer, possibleArrayIndex.length);
                        } else {
                            throw new IllegalArgumentException("Unexpected RPM context tag: " + possibleArrayIndex.tagNumber);
                        }
                    }
                }

                ContextTag propertyRefClose = readContextTag(buffer);
                if (propertyRefClose.tagNumber != 0 || !propertyRefClose.closing) {
                    throw new IllegalArgumentException("Invalid RPM property reference closing tag");
                }

                RequestModel model = new RequestModel();
                model.objectType = objectType;
                model.instanceNumber = instanceNumber;
                model.propertyIdentifier = propertyIdentifier;
                model.arrayIndex = arrayIndex;
                requests.put(key(objectType, instanceNumber, propertyIdentifier, arrayIndex), model);
            }
        }
        return requests;
    }

    private byte[] buildReadPropertyAck(RequestModel request, PropertyValue propertyValue) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_COMPLEX_ACK << 4);
        apdu.write(request.invokeId & 0xFF);
        apdu.write(BacnetReadPropertyCodec.SERVICE_CHOICE_READ_PROPERTY);

        writeContextPrimitive(apdu, 0, encodeObjectIdentifier(request.objectType, request.instanceNumber));
        writeContextPrimitive(apdu, 1, encodeEnumerated(request.propertyIdentifier.getId()));
        if (request.arrayIndex != null) {
            writeContextPrimitive(apdu, 2, encodeUnsigned(request.arrayIndex));
        }

        apdu.write(0x3E);
        apdu.writeBytes(encodeAnyValue(propertyValue));
        apdu.write(0x3F);
        return wrap(apdu.toByteArray());
    }

    private byte[] buildReject(int invokeId, int reason) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_REJECT << 4);
        apdu.write(invokeId & 0xFF);
        apdu.write(reason & 0xFF);
        return wrap(apdu.toByteArray());
    }

    private byte[] buildReadPropertyMultipleAck(RequestEnvelope envelope) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_COMPLEX_ACK << 4);
        apdu.write(envelope.invokeId & 0xFF);
        apdu.write(SERVICE_CHOICE_READ_PROPERTY_MULTIPLE);

        Map<String, java.util.List<RequestModel>> grouped = new ConcurrentHashMap<>();
        for (RequestModel request : envelope.multipleRequests.values()) {
            grouped.computeIfAbsent(request.objectType.getId() + ":" + request.instanceNumber,
                    ignored -> new java.util.ArrayList<>()).add(request);
        }
        for (java.util.List<RequestModel> group : grouped.values()) {
            RequestModel first = group.get(0);
            writeContextOpening(apdu, 0);
            apdu.writeBytes(encodeApplicationObjectIdentifier(first.objectType, first.instanceNumber));
            writeContextClosing(apdu, 0);

            writeContextOpening(apdu, 1);
            for (RequestModel request : group) {
                writeContextOpening(apdu, 2);
                writeContextPrimitive(apdu, 2, encodeEnumerated(request.propertyIdentifier.getId()));
                if (request.arrayIndex != null) {
                    writeContextPrimitive(apdu, 3, encodeUnsigned(request.arrayIndex));
                }
                PropertyValue propertyValue = values.get(key(request.objectType, request.instanceNumber,
                        request.propertyIdentifier, request.arrayIndex));
                if (propertyValue == null) {
                    writeContextOpening(apdu, 5);
                    writeContextPrimitive(apdu, 0, encodeEnumerated(2));
                    writeContextPrimitive(apdu, 1, encodeEnumerated(32));
                    writeContextClosing(apdu, 5);
                } else {
                    writeContextOpening(apdu, 4);
                    apdu.writeBytes(encodeAnyValue(propertyValue));
                    writeContextClosing(apdu, 4);
                }
                writeContextClosing(apdu, 2);
            }
            writeContextClosing(apdu, 1);
        }
        return wrap(apdu.toByteArray());
    }

    private byte[] buildIAm() {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(APDU_TYPE_UNCONFIRMED_REQUEST << 4);
        apdu.write(0x00);
        apdu.writeBytes(encodeApplicationObjectIdentifier(BacnetObjectType.DEVICE, localDeviceInstance));
        apdu.writeBytes(encodeUnsignedApplication(maxApduLengthAccepted, 2));
        apdu.writeBytes(encodeEnumeratedApplication(3));
        apdu.writeBytes(encodeUnsignedApplication(vendorId, 2));
        return wrap(apdu.toByteArray());
    }

    private byte[] wrap(byte[] npdu) {
        ByteArrayOutputStream npduWrapped = new ByteArrayOutputStream();
        npduWrapped.write(BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION);
        npduWrapped.write(0x00);
        npduWrapped.writeBytes(npdu);
        byte[] npduBytes = npduWrapped.toByteArray();

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(BacnetReadPropertyCodec.BVLC_TYPE_IP);
        frame.write(BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU);
        int length = npduBytes.length + 4;
        frame.write((length >> 8) & 0xFF);
        frame.write(length & 0xFF);
        frame.writeBytes(npduBytes);
        return frame.toByteArray();
    }

    private void writeContextPrimitive(ByteArrayOutputStream out, int tagNumber, byte[] payload) {
        out.write(((tagNumber & 0x0F) << 4) | (0x08 | (payload.length & 0x07)));
        out.writeBytes(payload);
    }

    private void writeContextOpening(ByteArrayOutputStream out, int tagNumber) {
        out.write((tagNumber << 4) | 0x0E);
    }

    private void writeContextClosing(ByteArrayOutputStream out, int tagNumber) {
        out.write((tagNumber << 4) | 0x0F);
    }

    private byte[] encodeObjectIdentifier(BacnetObjectType objectType, int instance) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int raw = ((objectType.getId() & 0x03FF) << 22) | (instance & 0x3FFFFF);
        out.write((raw >> 24) & 0xFF);
        out.write((raw >> 16) & 0xFF);
        out.write((raw >> 8) & 0xFF);
        out.write(raw & 0xFF);
        return out.toByteArray();
    }

    private byte[] encodeEnumerated(int value) {
        return encodeUnsigned(value);
    }

    private byte[] encodeUnsignedApplication(int value, int applicationTypeId) {
        byte[] raw = encodeUnsigned(value);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((applicationTypeId << 4) | (raw.length & 0x07));
        out.writeBytes(raw);
        return out.toByteArray();
    }

    private byte[] encodeEnumeratedApplication(int value) {
        byte[] raw = encodeUnsigned(value);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((9 << 4) | (raw.length & 0x07));
        out.writeBytes(raw);
        return out.toByteArray();
    }

    private byte[] encodeApplicationObjectIdentifier(BacnetObjectType objectType, int instance) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((12 << 4) | 4);
        out.writeBytes(encodeObjectIdentifier(objectType, instance));
        return out.toByteArray();
    }

    private byte[] encodeUnsigned(int value) {
        if (value <= 0xFF) {
            return new byte[]{(byte) value};
        }
        if (value <= 0xFFFF) {
            return new byte[]{(byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
        }
        if (value <= 0xFFFFFF) {
            return new byte[]{(byte) ((value >> 16) & 0xFF), (byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
        }
        return new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    private byte[] encodeAnyValue(PropertyValue propertyValue) {
        return switch (propertyValue.type) {
            case REAL -> encodeReal((Float) propertyValue.value);
            case BOOLEAN -> encodeBoolean((Boolean) propertyValue.value);
            case STRING -> encodeCharacterString((String) propertyValue.value);
            case ENUMERATED -> encodeEnumeratedValue((Integer) propertyValue.value);
        };
    }

    private byte[] encodeReal(float value) {
        ByteBuffer payload = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
        payload.put((byte) ((4 << 4) | 4));
        payload.putFloat(value);
        return payload.array();
    }

    private byte[] encodeBoolean(boolean value) {
        return new byte[]{(byte) ((1 << 4) | (value ? 1 : 0))};
    }

    private byte[] encodeCharacterString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int length = bytes.length + 1;
        if (length < 5) {
            out.write((7 << 4) | length);
        } else {
            out.write((7 << 4) | 0x05);
            out.write(length);
        }
        out.write(0);
        out.writeBytes(bytes);
        return out.toByteArray();
    }

    private byte[] encodeEnumeratedValue(int value) {
        byte[] raw = encodeUnsigned(value);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((9 << 4) | (raw.length & 0x07));
        out.writeBytes(raw);
        return out.toByteArray();
    }

    private ContextTag readContextTag(ByteBuffer buffer) {
        int first = Byte.toUnsignedInt(buffer.get());
        int tagNumber = (first >> 4) & 0x0F;
        int length = first & 0x07;
        boolean context = (first & 0x08) != 0;
        if (!context) {
            throw new IllegalArgumentException("Expected context tag");
        }
        return new ContextTag(tagNumber, length, length == 6, length == 7);
    }

    private ApplicationTag readApplicationTag(ByteBuffer buffer) {
        int first = Byte.toUnsignedInt(buffer.get());
        if ((first & 0x08) != 0) {
            throw new IllegalArgumentException("Expected application tag");
        }
        int tagNumber = (first >> 4) & 0x0F;
        int length = first & 0x07;
        if (length == 5) {
            length = Byte.toUnsignedInt(buffer.get());
        }
        return new ApplicationTag(tagNumber, length);
    }

    private boolean isContextPrimitiveTag(int firstByte) {
        return (firstByte & 0x08) != 0 && (firstByte & 0x07) < 5;
    }

    private int readUnsigned(ByteBuffer buffer, int length) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | Byte.toUnsignedInt(buffer.get());
        }
        return value;
    }

    private String key(BacnetObjectType objectType, int instance, BacnetPropertyIdentifier propertyIdentifier, Integer arrayIndex) {
        return objectType.getId() + ":" + instance + "." + propertyIdentifier.getId()
                + (arrayIndex != null ? "[" + arrayIndex + "]" : "");
    }

    private boolean isWhoIs(byte[] frame) {
        if (frame == null || frame.length < 8) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int bvlcType = Byte.toUnsignedInt(buffer.get());
        int function = Byte.toUnsignedInt(buffer.get());
        if (bvlcType != BacnetReadPropertyCodec.BVLC_TYPE_IP
                || function != BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU) {
            return false;
        }
        buffer.getShort();
        if (Byte.toUnsignedInt(buffer.get()) != BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION) {
            return false;
        }
        buffer.get();
        int apduHeader = Byte.toUnsignedInt(buffer.get());
        int pduType = (apduHeader >> 4) & 0x0F;
        if (pduType != APDU_TYPE_UNCONFIRMED_REQUEST) {
            return false;
        }
        int serviceChoice = Byte.toUnsignedInt(buffer.get());
        return serviceChoice == SERVICE_CHOICE_WHO_IS;
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

    private enum ValueType {
        REAL,
        BOOLEAN,
        STRING,
        ENUMERATED
    }

    private record PropertyValue(ValueType type, Object value, Integer arrayIndex) {
    }

    private record ContextTag(int tagNumber, int length, boolean opening, boolean closing) {
    }

    private record ApplicationTag(int tagNumber, int length) {
    }

    private static final class RequestModel {
        private int invokeId;
        private BacnetObjectType objectType;
        private int instanceNumber;
        private BacnetPropertyIdentifier propertyIdentifier;
        private Integer arrayIndex;
    }

    private static final class RequestEnvelope {
        private int invokeId;
        private boolean readPropertyMultiple;
        private RequestModel singleRequest;
        private final Map<String, RequestModel> multipleRequests = new ConcurrentHashMap<>();
    }
}

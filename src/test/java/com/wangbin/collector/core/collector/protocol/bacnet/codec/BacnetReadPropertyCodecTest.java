package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacnetReadPropertyCodecTest {

    @Test
    void shouldEncodeConfirmedReadPropertyRequest() {
        BacnetReadPropertyRequest request = BacnetReadPropertyRequest.builder()
                .objectType(BacnetObjectType.ANALOG_INPUT)
                .objectInstance(1)
                .propertyIdentifier(BacnetPropertyIdentifier.PRESENT_VALUE)
                .invokeId(7)
                .remoteDeviceInstance(1001)
                .build();

        byte[] frame = BacnetReadPropertyCodec.encode(request);

        assertEquals(0x81, Byte.toUnsignedInt(frame[0]));
        assertEquals(0x0A, Byte.toUnsignedInt(frame[1]));
        assertEquals(frame.length, Short.toUnsignedInt(ByteBuffer.wrap(frame, 2, 2).order(ByteOrder.BIG_ENDIAN).getShort()));
        assertEquals(0x01, Byte.toUnsignedInt(frame[4]));
        assertEquals(0x04, Byte.toUnsignedInt(frame[5]));
        assertEquals(0x02, frame[6] & 0x0F);
        assertEquals(7, Byte.toUnsignedInt(frame[8]));
        assertEquals(0x0C, Byte.toUnsignedInt(frame[9]));
    }

    @Test
    void shouldDecodeCharacterStringComplexAck() {
        byte[] frame = characterStringAck("AHU-01");

        BacnetReadPropertyResponse response = BacnetReadPropertyResponseDecoder.decode(frame, 3);

        assertEquals(BacnetObjectType.DEVICE, response.getObjectType());
        assertEquals(1001, response.getObjectInstance());
        assertEquals(BacnetPropertyIdentifier.OBJECT_NAME, response.getPropertyIdentifier());
        assertEquals("AHU-01", response.getValue());
        assertEquals("CHARACTER_STRING", response.getValueType());
    }

    @Test
    void shouldDecodePrivateObjectAndPropertyIdentifiers() {
        byte[] frame = ackFrame(BacnetObjectType.fromId(128),
                42,
                BacnetPropertyIdentifier.fromId(512),
                17,
                characterStringAny("PRIVATE-VALUE"));

        BacnetReadPropertyResponse response = BacnetReadPropertyResponseDecoder.decode(frame, 17);

        assertEquals(128, response.getObjectType().getId());
        assertEquals("objectType#128", response.getObjectType().getName());
        assertEquals(512, response.getPropertyIdentifier().getId());
        assertEquals("property#512", response.getPropertyIdentifier().getName());
        assertEquals("PRIVATE-VALUE", response.getValue());
    }

    @Test
    void shouldDecodeBooleanComplexAck() {
        byte[] frame = booleanAck(true);

        BacnetReadPropertyResponse response = BacnetReadPropertyResponseDecoder.decode(frame, 9);

        assertEquals(BacnetObjectType.BINARY_INPUT, response.getObjectType());
        assertEquals(BacnetPropertyIdentifier.PRESENT_VALUE, response.getPropertyIdentifier());
        assertEquals(true, response.getValue());
        assertEquals("BOOLEAN", response.getValueType());
    }

    @Test
    void shouldDecodeRejectReason() {
        byte[] frame = rejectFrame(5, 9);

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> BacnetReadPropertyResponseDecoder.decode(frame, 5));

        assertTrue(error.getMessage().contains("unrecognized-service"));
    }

    @Test
    void shouldEncodeReadPropertyMultipleRequest() {
        BacnetReadPropertyMultipleRequest request = BacnetReadPropertyMultipleRequest.builder()
                .invokeId(12)
                .remoteDeviceInstance(1001)
                .accessSpecification(BacnetReadPropertyMultipleRequest.ReadAccessSpec.builder()
                        .objectType(BacnetObjectType.ANALOG_INPUT)
                        .objectInstance(1)
                        .propertyReference(BacnetReadPropertyMultipleRequest.PropertyReferenceSpec.builder()
                                .propertyIdentifier(BacnetPropertyIdentifier.PRESENT_VALUE)
                                .build())
                        .propertyReference(BacnetReadPropertyMultipleRequest.PropertyReferenceSpec.builder()
                                .propertyIdentifier(BacnetPropertyIdentifier.OBJECT_NAME)
                                .build())
                        .build())
                .build();

        byte[] frame = BacnetReadPropertyMultipleCodec.encode(request);

        assertEquals(0x81, Byte.toUnsignedInt(frame[0]));
        assertEquals(0x0A, Byte.toUnsignedInt(frame[1]));
        assertEquals(0x0E, Byte.toUnsignedInt(frame[9]));
    }

    @Test
    void shouldDecodeReadPropertyMultipleAck() {
        byte[] frame = readPropertyMultipleAck();

        BacnetReadPropertyMultipleResponse response =
                BacnetReadPropertyMultipleResponseDecoder.decode(frame, 12);

        assertEquals(1, response.getResults().size());
        assertEquals(BacnetObjectType.ANALOG_INPUT, response.getResults().get(0).getObjectType());
        assertEquals(2, response.getResults().get(0).getPropertyResults().size());
        assertEquals(12.5f, ((Number) response.getResults().get(0).getPropertyResults().get(0).getValue()).floatValue(), 1.0E-6f);
        assertEquals("AI-1", response.getResults().get(0).getPropertyResults().get(1).getValue());
    }

    private byte[] characterStringAck(String value) {
        return ackFrame(BacnetObjectType.DEVICE, 1001, BacnetPropertyIdentifier.OBJECT_NAME, 3, characterStringAny(value));
    }

    private byte[] characterStringAny(String value) {
        byte[] stringPayload = value.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream any = new ByteArrayOutputStream();
        int length = stringPayload.length + 1;
        any.write((7 << 4) | 0x05);
        any.write(length);
        any.write(0);
        any.writeBytes(stringPayload);
        return any.toByteArray();
    }

    private byte[] booleanAck(boolean value) {
        byte[] any = new byte[]{(byte) ((1 << 4) | (value ? 1 : 0))};
        return ackFrame(BacnetObjectType.BINARY_INPUT, 2, BacnetPropertyIdentifier.PRESENT_VALUE, 9, any);
    }

    private byte[] rejectFrame(int invokeId, int reason) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_REJECT << 4);
        apdu.write(invokeId);
        apdu.write(reason);
        return wrap(apdu.toByteArray());
    }

    private byte[] readPropertyMultipleAck() {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_COMPLEX_ACK << 4);
        apdu.write(12);
        apdu.write(BacnetReadPropertyMultipleCodec.SERVICE_CHOICE_READ_PROPERTY_MULTIPLE);

        apdu.write(0x0E);
        apdu.write((12 << 4) | 4);
        int objectIdentifier = ((BacnetObjectType.ANALOG_INPUT.getId() & 0x03FF) << 22) | 1;
        apdu.write((objectIdentifier >> 24) & 0xFF);
        apdu.write((objectIdentifier >> 16) & 0xFF);
        apdu.write((objectIdentifier >> 8) & 0xFF);
        apdu.write(objectIdentifier & 0xFF);
        apdu.write(0x0F);

        apdu.write(0x1E);

        apdu.write(0x2E);
        apdu.write(0x29);
        apdu.write(BacnetPropertyIdentifier.PRESENT_VALUE.getId());
        apdu.write(0x4E);
        apdu.write((4 << 4) | 4);
        apdu.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(12.5f).array());
        apdu.write(0x4F);
        apdu.write(0x2F);

        apdu.write(0x2E);
        apdu.write(0x29);
        apdu.write(BacnetPropertyIdentifier.OBJECT_NAME.getId());
        apdu.write(0x4E);
        byte[] nameBytes = "AI-1".getBytes(StandardCharsets.US_ASCII);
        apdu.write((7 << 4) | 0x05);
        apdu.write(nameBytes.length + 1);
        apdu.write(0);
        apdu.writeBytes(nameBytes);
        apdu.write(0x4F);
        apdu.write(0x2F);

        apdu.write(0x1F);
        return wrap(apdu.toByteArray());
    }

    private byte[] ackFrame(BacnetObjectType objectType,
                            int instance,
                            BacnetPropertyIdentifier propertyIdentifier,
                            int invokeId,
                            byte[] anyPayload) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_COMPLEX_ACK << 4);
        apdu.write(invokeId);
        apdu.write(BacnetReadPropertyCodec.SERVICE_CHOICE_READ_PROPERTY);
        apdu.write(0x0C);
        int objectIdentifier = ((objectType.getId() & 0x03FF) << 22) | (instance & 0x3FFFFF);
        apdu.write((objectIdentifier >> 24) & 0xFF);
        apdu.write((objectIdentifier >> 16) & 0xFF);
        apdu.write((objectIdentifier >> 8) & 0xFF);
        apdu.write(objectIdentifier & 0xFF);
        int propertyLength = unsignedLength(propertyIdentifier.getId());
        BacnetTagSupport.writeTag(apdu, 1, true, propertyLength);
        BacnetTagSupport.writeUnsigned(apdu, propertyIdentifier.getId(), propertyLength);
        apdu.write(0x3E);
        apdu.writeBytes(anyPayload);
        apdu.write(0x3F);
        return wrap(apdu.toByteArray());
    }

    private int unsignedLength(int value) {
        if (value <= 0xFF) {
            return 1;
        }
        if (value <= 0xFFFF) {
            return 2;
        }
        if (value <= 0xFFFFFF) {
            return 3;
        }
        return 4;
    }

    private byte[] wrap(byte[] apdu) {
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
}
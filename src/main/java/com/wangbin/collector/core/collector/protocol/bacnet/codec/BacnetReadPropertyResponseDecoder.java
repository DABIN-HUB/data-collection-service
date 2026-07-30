package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetValue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class BacnetReadPropertyResponseDecoder {

    private BacnetReadPropertyResponseDecoder() {
    }

    public static BacnetReadPropertyResponse decode(byte[] frame, int expectedInvokeId) {
        BacnetFrameHeader header = readFrameHeader(frame);
        ByteBuffer buffer = header.payload();
        int pduHeader = header.pduHeader();
        int pduType = header.pduType();
        return switch (pduType) {
            case BacnetReadPropertyCodec.APDU_TYPE_COMPLEX_ACK -> decodeComplexAck(buffer, pduHeader, expectedInvokeId);
            case BacnetReadPropertyCodec.APDU_TYPE_ERROR -> throw decodeError(buffer);
            case BacnetReadPropertyCodec.APDU_TYPE_REJECT -> throw decodeReject(buffer, pduHeader);
            case BacnetReadPropertyCodec.APDU_TYPE_ABORT -> throw decodeAbort(buffer, pduHeader);
            default -> throw new IllegalArgumentException("Unsupported BACnet APDU type: " + pduType);
        };
    }

    static BacnetFrameHeader readFrameHeader(byte[] frame) {
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        return readFrameHeader(buffer);
    }

    static BacnetFrameHeader readFrameHeader(ByteBuffer buffer) {
        int actualFrameLength = buffer.limit();
        int bvlcType = Byte.toUnsignedInt(buffer.get());
        if (bvlcType != BacnetReadPropertyCodec.BVLC_TYPE_IP) {
            throw new IllegalArgumentException("Unexpected BACnet BVLC type: 0x" + Integer.toHexString(bvlcType));
        }
        int function = Byte.toUnsignedInt(buffer.get());
        if (function != BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU
                && function != BacnetReadPropertyCodec.BVLC_ORIGINAL_BROADCAST_NPDU
                && function != BacnetReadPropertyCodec.BVLC_FORWARDED_NPDU) {
            throw new IllegalArgumentException("Unsupported BACnet BVLC function: 0x" + Integer.toHexString(function));
        }
        int frameLength = Short.toUnsignedInt(buffer.getShort());
        if (frameLength != actualFrameLength) {
            throw new IllegalArgumentException("BACnet frame length mismatch: declared="
                    + frameLength + ", actual=" + actualFrameLength);
        }
        if (function == BacnetReadPropertyCodec.BVLC_FORWARDED_NPDU) {
            buffer.position(buffer.position() + 6);
        }

        int npduVersion = Byte.toUnsignedInt(buffer.get());
        if (npduVersion != BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported BACnet NPDU version: " + npduVersion);
        }
        int npduControl = Byte.toUnsignedInt(buffer.get());
        skipNpduAddresses(buffer, npduControl);

        int pduHeader = Byte.toUnsignedInt(buffer.get());
        int pduType = (pduHeader >> 4) & 0x0F;
        return new BacnetFrameHeader(buffer, pduHeader, pduType);
    }

    private static BacnetReadPropertyResponse decodeComplexAck(ByteBuffer buffer, int pduHeader, int expectedInvokeId) {
        boolean segmented = (pduHeader & 0x08) != 0;
        boolean moreFollows = (pduHeader & 0x04) != 0;
        if (segmented || moreFollows) {
            throw new IllegalStateException("Segmented BACnet ComplexACK is not supported yet");
        }

        int invokeId = Byte.toUnsignedInt(buffer.get());
        if ((expectedInvokeId & 0xFF) != invokeId) {
            throw new IllegalStateException("BACnet invokeId mismatch: expected="
                    + (expectedInvokeId & 0xFF) + ", actual=" + invokeId);
        }
        int serviceChoice = Byte.toUnsignedInt(buffer.get());
        if (serviceChoice != BacnetReadPropertyCodec.SERVICE_CHOICE_READ_PROPERTY) {
            throw new IllegalStateException("Unexpected BACnet ComplexACK service choice: " + serviceChoice);
        }

        BacnetTagReader.TagHeader objectIdTag = BacnetTagReader.readTag(buffer);
        requireContextTag(objectIdTag, 0);
        if (objectIdTag.length() != 4) {
            throw new IllegalArgumentException("BACnet objectIdentifier length must be 4");
        }
        int objectIdRaw = buffer.getInt();
        BacnetObjectType objectType = BacnetObjectType.fromId((objectIdRaw >>> 22) & 0x03FF);
        int objectInstance = objectIdRaw & 0x3FFFFF;

        BacnetTagReader.TagHeader propertyTag = BacnetTagReader.readTag(buffer);
        requireContextTag(propertyTag, 1);
        int propertyId = readUnsigned(buffer, propertyTag.length());
        BacnetPropertyIdentifier propertyIdentifier = BacnetPropertyIdentifier.fromId(propertyId);

        Integer arrayIndex = null;
        BacnetTagReader.TagHeader next = BacnetTagReader.readTag(buffer);
        if (next.contextSpecific() && !next.openingTag() && !next.closingTag() && next.tagNumber() == 2) {
            arrayIndex = readUnsigned(buffer, next.length());
            next = BacnetTagReader.readTag(buffer);
        }

        if (!next.contextSpecific() || !next.openingTag() || next.tagNumber() != 3) {
            throw new IllegalArgumentException("BACnet ReadPropertyAck missing value opening tag");
        }
        BacnetValue decodedValue = BacnetValueDecoder.readAnyValue(buffer);
        decodedValue = BacnetValueDecoder.normalizeDecodedPropertyValue(propertyIdentifier, arrayIndex, decodedValue);
        BacnetTagReader.TagHeader closing = BacnetTagReader.readTag(buffer);
        if (!closing.contextSpecific() || !closing.closingTag() || closing.tagNumber() != 3) {
            throw new IllegalArgumentException("BACnet ReadPropertyAck missing value closing tag");
        }

        return BacnetReadPropertyResponse.builder()
                .objectType(objectType)
                .objectInstance(objectInstance)
                .propertyIdentifier(propertyIdentifier)
                .arrayIndex(arrayIndex)
                .value(decodedValue.getValue())
                .valueType(decodedValue.getValueType())
                .valueMetadata(decodedValue.getMetadata())
                .invokeId(invokeId)
                .build();
    }

    static PrimitiveValue readAnyPrimitiveValue(ByteBuffer buffer) {
        BacnetTagReader.TagHeader tag = BacnetTagReader.readTag(buffer);
        if (tag.contextSpecific() || tag.openingTag() || tag.closingTag()) {
            throw new IllegalArgumentException("Only primitive BACnet ANY values are supported in primitive decoder");
        }
        BacnetValue value = BacnetValueDecoder.readPrimitiveValue(buffer, tag);
        return new PrimitiveValue(value.getValue(), value.getValueType());
    }

    static IllegalStateException decodeError(ByteBuffer buffer) {
        int invokeId = Byte.toUnsignedInt(buffer.get());
        int errorChoice = Byte.toUnsignedInt(buffer.get());
        String detail = "invokeId=" + invokeId + ", errorChoice=" + errorChoice;
        if (buffer.hasRemaining()) {
            detail = detail + ", payloadLength=" + buffer.remaining();
        }
        return new IllegalStateException("BACnet Error APDU received: " + detail);
    }

    static IllegalStateException decodeReject(ByteBuffer buffer, int pduHeader) {
        int invokeId = Byte.toUnsignedInt(buffer.get());
        int reason = Byte.toUnsignedInt(buffer.get());
        return new IllegalStateException("BACnet Reject APDU received: invokeId="
                + invokeId + ", reason=" + rejectReasonName(reason) + ", pduHeader=0x"
                + Integer.toHexString(pduHeader));
    }

    static IllegalStateException decodeAbort(ByteBuffer buffer, int pduHeader) {
        boolean server = (pduHeader & 0x01) != 0;
        int invokeId = Byte.toUnsignedInt(buffer.get());
        int reason = Byte.toUnsignedInt(buffer.get());
        return new IllegalStateException("BACnet Abort APDU received: invokeId="
                + invokeId + ", server=" + server + ", reason=" + abortReasonName(reason));
    }

    private static void skipNpduAddresses(ByteBuffer buffer, int control) {
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

    static void requireContextTag(BacnetTagReader.TagHeader tag, int expectedContext) {
        if (!tag.contextSpecific() || tag.openingTag() || tag.closingTag() || tag.tagNumber() != expectedContext) {
            throw new IllegalArgumentException("Unexpected BACnet context tag: expected=" + expectedContext
                    + ", actual=" + tag.tagNumber());
        }
    }

    static int readUnsigned(ByteBuffer buffer, int length) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | Byte.toUnsignedInt(buffer.get());
        }
        return value;
    }

    private static long readUnsigned(byte[] payload) {
        long value = 0;
        for (byte item : payload) {
            value = (value << 8) | Byte.toUnsignedLong(item);
        }
        return value;
    }

    private static long readSigned(byte[] payload) {
        long value = 0;
        for (byte item : payload) {
            value = (value << 8) | Byte.toUnsignedLong(item);
        }
        int bits = payload.length * 8;
        long signBit = 1L << (bits - 1);
        if ((value & signBit) != 0) {
            long mask = (-1L) << bits;
            value |= mask;
        }
        return value;
    }

    static String readCharacterStringPayload(byte[] payload) {
        if (payload.length == 0) {
            return "";
        }
        int encoding = Byte.toUnsignedInt(payload[0]);
        byte[] bytes;
        Charset charset;
        switch (encoding) {
            case 0 -> {
                charset = StandardCharsets.US_ASCII;
                bytes = Arrays.copyOfRange(payload, 1, payload.length);
            }
            case 4 -> {
                charset = StandardCharsets.UTF_16BE;
                bytes = Arrays.copyOfRange(payload, 1, payload.length);
            }
            case 5 -> {
                charset = StandardCharsets.ISO_8859_1;
                bytes = Arrays.copyOfRange(payload, 1, payload.length);
            }
            default -> throw new IllegalArgumentException("Unsupported BACnet CharacterString encoding: " + encoding);
        }
        return new String(bytes, charset);
    }

    static boolean[] readBitStringPayload(byte[] payload) {
        if (payload.length == 0) {
            return new boolean[0];
        }
        int unusedBits = Byte.toUnsignedInt(payload[0]);
        int bitLength = ((payload.length - 1) * 8) - unusedBits;
        boolean[] bits = new boolean[Math.max(bitLength, 0)];
        for (int i = 0; i < bits.length; i++) {
            int current = payload[1 + (i / 8)] & 0xFF;
            bits[i] = ((current >> (7 - (i % 8))) & 0x01) == 1;
        }
        return bits;
    }

    private static String readObjectIdentifier(byte[] payload) {
        if (payload.length != 4) {
            throw new IllegalArgumentException("BACnet objectIdentifier payload length must be 4");
        }
        int raw = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getInt();
        BacnetObjectType objectType = BacnetObjectType.fromId((raw >>> 22) & 0x03FF);
        int instance = raw & 0x3FFFFF;
        return objectType.getName() + ":" + instance;
    }

    private static String rejectReasonName(int reason) {
        return switch (reason) {
            case 0 -> "other";
            case 1 -> "buffer-overflow";
            case 2 -> "inconsistent-parameters";
            case 3 -> "invalid-parameter-data-type";
            case 4 -> "invalid-tag";
            case 5 -> "missing-required-parameter";
            case 6 -> "parameter-out-of-range";
            case 7 -> "too-many-arguments";
            case 8 -> "undefined-enumeration";
            case 9 -> "unrecognized-service";
            default -> Integer.toString(reason);
        };
    }

    private static String abortReasonName(int reason) {
        return switch (reason) {
            case 0 -> "other";
            case 1 -> "buffer-overflow";
            case 2 -> "invalid-apdu-in-this-state";
            case 3 -> "preempted-by-higher-priority-task";
            case 4 -> "segmentation-not-supported";
            case 5 -> "security-error";
            case 6 -> "insufficient-security";
            case 7 -> "window-size-out-of-range";
            case 8 -> "application-exceeded-reply-time";
            case 9 -> "out-of-resources";
            case 10 -> "tsm-timeout";
            case 11 -> "apdu-too-long";
            default -> Integer.toString(reason);
        };
    }

    static record PrimitiveValue(Object value, String type) {
    }

    static record BacnetFrameHeader(ByteBuffer payload, int pduHeader, int pduType) {
    }
}

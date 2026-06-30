package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetRemoteDevice;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class BacnetIAmDecoder {

    private static final int APDU_TYPE_UNCONFIRMED_REQUEST = 0x01;
    private static final int SERVICE_CHOICE_I_AM = 0x00;

    private BacnetIAmDecoder() {
    }

    public static BacnetRemoteDevice decode(byte[] frame, InetSocketAddress remoteAddress) {
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int bvlcType = Byte.toUnsignedInt(buffer.get());
        if (bvlcType != BacnetReadPropertyCodec.BVLC_TYPE_IP) {
            throw new IllegalArgumentException("Unexpected BACnet BVLC type: 0x" + Integer.toHexString(bvlcType));
        }
        int function = Byte.toUnsignedInt(buffer.get());
        if (function != BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU) {
            throw new IllegalArgumentException("Unsupported BACnet BVLC function for I-Am: 0x"
                    + Integer.toHexString(function));
        }
        int frameLength = Short.toUnsignedInt(buffer.getShort());
        if (frameLength != frame.length) {
            throw new IllegalArgumentException("BACnet frame length mismatch: declared="
                    + frameLength + ", actual=" + frame.length);
        }
        int version = Byte.toUnsignedInt(buffer.get());
        if (version != BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported BACnet NPDU version: " + version);
        }
        int control = Byte.toUnsignedInt(buffer.get());
        skipNpdu(buffer, control);

        int apduHeader = Byte.toUnsignedInt(buffer.get());
        int pduType = (apduHeader >> 4) & 0x0F;
        if (pduType != APDU_TYPE_UNCONFIRMED_REQUEST) {
            throw new IllegalArgumentException("Unsupported BACnet APDU type for I-Am: " + pduType);
        }
        int serviceChoice = Byte.toUnsignedInt(buffer.get());
        if (serviceChoice != SERVICE_CHOICE_I_AM) {
            throw new IllegalArgumentException("Unsupported BACnet unconfirmed service choice: " + serviceChoice);
        }

        BacnetTagReader.TagHeader objectIdTag = BacnetTagReader.readTag(buffer);
        if (objectIdTag.contextSpecific() || objectIdTag.openingTag() || objectIdTag.closingTag()
                || objectIdTag.tagNumber() != 12 || objectIdTag.length() != 4) {
            throw new IllegalArgumentException("Invalid BACnet I-Am object identifier tag");
        }
        int objectIdRaw = buffer.getInt();
        BacnetObjectType objectType = BacnetObjectType.fromId((objectIdRaw >>> 22) & 0x03FF);
        if (objectType != BacnetObjectType.DEVICE) {
            throw new IllegalArgumentException("BACnet I-Am object type must be device");
        }
        int deviceInstance = objectIdRaw & 0x3FFFFF;

        Integer maxApdu = readUnsignedPrimitive(buffer, 2);
        Integer segmentation = readUnsignedPrimitive(buffer, 9);
        Integer vendorId = readUnsignedPrimitive(buffer, 2);

        return BacnetRemoteDevice.builder()
                .deviceInstance(deviceInstance)
                .socketAddress(remoteAddress)
                .maxApduLengthAccepted(maxApdu)
                .vendorId(vendorId)
                .segmentationSupported(segmentationName(segmentation))
                .discoveredByWhoIs(true)
                .build();
    }

    private static Integer readUnsignedPrimitive(ByteBuffer buffer, int expectedTypeId) {
        BacnetTagReader.TagHeader tag = BacnetTagReader.readTag(buffer);
        if (tag.contextSpecific() || tag.openingTag() || tag.closingTag() || tag.tagNumber() != expectedTypeId) {
            throw new IllegalArgumentException("Unexpected BACnet primitive type id: expected="
                    + expectedTypeId + ", actual=" + tag.tagNumber());
        }
        int value = 0;
        for (int i = 0; i < tag.length(); i++) {
            value = (value << 8) | Byte.toUnsignedInt(buffer.get());
        }
        return value;
    }

    private static void skipNpdu(ByteBuffer buffer, int control) {
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

    private static String segmentationName(Integer value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case 0 -> "segmented-both";
            case 1 -> "segmented-transmit";
            case 2 -> "segmented-receive";
            case 3 -> "no-segmentation";
            default -> Integer.toString(value);
        };
    }
}

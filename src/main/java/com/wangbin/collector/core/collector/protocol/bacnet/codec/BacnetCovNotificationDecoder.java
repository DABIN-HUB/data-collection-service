package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetCovNotification;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class BacnetCovNotificationDecoder {

    public static final int APDU_TYPE_UNCONFIRMED_REQUEST = 0x01;
    public static final int SERVICE_CHOICE_UNCONFIRMED_COV_NOTIFICATION = 0x02;

    private BacnetCovNotificationDecoder() {
    }

    public static boolean isUnconfirmedCovNotification(byte[] frame) {
        if (frame == null || frame.length < 8) {
            return false;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
            BacnetReadPropertyResponseDecoder.BacnetFrameHeader header =
                    BacnetReadPropertyResponseDecoder.readFrameHeader(buffer);
            if (header.pduType() != APDU_TYPE_UNCONFIRMED_REQUEST) {
                return false;
            }
            return Byte.toUnsignedInt(buffer.get()) == SERVICE_CHOICE_UNCONFIRMED_COV_NOTIFICATION;
        } catch (Exception ex) {
            return false;
        }
    }

    public static BacnetCovNotification decode(byte[] frame) {
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        BacnetReadPropertyResponseDecoder.BacnetFrameHeader header =
                BacnetReadPropertyResponseDecoder.readFrameHeader(buffer);
        if (header.pduType() != APDU_TYPE_UNCONFIRMED_REQUEST) {
            throw new IllegalArgumentException("Unsupported BACnet APDU type for COV notification: " + header.pduType());
        }
        int serviceChoice = Byte.toUnsignedInt(buffer.get());
        if (serviceChoice != SERVICE_CHOICE_UNCONFIRMED_COV_NOTIFICATION) {
            throw new IllegalArgumentException("Unsupported BACnet unconfirmed service choice: " + serviceChoice);
        }

        BacnetTagReader.TagHeader processTag = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(processTag, 0);
        int processIdentifier = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, processTag.length());

        BacnetTagReader.TagHeader initiatingTag = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(initiatingTag, 1);
        if (initiatingTag.length() != 4) {
            throw new IllegalArgumentException("BACnet COV initiating device identifier length must be 4");
        }
        int initiatingRaw = buffer.getInt();
        int initiatingType = (initiatingRaw >>> 22) & 0x03FF;
        if (initiatingType != BacnetObjectType.DEVICE.getId()) {
            throw new IllegalArgumentException("BACnet COV initiating object must be device");
        }
        int initiatingDeviceInstance = initiatingRaw & 0x3FFFFF;

        BacnetTagReader.TagHeader monitoredTag = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(monitoredTag, 2);
        if (monitoredTag.length() != 4) {
            throw new IllegalArgumentException("BACnet COV monitored object identifier length must be 4");
        }
        int monitoredRaw = buffer.getInt();
        BacnetObjectType monitoredObjectType = BacnetObjectType.fromId((monitoredRaw >>> 22) & 0x03FF);
        int monitoredObjectInstance = monitoredRaw & 0x3FFFFF;

        BacnetTagReader.TagHeader timeRemainingTag = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(timeRemainingTag, 3);
        int timeRemaining = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, timeRemainingTag.length());

        BacnetTagReader.TagHeader listOpen = BacnetTagReader.readTag(buffer);
        if (!listOpen.contextSpecific() || !listOpen.openingTag() || listOpen.tagNumber() != 4) {
            throw new IllegalArgumentException("BACnet COV listOfValues missing opening tag 4");
        }

        BacnetCovNotification.BacnetCovNotificationBuilder builder = BacnetCovNotification.builder()
                .subscriberProcessIdentifier(processIdentifier)
                .initiatingDeviceInstance(initiatingDeviceInstance)
                .monitoredObjectType(monitoredObjectType)
                .monitoredObjectInstance(monitoredObjectInstance)
                .timeRemaining(timeRemaining);

        while (buffer.hasRemaining()) {
            int nextByte = Byte.toUnsignedInt(buffer.get(buffer.position()));
            if ((nextByte & 0x08) != 0 && (nextByte & 0x07) == 0x07 && ((nextByte >> 4) & 0x0F) == 4) {
                BacnetTagReader.readTag(buffer);
                break;
            }

            BacnetTagReader.TagHeader propertyTag = BacnetTagReader.readTag(buffer);
            BacnetReadPropertyResponseDecoder.requireContextTag(propertyTag, 0);
            int propertyId = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, propertyTag.length());
            BacnetPropertyIdentifier propertyIdentifier = BacnetPropertyIdentifier.fromId(propertyId);

            Integer arrayIndex = null;
            int valueStartByte = Byte.toUnsignedInt(buffer.get(buffer.position()));
            if ((valueStartByte & 0x08) != 0 && ((valueStartByte >> 4) & 0x0F) == 1 && (valueStartByte & 0x07) < 5) {
                BacnetTagReader.TagHeader indexTag = BacnetTagReader.readTag(buffer);
                arrayIndex = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, indexTag.length());
            }

            BacnetTagReader.TagHeader valueOpen = BacnetTagReader.readTag(buffer);
            if (!valueOpen.contextSpecific() || !valueOpen.openingTag() || valueOpen.tagNumber() != 2) {
                throw new IllegalArgumentException("BACnet COV property value missing opening tag 2");
            }
            BacnetReadPropertyResponseDecoder.PrimitiveValue primitiveValue =
                    BacnetReadPropertyResponseDecoder.readAnyPrimitiveValue(buffer);
            BacnetTagReader.TagHeader valueClose = BacnetTagReader.readTag(buffer);
            if (!valueClose.contextSpecific() || !valueClose.closingTag() || valueClose.tagNumber() != 2) {
                throw new IllegalArgumentException("BACnet COV property value missing closing tag 2");
            }

            Integer priority = null;
            if (buffer.hasRemaining()) {
                int possiblePriorityByte = Byte.toUnsignedInt(buffer.get(buffer.position()));
                if ((possiblePriorityByte & 0x08) != 0 && ((possiblePriorityByte >> 4) & 0x0F) == 3
                        && (possiblePriorityByte & 0x07) < 5) {
                    BacnetTagReader.TagHeader priorityTag = BacnetTagReader.readTag(buffer);
                    priority = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, priorityTag.length());
                }
            }

            builder.propertyValue(BacnetCovNotification.PropertyValue.builder()
                    .propertyIdentifier(propertyIdentifier)
                    .arrayIndex(arrayIndex)
                    .value(primitiveValue.value())
                    .valueType(primitiveValue.type())
                    .priority(priority)
                    .build());
        }
        return builder.build();
    }
}

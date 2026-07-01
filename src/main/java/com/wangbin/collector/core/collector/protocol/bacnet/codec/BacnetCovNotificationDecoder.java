package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetCovNotification;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetValue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class BacnetCovNotificationDecoder {

    public static final int APDU_TYPE_CONFIRMED_REQUEST = 0x00;
    public static final int APDU_TYPE_UNCONFIRMED_REQUEST = 0x01;
    public static final int SERVICE_CHOICE_CONFIRMED_COV_NOTIFICATION = 0x01;
    public static final int SERVICE_CHOICE_UNCONFIRMED_COV_NOTIFICATION = 0x02;

    private BacnetCovNotificationDecoder() {
    }

    public static boolean isUnconfirmedCovNotification(byte[] frame) {
        return hasServiceChoice(frame, APDU_TYPE_UNCONFIRMED_REQUEST, SERVICE_CHOICE_UNCONFIRMED_COV_NOTIFICATION);
    }

    public static boolean isConfirmedCovNotification(byte[] frame) {
        return hasServiceChoice(frame, APDU_TYPE_CONFIRMED_REQUEST, SERVICE_CHOICE_CONFIRMED_COV_NOTIFICATION);
    }

    public static BacnetCovNotification decode(byte[] frame) {
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        BacnetReadPropertyResponseDecoder.BacnetFrameHeader header =
                BacnetReadPropertyResponseDecoder.readFrameHeader(buffer);
        return switch (header.pduType()) {
            case APDU_TYPE_UNCONFIRMED_REQUEST -> decodeUnconfirmed(buffer);
            case APDU_TYPE_CONFIRMED_REQUEST -> decodeConfirmed(buffer, header.pduHeader());
            default -> throw new IllegalArgumentException("Unsupported BACnet APDU type for COV notification: " + header.pduType());
        };
    }

    private static boolean hasServiceChoice(byte[] frame, int expectedPduType, int expectedServiceChoice) {
        if (frame == null || frame.length < 8) {
            return false;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
            BacnetReadPropertyResponseDecoder.BacnetFrameHeader header =
                    BacnetReadPropertyResponseDecoder.readFrameHeader(buffer);
            if (header.pduType() != expectedPduType) {
                return false;
            }
            if (expectedPduType == APDU_TYPE_CONFIRMED_REQUEST) {
                if (buffer.remaining() < 3) {
                    return false;
                }
                buffer.get();
                buffer.get();
            }
            return Byte.toUnsignedInt(buffer.get()) == expectedServiceChoice;
        } catch (Exception ex) {
            return false;
        }
    }

    private static BacnetCovNotification decodeUnconfirmed(ByteBuffer buffer) {
        int serviceChoice = Byte.toUnsignedInt(buffer.get());
        if (serviceChoice != SERVICE_CHOICE_UNCONFIRMED_COV_NOTIFICATION) {
            throw new IllegalArgumentException("Unsupported BACnet unconfirmed service choice: " + serviceChoice);
        }
        return decodeNotificationBody(buffer, false, null);
    }

    private static BacnetCovNotification decodeConfirmed(ByteBuffer buffer, int pduHeader) {
        boolean segmented = (pduHeader & 0x08) != 0;
        boolean moreFollows = (pduHeader & 0x04) != 0;
        if (segmented || moreFollows) {
            throw new IllegalStateException("Segmented confirmed BACnet COV notification is not supported");
        }
        if (buffer.remaining() < 3) {
            throw new IllegalArgumentException("BACnet confirmed COV notification header is truncated");
        }
        buffer.get();
        int invokeId = Byte.toUnsignedInt(buffer.get());
        int serviceChoice = Byte.toUnsignedInt(buffer.get());
        if (serviceChoice != SERVICE_CHOICE_CONFIRMED_COV_NOTIFICATION) {
            throw new IllegalArgumentException("Unsupported BACnet confirmed service choice: " + serviceChoice);
        }
        return decodeNotificationBody(buffer, true, invokeId);
    }

    private static BacnetCovNotification decodeNotificationBody(ByteBuffer buffer,
                                                                boolean confirmed,
                                                                Integer invokeId) {
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
                .confirmed(confirmed)
                .invokeId(invokeId)
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
            BacnetValue decodedValue = BacnetValueDecoder.readAnyValue(buffer);
            decodedValue = BacnetValueDecoder.normalizeDecodedPropertyValue(propertyIdentifier, arrayIndex, decodedValue);
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
                    .value(decodedValue.getValue())
                    .valueType(decodedValue.getValueType())
                    .valueMetadata(decodedValue.getMetadata())
                    .priority(priority)
                    .build());
        }
        return builder.build();
    }
}

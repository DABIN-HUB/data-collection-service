package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetRemoteDevice;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 定义当前模块的业务组件。
 */
public final class BacnetIAmDecoder {

    private static final int APDU_TYPE_UNCONFIRMED_REQUEST = 0x01;
    private static final int SERVICE_CHOICE_I_AM = 0x00;

    /**
     * 创建当前组件实例。
     */
    private BacnetIAmDecoder() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static BacnetRemoteDevice decode(byte[] frame, InetSocketAddress transportSource) {
        InetSocketAddress resolvedSource = BacnetBvlcCodec.resolveMessageSource(frame, transportSource);
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        BacnetReadPropertyResponseDecoder.BacnetFrameHeader header =
                BacnetReadPropertyResponseDecoder.readFrameHeader(buffer);

        if (header.pduType() != APDU_TYPE_UNCONFIRMED_REQUEST) {
            throw new IllegalArgumentException("Unsupported BACnet APDU type for I-Am: " + header.pduType());
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
        if (!BacnetObjectType.DEVICE.equals(objectType)) {
            throw new IllegalArgumentException("BACnet I-Am object type must be device");
        }
        int deviceInstance = objectIdRaw & 0x3FFFFF;

        Integer maxApdu = readUnsignedPrimitive(buffer, 2);
        Integer segmentation = readUnsignedPrimitive(buffer, 9);
        Integer vendorId = readUnsignedPrimitive(buffer, 2);

        return BacnetRemoteDevice.builder()
                .deviceInstance(deviceInstance)
                .socketAddress(resolvedSource)
                .maxApduLengthAccepted(maxApdu)
                .vendorId(vendorId)
                .segmentationSupported(segmentationName(segmentation))
                .discoveredByWhoIs(true)
                .build();
    }

    /**
     * 查询并返回业务数据。
     */
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

    /**
     * 执行当前业务逻辑。
     */
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
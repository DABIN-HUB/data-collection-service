package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import java.nio.ByteBuffer;

final class BacnetTagReader {

    private BacnetTagReader() {
    }

    static TagHeader readTag(ByteBuffer buffer) {
        int first = Byte.toUnsignedInt(buffer.get());
        int tagNumber = (first >> 4) & 0x0F;
        boolean contextSpecific = (first & 0x08) != 0;
        int lowNibble = first & 0x07;

        if (tagNumber == 0x0F) {
            tagNumber = Byte.toUnsignedInt(buffer.get());
        }

        if (lowNibble == 0x06 || lowNibble == 0x07) {
            return new TagHeader(tagNumber, contextSpecific, lowNibble == 0x06, lowNibble == 0x07, -1);
        }

        int length = lowNibble;
        if (length == 0x05) {
            int extended = Byte.toUnsignedInt(buffer.get());
            if (extended == 254) {
                length = Short.toUnsignedInt(buffer.getShort());
            } else if (extended == 255) {
                long value = Integer.toUnsignedLong(buffer.getInt());
                if (value > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("BACnet tag length is too large: " + value);
                }
                length = (int) value;
            } else {
                length = extended;
            }
        }
        return new TagHeader(tagNumber, contextSpecific, false, false, length);
    }

    record TagHeader(int tagNumber,
                     boolean contextSpecific,
                     boolean openingTag,
                     boolean closingTag,
                     int length) {
    }
}

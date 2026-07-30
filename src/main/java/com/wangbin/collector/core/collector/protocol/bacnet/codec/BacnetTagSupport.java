package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import java.io.ByteArrayOutputStream;

final class BacnetTagSupport {

    private BacnetTagSupport() {
    }

    static void writeTag(ByteArrayOutputStream out, int tagNumber, boolean contextTag, long length) {
        int classValue = contextTag ? 0x08 : 0x00;
        boolean extendedTag = tagNumber > 14;
        if (length < 5) {
            if (extendedTag) {
                out.write(0xF0 | classValue | (int) length);
                out.write(tagNumber);
            } else {
                out.write((tagNumber << 4) | classValue | (int) length);
            }
            return;
        }

        if (extendedTag) {
            out.write(0xF5 | classValue);
            out.write(tagNumber);
        } else {
            out.write((tagNumber << 4) | classValue | 0x05);
        }
        if (length < 254) {
            out.write((int) length);
        } else if (length < 65536) {
            out.write(254);
            writeUnsigned(out, length, 2);
        } else {
            out.write(255);
            writeUnsigned(out, length, 4);
        }
    }

    static void writeContextOpeningTag(ByteArrayOutputStream out, int contextId) {
        writeContextTag(out, contextId, true);
    }

    static void writeContextClosingTag(ByteArrayOutputStream out, int contextId) {
        writeContextTag(out, contextId, false);
    }

    private static void writeContextTag(ByteArrayOutputStream out, int contextId, boolean start) {
        if (contextId <= 14) {
            out.write((contextId << 4) | (start ? 0x0E : 0x0F));
        } else {
            out.write(start ? 0xFE : 0xFF);
            out.write(contextId);
        }
    }

    static void writeUnsigned(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = bytes - 1; i >= 0; i--) {
            out.write((int) ((value >> (i * 8)) & 0xFF));
        }
    }

    static void writeObjectIdentifier(ByteArrayOutputStream out, int objectTypeId, int instanceNumber) {
        writeTag(out, 12, false, 4);
        int value = ((objectTypeId & 0x03FF) << 22) | (instanceNumber & 0x3FFFFF);
        writeUnsigned(out, value, 4);
    }

    static void writeEnumerated(ByteArrayOutputStream out, int value) {
        int length = unsignedLength(value);
        writeTag(out, 9, false, length);
        writeUnsigned(out, value, length);
    }

    static void writeUnsignedInteger(ByteArrayOutputStream out, long value) {
        int length = unsignedLength(value);
        writeTag(out, 2, false, length);
        writeUnsigned(out, value, length);
    }

    static int unsignedLength(long value) {
        if (value <= 0xFFL) {
            return 1;
        }
        if (value <= 0xFFFFL) {
            return 2;
        }
        if (value <= 0xFFFFFFL) {
            return 3;
        }
        return 4;
    }
}

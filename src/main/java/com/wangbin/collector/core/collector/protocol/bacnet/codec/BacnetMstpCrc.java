package com.wangbin.collector.core.collector.protocol.bacnet.codec;

public final class BacnetMstpCrc {

    private BacnetMstpCrc() {
    }

    public static int headerCrc(byte[] data) {
        return headerCrc(data, 0, data != null ? data.length : 0);
    }

    public static int headerCrc(byte[] data, int offset, int length) {
        if (data == null) {
            return 0xFF;
        }
        int crc = 0xFF;
        for (int index = 0; index < length; index++) {
            crc = updateHeader(crc, Byte.toUnsignedInt(data[offset + index]));
        }
        return crc ^ 0xFF;
    }

    public static int dataCrc(byte[] data) {
        return dataCrc(data, 0, data != null ? data.length : 0);
    }

    public static int dataCrc(byte[] data, int offset, int length) {
        if (data == null) {
            return 0xFFFF;
        }
        int crc = 0xFFFF;
        for (int index = 0; index < length; index++) {
            crc = updateData(crc, Byte.toUnsignedInt(data[offset + index]));
        }
        return crc ^ 0xFFFF;
    }

    private static int updateHeader(int current, int value) {
        int crc = (current ^ value) & 0xFF;
        for (int bit = 0; bit < 8; bit++) {
            if ((crc & 0x01) != 0) {
                crc = (crc >> 1) ^ 0x81;
            } else {
                crc >>= 1;
            }
        }
        return crc & 0xFF;
    }

    private static int updateData(int current, int value) {
        int crc = (current ^ value) & 0xFFFF;
        for (int bit = 0; bit < 8; bit++) {
            if ((crc & 0x01) != 0) {
                crc = (crc >> 1) ^ 0xF0B8;
            } else {
                crc >>= 1;
            }
        }
        return crc & 0xFFFF;
    }
}
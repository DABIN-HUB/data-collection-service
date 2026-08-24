package com.wangbin.collector.core.collector.protocol.bacnet.codec;

/**
 * 定义当前模块的业务组件。
 */
public final class BacnetMstpCrc {

    /**
     * 创建当前组件实例。
     */
    private BacnetMstpCrc() {
    }

    /**
     * 执行当前业务逻辑。
     */
    public static int headerCrc(byte[] data) {
        return headerCrc(data, 0, data != null ? data.length : 0);
    }

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 执行当前业务逻辑。
     */
    public static int dataCrc(byte[] data) {
        return dataCrc(data, 0, data != null ? data.length : 0);
    }

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 更新或刷新业务状态。
     */
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

    /**
     * 更新或刷新业务状态。
     */
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
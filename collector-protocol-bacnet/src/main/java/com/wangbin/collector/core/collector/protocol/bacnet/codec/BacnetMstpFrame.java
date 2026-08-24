package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import java.util.Arrays;

/**
 * 定义当前模块的不可变数据记录。
 */
public record BacnetMstpFrame(BacnetMstpFrameType frameType,
                               int destinationAddress,
                               int sourceAddress,
                               byte[] data) {

    public static final int BROADCAST_ADDRESS = 0xFF;

    public BacnetMstpFrame {
        if (frameType == null) {
            throw new IllegalArgumentException("BACnet MS/TP frame type is required");
        }
        if (destinationAddress < 0 || destinationAddress > 0xFF) {
            throw new IllegalArgumentException("BACnet MS/TP destination address must be between 0 and 255");
        }
        if (sourceAddress < 0 || sourceAddress > 0xFF) {
            throw new IllegalArgumentException("BACnet MS/TP source address must be between 0 and 255");
        }
        data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }

    /**
     * 执行当前业务逻辑。
     */
    public int dataLength() {
        return data.length;
    }

    public boolean isBroadcast() {
        return destinationAddress == BROADCAST_ADDRESS;
    }
}
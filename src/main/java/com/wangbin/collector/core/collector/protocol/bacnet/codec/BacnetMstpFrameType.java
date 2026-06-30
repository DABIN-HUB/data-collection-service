package com.wangbin.collector.core.collector.protocol.bacnet.codec;

public enum BacnetMstpFrameType {

    TOKEN(0x00),
    POLL_FOR_MASTER(0x01),
    REPLY_TO_POLL_FOR_MASTER(0x02),
    TEST_REQUEST(0x03),
    TEST_RESPONSE(0x04),
    BACNET_DATA_EXPECTING_REPLY(0x05),
    BACNET_DATA_NOT_EXPECTING_REPLY(0x06),
    REPLY_POSTPONED(0x07);

    private final int code;

    BacnetMstpFrameType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public boolean isDataFrame() {
        return this == BACNET_DATA_EXPECTING_REPLY
                || this == BACNET_DATA_NOT_EXPECTING_REPLY
                || this == REPLY_POSTPONED;
    }

    public static BacnetMstpFrameType fromCode(int code) {
        for (BacnetMstpFrameType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported BACnet MS/TP frame type: 0x" + Integer.toHexString(code));
    }
}
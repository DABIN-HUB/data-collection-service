package com.wangbin.collector.core.collector.protocol.dlt645.domain;

/**
 * DL/T 645 控制码。
 */
public enum Dlt645ControlCode {

    BROADCAST_TIME(0x08),
    READ_DATA(0x11),
    READ_FOLLOWING_DATA(0x12),
    READ_ADDRESS(0x13),
    WRITE_DATA(0x14);

    private final int functionCode;

    Dlt645ControlCode(int functionCode) {
        this.functionCode = functionCode;
    }

    public int requestCode() {
        return functionCode;
    }

    public int functionCode() {
        return functionCode & 0x1F;
    }
}

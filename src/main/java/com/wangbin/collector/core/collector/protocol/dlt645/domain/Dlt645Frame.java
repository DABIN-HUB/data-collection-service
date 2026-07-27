package com.wangbin.collector.core.collector.protocol.dlt645.domain;

import java.util.Arrays;

/**
 * 已完成数据域变换的 DL/T 645 逻辑帧。
 */
public final class Dlt645Frame {

    private final Dlt645Address address;
    private final int control;
    private final byte[] data;

    public Dlt645Frame(Dlt645Address address, int control, byte[] data) {
        this.address = address;
        this.control = control & 0xFF;
        this.data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
        if (this.data.length > 255) {
            throw new IllegalArgumentException("DL/T 645 数据域不能超过 255 字节");
        }
    }

    public Dlt645Address address() {
        return address;
    }

    public int control() {
        return control;
    }

    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }

    public int functionCode() {
        return control & 0x1F;
    }

    public boolean response() {
        return (control & 0x80) != 0;
    }

    public boolean abnormal() {
        return (control & 0x40) != 0;
    }

    public boolean hasFollowingData() {
        return (control & 0x20) != 0;
    }
}

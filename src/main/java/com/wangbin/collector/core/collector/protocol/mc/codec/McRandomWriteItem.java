package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

public class McRandomWriteItem {

    private final McAddress address;
    private final byte[] payload;

    public McRandomWriteItem(McAddress address, byte[] payload) {
        this.address = address;
        this.payload = payload != null ? payload.clone() : new byte[0];
    }

    public McAddress getAddress() {
        return address;
    }

    public byte[] getPayload() {
        return payload.clone();
    }
}

package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

/**
 * 定义当前模块的业务组件。
 */
public class McRandomWriteItem {

    private final McAddress address;
    private final byte[] payload;

    /**
     * 创建当前组件实例。
     */
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

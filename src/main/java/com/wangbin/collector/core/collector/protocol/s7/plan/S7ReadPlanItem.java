package com.wangbin.collector.core.collector.protocol.s7.plan;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7Address;

public class S7ReadPlanItem {

    private final DataPoint point;
    private final S7Address address;
    private final int byteOffset;
    private final int bitOffset;
    private final int byteLength;
    private final boolean blockOptimizable;

    public S7ReadPlanItem(DataPoint point,
                          S7Address address,
                          int byteOffset,
                          int bitOffset,
                          int byteLength,
                          boolean blockOptimizable) {
        this.point = point;
        this.address = address;
        this.byteOffset = byteOffset;
        this.bitOffset = bitOffset;
        this.byteLength = byteLength;
        this.blockOptimizable = blockOptimizable;
    }

    public DataPoint getPoint() {
        return point;
    }

    public S7Address getAddress() {
        return address;
    }

    public int getByteOffset() {
        return byteOffset;
    }

    public int getBitOffset() {
        return bitOffset;
    }

    public int getByteLength() {
        return byteLength;
    }

    public boolean isBlockOptimizable() {
        return blockOptimizable;
    }
}


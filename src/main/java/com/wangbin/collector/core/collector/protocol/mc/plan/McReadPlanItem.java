package com.wangbin.collector.core.collector.protocol.mc.plan;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;

public class McReadPlanItem {

    private final DataPoint point;
    private final McAddress address;
    private final int unitOffset;
    private final int unitCount;
    private final int payloadByteOffset;
    private final int payloadByteLength;

    public McReadPlanItem(DataPoint point,
                          McAddress address,
                          int unitOffset,
                          int unitCount,
                          int payloadByteOffset,
                          int payloadByteLength) {
        this.point = point;
        this.address = address;
        this.unitOffset = unitOffset;
        this.unitCount = unitCount;
        this.payloadByteOffset = payloadByteOffset;
        this.payloadByteLength = payloadByteLength;
    }

    public DataPoint getPoint() {
        return point;
    }

    public McAddress getAddress() {
        return address;
    }

    public int getUnitOffset() {
        return unitOffset;
    }

    public int getUnitCount() {
        return unitCount;
    }

    public int getPayloadByteOffset() {
        return payloadByteOffset;
    }

    public int getPayloadByteLength() {
        return payloadByteLength;
    }
}
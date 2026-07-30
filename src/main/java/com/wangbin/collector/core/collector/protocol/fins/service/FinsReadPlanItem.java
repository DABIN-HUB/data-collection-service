package com.wangbin.collector.core.collector.protocol.fins.service;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsAddress;
import lombok.Getter;

@Getter
public class FinsReadPlanItem {

    private final DataPoint point;
    private final FinsAddress address;
    private final int unitOffset;
    private final int unitCount;
    private final int payloadByteOffset;
    private final int payloadByteLength;

    public FinsReadPlanItem(DataPoint point,
                            FinsAddress address,
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
}
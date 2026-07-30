package com.wangbin.collector.core.collector.protocol.s7.domain;

import lombok.Value;

@Value
public class S7Address {

    String rawAddress;
    String plc4xAddress;
    String area;
    String plcType;
    int arraySize;

    public String getBasePlcType() {
        int idx = plcType.indexOf('(');
        return idx >= 0 ? plcType.substring(0, idx) : plcType;
    }

    public boolean isScalar() {
        return arraySize <= 1;
    }
}

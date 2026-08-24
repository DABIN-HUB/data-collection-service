package com.wangbin.collector.core.collector.protocol.ads.domain;

import lombok.Value;

/**
 * 定义当前模块的业务组件。
 */
@Value
public class AdsAddress {

    String rawAddress;
    String plc4xAddress;
    String addressKind;
    String plcType;
    int arraySize;
    Integer stringLength;

    public String getBasePlcType() {
        if (plcType == null) {
            return null;
        }
        int idx = plcType.indexOf('(');
        return idx >= 0 ? plcType.substring(0, idx) : plcType;
    }

    public boolean isScalar() {
        return arraySize <= 1;
    }

    public boolean isSymbolic() {
        return "SYMBOLIC".equals(addressKind);
    }
}

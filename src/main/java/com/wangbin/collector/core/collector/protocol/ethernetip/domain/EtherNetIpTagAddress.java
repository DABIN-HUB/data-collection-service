package com.wangbin.collector.core.collector.protocol.ethernetip.domain;

import lombok.Value;

/**
 * 定义当前模块的业务组件。
 */
@Value
public class EtherNetIpTagAddress {

    String rawAddress;
    String plc4xAddress;
    String tagName;
    String plcType;
    int arraySize;

    public String getBasePlcType() {
        if (plcType == null) {
            return null;
        }
        int idx = plcType.indexOf('[');
        return idx >= 0 ? plcType.substring(0, idx) : plcType;
    }

    public boolean isScalar() {
        return arraySize <= 1;
    }
}

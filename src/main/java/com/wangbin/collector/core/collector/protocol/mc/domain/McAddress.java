package com.wangbin.collector.core.collector.protocol.mc.domain;

import lombok.Value;

/**
 * 定义当前模块的业务组件。
 */
@Value
public class McAddress {

    String rawAddress;
    String canonicalAddress;
    McDeviceCode deviceCode;
    int deviceNumber;
    McDriverType driverType;
    int arraySize;
    Integer stringLength;
    Integer bitIndex;

    public boolean isBitDevice() {
        return deviceCode.isBitDevice();
    }

    public boolean isWordDevice() {
        return deviceCode.isWordDevice();
    }

    public boolean isScalar() {
        return !isArray();
    }

    public boolean isArray() {
        return driverType != McDriverType.STRING && arraySize > 1;
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean hasBitOffset() {
        return bitIndex != null;
    }

    public int getElementCount() {
        return Math.max(1, arraySize);
    }

    public int getReadUnitCount() {
        return isBitDevice() ? getElementCount() : getWordCount();
    }

    public int getWordCount() {
        if (driverType == McDriverType.STRING) {
            int length = stringLength != null ? stringLength : 0;
            return Math.max(1, (length + 1) / 2);
        }
        return Math.max(1, driverType.getWordLength() * getElementCount());
    }

    public int getExpectedPayloadLength() {
        return isBitDevice() ? (getReadUnitCount() + 1) / 2 : getWordCount() * 2;
    }
}

package com.wangbin.collector.core.collector.protocol.iec101.domain;

/**
 * IEC101 链路和 ASDU 地址长度配置。
 */
public record Iec101LinkConfig(int linkAddressSize,
                               int causeOfTransmissionSize,
                               int commonAddressSize,
                               int informationObjectAddressSize) {

    public Iec101LinkConfig {
        requireRange("链路地址长度", linkAddressSize, 1, 2);
        requireRange("传送原因长度", causeOfTransmissionSize, 1, 2);
        requireRange("公共地址长度", commonAddressSize, 1, 2);
        requireRange("信息对象地址长度", informationObjectAddressSize, 1, 3);
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + "必须在 " + min + " 到 " + max + " 之间");
        }
    }
}

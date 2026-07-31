package com.wangbin.collector.core.collector.protocol.opc.plc4x.domain;

import lombok.Getter;

/**
 * Parsed PLC4X OPC UA 点位 元数据.
 */
@Getter
public class Plc4xOpcUaAddress {

    private final String rawAddress;
    private final String plc4xAddress;
    private final String dataType;
    private final double samplingInterval;
    private final int queueSize;
    private final double deadband;
    private final boolean subscribe;
    private final int arraySize;

    /**
     * 创建当前组件实例。
     */
    public Plc4xOpcUaAddress(String rawAddress,
                             String plc4xAddress,
                             String dataType,
                             double samplingInterval,
                             int queueSize,
                             double deadband,
                             boolean subscribe,
                             int arraySize) {
        this.rawAddress = rawAddress;
        this.plc4xAddress = plc4xAddress;
        this.dataType = dataType;
        this.samplingInterval = samplingInterval;
        this.queueSize = queueSize;
        this.deadband = deadband;
        this.subscribe = subscribe;
        this.arraySize = Math.max(1, arraySize);
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean needSubscribe() {
        return subscribe;
    }

    public boolean isScalar() {
        return arraySize <= 1;
    }
}

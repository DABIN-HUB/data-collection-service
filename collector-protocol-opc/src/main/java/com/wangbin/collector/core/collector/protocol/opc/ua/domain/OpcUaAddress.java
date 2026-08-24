package com.wangbin.collector.core.collector.protocol.opc.ua.domain;

import lombok.Getter;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/**
 * Holder for parsed OPC UA addressing 元数据.
 */
@Getter
public class OpcUaAddress {

    private final NodeId nodeId;
    private final OpcUaDataType dataType;
    private final double samplingInterval;
    private final int queueSize;
    private final double deadband;
    private final boolean subscribe;

    /**
     * 创建当前组件实例。
     */
    public OpcUaAddress(NodeId nodeId,
                        OpcUaDataType dataType,
                        double samplingInterval,
                        int queueSize,
                        double deadband,
                        boolean subscribe) {
        this.nodeId = nodeId;
        this.dataType = dataType;
        this.samplingInterval = samplingInterval;
        this.queueSize = queueSize;
        this.deadband = deadband;
        this.subscribe = subscribe;
    }

    /**
     * 解析或转换业务数据。
     */
    public NodeId toNodeId() {
        return nodeId;
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean needSubscribe() {
        return subscribe;
    }
}


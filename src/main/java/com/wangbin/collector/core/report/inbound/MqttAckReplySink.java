package com.wangbin.collector.core.report.inbound;

/**
 * 平台业务 ACK 状态接收端。
 */
@FunctionalInterface
public interface MqttAckReplySink {

    /**
     * 执行当前业务逻辑。
     */
    void complete(MqttAckReply ackReply);
}

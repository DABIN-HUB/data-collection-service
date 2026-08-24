package com.wangbin.collector.core.report.inbound;

/**
 * MQTT平台业务确认观察者。
 */
@FunctionalInterface
public interface MqttAckReplyObserver {

    /**
     * 执行当前业务逻辑。
     */
    void onAck(MqttAckReply ackReply);
}

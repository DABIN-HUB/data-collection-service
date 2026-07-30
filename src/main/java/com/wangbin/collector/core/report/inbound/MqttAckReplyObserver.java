package com.wangbin.collector.core.report.inbound;

/**
 * MQTT平台业务确认观察者。
 */
@FunctionalInterface
public interface MqttAckReplyObserver {

    void onAck(MqttAckReply ackReply);
}

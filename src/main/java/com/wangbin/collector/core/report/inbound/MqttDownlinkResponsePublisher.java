package com.wangbin.collector.core.report.inbound;

/**
 * MQTT 下行响应发布器。
 */
@FunctionalInterface
public interface MqttDownlinkResponsePublisher {

    void publish(String topic, byte[] payload, int qos) throws Exception;
}

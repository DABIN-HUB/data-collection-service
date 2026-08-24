package com.wangbin.collector.core.report.inbound;

/**
 * MQTT 下行响应发布器。
 */
@FunctionalInterface
public interface MqttDownlinkResponsePublisher {

    /**
     * 执行当前业务逻辑。
     */
    void publish(String topic, byte[] payload, int qos) throws Exception;
}

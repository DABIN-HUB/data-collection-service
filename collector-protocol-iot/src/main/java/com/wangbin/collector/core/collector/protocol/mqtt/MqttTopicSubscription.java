package com.wangbin.collector.core.collector.protocol.mqtt;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 定义当前模块的业务组件。
 */
@Getter
@RequiredArgsConstructor
public class MqttTopicSubscription {
    private final String topic;
    private final int qos;
}

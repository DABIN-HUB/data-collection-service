package com.wangbin.collector.core.collector.protocol.mqtt;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * 定义当前模块的业务组件。
 */
@Getter
@RequiredArgsConstructor
public class MqttMessageEnvelope {
    private final byte[] payload;
    private final int qos;
    private final boolean retained;
    private final Map<String, String> properties;
}

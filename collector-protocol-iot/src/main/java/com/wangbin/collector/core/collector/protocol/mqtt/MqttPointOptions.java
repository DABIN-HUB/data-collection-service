package com.wangbin.collector.core.collector.protocol.mqtt;

import com.wangbin.collector.common.domain.entity.DataPoint;
import lombok.Getter;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 定义当前模块的业务组件。
 */
@Getter
public class MqttPointOptions {

    private final String topic;
    private final String writeTopic;
    private final int qos;
    private final boolean retain;
    private final String jsonPath;
    private final String payloadEncoding;
    private final String publishTemplate;
    private final Charset charset;

    /**
     * 创建当前组件实例。
     */
    private MqttPointOptions(String topic,
                             String writeTopic,
                             int qos,
                             boolean retain,
                             String jsonPath,
                             String payloadEncoding,
                             String publishTemplate,
                             Charset charset) {
        this.topic = topic;
        this.writeTopic = writeTopic;
        this.qos = qos;
        this.retain = retain;
        this.jsonPath = jsonPath;
        this.payloadEncoding = payloadEncoding;
        this.publishTemplate = publishTemplate;
        this.charset = charset;
    }

    /**
     * 创建并返回业务对象。
     */
    public static MqttPointOptions from(DataPoint point, int defaultQos) {
        return from(point, defaultQos, null);
    }

    /**
     * 根据设备身份解析 ProtoForge 等设备端使用的主题占位符。
     */
    public static MqttPointOptions from(DataPoint point, int defaultQos, String deviceId) {
        Map<String, Object> templateValues = new HashMap<>();
        templateValues.put("deviceId", deviceId);
        templateValues.put("device_id", deviceId);
        templateValues.put("pointId", point.getPointId());
        templateValues.put("pointCode", point.getPointCode());
        templateValues.put("address", point.getAddress());
        String topic = resolveTopic(Optional.ofNullable(point.getAdditionalConfig("topic"))
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .orElse(point.getAddress()), templateValues);
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("MQTT point missing topic: " + point.getPointId());
        }
        String writeTopic = resolveTopic(Optional.ofNullable(point.getAdditionalConfig("writeTopic"))
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .orElse(topic), templateValues);
        int qos = Optional.ofNullable(point.getAdditionalConfig("qos"))
                .map(value -> MqttCollectorUtils.asInt(value, defaultQos))
                .orElse(defaultQos);
        boolean retain = Optional.ofNullable(point.getAdditionalConfig("retain"))
                .map(value -> MqttCollectorUtils.asBoolean(value, false))
                .orElse(false);
        String jsonPath = Optional.ofNullable(point.getAdditionalConfig("jsonPath"))
                .map(Object::toString)
                .orElse(null);
        String encoding = Optional.ofNullable(point.getAdditionalConfig("payloadEncoding"))
                .map(Object::toString)
                .orElse("plain");
        String template = Optional.ofNullable(point.getAdditionalConfig("publishTemplate"))
                .map(Object::toString)
                .orElse(null);
        Charset charset = Optional.ofNullable(point.getAdditionalConfig("charset"))
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .map(Charset::forName)
                .orElse(StandardCharsets.UTF_8);
        return new MqttPointOptions(topic, writeTopic, qos, retain, jsonPath, encoding, template, charset);
    }

    private static String resolveTopic(String topic, Map<String, ?> values) {
        return ProtocolTemplateResolver.resolve(topic, values);
    }
}

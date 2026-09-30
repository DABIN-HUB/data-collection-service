package com.wangbin.collector.core.collector.protocol.mqtt;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.config.validator.MqttConfigurationContract;
import lombok.Getter;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import com.alibaba.fastjson2.JSONPath;

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
     * 根据设备和点位上下文解析 MQTT topic template。
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
        MqttConfigurationContract.filter(topic);
        String configuredWriteTopic = Optional.ofNullable(point.getAdditionalConfig("writeTopic"))
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .orElse(null);
        String writeTopic = configuredWriteTopic == null ? topic : resolveTopic(configuredWriteTopic, templateValues);
        if (configuredWriteTopic != null || "W".equalsIgnoreCase(point.getReadWrite())
                || "RW".equalsIgnoreCase(point.getReadWrite())) {
            MqttConfigurationContract.publishTopic(writeTopic);
        }
        int qos = MqttConfigurationContract.qos(point.getAdditionalConfig("qos"), defaultQos, "point qos");
        boolean retain = MqttConfigurationContract.flag(point.getAdditionalConfig("retain"), false, "point retain");
        String jsonPath = Optional.ofNullable(point.getAdditionalConfig("jsonPath"))
                .map(Object::toString)
                .orElse(null);
        String encoding = Optional.ofNullable(point.getAdditionalConfig("payloadEncoding"))
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .orElse(jsonPath == null || jsonPath.isBlank() ? "AUTO_COMPAT" : "JSON")
                .trim().toUpperCase(Locale.ROOT);
        if (!Set.of("JSON", "PLAIN_TEXT", "BASE64", "HEX", "AUTO_COMPAT").contains(encoding)
                || "AUTO_COMPAT".equals(encoding) && point.getAdditionalConfig("payloadEncoding") != null
                && !point.getAdditionalConfig("payloadEncoding").toString().isBlank()) {
            throw new IllegalArgumentException("MQTT unsupported payloadEncoding");
        }
        if (jsonPath != null && !jsonPath.isBlank()) {
            if (!"JSON".equals(encoding)) {
                throw new IllegalArgumentException("MQTT jsonPath requires JSON payloadEncoding");
            }
            JSONPath.of(jsonPath);
        }
        String template = Optional.ofNullable(point.getAdditionalConfig("publishTemplate"))
                .map(Object::toString)
                .orElse(null);
        if (template != null && !template.isBlank()) {
            Map<String, Object> payloadValues = new HashMap<>();
            payloadValues.put("value", "sample");
            payloadValues.put("pointId", point.getPointId());
            payloadValues.put("pointCode", point.getPointCode());
            payloadValues.put("deviceId", deviceId);
            payloadValues.put("timestamp", 1);
            MqttConfigurationContract.resolveTemplate(template, payloadValues,
                    Set.of("value", "pointId", "pointCode", "deviceId", "timestamp"));
        }
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

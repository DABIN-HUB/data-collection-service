package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.mqtt.MqttCollector;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * MQTT 协议元数据提供者。
 */
@Component
@Order(130)
public class MqttProtocolDescriptorProvider implements ProtocolDescriptorProvider {

    @Override
    public void register(ProtocolDescriptorRegistry registry) {
        registry.registerPrimary(registry.descriptor("MQTT", "MQTT",
                "MQTT subscription/publish collection protocol.",
                List.of("MQTT_SSL"), MqttCollector.class, "MQTT", 1883,
                ProtocolAddressingMode.SYMBOLIC,
                true, true, true,
                List.of("devices/${deviceId}/temperature", "factory/line1/+/status"),
                registry.fields(
                        registry.field("url", "string", "Broker URL", false, "tcp://127.0.0.1:1883", null, "connection"),
                        registry.field("brokerUrl", "string", "Broker URL alias", false, "tcp://127.0.0.1:1883", null, "connection"),
                        registry.field("host", "string", "Broker host", false, "127.0.0.1", null, "connection"),
                        registry.field("port", "number", "Broker port", false, "1883", null, "connection"),
                        registry.field("clientId", "string", "Client ID", true, "device_mqtt", null, "connection"),
                        registry.field("version", "select", "MQTT version", true, "v5", List.of("v5", "v3"), "connection"),
                        registry.field("username", "string", "Username", false, "", null, "security"),
                        registry.field("password", "password", "Password", false, "", null, "security"),
                        registry.field("sslEnabled", "boolean", "Enable SSL", false, "false",
                                List.of("true", "false"), "security"),
                        registry.field("subscribeTopics", "string", "Default subscribe topics", false,
                                "devices/${deviceId}/#", null, "topic"),
                        registry.field("subscribeQos", "select", "Default subscribe QoS", false, "1",
                                List.of("0", "1", "2"), "topic"),
                        registry.field("publishTopic", "string", "Default publish topic", false,
                                "devices/${deviceId}/data", null, "topic"),
                        registry.field("publishQos", "select", "Publish QoS", false, "1",
                                List.of("0", "1", "2"), "topic"),
                        registry.field("retained", "boolean", "Retained publish flag", false, "false",
                                List.of("true", "false"), "topic"),
                        registry.field("cleanSession", "boolean", "Clean session", false, "true",
                                List.of("true", "false"), "advanced"),
                        registry.field("autoReconnect", "boolean", "Auto reconnect", false, "true",
                                List.of("true", "false"), "advanced"),
                        registry.field("connectTimeout", "number", "Connect timeout (ms)", false, "10000", null, "advanced"),
                        registry.field("heartbeatInterval", "number", "Heartbeat interval (ms)", false, "60000", null, "advanced"),
                        registry.field("readTimeout", "number", "Read timeout (ms)", false, "5000", null, "advanced"),
                        registry.field("sessionExpiryInterval", "number", "Session expiry interval (s)", false, "86400", null, "advanced"),
                        registry.field("receiveMaximum", "number", "Receive maximum", false, "65535", null, "advanced"),
                        registry.field("willTopic", "string", "Will topic", false, "", null, "topic"),
                        registry.field("willMessage", "string", "Will message", false, "", null, "topic"),
                        registry.field("willQos", "select", "Will QoS", false, "0",
                                List.of("0", "1", "2"), "topic"),
                        registry.field("willRetained", "boolean", "Will retained flag", false, "false",
                                List.of("true", "false"), "topic"),
                        registry.field("authTopic", "string", "Auth topic", false, "", null, "security"),
                        registry.field("messageProperties", "object", "MQTT v5 message properties", false, "{}", null, "advanced"),
                        registry.field("maxPendingMessages", "number", "Max pending messages", false, "5000", null, "advanced"),
                        registry.field("dispatchBatchSize", "number", "Dispatch batch size", false, "1", null, "advanced"),
                        registry.field("dispatchFlushInterval", "number", "Dispatch flush interval (ms)", false, "0", null, "advanced"),
                        registry.field("overflowStrategy", "select", "Overflow strategy", false, "BLOCK",
                                List.of("BLOCK", "DROP_LATEST", "DROP_OLDEST"), "advanced"),
                        registry.field("productKey", "string", "Product key", false, "", null, "security"),
                        registry.field("deviceSecret", "password", "Device secret", false, "", null, "security"),
                        registry.field("authParams", "object", "Extended auth params", false, "{}", null, "security")))
                .withPointFields(pointFields(registry)));

        registry.registerAlias("MQTT_SSL", "MQTT", cfg -> {
            cfg.setSslEnabled(true);
            ProtocolDescriptorRegistry.applyDefaultPort(cfg, 8883);
        });
    }

    private List<ProtocolFieldConfig> pointFields(ProtocolDescriptorRegistry registry) {
        return List.of(
                registry.pointField("additionalConfig.topic", "string", "Topic", false, "",
                        Collections.emptyList(), "MQTT subscribe topic. When empty, address is used as the topic.", null),
                registry.pointField("additionalConfig.writeTopic", "string", "Write topic", false, "",
                        Collections.emptyList(), "MQTT topic used for point writes or command publishes.", null),
                registry.pointField("additionalConfig.qos", "select", "QoS", false, "",
                        List.of("0", "1", "2"), "MQTT quality of service level.", null),
                registry.pointField("additionalConfig.retain", "boolean", "Retain", false, "",
                        List.of("true", "false"), "Whether writes or publishes should use MQTT retain.", null),
                registry.pointField("additionalConfig.jsonPath", "string", "JSONPath", false, "",
                        Collections.emptyList(), "Path used to extract the target value from a JSON payload.", null),
                registry.pointField("additionalConfig.payloadEncoding", "select", "Payload encoding", false, "",
                        List.of("JSON", "PLAIN_TEXT", "BASE64", "HEX"), "Decoder used for subscribed payloads.", null),
                registry.pointField("additionalConfig.charset", "string", "Charset", false, "UTF-8",
                        Collections.emptyList(), "Charset used for text payload decoding.", null),
                registry.pointField("additionalConfig.publishTemplate", "textarea", "Publish template", false, "",
                        Collections.emptyList(), "Template used to build MQTT payloads for point writes.", null)
        );
    }
}

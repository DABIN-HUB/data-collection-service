package com.wangbin.collector.core.collector.protocol.mqtt;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.config.validator.MqttConfigurationContract;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttConfigurationAndPointRegressionTest {

    @Test
    void versionAliasesAndQosBoundariesAreExplicit() {
        for (String alias : List.of("v3", "3", "3.1.1", "mqtt3")) {
            assertEquals("v3", MqttConfigurationContract.version(alias));
            assertEquals(MqttProtocolVersion.V3, MqttProtocolVersion.fromText(alias));
        }
        for (String alias : List.of("v5", "5")) {
            assertEquals("v5", MqttConfigurationContract.version(alias));
            assertEquals(MqttProtocolVersion.V5, MqttProtocolVersion.fromText(alias));
        }
        assertEquals("v5", MqttConfigurationContract.version(null));
        assertEquals(1, MqttConfigurationContract.qos(null, 1, "subscribeQos"));
        for (int qos = 0; qos <= 2; qos++) {
            assertEquals(qos, MqttConfigurationContract.qos(String.valueOf(qos), 1, "subscribeQos"));
        }
        for (String invalid : List.of("v4", "mqtt6", "TEST", "mqtt5.1")) {
            assertThrows(IllegalArgumentException.class, () -> MqttConfigurationContract.version(invalid));
        }
        for (String invalid : List.of("-1", "3", "abc", "1.5")) {
            assertThrows(IllegalArgumentException.class,
                    () -> MqttConfigurationContract.qos(invalid, 1, "subscribeQos"));
        }
    }

    @Test
    void brokerUrlAliasAndHostFallbackRequireConsistentTlsAndUrl() {
        DeviceConnection canonical = connection();
        canonical.setUrl("tcp://127.0.0.1:1883");
        assertEquals("tcp://127.0.0.1:1883", MqttConfigurationContract.brokerUri(canonical));

        DeviceConnection legacy = connection();
        legacy.setExtJson(Map.of("brokerUrl", "ssl://127.0.0.1:8883"));
        legacy.setSslEnabled(true);
        assertEquals("ssl://127.0.0.1:8883", MqttConfigurationContract.brokerUri(legacy));

        DeviceConnection host = connection();
        host.setHost("localhost");
        host.setPort(1883);
        assertEquals("tcp://localhost:1883", MqttConfigurationContract.brokerUri(host));
        host.setSslEnabled(true);
        assertEquals("ssl://localhost:1883", MqttConfigurationContract.brokerUri(host));

        canonical.setExtJson(Map.of("brokerUrl", "tcp://other:1883"));
        assertThrows(IllegalArgumentException.class, () -> MqttConfigurationContract.brokerUri(canonical));
        legacy.setSslEnabled(false);
        assertThrows(IllegalArgumentException.class, () -> MqttConfigurationContract.brokerUri(legacy));
        assertThrows(IllegalArgumentException.class, () -> MqttConfigurationContract.brokerUri(connection()));
    }

    @Test
    void subscribeTopicsStringAndCollectionEachResolveDeviceTemplate() {
        DeviceConnection config = connection();
        config.setUrl("tcp://127.0.0.1:1883");
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("subscribeQos", "2");
        ext.put("subscribeTopics", " devices/${deviceId}/#, factory/{device_id}/+ ");
        config.setExtJson(ext);
        MqttConfigurationContract.validate(config, "device-1");
        ext.put("subscribeTopics", List.of("devices/${deviceId}/#", "factory/{device_id}/+"));
        MqttConfigurationContract.validate(config, "device-1");
        ext.put("subscribeTopics", Set.of("devices/${deviceId}/#", "factory/{device_id}/+"));
        MqttConfigurationContract.validate(config, "device-1");
        ext.put("subscribeTopics", List.of("devices/${unknown}/#"));
        assertThrows(IllegalArgumentException.class,
                () -> MqttConfigurationContract.validate(config, "device-1"));
        ext.put("subscribeTopics", List.of("devices/${deviceId}/#/invalid"));
        assertThrows(IllegalArgumentException.class,
                () -> MqttConfigurationContract.validate(config, "device-1"));
        ext.put("subscribeTopics", List.of("devices/${deviceId}/#"));
        assertThrows(IllegalArgumentException.class,
                () -> MqttConfigurationContract.validate(config, null));
    }

    @Test
    void pointOptionsResolveReadAndWriteTopicsAndDefaults() {
        DataPoint point = point("devices/${deviceId}/{pointCode}", "RW");
        point.setAdditionalConfig(Map.of("topic", "telemetry/${deviceId}/+",
                "writeTopic", "commands/{device_id}/${pointId}", "qos", "2",
                "retain", "true", "payloadEncoding", "JSON", "jsonPath", "$.value",
                "charset", "UTF-8", "publishTemplate", "{\"value\":${value}}"));
        MqttPointOptions options = MqttPointOptions.from(point, 1, "device-1");
        assertEquals("telemetry/device-1/+", options.getTopic());
        assertEquals("commands/device-1/point-1", options.getWriteTopic());
        assertEquals(2, options.getQos());
        assertTrue(options.isRetain());
        assertEquals("JSON", options.getPayloadEncoding());
        assertEquals("$.value", options.getJsonPath());
        assertEquals(StandardCharsets.UTF_8, options.getCharset());
        assertEquals("{\"value\":${value}}", options.getPublishTemplate());

        DataPoint defaultPoint = point("devices/${deviceId}/temperature", "R");
        MqttPointOptions defaults = MqttPointOptions.from(defaultPoint, 1, "device-1");
        assertEquals("devices/device-1/temperature", defaults.getTopic());
        assertEquals(defaults.getTopic(), defaults.getWriteTopic());
        assertEquals(1, defaults.getQos());
        assertFalse(defaults.isRetain());
        assertEquals("AUTO_COMPAT", defaults.getPayloadEncoding());
    }

    @Test
    void writableWildcardRequiresConcreteWriteTopic() {
        DataPoint writable = point("devices/+/temperature", "RW");
        assertThrows(IllegalArgumentException.class, () -> MqttPointOptions.from(writable, 1, "dev"));
        writable.setAdditionalConfig(Map.of("writeTopic", "commands/${deviceId}/temperature"));
        assertEquals("commands/dev/temperature", MqttPointOptions.from(writable, 1, "dev").getWriteTopic());
        writable.setAdditionalConfig(Map.of("writeTopic", "commands/#"));
        assertThrows(IllegalArgumentException.class, () -> MqttPointOptions.from(writable, 1, "dev"));
    }

    @Test
    void pointOptionsRejectInvalidQosEncodingAndMissingTemplateValues() {
        DataPoint point = point("devices/${deviceId}/temperature", "R");
        point.setAdditionalConfig(Map.of("qos", "3"));
        assertThrows(IllegalArgumentException.class, () -> MqttPointOptions.from(point, 1, "dev"));
        point.setAdditionalConfig(Map.of("jsonPath", "$.value", "payloadEncoding", "HEX"));
        assertThrows(IllegalArgumentException.class, () -> MqttPointOptions.from(point, 1, "dev"));
        point.setAdditionalConfig(Map.of("publishTemplate", "${unknown}"));
        assertThrows(IllegalArgumentException.class, () -> MqttPointOptions.from(point, 1, "dev"));
        point.setAdditionalConfig(Map.of());
        assertThrows(IllegalArgumentException.class, () -> MqttPointOptions.from(point, 1, null));
    }

    private static DeviceConnection connection() {
        return new DeviceConnection();
    }

    private static DataPoint point(String address, String readWrite) {
        DataPoint point = new DataPoint();
        point.setPointId("point-1");
        point.setPointCode("temperature");
        point.setAddress(address);
        point.setReadWrite(readWrite);
        return point;
    }
}

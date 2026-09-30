package com.wangbin.collector.core.collector.protocol.mqtt;

import com.wangbin.collector.core.config.validator.MqttConfigurationContract;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MqttTemplateContractRegressionTest {

    @Test
    void topicTemplateExpandsBothPlaceholderStylesAndEscapesReplacementText() {
        Map<String, Object> values = Map.of("deviceId", "dev$1\\test", "device_id", "legacy",
                "pointId", "point-1", "pointCode", "temp", "address", "raw");
        assertEquals("devices/dev$1\\test/legacy/temp/point-1/raw",
                ProtocolTemplateResolver.resolve("devices/${deviceId}/{device_id}/{pointCode}/${pointId}/{address}", values));
    }

    @Test
    void topicTemplateRejectsUnknownMissingAndMalformedPlaceholders() {
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolTemplateResolver.resolve("devices/${unknown}", Map.of("deviceId", "dev")));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolTemplateResolver.resolve("devices/${deviceId}", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolTemplateResolver.resolve("devices/{deviceId}", Map.of("deviceId", " ")));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolTemplateResolver.resolve("devices/${deviceId", Map.of("deviceId", "dev")));
    }

    @Test
    void payloadTemplateAcceptsOnlyExplicitlyAllowedValues() {
        Set<String> allowed = Set.of("value", "pointId", "pointCode", "deviceId", "timestamp");
        assertEquals("{\"value\":42,\"device\":dev}",
                MqttConfigurationContract.resolveTemplate("{\"value\":${value},\"device\":{deviceId}}",
                        Map.of("value", 42, "deviceId", "dev"), allowed));
        assertThrows(IllegalArgumentException.class,
                () -> MqttConfigurationContract.resolveTemplate("${address}", Map.of("address", "raw"), allowed));
        assertThrows(IllegalArgumentException.class,
                () -> MqttConfigurationContract.resolveTemplate("${value}", Map.of(), allowed));
    }
}

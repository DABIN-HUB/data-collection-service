package com.wangbin.collector.common.domain.entity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceConnectionTest {

    @Test
    void shouldAcceptUrlForNetworkConnection() {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("HTTP");
        connection.setUrl("https://example.com/api");

        assertTrue(connection.isValid());
    }

    @Test
    void shouldRejectNetworkConnectionWithoutUrlOrPort() {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("MQTT");
        connection.setHost("127.0.0.1");

        assertFalse(connection.isValid());
    }

    @Test
    void shouldRejectOpcDaHttpBridgeWithoutBridgeUrl() {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("OPC_DA");
        connection.setExtJson(ext("bridgeMode", "HTTP"));

        assertFalse(connection.isValid());
    }

    @Test
    void shouldAcceptOpcDaHttpBridgeWithBridgeUrl() {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("OPC_DA");
        connection.setExtJson(ext("bridgeMode", "HTTP", "bridgeBaseUrl", "http://127.0.0.1:8080"));

        assertTrue(connection.isValid());
    }

    @Test
    void deviceIdentityShouldRoundTripCanonicalAndLegacyNames() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        DeviceInfo device = mapper.readValue("{\"id\":\"historic-device\"}", DeviceInfo.class);
        org.junit.jupiter.api.Assertions.assertEquals("historic-device", device.getDeviceId());
        com.fasterxml.jackson.databind.JsonNode output = mapper.readTree(mapper.writeValueAsString(device));
        org.junit.jupiter.api.Assertions.assertEquals("historic-device", output.path("deviceId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("historic-device", output.path("id").asText());
        org.junit.jupiter.api.Assertions.assertEquals("historic-device", mapper.readValue(
                "{\"deviceId\":\"historic-device\",\"id\":\"historic-device\"}", DeviceInfo.class).getDeviceId());
    }

    @Test
    void conflictingIdentityAliasesShouldFailInEitherOrder() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String payload : java.util.List.of("{\"id\":\"A\",\"deviceId\":\"B\"}",
                "{\"deviceId\":\"B\",\"id\":\"A\"}")) {
            org.junit.jupiter.api.Assertions.assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> mapper.readValue(payload, DeviceInfo.class));
        }
    }

    private Map<String, Object> ext(Object... entries) {
        Map<String, Object> extJson = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            extJson.put(entries[i].toString(), entries[i + 1]);
        }
        return extJson;
    }
}

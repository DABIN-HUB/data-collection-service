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

    private Map<String, Object> ext(Object... entries) {
        Map<String, Object> extJson = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            extJson.put(entries[i].toString(), entries[i + 1]);
        }
        return extJson;
    }
}

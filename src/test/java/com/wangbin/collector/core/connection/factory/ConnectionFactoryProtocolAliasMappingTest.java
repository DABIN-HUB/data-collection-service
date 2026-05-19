package com.wangbin.collector.core.connection.factory;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.connection.adapter.CoapConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.HttpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.ModbusRtuConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.MqttConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.SnmpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.WebSocketConnectionAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionFactoryProtocolAliasMappingTest {

    private final ConnectionFactory factory = new ConnectionFactory();

    @Test
    void shouldMapHttpsAliasToHttpAdapterWithSslDefaults() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(HttpConnectionAdapter.class, factory.createConnection(device("dev-https", "HTTPS"), config));
        assertTrue(config.getSslEnabled());
        assertEquals(443, config.getPort());
    }

    @Test
    void shouldMapWebSocketSslAliasToWebSocketAdapterWithSslDefaults() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(WebSocketConnectionAdapter.class,
                factory.createConnection(device("dev-wss", "WEBSOCKET_SSL"), config));
        assertTrue(config.getSslEnabled());
        assertEquals(443, config.getPort());
    }

    @Test
    void shouldMapMqttSslAliasToMqttAdapterWithSslDefaults() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(MqttConnectionAdapter.class, factory.createConnection(device("dev-mqtts", "MQTT_SSL"), config));
        assertTrue(config.getSslEnabled());
        assertEquals(8883, config.getPort());
    }

    @Test
    void shouldMapCoapSslAliasToCoapAdapterWithSchemeDefaults() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(CoapConnectionAdapter.class, factory.createConnection(device("dev-coaps", "COAP_SSL"), config));
        assertTrue(config.getSslEnabled());
        assertEquals(5684, config.getPort());
        assertEquals("coaps", config.getExtJson().get("scheme"));
    }

    @Test
    void shouldMapSnmpVersionAliasToSnmpAdapterWithVersionDefaults() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(SnmpConnectionAdapter.class, factory.createConnection(device("dev-snmp-v3", "SNMP_V3"), config));
        assertEquals(161, config.getPort());
        assertEquals("3", config.getExtJson().get("snmpVersion"));
    }

    @Test
    void shouldMapModbusAsciiAliasToRtuAdapter() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(ModbusRtuConnectionAdapter.class,
                factory.createConnection(device("dev-modbus-ascii", "MODBUS_ASCII"), config));
    }

    private DeviceInfo device(String deviceId, String protocolType) {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId(deviceId);
        deviceInfo.setProtocolType(protocolType);
        deviceInfo.setIpAddress("127.0.0.1");
        return deviceInfo;
    }
}

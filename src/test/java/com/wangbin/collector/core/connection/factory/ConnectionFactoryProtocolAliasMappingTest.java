package com.wangbin.collector.core.connection.factory;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.connection.adapter.CoapConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.AdsConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.EtherNetIpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.HttpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.MqttConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Plc4xOpcUaConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Plc4xModbusRtuConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.S7ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.SnmpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.WebSocketConnectionAdapter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

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
        config.setExtJson(ext(
                "snmpSecurityName", "collector",
                "snmpSecurityLevel", "noAuthNoPriv"
        ));

        assertInstanceOf(SnmpConnectionAdapter.class, factory.createConnection(device("dev-snmp-v3", "SNMP_V3"), config));
        assertEquals(161, config.getPort());
        assertEquals("3", config.getExtJson().get("snmpVersion"));
    }

    @Test
    void shouldMapModbusAsciiAliasToPlc4xRtuAdapter() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(Plc4xModbusRtuConnectionAdapter.class,
                factory.createConnection(device("dev-modbus-ascii", "MODBUS_ASCII"), config));
    }

    @Test
    void shouldMapS7AliasToAdapterWithDefaultPort() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(S7ConnectionAdapter.class, factory.createConnection(device("dev-s7", "S7"), config));
        assertEquals(102, config.getPort());
    }

    @Test
    void shouldMapLogixAliasToEtherNetIpAdapterWithDefaultPort() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(EtherNetIpConnectionAdapter.class,
                factory.createConnection(device("dev-logix", "LOGIX"), config));
        assertEquals(44818, config.getPort());
    }

    @Test
    void shouldMapAmsAliasToAdsAdapterWithDefaultPort() {
        DeviceConnection config = new DeviceConnection();
        config.setExtJson(ext(
                "targetAmsNetId", "1.2.3.4.1.1",
                "targetAmsPort", 851,
                "sourceAmsNetId", "1.2.3.4.1.2",
                "sourceAmsPort", 30000
        ));

        assertInstanceOf(AdsConnectionAdapter.class,
                factory.createConnection(device("dev-ads", "AMS"), config));
        assertEquals(48898, config.getPort());
    }

    @Test
    void shouldMapPlc4xOpcUaAliasToAdapterWithDefaultPort() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(Plc4xOpcUaConnectionAdapter.class,
                factory.createConnection(device("dev-opcua-plc4x", "OPCUA_PLC4X"), config));
        assertEquals(4840, config.getPort());
    }

    @Test
    void shouldMapOpcUaPrimaryProtocolToPlc4xAdapterWithDefaultPort() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(Plc4xOpcUaConnectionAdapter.class,
                factory.createConnection(device("dev-opcua", "OPC_UA"), config));
        assertEquals(4840, config.getPort());
    }

    private DeviceInfo device(String deviceId, String protocolType) {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId(deviceId);
        deviceInfo.setProtocolType(protocolType);
        deviceInfo.setIpAddress("127.0.0.1");
        return deviceInfo;
    }

    private Map<String, Object> ext(Object... entries) {
        Map<String, Object> extJson = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            extJson.put(entries[i].toString(), entries[i + 1]);
        }
        return extJson;
    }
}

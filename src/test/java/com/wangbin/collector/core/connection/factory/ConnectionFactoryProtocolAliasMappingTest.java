package com.wangbin.collector.core.connection.factory;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorRegistry;
import com.wangbin.collector.core.connection.adapter.CoapConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.CustomTcpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.CustomUdpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.AdsConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.BacnetIpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.EtherNetIpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.HttpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.KnxNetIpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.MqttConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.OpcUaConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Plc4xOpcUaConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Plc4xModbusRtuConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.Plc4xModbusTcpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.S7ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.SnmpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.TcpConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.WebSocketConnectionAdapter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionFactoryProtocolAliasMappingTest {

    private final ConnectionFactory factory = new ConnectionFactory(new ProtocolDescriptorRegistry());

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
    void shouldPreferModbusTcpProtocolOverGenericTcpConnectionType() {
        DeviceInfo deviceInfo = device("dev-modbus-tcp", "MODBUS_TCP");
        deviceInfo.setConnectionType("TCP");
        DeviceConnection config = new DeviceConnection();
        config.setConnectionType("MODBUS_TCP");

        assertInstanceOf(Plc4xModbusTcpConnectionAdapter.class,
                factory.createConnection(deviceInfo, config));
        assertEquals(502, config.getPort());
    }

    @Test
    void shouldFallbackToGenericConnectionTypeForUnregisteredProtocol() {
        DeviceInfo deviceInfo = device("dev-custom", "VENDOR_PRIVATE_PROTOCOL");
        deviceInfo.setConnectionType("TCP");

        assertInstanceOf(TcpConnectionAdapter.class,
                factory.createConnection(deviceInfo, new DeviceConnection()));
    }

    @Test
    void shouldCreateIndependentCustomTransportAdapters() {
        DeviceConnection tcpConfig = new DeviceConnection();
        tcpConfig.setHost("127.0.0.1");
        tcpConfig.setPort(19001);
        DeviceConnection udpConfig = new DeviceConnection();
        udpConfig.setHost("127.0.0.1");
        udpConfig.setPort(19002);

        assertInstanceOf(CustomTcpConnectionAdapter.class,
                factory.createConnection(device("dev-custom-tcp", "CUSTOM_TCP"), tcpConfig));
        assertInstanceOf(CustomUdpConnectionAdapter.class,
                factory.createConnection(device("dev-custom-udp", "CUSTOM_UDP"), udpConfig));
    }

    @Test
    void shouldMapS7AliasToAdapterWithDefaultPort() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(S7ConnectionAdapter.class, factory.createConnection(device("dev-s7", "S7"), config));
        assertEquals(102, config.getPort());
    }

    @Test
    void shouldMapBacnetAliasToAdapterWithDefaultPort() {
        DeviceConnection config = new DeviceConnection();
        config.setExtJson(ext("remoteDeviceInstance", 1001));

        assertInstanceOf(BacnetIpConnectionAdapter.class,
                factory.createConnection(device("dev-bacnet", "BACNET/IP"), config));
        assertEquals(47808, config.getPort());
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
    void shouldMapKnxAliasToKnxNetIpAdapterWithDefaultPort() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(KnxNetIpConnectionAdapter.class,
                factory.createConnection(device("dev-knx", "KNX"), config));
        assertEquals(3671, config.getPort());
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

    @Test
    void shouldMapMiloOpcUaProtocolToIndependentAdapter() {
        DeviceConnection config = new DeviceConnection();

        assertInstanceOf(OpcUaConnectionAdapter.class,
                factory.createConnection(device("dev-opcua-milo", "OPCUA_MILO"), config));
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

package com.wangbin.collector.core.config.validator;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolConnectionValidatorTest {

    private final ProtocolConnectionValidator validator = new ProtocolConnectionValidator();

    @Test
    void shouldAcceptUrlForNetworkProtocols() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUrl("tcp://127.0.0.1:1883");

        assertDoesNotThrow(() -> validator.validate(device("dev-mqtt", "MQTT"), connection));
    }

    @Test
    void shouldRejectNetworkProtocolWithoutUrlOrHostPort() {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");

        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-mqtt", "MQTT"), connection));
    }

    @Test
    void shouldRejectOpcUaWithoutEndpoint() {
        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-opcua", "OPC_UA"), new DeviceConnection()));
    }

    @Test
    void shouldRequireOpcUaUsernameWhenUsernameAuthIsSelected() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUrl("opc.tcp://127.0.0.1:4840");
        connection.setExtJson(ext("authType", "USERNAME"));

        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-opcua", "OPC_UA"), connection));
    }

    @Test
    void shouldRequireOpcDaBridgeUrlInHttpBridgeMode() {
        DeviceConnection connection = new DeviceConnection();
        connection.setExtJson(ext("bridgeMode", "HTTP"));

        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-opcda", "OPC_DA"), connection));
    }

    @Test
    void shouldRequireSnmpV3SecurityFields() {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");
        connection.setExtJson(ext("snmpVersion", "3"));

        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-snmp", "SNMP"), connection));
    }

    @Test
    void shouldAcceptSnmpV3NoAuthNoPrivWithSecurityName() {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");
        connection.setExtJson(ext(
                "snmpVersion", "3",
                "snmpSecurityName", "collector",
                "snmpSecurityLevel", "noAuthNoPriv"
        ));

        assertDoesNotThrow(() -> validator.validate(device("dev-snmp", "SNMP"), connection));
    }

    private DeviceInfo device(String deviceId, String protocolType) {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId(deviceId);
        deviceInfo.setProtocolType(protocolType);
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

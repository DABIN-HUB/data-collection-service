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
    void shouldRequireOpcUaPasswordWhenUsernameIsConfigured() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUrl("opc.tcp://127.0.0.1:4840");
        connection.setExtJson(ext("username", "collector"));

        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-opcua", "OPC_UA"), connection));
    }

    @Test
    void shouldRequireOpcUaKeyStoreForSecurePolicy() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUrl("opc.tcp://127.0.0.1:4840");
        connection.setExtJson(ext("securityPolicy", "Basic256Sha256"));

        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-opcua", "OPC_UA"), connection));
    }

    @Test
    void shouldAcceptOpcUaWithEndpointAndUsernamePassword() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUrl("opc.tcp://127.0.0.1:4840");
        connection.setExtJson(ext(
                "username", "collector",
                "password", "secret"
        ));

        assertDoesNotThrow(() -> validator.validate(device("dev-opcua", "OPC_UA"), connection));
    }

    @Test
    void shouldRejectOpcUaTrustAllCompatibilityFlag() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUrl("opc.tcp://127.0.0.1:4840");
        connection.setExtJson(ext("trustAllServerCert", true));

        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-opcua", "OPC_UA"), connection));
    }

    @Test
    void shouldAcceptOpcUaRawConnectionStringWithoutEndpointFields() {
        DeviceConnection connection = new DeviceConnection();
        connection.setExtJson(ext("plc4xConnectionString", "opcua:tcp://127.0.0.1:4840"));

        assertDoesNotThrow(() -> validator.validate(device("dev-opcua", "OPC_UA"), connection));
    }

    @Test
    void shouldRejectPlc4xOpcUaWithoutEndpoint() {
        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-opcua-plc4x", "OPC_UA_PLC4X"), new DeviceConnection()));
    }

    @Test
    void shouldRequirePlc4xOpcUaPasswordWhenUsernameIsConfigured() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUrl("opc.tcp://127.0.0.1:4840");
        connection.setExtJson(ext("username", "collector"));

        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-opcua-plc4x", "OPC_UA_PLC4X"), connection));
    }

    @Test
    void shouldRequirePlc4xOpcUaKeyStoreForSecurePolicy() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUrl("opc.tcp://127.0.0.1:4840");
        connection.setExtJson(ext("securityPolicy", "Basic256Sha256"));

        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-opcua-plc4x", "OPC_UA_PLC4X"), connection));
    }

    @Test
    void shouldAcceptPlc4xOpcUaWithEndpointAndUsernamePassword() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUrl("opc.tcp://127.0.0.1:4840");
        connection.setExtJson(ext(
                "username", "collector",
                "password", "secret"
        ));

        assertDoesNotThrow(() -> validator.validate(device("dev-opcua-plc4x", "OPC_UA_PLC4X"), connection));
    }

    @Test
    void shouldAcceptPlc4xOpcUaUsernameAuthWithAuthParams() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUrl("opc.tcp://127.0.0.1:4840");
        connection.setExtJson(ext("authType", "USERNAME"));

        Map<String, String> authParams = new LinkedHashMap<>();
        authParams.put("username", "collector");
        authParams.put("password", "secret");
        connection.setAuthParams(authParams);

        assertDoesNotThrow(() -> validator.validate(device("dev-opcua-plc4x", "OPC_UA_PLC4X"), connection));
    }

    @Test
    void shouldAcceptPlc4xOpcUaCertAuthWithClientCertAlias() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUrl("opc.tcp://127.0.0.1:4840");
        connection.setExtJson(ext(
                "authType", "CERT",
                "clientCertPath", "client.p12"
        ));

        assertDoesNotThrow(() -> validator.validate(device("dev-opcua-plc4x", "OPC_UA_PLC4X"), connection));
    }

    @Test
    void shouldRejectPlc4xOpcUaTrustAllCompatibilityFlag() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUrl("opc.tcp://127.0.0.1:4840");
        connection.setExtJson(ext("trustAllServerCert", true));

        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-opcua-plc4x", "OPC_UA_PLC4X"), connection));
    }

    @Test
    void shouldAcceptPlc4xOpcUaRawConnectionStringWithoutEndpointFields() {
        DeviceConnection connection = new DeviceConnection();
        connection.setExtJson(ext("plc4xConnectionString", "opcua:tcp://127.0.0.1:4840"));

        assertDoesNotThrow(() -> validator.validate(device("dev-opcua-plc4x", "OPC_UA_PLC4X"), connection));
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

    @Test
    void shouldAcceptKnxNetIpWithHostOnly() {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");

        assertDoesNotThrow(() -> validator.validate(device("dev-knx", "KNXNET_IP"), connection));
    }

    @Test
    void shouldAcceptKnxNetIpWithRawConnectionStringOnly() {
        DeviceConnection connection = new DeviceConnection();
        connection.setExtJson(ext("plc4xConnectionString", "knxnet-ip://127.0.0.1"));

        assertDoesNotThrow(() -> validator.validate(device("dev-knx", "KNXNET_IP"), connection));
    }

    @Test
    void shouldRejectKnxNetIpWithInvalidGroupAddressLevels() {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");
        connection.setExtJson(ext("groupAddressNumLevels", 0));

        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-knx", "KNXNET_IP"), connection));
    }

    @Test
    void shouldRejectKnxNetIpWithInvalidConnectionType() {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");
        connection.setExtJson(ext("knxConnectionType", "TUNNEL"));

        assertThrows(CollectorException.class,
                () -> validator.validate(device("dev-knx", "KNXNET_IP"), connection));
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

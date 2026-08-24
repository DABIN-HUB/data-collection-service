package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Plc4xOpcUaConnectionAdapterTest {

    @Test
    void shouldBuildConnectionStringFromCompatibilityFields() {
        DeviceConnection connection = new DeviceConnection();
        connection.setUrl("opc.tcp://127.0.0.1:4840");
        connection.setExtJson(ext(
                "authType", "USERNAME",
                "securityPolicy", "http://opcfoundation.org/UA/SecurityPolicy#Basic256Sha256",
                "securityMode", "SignAndEncrypt",
                "requestTimeoutMs", 7000,
                "connectTimeoutMs", 9000
        ));

        Map<String, String> authParams = new LinkedHashMap<>();
        authParams.put("username", "collector");
        authParams.put("password", "secret");
        connection.setAuthParams(authParams);

        Plc4xOpcUaConnectionAdapter adapter = new Plc4xOpcUaConnectionAdapter(device(), connection);
        String connectionString = ReflectionTestUtils.invokeMethod(adapter, "buildConnectionString");

        assertTrue(connectionString.startsWith("opcua:tcp://127.0.0.1:4840"));
        assertTrue(connectionString.contains("username=collector"));
        assertTrue(connectionString.contains("password=secret"));
        assertTrue(connectionString.contains("security-policy=Basic256Sha256"));
        assertTrue(connectionString.contains("message-security=SIGN_ENCRYPT"));
        assertTrue(connectionString.contains("request-timeout=7000"));
        assertTrue(connectionString.contains("negotiation-timeout=9000"));
    }

    @Test
    void shouldMapClientCertificateAliasesToKeyStoreOptions() {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");
        connection.setPort(4840);
        connection.setExtJson(ext(
                "authType", "CERT",
                "clientCertPath", "client.p12",
                "clientCertPassword", "changeit"
        ));

        Plc4xOpcUaConnectionAdapter adapter = new Plc4xOpcUaConnectionAdapter(device(), connection);
        String connectionString = ReflectionTestUtils.invokeMethod(adapter, "buildConnectionString");

        assertTrue(connectionString.startsWith("opcua:tcp://127.0.0.1:4840"));
        assertTrue(connectionString.contains("key-store-file=client.p12"));
        assertTrue(connectionString.contains("key-store-password=changeit"));
    }

    @Test
    void shouldReturnRawPlc4xConnectionStringWhenProvided() {
        DeviceConnection connection = new DeviceConnection();
        connection.setExtJson(ext("plc4xConnectionString", "opcua:tcp://plc:4840?request-timeout=5000"));

        Plc4xOpcUaConnectionAdapter adapter = new Plc4xOpcUaConnectionAdapter(device(), connection);
        String connectionString = ReflectionTestUtils.invokeMethod(adapter, "buildConnectionString");

        assertEquals("opcua:tcp://plc:4840?request-timeout=5000", connectionString);
    }

    private DeviceInfo device() {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-opcua-plc4x");
        deviceInfo.setProtocolType("OPC_UA_PLC4X");
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

package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.apache.plc4x.java.s7.readwrite.configuration.S7TcpTransportConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S7ConnectionAdapterTest {

    @Test
    void shouldBuildConnectionStringWithAdvancedRouteParameters() {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");
        connection.setPort(102);
        connection.setExtJson(ext(
                "controllerType", "S7_1500",
                "remoteRack2", 1,
                "remoteSlot2", 3,
                "remoteDeviceGroup2", "os",
                "maxAmqCaller", 8,
                "maxAmqCallee", 4
        ));

        S7ConnectionAdapter adapter = new S7ConnectionAdapter(device(), connection);
        String connectionString = ReflectionTestUtils.invokeMethod(adapter, "buildConnectionString");

        assertTrue(connectionString.contains("controller-type=S7_1500"));
        assertTrue(connectionString.contains("remote-rack2=1"));
        assertTrue(connectionString.contains("remote-slot2=3"));
        assertTrue(connectionString.contains("remote-device-group2=OS"));
        assertTrue(connectionString.contains("max-amq-caller=8"));
        assertTrue(connectionString.contains("max-amq-callee=4"));
    }

    @Test
    void shouldUseOnlyExplicitS7UriWhenOverrideIsPresent() {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("ignored-host");
        connection.setPort(-1);
        connection.setExtJson(ext("plc4xConnectionString", "s7://plc.example:102?controller-type=S7_1500&ping=false",
                "controllerType", "INVALID", "rack", -1, "retryTime", 5, "ping", true));

        assertEquals("s7://plc.example:102?controller-type=S7_1500&ping=false",
                ReflectionTestUtils.invokeMethod(new S7ConnectionAdapter(device(), connection), "buildConnectionString"));
    }

    @Test
    void shouldPreserveExplicitS7UriWithoutPortForPlc4xDefaultPort() {
        assertEquals(102, new S7TcpTransportConfiguration().getDefaultPort());
        DeviceConnection connection = new DeviceConnection();
        connection.setPort(-1);
        connection.setExtJson(ext("plc4xConnectionString", "s7://plc.example?controller-type=S7_1500",
                "rack", -1));

        assertEquals("s7://plc.example?controller-type=S7_1500",
                ReflectionTestUtils.invokeMethod(new S7ConnectionAdapter(device(), connection), "buildConnectionString"));
    }

    @Test
    void shouldRejectInvalidExplicitUriWithoutFallbackToGeneratedHost() {
        for (String uri : new String[]{"modbus-tcp://plc:102", "s7://:102", "s7://plc:", "s7://plc:0",
                "s7://plc:65536", "s7://plc:102/path", "s7://plc?retry-time=5",
                "s7://plc:102?retry-time=5",
                "s7://plc:102?retry%2Dtime=5", "s7://plc:102?bad=%GG"}) {
            DeviceConnection connection = new DeviceConnection();
            connection.setHost("127.0.0.1");
            connection.setExtJson(ext("plc4xConnectionString", uri));
            assertThrows(IllegalArgumentException.class,
                    () -> ReflectionTestUtils.invokeMethod(new S7ConnectionAdapter(device(), connection),
                            "buildConnectionString"), uri);
        }
    }

    @Test
    void shouldConvertMillisecondsOnlyForEnabledDriverPing() {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");
        connection.setReadTimeout(1501);

        String withoutPing = ReflectionTestUtils.invokeMethod(new S7ConnectionAdapter(device(), connection),
                "buildConnectionString");
        assertTrue(!withoutPing.contains("read-timeout="));
        connection.setExtJson(ext("ping", true));
        String withPing = ReflectionTestUtils.invokeMethod(new S7ConnectionAdapter(device(), connection),
                "buildConnectionString");
        assertTrue(withPing.contains("read-timeout=2"));
        assertTrue(withPing.contains("retry-time=0"));
    }

    @Test
    void shouldRejectInvalidGeneratedRouteInsteadOfUsingDefaults() {
        for (Map<String, Object> values : java.util.List.of(
                ext("rack", "not-a-number"), ext("slot", -1), ext("rack", 8), ext("slot", 32),
                ext("remoteRack2", 8), ext("remoteSlot2", 32),
                ext("pduSize", 0), ext("pduSize", 65536), ext("maxFieldsPerRequest", "bad"),
                ext("controllerType", "S7_200"), ext("controllerType", ""), ext("retryTime", 1))) {
            DeviceConnection connection = new DeviceConnection();
            connection.setHost("127.0.0.1");
            connection.setExtJson(values);
            assertThrows(IllegalArgumentException.class,
                    () -> ReflectionTestUtils.invokeMethod(new S7ConnectionAdapter(device(), connection),
                            "buildConnectionString"), values.toString());
        }
    }

    @Test
    void shouldNotExposeFullUriFromConnectionInformation() {
        DeviceConnection connection = new DeviceConnection();
        connection.setExtJson(ext("plc4xConnectionString", "s7://plc.example:102?secret=placeholder"));
        S7ConnectionAdapter adapter = new S7ConnectionAdapter(device(), connection);
        ReflectionTestUtils.setField(adapter, "connectionString", "s7://plc.example:102?secret=placeholder");

        assertEquals("s7://[已隐藏]", adapter.getConnectionString());
        assertTrue(!adapter.getConnectionParams().containsKey("connectionString"));
    }

    private DeviceInfo device() {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-s7");
        deviceInfo.setProtocolType("SIEMENS_S7");
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
package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.apache.plc4x.java.eip.base.configuration.EIPConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtherNetIpConnectionAdapterTest {

    @Test
    void shouldUseOnlyExplicitLogixConnectionString() {
        DeviceConnection connection = new DeviceConnection();
        connection.setPort(-1);
        connection.setExtJson(ext("plc4xConnectionString", "logix:tcp://plc.example:44818?big-endian=false",
                "communicationPath", "[", "backplane", -1));
        assertEquals("logix:tcp://plc.example:44818?big-endian=false",
                ReflectionTestUtils.invokeMethod(adapter(connection), "buildConnectionString"));
        for (String invalid : new String[]{"s7://plc", "modbus://plc", "abc", "logix:tcp://plc:0"}) {
            connection.setExtJson(ext("plc4xConnectionString", invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> ReflectionTestUtils.invokeMethod(adapter(connection), "buildConnectionString"));
        }
    }

    @Test
    void shouldBuildValidatedAutoRouteAndDefaults() {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("plc.example");
        assertTrue(new EIPConfiguration().getByteOrder() != null);
        String generated = ReflectionTestUtils.invokeMethod(adapter(connection), "buildConnectionString");
        assertTrue(generated.startsWith("logix:tcp://plc.example:44818?"));
        assertTrue(generated.contains("communication-path=1,0"));
        assertTrue(generated.contains("tcp.default-timeout=5000"));
        assertFalse(generated.contains("communicationPath="));
        assertFalse(generated.contains("controller-type="));
        connection.setExtJson(ext("communicationPath", "[1,4,2,192.168.0.1,1,1]",
                "backplane", -1, "slot", -1));
        generated = ReflectionTestUtils.invokeMethod(adapter(connection), "buildConnectionString");
        assertTrue(generated.contains("communication-path=1,4,2,192.168.0.1,1,1"));
    }

    @Test
    void shouldNotExposeRawUriInStatus() {
        EtherNetIpConnectionAdapter adapter = adapter(new DeviceConnection());
        ReflectionTestUtils.setField(adapter, "connectionString", "logix:tcp://plc.example?secret=placeholder");
        assertEquals("logix:tcp://[已隐藏]", adapter.getConnectionString());
        assertFalse(adapter.getConnectionParams().containsKey("connectionString"));
    }

    private EtherNetIpConnectionAdapter adapter(DeviceConnection connection) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("dev-eip");
        device.setProtocolType("ETHERNET_IP");
        return new EtherNetIpConnectionAdapter(device, connection);
    }

    private Map<String, Object> ext(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(entries[index].toString(), entries[index + 1]);
        }
        return result;
    }
}

package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

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
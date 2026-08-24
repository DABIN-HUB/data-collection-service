package com.wangbin.collector.core.collector.protocol.bacnet;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacnetScCollectorTest {

    @Test
    void shouldExposeScStatus() throws Exception {
        BacnetScCollector collector = new BacnetScCollector();
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-bacnet-sc");
        deviceInfo.setProtocolType("BACNET_SC");
        collector.init(deviceInfo);

        Map<String, Object> status = collector.getDeviceStatus();

        assertEquals("BACNET_SC", status.get("protocol"));
        assertEquals(Boolean.TRUE, status.get("implemented"));
        assertEquals("WSS", status.get("transport"));
        assertTrue(String.valueOf(status.get("message")).contains("experimental"));
    }
}
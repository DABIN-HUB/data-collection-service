package com.wangbin.collector.core.collector.protocol.bacnet;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacnetMstpCollectorTest {

    @Test
    void shouldExposeMstpStatus() throws Exception {
        BacnetMstpCollector collector = new BacnetMstpCollector();
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-bacnet-mstp");
        deviceInfo.setProtocolType("BACNET_MSTP");
        collector.init(deviceInfo);

        Map<String, Object> status = collector.getDeviceStatus();

        assertEquals("BACNET_MSTP", status.get("protocol"));
        assertEquals(Boolean.TRUE, status.get("implemented"));
        assertEquals("MS/TP", status.get("transport"));
        assertTrue(String.valueOf(status.get("message")).contains("token-based"));
    }
}
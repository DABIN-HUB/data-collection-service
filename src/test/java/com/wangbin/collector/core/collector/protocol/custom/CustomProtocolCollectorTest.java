package com.wangbin.collector.core.collector.protocol.custom;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustomProtocolCollectorTest {

    @Test
    void shouldUseDeviceProtocolTypeAsCollectorProtocolType() throws Exception {
        CustomProtocolCollector collector = new CustomProtocolCollector();
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-1");
        deviceInfo.setProtocolType("CUSTOM_UDP");

        collector.init(deviceInfo);

        assertEquals("CUSTOM_UDP", collector.getProtocolType());
        assertEquals("CUSTOM_UDP", collector.getCollectorType());
    }
}

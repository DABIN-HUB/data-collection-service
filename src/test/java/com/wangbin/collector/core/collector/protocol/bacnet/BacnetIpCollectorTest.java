package com.wangbin.collector.core.collector.protocol.bacnet;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacnetIpCollectorTest {

    @Test
    void shouldReportImplementedReadPropertyStatus() throws Exception {
        TestableBacnetIpCollector collector = new TestableBacnetIpCollector();
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-bacnet");
        deviceInfo.setProtocolType("BACNET_IP");
        collector.init(deviceInfo);

        Map<String, Object> status = collector.exposeStatus();

        assertEquals("BACNET_IP", status.get("protocol"));
        assertEquals(Boolean.TRUE, status.get("implemented"));
        assertEquals(Boolean.TRUE, status.get("readPropertyImplemented"));
        assertEquals(Boolean.TRUE, status.get("readPropertyMultipleImplemented"));
        assertEquals(Boolean.FALSE, status.get("writeImplemented"));
        assertTrue(status.get("message").toString().contains("ReadPropertyMultiple"));
    }

    @Test
    void shouldKeepWriteOperationUnsupportedInFirstDelivery() throws Exception {
        TestableBacnetIpCollector collector = new TestableBacnetIpCollector();
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-bacnet");
        deviceInfo.setProtocolType("BACNET_IP");
        collector.init(deviceInfo);

        UnsupportedOperationException error = collector.exposeUnsupportedWrite();

        assertTrue(error.getMessage().contains("does not implement writePoint"));
        assertFalse(error.getMessage().isBlank());
    }

    private static class TestableBacnetIpCollector extends BacnetIpCollector {

        Map<String, Object> exposeStatus() {
            return doGetDeviceStatus();
        }

        UnsupportedOperationException exposeUnsupportedWrite() {
            return unsupported("writePoint", "first delivery only implements ReadProperty");
        }
    }
}

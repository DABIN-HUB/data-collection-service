package com.wangbin.collector.core.collector.protocol.bacnet;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacnetIpCollectorTest {

    @Test
    void shouldReportImplementedReadWriteAndSubscriptionStatus() throws Exception {
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
        assertEquals(Boolean.TRUE, status.get("writeImplemented"));
        assertEquals(Boolean.TRUE, status.get("subscriptionImplemented"));
        assertTrue(status.get("message").toString().contains("WriteProperty"));
    }

    @Test
    void shouldUseBacnetWriteConversionOverrideBeforeBaseConverter() {
        TestableBacnetIpCollector collector = new TestableBacnetIpCollector();

        assertEquals("123", collector.exposeConvertDataForWrite(point("STRING"), 123));
        assertEquals(Boolean.TRUE, collector.exposeConvertDataForWrite(point("BOOLEAN"), 1));
    }

    private static class TestableBacnetIpCollector extends BacnetIpCollector {

        Map<String, Object> exposeStatus() {
            return doGetDeviceStatus();
        }

        Object exposeConvertDataForWrite(DataPoint point, Object value) {
            return convertDataForWrite(point, value);
        }
    }

    private DataPoint point(String dataType) {
        DataPoint point = new DataPoint();
        point.setPointId("p1");
        point.setAddress("analogInput:1.presentValue");
        point.setDataType(dataType);
        return point;
    }
}

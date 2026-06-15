package com.wangbin.collector.core.collector.protocol.ethernetip;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import com.wangbin.collector.core.connection.adapter.EtherNetIpConnectionAdapter;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EtherNetIpCollectorTest {

    @Test
    void shouldRouteReadAndWriteCommandsThroughConfiguredPoints() throws Exception {
        ConfigManager configManager = mock(ConfigManager.class);
        DataPoint point = point("p1", "temperature", "MainProgram.Tag1", "RW");
        point.setAdditionalConfig(Map.of("reportField", "temp_report"));
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));

        TestableEtherNetIpCollector collector = new TestableEtherNetIpCollector();
        collector.readValue = 18;
        prepareCommandCollector(collector, configManager);

        @SuppressWarnings("unchecked")
        Map<String, Object> readResult = (Map<String, Object>) collector.executeCommand(
                "read", Map.of("pointRef", "temp_report"));
        assertEquals("p1", readResult.get("pointId"));
        assertEquals(18.0, readResult.get("value"));

        @SuppressWarnings("unchecked")
        Map<String, Object> writeResult = (Map<String, Object>) collector.executeCommand(
                "write", Map.of("pointCode", "temperature", "value", 30));
        assertEquals(true, writeResult.get("success"));
        assertEquals(30.0, collector.lastWriteValue);
    }

    @Test
    void shouldKeepSubscriptionUnsupportedForCurrentPlc4xLogixPath() throws Exception {
        EtherNetIpCollector collector = new EtherNetIpCollector();
        prepareCommandCollector(collector, mock(ConfigManager.class));

        CollectorException exception = assertThrows(CollectorException.class,
                () -> collector.subscribe(List.of(point("p1", "temperature", "MainProgram.Tag1", "R"))));
        assertEquals("点位订阅失败", exception.getMessage());

        Map<String, Object> status = collector.getDeviceStatus();
        assertFalse((Boolean) status.get("subscribable"));
    }

    @Test
    void shouldPassThroughArrayReadsAndWritesWithoutScalarProcessing() throws Exception {
        ConfigManager configManager = mock(ConfigManager.class);
        DataPoint point = point("p2", "temperatures", "Program:Main.ArrayTag:DINT[3]", "RW");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));

        TestableEtherNetIpCollector collector = new TestableEtherNetIpCollector();
        collector.readValue = List.of(11, 12, 13);
        prepareCommandCollector(collector, configManager);

        Object readResult = collector.readPoint(point);
        assertEquals(List.of(11, 12, 13), readResult);

        ProcessResult processResult = collector.getLatestProcessResult("p2");
        assertTrue(processResult.isSuccess());
        assertEquals(true, processResult.getMetadata("arrayValue"));
        assertEquals(Integer.valueOf(3), processResult.<Integer>getMetadata("arraySize"));

        assertTrue(collector.writePoint(point, List.of(21, 22, 23)));
        assertEquals(List.of(21, 22, 23), collector.lastWriteValue);
    }

    @Test
    void shouldRejectArrayPointsWithScalarTransformSettings() throws Exception {
        ConfigManager configManager = mock(ConfigManager.class);
        DataPoint point = point("p3", "temperatures", "Program:Main.ArrayTag:DINT[3]", "R");
        point.setScalingFactor(0.1d);
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));

        TestableEtherNetIpCollector collector = new TestableEtherNetIpCollector();
        collector.readValue = List.of(1, 2, 3);
        prepareCommandCollector(collector, configManager);

        CollectorException exception = assertThrows(CollectorException.class, () -> collector.readPoint(point));
        assertEquals("点位读取失败", exception.getMessage());
    }

    private void prepareCommandCollector(EtherNetIpCollector collector, ConfigManager configManager) throws Exception {
        collector.init(device());
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", new DataQualityProcessor(null));
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "devicePointResolver", new DevicePointResolver(configManager));

        EtherNetIpConnectionAdapter connectionAdapter = mock(EtherNetIpConnectionAdapter.class);
        when(connectionAdapter.isConnected()).thenReturn(true);
        ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);
    }

    private DeviceInfo device() {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-1");
        deviceInfo.setDeviceName("ethernet-ip-device");
        deviceInfo.setProtocolType("ETHERNET_IP");
        deviceInfo.setCollectionInterval(2000);
        return deviceInfo;
    }

    private DataPoint point(String pointId, String pointCode, String address, String readWrite) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointCode);
        point.setPointName(pointCode);
        point.setDeviceId("dev-1");
        point.setAddress(address);
        point.setDataType("DINT");
        point.setReadWrite(readWrite);
        point.setStatus(1);
        return point;
    }

    private static final class TestableEtherNetIpCollector extends EtherNetIpCollector {

        private Object readValue;
        private Object lastWriteValue;

        @Override
        protected Object doReadPoint(DataPoint point) {
            return readValue;
        }

        @Override
        protected boolean doWritePoint(DataPoint point, Object value) {
            lastWriteValue = value;
            return true;
        }
    }
}

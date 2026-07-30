package com.wangbin.collector.core.collector.protocol.bacnet;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.support.FakeBacnetIpServer;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BacnetCompatibilityMatrixTest {

    @Test
    void shouldCoverVendorProfilesByDeviceIdentityAndPropertyMix() throws Exception {
        verifyProfile("siemens", 1001, 480, 7.5f, "AHU-SI");
        verifyProfile("johnson", 2002, 1024, 18.0f, "AHU-JC");
        verifyProfile("honeywell", 3003, 1476, 25.5f, "AHU-HW");
    }

    private void verifyProfile(String profile,
                               int vendorId,
                               int maxApdu,
                               float presentValue,
                               String objectName) throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.setDeviceIdentity(1001, maxApdu, vendorId);
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, presentValue);
            server.putString(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.OBJECT_NAME, objectName);
            DataPoint pv = point("pv", "analogInput:1.presentValue", "FLOAT", "dev-" + profile);
            DataPoint name = point("name", "analogInput:1.objectName", "STRING", "dev-" + profile);
            BacnetIpCollector collector = prepareCollector("dev-" + profile, server.port(), List.of(pv, name), 1000);

            Map<String, Object> values = collector.readPoints(List.of(pv, name));
            Map<String, Object> status = collector.getDeviceStatus();

            assertEquals((double) presentValue, ((Number) values.get("pv")).doubleValue(), 1.0E-6);
            assertEquals(objectName, values.get("name"));
            assertEquals("BACNET_IP", status.get("protocol"));
        }
    }

    private BacnetIpCollector prepareCollector(String deviceId,
                                               int port,
                                               List<DataPoint> points,
                                               int readTimeoutMs) throws Exception {
        DeviceInfo deviceInfo = device(deviceId);
        DeviceConnection connection = connection(port, readTimeoutMs);
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getDeviceContext(deviceId)).thenReturn(DeviceContext.of(deviceInfo, connection, points));
        when(configManager.getDataPoints(deviceId)).thenReturn(points);

        com.wangbin.collector.core.connection.adapter.BacnetIpConnectionAdapter adapter =
                new com.wangbin.collector.core.connection.adapter.BacnetIpConnectionAdapter(deviceInfo, connection);
        adapter.connect();

        BacnetIpCollector collector = new BacnetIpCollector();
        collector.init(deviceInfo);
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", new DataQualityProcessor(null));
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "connectionAdapter", adapter);
        ReflectionTestUtils.setField(collector, "connected", true);
        ReflectionTestUtils.setField(collector, "connectionStatus", "CONNECTED");
        ReflectionTestUtils.setField(collector, "requestTimeoutMs", readTimeoutMs);
        collector.rebuildReadPlans(deviceId, points);
        return collector;
    }

    private DeviceInfo device(String deviceId) {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId(deviceId);
        deviceInfo.setDeviceName(deviceId);
        deviceInfo.setProtocolType("BACNET_IP");
        deviceInfo.setConnectionType("BACNET_IP");
        deviceInfo.setCollectionInterval(1000);
        deviceInfo.setIpAddress("127.0.0.1");
        return deviceInfo;
    }

    private DeviceConnection connection(int port, int readTimeoutMs) {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");
        connection.setPort(port);
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setTimeout(readTimeoutMs);
        connection.setConnectionType("BACNET_IP");
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("remoteDeviceInstance", 1001);
        ext.put("localBindHost", "127.0.0.1");
        ext.put("localBindPort", 0);
        connection.setExtJson(ext);
        return connection;
    }

    private DataPoint point(String pointId, String address, String dataType, String deviceId) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setPointName(pointId);
        point.setDeviceId(deviceId);
        point.setAddress(address);
        point.setDataType(dataType);
        point.setReadWrite("R");
        point.setStatus(1);
        point.setAdditionalConfig(new LinkedHashMap<>());
        return point;
    }
}

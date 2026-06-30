package com.wangbin.collector.core.collector.protocol.bacnet;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.support.FakeBacnetIpServer;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.connection.adapter.BacnetIpConnectionAdapter;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BacnetIpCollectorIntegrationTest {

    @Test
    void shouldReadRealPresentValueThroughRealUdpAdapter() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 12.5f);
            DataPoint point = point("p1", "analogInput:1.presentValue", "FLOAT");
            BacnetIpCollector collector = prepareCollector(server, List.of(point), 1000);

            Object value = collector.readPoint(point);

            assertEquals(12.5d, ((Number) value).doubleValue(), 1.0E-6);
            assertNotNull(collector.getLatestProcessResult("p1"));
        }
    }

    @Test
    void shouldReadStringObjectNameWithoutNumericConversion() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.putString(BacnetObjectType.DEVICE, 1001, BacnetPropertyIdentifier.OBJECT_NAME, "AHU-01");
            DataPoint point = point("p1", "device:1001.objectName", "STRING");
            BacnetIpCollector collector = prepareCollector(server, List.of(point), 1000);

            Object value = collector.readPoint(point);

            assertEquals("AHU-01", value);
            assertEquals("AHU-01", collector.getLatestProcessResult("p1").getFinalValue());
        }
    }

    @Test
    void shouldBatchReadRealAndBooleanPointsThroughRealUdpAdapter() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 18.75f);
            server.putBoolean(BacnetObjectType.BINARY_INPUT, 2, BacnetPropertyIdentifier.PRESENT_VALUE, true);
            DataPoint p1 = point("p1", "analogInput:1.presentValue", "FLOAT");
            DataPoint p2 = point("p2", "binaryInput:2.presentValue", "boolean");
            BacnetIpCollector collector = prepareCollector(server, List.of(p1, p2), 1000);
            collector.rebuildReadPlans("dev-bacnet", List.of(p1, p2));

            Map<String, Object> values = collector.readPoints(List.of(p1, p2));

            assertEquals(18.75d, ((Number) values.get("p1")).doubleValue(), 1.0E-6);
            assertEquals(true, values.get("p2"));
        }
    }

    @Test
    void shouldBatchReadByReadPropertyMultipleWhenEnabled() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 23.5f);
            server.putString(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.OBJECT_NAME, "AI-1");
            DataPoint p1 = point("p1", "analogInput:1.presentValue", "FLOAT");
            DataPoint p2 = point("p2", "analogInput:1.objectName", "STRING");
            BacnetIpCollector collector = prepareCollector(server, List.of(p1, p2), 1000);

            Map<String, Object> values = collector.readPoints(List.of(p1, p2));

            assertEquals(23.5d, ((Number) values.get("p1")).doubleValue(), 1.0E-6);
            assertEquals("AI-1", values.get("p2"));
        }
    }

    @Test
    void shouldFallbackToReadPropertyWhenReadPropertyMultipleRejected() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 11.25f);
            server.putString(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.OBJECT_NAME, "AI-1");
            server.forceReadPropertyMultipleRejectReason(9);
            DataPoint p1 = point("p1", "analogInput:1.presentValue", "FLOAT");
            DataPoint p2 = point("p2", "analogInput:1.objectName", "STRING");
            BacnetIpCollector collector = prepareCollector(server, List.of(p1, p2), 1000);

            Map<String, Object> values = collector.readPoints(List.of(p1, p2));

            assertEquals(11.25d, ((Number) values.get("p1")).doubleValue(), 1.0E-6);
            assertEquals("AI-1", values.get("p2"));
        }
    }

    @Test
    void shouldDisconnectAfterReadTimeout() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 8.5f);
            server.setResponseDelayMs(800);
            DataPoint point = point("p1", "analogInput:1.presentValue", "FLOAT");
            BacnetIpCollector collector = prepareCollector(server, List.of(point), 100);

            CollectorException exception = assertThrows(CollectorException.class, () -> collector.readPoint(point));

            assertNotNull(exception.getMessage());
            assertFalse(collector.isConnected());
            assertEquals("DISCONNECTED", collector.getConnectionStatus());
        }
    }

    @Test
    void shouldSurfaceRejectReasonFromDevice() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.forceRejectReason(9);
            DataPoint point = point("p1", "analogInput:1.presentValue", "FLOAT");
            BacnetIpCollector collector = prepareCollector(server, List.of(point), 1000);

            CollectorException exception = assertThrows(CollectorException.class, () -> collector.readPoint(point));

            assertNotNull(exception.getCause());
            assertNotNull(exception.getCause().getMessage());
        }
    }

    @Test
    void shouldDiscoverRemoteDeviceByWhoIsDuringConnect() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.setDeviceIdentity(1001, 480, 4321);
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 7.25f);

            DeviceInfo deviceInfo = device();
            DeviceConnection connection = connection(server.port(), 1000, true);
            BacnetIpConnectionAdapter adapter = new BacnetIpConnectionAdapter(deviceInfo, connection);

            adapter.connect();

            assertNotNull(adapter.getRemoteDevice());
            assertEquals(1001, adapter.getRemoteDevice().getDeviceInstance());
            assertEquals(true, adapter.getRemoteDevice().isDiscoveredByWhoIs());
            assertEquals(4321, adapter.getRemoteDevice().getVendorId());
            adapter.disconnect();
        }
    }

    private BacnetIpCollector prepareCollector(FakeBacnetIpServer server,
                                               List<DataPoint> points,
                                               int readTimeoutMs) throws Exception {
        DeviceInfo deviceInfo = device();
        DeviceConnection connection = connection(server.port(), readTimeoutMs, false);
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getDeviceContext("dev-bacnet")).thenReturn(DeviceContext.of(deviceInfo, connection, points));
        when(configManager.getDataPoints("dev-bacnet")).thenReturn(points);

        BacnetIpConnectionAdapter adapter = new BacnetIpConnectionAdapter(deviceInfo, connection);
        adapter.connect();

        BacnetIpCollector collector = new BacnetIpCollector();
        collector.init(deviceInfo);
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", new DataQualityProcessor(null));
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "connectionAdapter", adapter);
        ReflectionTestUtils.setField(collector, "connected", true);
        ReflectionTestUtils.setField(collector, "connectionStatus", "CONNECTED");
        ReflectionTestUtils.setField(collector, "requestTimeoutMs", readTimeoutMs);
        collector.rebuildReadPlans("dev-bacnet", points);
        return collector;
    }

    private DeviceInfo device() {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-bacnet");
        deviceInfo.setDeviceName("bacnet-device");
        deviceInfo.setProtocolType("BACNET_IP");
        deviceInfo.setConnectionType("BACNET_IP");
        deviceInfo.setCollectionInterval(1000);
        deviceInfo.setIpAddress("127.0.0.1");
        return deviceInfo;
    }

    private DeviceConnection connection(int port, int readTimeoutMs) {
        return connection(port, readTimeoutMs, false);
    }

    private DeviceConnection connection(int port, int readTimeoutMs, boolean useWhoIsDiscovery) {
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
        ext.put("useWhoIsDiscovery", useWhoIsDiscovery);
        connection.setExtJson(ext);
        return connection;
    }

    private DataPoint point(String pointId, String address, String dataType) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setPointName(pointId);
        point.setDeviceId("dev-bacnet");
        point.setAddress(address);
        point.setDataType(dataType);
        point.setReadWrite("R");
        point.setStatus(1);
        point.setAdditionalConfig(new LinkedHashMap<>());
        return point;
    }
}

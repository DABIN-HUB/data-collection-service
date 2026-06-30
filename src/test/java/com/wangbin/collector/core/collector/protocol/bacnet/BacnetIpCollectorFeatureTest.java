package com.wangbin.collector.core.collector.protocol.bacnet;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.FeatureBacnetTestServer;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BacnetIpCollectorFeatureTest {

    @Test
    void shouldWritePointAndReadBackThroughWriteProperty() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            server.putReal(BacnetObjectType.ANALOG_OUTPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 10.0f);
            DataPoint point = point("p1", "analogOutput:1.presentValue", "FLOAT");
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000);

            boolean written = collector.writePoint(point, 21.5d);
            Object value = collector.readPoint(point);

            assertTrue(written);
            assertEquals(21.5d, ((Number) value).doubleValue(), 1.0E-6);
        }
    }

    @Test
    void shouldReceiveCovNotificationAfterPropertySubscription() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 12.0f);
            DataPoint point = point("p1", "analogInput:1.presentValue", "FLOAT");
            Map<String, Object> config = point.getAdditionalConfig();
            config.put("covPropertyEnabled", true);
            config.put("covLifetimeSeconds", 60);
            point.setAdditionalConfig(config);
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000);

            collector.subscribe(List.of(point));
            server.publishReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 15.25f);

            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                if (collector.getLatestProcessResult("p1") != null
                        && collector.getLatestProcessResult("p1").getFinalValue() instanceof Number number
                        && Math.abs(number.doubleValue() - 15.25d) < 1.0E-6) {
                    break;
                }
                Thread.sleep(50);
            }

            assertNotNull(collector.getLatestProcessResult("p1"));
            assertEquals(15.25d,
                    ((Number) collector.getLatestProcessResult("p1").getFinalValue()).doubleValue(),
                    1.0E-6);
            collector.unsubscribe(List.of(point));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExecuteReadAndWritePropertyCommands() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            server.putReal(BacnetObjectType.ANALOG_VALUE, 3, BacnetPropertyIdentifier.PRESENT_VALUE, 7.0f);
            DataPoint point = point("p1", "analogValue:3.presentValue", "FLOAT");
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000);

            Map<String, Object> readBefore = (Map<String, Object>) collector.executeCommand("read_property",
                    Map.of("address", "analogValue:3.presentValue"));
            Map<String, Object> writeResult = (Map<String, Object>) collector.executeCommand("write_property",
                    Map.of("address", "analogValue:3.presentValue", "value", 19.5d, "valueType", "REAL"));
            Map<String, Object> readAfter = (Map<String, Object>) collector.executeCommand("read_property",
                    Map.of("address", "analogValue:3.presentValue"));

            assertEquals(7.0d, ((Number) readBefore.get("value")).doubleValue(), 1.0E-6);
            assertEquals(Boolean.TRUE, writeResult.get("success"));
            assertEquals(19.5d, ((Number) readAfter.get("value")).doubleValue(), 1.0E-6);
        }
    }

    private BacnetIpCollector prepareCollector(int port,
                                               List<DataPoint> points,
                                               int readTimeoutMs) throws Exception {
        DeviceInfo deviceInfo = device();
        DeviceConnection connection = connection(port, readTimeoutMs);
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

    private DataPoint point(String pointId, String address, String dataType) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setPointName(pointId);
        point.setDeviceId("dev-bacnet");
        point.setAddress(address);
        point.setDataType(dataType);
        point.setReadWrite("RW");
        point.setStatus(1);
        point.setAdditionalConfig(new LinkedHashMap<>());
        return point;
    }
}

package com.wangbin.collector.core.collector.protocol.bacnet;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.FeatureBacnetTestServer;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.processor.ProcessResult;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void shouldWritePointsThroughWritePropertyMultipleWhenEnabled() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            server.putReal(BacnetObjectType.ANALOG_OUTPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 10.0f);
            server.putReal(BacnetObjectType.ANALOG_OUTPUT, 2, BacnetPropertyIdentifier.PRESENT_VALUE, 11.0f);
            DataPoint point1 = point("p1", "analogOutput:1.presentValue", "FLOAT");
            DataPoint point2 = point("p2", "analogOutput:2.presentValue", "FLOAT");
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point1, point2), 1000, Map.of(
                    "writePropertyMultipleEnabled", true
            ));

            Map<String, Boolean> results = collector.writePoints(Map.of(point1, 20.5d, point2, 21.5d));

            assertEquals(Boolean.TRUE, results.get("p1"));
            assertEquals(Boolean.TRUE, results.get("p2"));
            assertEquals(20.5d, ((Number) collector.readPoint(point1)).doubleValue(), 1.0E-6);
            assertEquals(21.5d, ((Number) collector.readPoint(point2)).doubleValue(), 1.0E-6);
            Map<String, Object> status = collector.getDeviceStatus();
            assertEquals(1L, ((Number) status.get("writePropertyMultipleCount")).longValue());
            assertEquals(0L, ((Number) status.get("writePropertyMultipleFallbackCount")).longValue());
        }
    }

    @Test
    void shouldFallbackToWritePropertyWhenWritePropertyMultipleRejected() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            server.putReal(BacnetObjectType.ANALOG_OUTPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 10.0f);
            server.putReal(BacnetObjectType.ANALOG_OUTPUT, 2, BacnetPropertyIdentifier.PRESENT_VALUE, 11.0f);
            server.forceWritePropertyMultipleRejectReason(9);
            DataPoint point1 = point("p1", "analogOutput:1.presentValue", "FLOAT");
            DataPoint point2 = point("p2", "analogOutput:2.presentValue", "FLOAT");
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point1, point2), 1000, Map.of(
                    "writePropertyMultipleEnabled", true
            ));

            Map<String, Boolean> results = collector.writePoints(Map.of(point1, 30.5d, point2, 31.5d));

            assertEquals(Boolean.TRUE, results.get("p1"));
            assertEquals(Boolean.TRUE, results.get("p2"));
            assertEquals(30.5d, ((Number) collector.readPoint(point1)).doubleValue(), 1.0E-6);
            assertEquals(31.5d, ((Number) collector.readPoint(point2)).doubleValue(), 1.0E-6);
            Map<String, Object> status = collector.getDeviceStatus();
            assertEquals(0L, ((Number) status.get("writePropertyMultipleCount")).longValue());
            assertEquals(1L, ((Number) status.get("writePropertyMultipleFallbackCount")).longValue());
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

    @Test
    @SuppressWarnings("unchecked")
    void shouldPassThroughComplexBacnetPropertyValue() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            server.putObjectList(BacnetObjectType.DEVICE, 1001,
                    List.of("analogInput:1", "analogOutput:2"));
            DataPoint point = point("p1", "device:1001.objectList", "STRING");
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000);

            Object value = collector.readPoint(point);

            List<Object> objectList = assertInstanceOf(List.class, value);
            assertEquals(2, objectList.size());
            assertEquals("analogInput:1", objectList.get(0));
            assertEquals("analogOutput:2", objectList.get(1));
            assertNotNull(collector.getLatestProcessResult("p1"));
            assertEquals(true, collector.getLatestProcessResult("p1").getMetadata("bacnetComplexValue", false));
            assertEquals("OBJECT_LIST", collector.getLatestProcessResult("p1").getMetadata("bacnetValueType"));
            Map<String, Object> valueMetadata = assertInstanceOf(Map.class,
                    collector.getLatestProcessResult("p1").getMetadata("bacnetValueMetadata"));
            assertEquals("objectList", valueMetadata.get("semantic"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldResolveDefaultCovIncrementFromConnectionConfig() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 12.0f);
            DataPoint point = point("p1", "analogInput:1.presentValue", "FLOAT");
            Map<String, Object> config = point.getAdditionalConfig();
            config.put("covPropertyEnabled", true);
            point.setAdditionalConfig(config);
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000, Map.of(
                    "defaultCovIncrement", 1.5d
            ));

            collector.subscribe(List.of(point));

            Map<String, Object> subscription = server.latestSubscription();
            assertNotNull(subscription);
            assertEquals(1.5d, ((Number) subscription.get("covIncrement")).doubleValue(), 1.0E-6);
        }
    }

    @Test
    void shouldSelectAutoSubscriptionPointsWhenCovEnabledAndModeMatches() throws Exception {
        DeviceInfo deviceInfo = device();
        DeviceConnection connection = connection(47808, 1000, Map.of("covEnabled", true));
        DataPoint subscriptionPoint = point("p1", "analogInput:1.presentValue", "FLOAT");
        subscriptionPoint.setCollectionMode("SUBSCRIPTION");
        DataPoint pollingPoint = point("p2", "analogInput:2.presentValue", "FLOAT");
        pollingPoint.setCollectionMode("POLLING");
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getDeviceContext("dev-bacnet"))
                .thenReturn(DeviceContext.of(deviceInfo, connection, List.of(subscriptionPoint, pollingPoint)));

        BacnetIpCollector collector = new BacnetIpCollector();
        collector.init(deviceInfo);
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "connected", false);
        ReflectionTestUtils.setField(collector, "connectionStatus", "DISCONNECTED");

        List<DataPoint> points = collector.filterAutoSubscriptionPoints(List.of(subscriptionPoint, pollingPoint));

        assertEquals(1, points.size());
        assertEquals("p1", points.get(0).getPointId());
    }

    @Test
    void shouldExcludeSubscriptionPointsFromPollingWhenCovEnabled() throws Exception {
        DataPoint subscriptionPoint = point("p1", "analogInput:1.presentValue", "FLOAT");
        subscriptionPoint.setCollectionMode("SUBSCRIPTION");
        DataPoint pollingPoint = point("p2", "analogInput:2.presentValue", "FLOAT");
        pollingPoint.setCollectionMode("POLLING");

        BacnetIpCollector collector = prepareUnconnectedCollector(Map.of("covEnabled", true));

        List<DataPoint> pollingPoints = collector.filterPollingPoints(List.of(subscriptionPoint, pollingPoint));

        assertEquals(1, pollingPoints.size());
        assertEquals("p2", pollingPoints.get(0).getPointId());
    }

    @Test
    void shouldBypassPollingForSubscriptionPointAndUseLatestPushedValue() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 10.0f);
            DataPoint point = point("p1", "analogInput:1.presentValue", "FLOAT");
            point.setCollectionMode("SUBSCRIPTION");
            Map<String, Object> config = point.getAdditionalConfig();
            config.put("covPropertyEnabled", true);
            point.setAdditionalConfig(config);
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000, Map.of("covEnabled", true));

            Map<String, Object> first = collector.readPoints(List.of(point));
            assertTrue(first.containsKey("p1"));
            assertNull(first.get("p1"));

            collector.subscribe(List.of(point));
            server.publishReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 15.5f);

            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                ProcessResult processResult = collector.getLatestProcessResult("p1");
                if (processResult != null
                        && processResult.getFinalValue() instanceof Number number
                        && Math.abs(number.doubleValue() - 15.5d) < 1.0E-6) {
                    break;
                }
                Thread.sleep(50);
            }

            Map<String, Object> second = collector.readPoints(List.of(point));
            assertEquals(15.5d, ((Number) second.get("p1")).doubleValue(), 1.0E-6);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReceiveConfirmedCovNotificationAndSendAck() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 10.0f);
            DataPoint point = point("p1", "analogInput:1.presentValue", "FLOAT");
            point.setCollectionMode("SUBSCRIPTION");
            Map<String, Object> config = point.getAdditionalConfig();
            config.put("covPropertyEnabled", true);
            point.setAdditionalConfig(config);
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000, Map.of(
                    "covEnabled", true,
                    "covConfirmedNotifications", true
            ));

            collector.subscribe(List.of(point));
            server.publishReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 18.75f);

            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                ProcessResult processResult = collector.getLatestProcessResult("p1");
                Map<String, Object> ack = server.lastConfirmedCovAck();
                if (processResult != null
                        && processResult.getFinalValue() instanceof Number number
                        && Math.abs(number.doubleValue() - 18.75d) < 1.0E-6
                        && ack != null) {
                    break;
                }
                Thread.sleep(50);
            }

            assertNotNull(collector.getLatestProcessResult("p1"));
            assertEquals(18.75d, ((Number) collector.getLatestProcessResult("p1").getFinalValue()).doubleValue(), 1.0E-6);
            Map<String, Object> status = collector.getDeviceStatus();
            assertEquals(1L, ((Number) status.get("covConfirmedNotificationCount")).longValue());
            Map<String, Object> protocolMetrics = assertInstanceOf(Map.class, status.get("protocolMetrics"));
            assertEquals(1L, ((Number) protocolMetrics.get("covConfirmedNotificationCount")).longValue());
            Map<String, Object> ack = server.lastConfirmedCovAck();
            assertNotNull(ack);
            assertEquals(1, ((Number) ack.get("serviceChoice")).intValue());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldResubscribeOnReconnectWhenCovEnabled() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 9.0f);
            DataPoint point = point("p1", "analogInput:1.presentValue", "FLOAT");
            point.setCollectionMode("SUBSCRIPTION");
            Map<String, Object> config = point.getAdditionalConfig();
            config.put("covPropertyEnabled", true);
            point.setAdditionalConfig(config);
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000, Map.of(
                    "covEnabled", true,
                    "resubscribeOnReconnect", true
            ));

            collector.subscribe(List.of(point));
            Map<String, Object> firstSubscription = server.latestSubscription();
            assertNotNull(firstSubscription);

            ReflectionTestUtils.invokeMethod(collector, "handleAdapterReconnect");

            Map<String, Object> secondSubscription = server.latestSubscription();
            assertNotNull(secondSubscription);
            assertFalse(firstSubscription.get("processIdentifier").equals(secondSubscription.get("processIdentifier")));

            Map<String, Object> status = collector.getDeviceStatus();
            assertEquals(1L, ((Number) status.get("covResubscribeCount")).longValue());
            Map<String, Object> protocolMetrics = assertInstanceOf(Map.class, status.get("protocolMetrics"));
            assertEquals(Boolean.TRUE, protocolMetrics.get("covEnabled"));
            assertEquals(Boolean.TRUE, protocolMetrics.get("resubscribeOnReconnect"));
            assertEquals(1L, ((Number) protocolMetrics.get("covResubscribeCount")).longValue());
        }
    }

    private BacnetIpCollector prepareCollector(int port,
                                               List<DataPoint> points,
                                               int readTimeoutMs) throws Exception {
        return prepareCollector(port, points, readTimeoutMs, Map.of());
    }

    private BacnetIpCollector prepareCollector(int port,
                                               List<DataPoint> points,
                                               int readTimeoutMs,
                                               Map<String, Object> extOverrides) throws Exception {
        DeviceInfo deviceInfo = device();
        DeviceConnection connection = connection(port, readTimeoutMs, extOverrides);
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getDeviceContext("dev-bacnet")).thenReturn(DeviceContext.of(deviceInfo, connection, points));
        when(configManager.getDataPoints("dev-bacnet")).thenReturn(points);

        BacnetIpConnectionAdapter adapter = new BacnetIpConnectionAdapter(deviceInfo, connection);
        adapter.connect();

        BacnetIpCollector collector = new BacnetIpCollector();
        collector.init(deviceInfo);
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "connectionAdapter", adapter);
        ReflectionTestUtils.setField(collector, "connected", true);
        ReflectionTestUtils.setField(collector, "connectionStatus", "CONNECTED");
        ReflectionTestUtils.setField(collector, "requestTimeoutMs", readTimeoutMs);
        collector.rebuildReadPlans("dev-bacnet", points);
        return collector;
    }

    private BacnetIpCollector prepareUnconnectedCollector(Map<String, Object> extOverrides) {
        DeviceInfo deviceInfo = device();
        DeviceConnection connection = connection(47808, 1000, extOverrides);
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getDeviceContext("dev-bacnet"))
                .thenReturn(DeviceContext.of(deviceInfo, connection, List.of()));

        BacnetIpCollector collector = new BacnetIpCollector();
        collector.init(deviceInfo);
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "connected", false);
        ReflectionTestUtils.setField(collector, "connectionStatus", "DISCONNECTED");
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
        return connection(port, readTimeoutMs, Map.of());
    }

    private DeviceConnection connection(int port, int readTimeoutMs, Map<String, Object> extOverrides) {
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
        ext.putAll(extOverrides);
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

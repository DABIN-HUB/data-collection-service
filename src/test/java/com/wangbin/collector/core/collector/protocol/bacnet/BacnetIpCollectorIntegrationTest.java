package com.wangbin.collector.core.collector.protocol.bacnet;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.FeatureBacnetTestServer;
import com.wangbin.collector.core.collector.protocol.bacnet.support.FakeBacnetIpServer;
import com.wangbin.collector.core.collector.protocol.bacnet.support.FakeBbmdServer;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.SegmentedBacnetTestServer;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.connection.adapter.BacnetIpConnectionAdapter;
import com.wangbin.collector.core.connection.manager.ConnectionManager;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

class BacnetIpCollectorIntegrationTest {

    @Test
    void shouldReadRealPresentValueThroughRealUdpAdapter() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 12.5f);
            DataPoint point = point("p1", "analogInput:1.presentValue", "FLOAT");
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000);

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
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000);

            Object value = collector.readPoint(point);

            assertEquals("AHU-01", value);
            assertEquals("AHU-01", collector.getLatestProcessResult("p1").getFinalValue());
        }
    }

    @Test
    void shouldReadPrivateObjectAndPropertyByDynamicAddress() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.putString(BacnetObjectType.fromId(128), 42, BacnetPropertyIdentifier.fromId(512), "PRIVATE-VALUE");
            DataPoint point = point("p1", "128:42.512", "STRING");
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000);

            Object value = collector.readPoint(point);

            assertEquals("PRIVATE-VALUE", value);
            assertEquals("PRIVATE-VALUE", collector.getLatestProcessResult("p1").getFinalValue());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildDeviceSnapshotThroughDeviceInfoAndDiscoverObjectsCommands() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.putString(BacnetObjectType.DEVICE, 1001, BacnetPropertyIdentifier.OBJECT_NAME, "AHU-01");
            server.putString(BacnetObjectType.DEVICE, 1001, BacnetPropertyIdentifier.DESCRIPTION, "AIR-HANDLER");
            server.putString(BacnetObjectType.DEVICE, 1001, BacnetPropertyIdentifier.MODEL_NAME, "MODEL-X");
            server.putEnumerated(BacnetObjectType.DEVICE, 1001, BacnetPropertyIdentifier.VENDOR_IDENTIFIER, 4321);
            server.putEnumerated(BacnetObjectType.DEVICE, 1001, BacnetPropertyIdentifier.PROTOCOL_VERSION, 1);
            server.putEnumerated(BacnetObjectType.DEVICE, 1001, BacnetPropertyIdentifier.PROTOCOL_REVISION, 22);
            server.putUnsigned(BacnetObjectType.DEVICE, 1001, BacnetPropertyIdentifier.MAX_APDU_LENGTH_ACCEPTED, null, 480);
            server.putEnumerated(BacnetObjectType.DEVICE, 1001, BacnetPropertyIdentifier.SEGMENTATION_SUPPORTED, 3);
            server.putUnsigned(BacnetObjectType.DEVICE, 1001, BacnetPropertyIdentifier.OBJECT_LIST, 0, 2);
            server.putObjectIdentifier(BacnetObjectType.DEVICE, 1001, BacnetPropertyIdentifier.OBJECT_LIST, 1, BacnetObjectType.ANALOG_INPUT, 1);
            server.putObjectIdentifier(BacnetObjectType.DEVICE, 1001, BacnetPropertyIdentifier.OBJECT_LIST, 2, BacnetObjectType.ANALOG_OUTPUT, 2);
            DataPoint point = point("p1", "device:1001.objectName", "STRING");
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000);

            Map<String, Object> deviceInfo = (Map<String, Object>) collector.executeCommand("device_info", Map.of());
            List<String> objectList = (List<String>) collector.executeCommand("discover_objects", Map.of());

            assertEquals("AHU-01", deviceInfo.get("objectName"));
            assertEquals("AIR-HANDLER", deviceInfo.get("description"));
            assertEquals(1001, deviceInfo.get("remoteDeviceInstance"));
            assertEquals(2, ((Number) deviceInfo.get("objectCount")).intValue());
            assertEquals(List.of("analogInput:1", "analogOutput:2"), objectList);
        }
    }

    @Test
    void shouldBatchReadRealAndBooleanPointsThroughRealUdpAdapter() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 18.75f);
            server.putBoolean(BacnetObjectType.BINARY_INPUT, 2, BacnetPropertyIdentifier.PRESENT_VALUE, true);
            DataPoint p1 = point("p1", "analogInput:1.presentValue", "FLOAT");
            DataPoint p2 = point("p2", "binaryInput:2.presentValue", "boolean");
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(p1, p2), 1000);
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
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(p1, p2), 1000);

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
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(p1, p2), 1000);

            Map<String, Object> values = collector.readPoints(List.of(p1, p2));

            assertEquals(11.25d, ((Number) values.get("p1")).doubleValue(), 1.0E-6);
            assertEquals("AI-1", values.get("p2"));
        }
    }

    @Test
    void shouldReadSegmentedComplexAck() throws Exception {
        try (SegmentedBacnetTestServer server = new SegmentedBacnetTestServer()) {
            DeviceInfo deviceInfo = device();
            DeviceConnection connection = connection("127.0.0.1", server.port(), 1000, false, Map.of("segmentTimeout", 500));
            BacnetIpConnectionAdapter adapter = new BacnetIpConnectionAdapter(deviceInfo, connection);
            adapter.connect();
            try {
                BacnetReadPropertyResponse response = adapter.readProperty(BacnetReadPropertyRequest.builder()
                        .objectType(BacnetObjectType.DEVICE)
                        .objectInstance(1001)
                        .propertyIdentifier(BacnetPropertyIdentifier.OBJECT_NAME)
                        .invokeId(1)
                        .remoteDeviceInstance(1001)
                        .build(), 1000);

                assertEquals("SEGMENTED-AHU-01", response.getValue());
                assertEquals(1L, adapter.getSegmentedResponseCount());
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void shouldDisconnectAfterReadTimeout() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 8.5f);
            server.setResponseDelayMs(800);
            DataPoint point = point("p1", "analogInput:1.presentValue", "FLOAT");
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 100);

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
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000);

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
            DeviceConnection connection = connection("127.0.0.1", server.port(), 1000, true, Map.of());
            BacnetIpConnectionAdapter adapter = new BacnetIpConnectionAdapter(deviceInfo, connection);

            adapter.connect();

            assertNotNull(adapter.getRemoteDevice());
            assertEquals(1001, adapter.getRemoteDevice().getDeviceInstance());
            assertEquals(true, adapter.getRemoteDevice().isDiscoveredByWhoIs());
            assertEquals(4321, adapter.getRemoteDevice().getVendorId());
            adapter.disconnect();
        }
    }

    @Test
    void shouldRegisterForeignDeviceDiscoverThroughBbmdAndRenewLease() throws Exception {
        try (FakeBacnetIpServer remoteDevice = new FakeBacnetIpServer();
             FakeBbmdServer bbmdServer = new FakeBbmdServer(new InetSocketAddress("127.0.0.1", remoteDevice.port()),
                     1001,
                     480,
                     4321)) {
            remoteDevice.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 7.25f);

            DeviceInfo deviceInfo = device();
            DeviceConnection connection = connection("127.0.0.1",
                    bbmdServer.port(),
                    1000,
                    true,
                    Map.of(
                            "bbmdHost", "127.0.0.1",
                            "bbmdPort", bbmdServer.port(),
                            "foreignDeviceTtlSeconds", 1,
                            "segmentTimeout", 500
                    ));
            BacnetIpConnectionAdapter adapter = new BacnetIpConnectionAdapter(deviceInfo, connection);
            adapter.connect();
            try {
                assertNotNull(adapter.getRemoteDevice());
                assertEquals(remoteDevice.port(), adapter.getRemoteDevice().getSocketAddress().getPort());
                assertEquals(true, adapter.isForeignDeviceRegistrationActive());
                assertEquals(1L, adapter.getForeignDeviceRegistrationCount());
                assertEquals(1L, bbmdServer.getForeignDeviceRegistrationCount());
                assertEquals(1L, bbmdServer.getDistributeBroadcastCount());
                assertEquals(1, bbmdServer.getLastForeignDeviceTtlSeconds());

                BacnetReadPropertyResponse first = adapter.readProperty(BacnetReadPropertyRequest.builder()
                        .objectType(BacnetObjectType.ANALOG_INPUT)
                        .objectInstance(1)
                        .propertyIdentifier(BacnetPropertyIdentifier.PRESENT_VALUE)
                        .invokeId(1)
                        .remoteDeviceInstance(1001)
                        .build(), 1000);
                assertEquals(7.25d, ((Number) first.getValue()).doubleValue(), 1.0E-6);

                Thread.sleep(900);

                BacnetReadPropertyResponse second = adapter.readProperty(BacnetReadPropertyRequest.builder()
                        .objectType(BacnetObjectType.ANALOG_INPUT)
                        .objectInstance(1)
                        .propertyIdentifier(BacnetPropertyIdentifier.PRESENT_VALUE)
                        .invokeId(2)
                        .remoteDeviceInstance(1001)
                        .build(), 1000);
                assertEquals(7.25d, ((Number) second.getValue()).doubleValue(), 1.0E-6);
                assertTrue(adapter.getForeignDeviceRenewCount() >= 1);
                assertTrue(bbmdServer.getForeignDeviceRegistrationCount() >= 2);
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void shouldSustainForeignDeviceRenewalAcrossMultipleLeaseCycles() throws Exception {
        try (FakeBacnetIpServer remoteDevice = new FakeBacnetIpServer();
             FakeBbmdServer bbmdServer = new FakeBbmdServer(new InetSocketAddress("127.0.0.1", remoteDevice.port()),
                     1001,
                     480,
                     4321)) {
            var scheduler = Executors.newSingleThreadScheduledExecutor();
            remoteDevice.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 9.5f);

            DeviceInfo deviceInfo = device();
            DeviceConnection connection = connection("127.0.0.1",
                    bbmdServer.port(),
                    1000,
                    true,
                    Map.of(
                            "bbmdHost", "127.0.0.1",
                            "bbmdPort", bbmdServer.port(),
                            "foreignDeviceTtlSeconds", 1,
                            "segmentTimeout", 500
                    ));
            BacnetIpConnectionAdapter adapter = new BacnetIpConnectionAdapter(deviceInfo, connection, scheduler);
            adapter.connect();
            try {
                Thread.sleep(2600);
                BacnetReadPropertyResponse response = adapter.readProperty(BacnetReadPropertyRequest.builder()
                        .objectType(BacnetObjectType.ANALOG_INPUT)
                        .objectInstance(1)
                        .propertyIdentifier(BacnetPropertyIdentifier.PRESENT_VALUE)
                        .invokeId(3)
                        .remoteDeviceInstance(1001)
                        .build(), 1000);

                assertEquals(9.5d, ((Number) response.getValue()).doubleValue(), 1.0E-6);
                assertTrue(adapter.getForeignDeviceRenewCount() >= 2);
                assertTrue(bbmdServer.getForeignDeviceRegistrationCount() >= 3);
                assertEquals(0L, adapter.getForeignDeviceRenewFailureCount());
            } finally {
                adapter.disconnect();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void shouldHandleConfirmedCovNotificationInIntegrationPath() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 10.0f);
            DataPoint point = point("p1", "analogInput:1.presentValue", "FLOAT");
            point.setCollectionMode("SUBSCRIPTION");
            point.getAdditionalConfig().put("covPropertyEnabled", true);
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 1000, Map.of(
                    "covEnabled", true,
                    "covPropertyEnabled", true,
                    "covConfirmedNotifications", true
            ));

            collector.subscribe(List.of(point));
            server.publishReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 16.5f);

            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                if (collector.getLatestProcessResult("p1") != null && server.lastConfirmedCovAck() != null) {
                    break;
                }
                Thread.sleep(50);
            }

            assertNotNull(collector.getLatestProcessResult("p1"));
            assertEquals(16.5d, ((Number) collector.getLatestProcessResult("p1").getFinalValue()).doubleValue(), 1.0E-6);
            assertNotNull(server.lastConfirmedCovAck());
        }
    }

    @Test
    void shouldRecoverConnectionAndResubscribeAfterTimeoutWhenCovEnabled() throws Exception {
        try (FeatureBacnetTestServer server = new FeatureBacnetTestServer()) {
            server.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 10.0f);
            DataPoint point = point("p1", "analogInput:1.presentValue", "FLOAT");
            point.setCollectionMode("SUBSCRIPTION");
            point.getAdditionalConfig().put("covPropertyEnabled", true);
            BacnetIpCollector collector = prepareCollector(server.port(), List.of(point), 100, Map.of(
                    "covEnabled", true,
                    "covPropertyEnabled", true,
                    "resubscribeOnReconnect", true
            ));

            collector.subscribe(List.of(point));
            assertNotNull(server.latestSubscription());

            BacnetIpConnectionAdapter brokenAdapter = (BacnetIpConnectionAdapter) ReflectionTestUtils.getField(collector, "connectionAdapter");
            assertNotNull(brokenAdapter);
            brokenAdapter.disconnect();
            ReflectionTestUtils.setField(collector, "connected", false);
            ReflectionTestUtils.setField(collector, "connectionStatus", "DISCONNECTED");
            assertFalse(collector.isConnected());

            Object value = collector.readPoint(point);

            assertEquals(10.0d, ((Number) value).doubleValue(), 1.0E-6);
            Map<String, Object> status = collector.getDeviceStatus();
            assertEquals(1L, ((Number) status.get("covResubscribeCount")).longValue());
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
        DeviceConnection connection = connection("127.0.0.1", port, readTimeoutMs, false, extOverrides);
        ConfigManager configManager = mock(ConfigManager.class);
        ConnectionManager connectionManager = mock(ConnectionManager.class);
        when(configManager.getDeviceContext("dev-bacnet")).thenReturn(DeviceContext.of(deviceInfo, connection, points));
        when(configManager.getDataPoints("dev-bacnet")).thenReturn(points);

        BacnetIpConnectionAdapter adapter = new BacnetIpConnectionAdapter(deviceInfo, connection);
        adapter.connect();
        BacnetIpConnectionAdapter recoveryAdapter = new BacnetIpConnectionAdapter(deviceInfo, connection);
        when(connectionManager.createConnection(deviceInfo, connection)).thenReturn(recoveryAdapter);
        doAnswer(invocation -> {
            recoveryAdapter.connect();
            return null;
        }).when(connectionManager).connect("dev-bacnet");
        doAnswer(invocation -> {
            recoveryAdapter.disconnect();
            return null;
        }).when(connectionManager).removeConnection("dev-bacnet");

        BacnetIpCollector collector = new BacnetIpCollector();
        collector.init(deviceInfo);
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "connectionManager", connectionManager);
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

    private DeviceConnection connection(String host,
                                        int port,
                                        int readTimeoutMs,
                                        boolean useWhoIsDiscovery,
                                        Map<String, Object> extra) {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost(host);
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
        ext.putAll(extra);
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

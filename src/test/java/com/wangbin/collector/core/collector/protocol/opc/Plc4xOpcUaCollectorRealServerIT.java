package com.wangbin.collector.core.collector.protocol.opc;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.connection.adapter.Plc4xOpcUaConnectionAdapter;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Plc4xOpcUaCollectorRealServerIT {

    private static final String DEFAULT_ENDPOINT = "opc.tcp://DESKTOP-IKHU04D:53530/OPCUA/SimulationServer";
    private static final int DEFAULT_NAMESPACE = 3;
    private static final int DEFAULT_START_NODE_ID = 1007;
    private static final int DEFAULT_END_NODE_ID = 1030;
    private static final long DEFAULT_EVENT_TIMEOUT_MS = 5000L;

    @Test
    void shouldReadConfiguredDoubleRangeAndRespectBrowseRuntimeBoundary() throws Exception {
        assumeRealServerEnabled();

        try (RealServerSession session = RealServerSession.connect()) {
            Plc4xOpcUaCollector collector = session.collector();
            List<DataPoint> points = session.points();

            Map<String, Object> values = collector.readPoints(points);
            assertEquals(points.size(), values.size());
            for (DataPoint point : points) {
                Object value = values.get(point.getPointId());
                assertNotNull(value, "Expected non-null value for point " + point.getPointId());
                assertInstanceOf(Number.class, value, "Expected numeric value for point " + point.getPointId());
            }

            Map<String, Object> status = collector.getDeviceStatus();
            assertEquals(true, status.get("isConnected"));
            assertEquals(true, status.get("parallelValidation"));
            assertTrue(Objects.toString(status.get("connectionString"), "").contains("/OPCUA/SimulationServer"));

            boolean browseable = Boolean.TRUE.equals(status.get("browseable"));
            if (browseable) {
                Object browseResult = collector.executeCommand("browse", Map.of("nodeId", "ns=0;i=84"));
                assertNotNull(browseResult);
            } else {
                CollectorException ex = org.junit.jupiter.api.Assertions.assertThrows(
                        CollectorException.class,
                        () -> collector.executeCommand("browse", Map.of("nodeId", "ns=0;i=84")));
                assertNotNull(ex);
            }
        }
    }

    @Test
    void shouldRegisterSubscriptionAndExposeCurrentWriteAndPushBoundary() throws Exception {
        assumeRealServerEnabled();

        try (RealServerSession session = RealServerSession.connect()) {
            Plc4xOpcUaCollector collector = session.collector();
            DataPoint point = session.points().get(0);

            double originalValue = ((Number) collector.readPoint(point)).doubleValue();

            collector.subscribe(List.of(point));
            Map<String, Object> subscribedStatus = collector.getDeviceStatus();
            assertEquals(true, subscribedStatus.get("subscribable"));
            assertEquals(1, subscribedStatus.get("activeSubscriptions"));

            long baselineEventCount = ((Number) subscribedStatus.get("subscriptionEventCount")).longValue();
            double updatedValue = originalValue + 1.0d;
            boolean writeSucceeded = false;
            CollectorException writeFailure = null;

            try {
                try {
                    writeSucceeded = collector.writePoint(point, updatedValue);
                } catch (CollectorException ex) {
                    writeFailure = ex;
                }

                Boolean expectedWriteSuccess = expectedWriteSuccess();
                if (expectedWriteSuccess != null) {
                    if (expectedWriteSuccess) {
                        assertTrue(writeSucceeded, "Expected write success but got failure: " + writeFailure);
                    } else {
                        assertNotNull(writeFailure, "Expected write failure but write succeeded");
                    }
                }

                if (writeSucceeded) {
                    writeSucceeded = true;
                    if (Boolean.TRUE.equals(expectedSubscriptionEvent())) {
                        waitForSubscriptionEvent(collector, baselineEventCount);

                        Map<String, Object> statusAfterEvent = collector.getDeviceStatus();
                        assertTrue(((Number) statusAfterEvent.get("subscriptionEventCount")).longValue() > baselineEventCount);
                        assertEquals(point.getPointId(), statusAfterEvent.get("lastSubscriptionPointId"));
                        assertNotNull(statusAfterEvent.get("lastSubscriptionEventTs"));

                        ProcessResult processResult = collector.getLatestProcessResult(point.getPointId());
                        assertNotNull(processResult);
                    }

                    double rereadValue = ((Number) collector.readPoint(point)).doubleValue();
                    assertEquals(updatedValue, rereadValue, 0.0001d);
                } else {
                    assertNotNull(writeFailure, "Write probe neither succeeded nor exposed a collector failure");
                }
            } finally {
                try {
                    if (writeSucceeded) {
                        collector.writePoint(point, originalValue);
                    }
                } catch (Exception ignored) {
                    // Keep the real-server test best-effort for cleanup.
                }
                collector.unsubscribe(List.of(point));
                Map<String, Object> unsubscribedStatus = collector.getDeviceStatus();
                assertEquals(0, unsubscribedStatus.get("activeSubscriptions"));
            }
        }
    }

    private void waitForSubscriptionEvent(Plc4xOpcUaCollector collector, long baselineEventCount) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(resolveEventTimeoutMs());
        while (System.nanoTime() < deadline) {
            Map<String, Object> status = collector.getDeviceStatus();
            long currentEventCount = ((Number) status.get("subscriptionEventCount")).longValue();
            if (currentEventCount > baselineEventCount) {
                return;
            }
            Thread.sleep(200L);
        }
        Map<String, Object> finalStatus = collector.getDeviceStatus();
        throw new AssertionError("Timed out waiting for subscription event, status=" + finalStatus);
    }

    private void assumeRealServerEnabled() {
        Assumptions.assumeTrue(resolveEnabled(), "Enable with -Dopcua.real.enabled=true");
    }

    private boolean resolveEnabled() {
        return Boolean.parseBoolean(System.getProperty("opcua.real.enabled",
                System.getenv().getOrDefault("OPCUA_REAL_ENABLED", "false")));
    }

    private long resolveEventTimeoutMs() {
        return Long.parseLong(System.getProperty("opcua.real.eventTimeoutMs",
                System.getenv().getOrDefault("OPCUA_REAL_EVENT_TIMEOUT_MS",
                        Long.toString(DEFAULT_EVENT_TIMEOUT_MS))));
    }

    private Boolean expectedWriteSuccess() {
        return optionalBoolean("opcua.real.expectWriteSuccess", "OPCUA_REAL_EXPECT_WRITE_SUCCESS");
    }

    private Boolean expectedSubscriptionEvent() {
        return optionalBoolean("opcua.real.expectSubscriptionEvent", "OPCUA_REAL_EXPECT_SUBSCRIPTION_EVENT");
    }

    private Boolean optionalBoolean(String propertyKey, String envKey) {
        String propertyValue = System.getProperty(propertyKey);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return Boolean.parseBoolean(propertyValue);
        }
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return Boolean.parseBoolean(envValue);
        }
        return null;
    }

    private static final class RealServerSession implements AutoCloseable {

        private final Plc4xOpcUaConnectionAdapter connectionAdapter;
        private final Plc4xOpcUaCollector collector;
        private final List<DataPoint> points;

        private RealServerSession(Plc4xOpcUaConnectionAdapter connectionAdapter,
                                  Plc4xOpcUaCollector collector,
                                  List<DataPoint> points) {
            this.connectionAdapter = connectionAdapter;
            this.collector = collector;
            this.points = points;
        }

        static RealServerSession connect() throws Exception {
            DeviceInfo deviceInfo = device();
            DeviceConnection connection = connection();
            Plc4xOpcUaConnectionAdapter connectionAdapter = new Plc4xOpcUaConnectionAdapter(deviceInfo, connection);
            connectionAdapter.connect();

            Plc4xOpcUaCollector collector = new Plc4xOpcUaCollector();
            collector.init(deviceInfo);
            ReflectionTestUtils.setField(collector, "dataQualityProcessor", new DataQualityProcessor(null));
            ReflectionTestUtils.setField(collector, "connected", true);
            ReflectionTestUtils.setField(collector, "connectionStatus", "CONNECTED");
            ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);

            return new RealServerSession(connectionAdapter, collector, buildPoints(deviceInfo.getDeviceId()));
        }

        Plc4xOpcUaCollector collector() {
            return collector;
        }

        List<DataPoint> points() {
            return points;
        }

        @Override
        public void close() throws Exception {
            connectionAdapter.disconnect();
        }

        private static DeviceInfo device() {
            DeviceInfo deviceInfo = new DeviceInfo();
            deviceInfo.setDeviceId("dev-opcua-plc4x-real-it");
            deviceInfo.setDeviceName("opcua-plc4x-real-it");
            deviceInfo.setProtocolType("OPC_UA_PLC4X");
            deviceInfo.setConnectionType("TCP");
            deviceInfo.setCollectionInterval(1000);
            return deviceInfo;
        }

        private static DeviceConnection connection() {
            String endpoint = System.getProperty("opcua.real.endpoint",
                    System.getenv().getOrDefault("OPCUA_REAL_ENDPOINT", DEFAULT_ENDPOINT));
            URI uri = normalizeUri(endpoint);

            DeviceConnection connection = new DeviceConnection();
            connection.setConnectionType("OPC_UA_PLC4X");
            connection.setUrl(endpoint);
            connection.setHost(uri.getHost());
            connection.setPort(uri.getPort() > 0 ? uri.getPort() : 4840);

            Map<String, Object> extJson = new LinkedHashMap<>();
            extJson.put("endpointUrl", endpoint);
            extJson.put("authType", "ANONYMOUS");
            extJson.put("discovery", false);
            extJson.put("securityPolicy", "NONE");
            extJson.put("messageSecurity", "NONE");
            extJson.put("requestTimeoutMs", 5000);
            extJson.put("connectTimeoutMs", 5000);
            extJson.put("subscriptionInterval", 1000);
            connection.setExtJson(extJson);
            return connection;
        }

        private static List<DataPoint> buildPoints(String deviceId) {
            int namespace = Integer.parseInt(System.getProperty("opcua.real.namespace",
                    System.getenv().getOrDefault("OPCUA_REAL_NAMESPACE", Integer.toString(DEFAULT_NAMESPACE))));
            int startNodeId = Integer.parseInt(System.getProperty("opcua.real.startNodeId",
                    System.getenv().getOrDefault("OPCUA_REAL_START_NODE_ID", Integer.toString(DEFAULT_START_NODE_ID))));
            int endNodeId = Integer.parseInt(System.getProperty("opcua.real.endNodeId",
                    System.getenv().getOrDefault("OPCUA_REAL_END_NODE_ID", Integer.toString(DEFAULT_END_NODE_ID))));

            List<DataPoint> points = new ArrayList<>();
            for (int identifier = startNodeId; identifier <= endNodeId; identifier++) {
                DataPoint point = new DataPoint();
                point.setPointId("real-node-" + identifier);
                point.setPointCode("real_node_" + identifier);
                point.setPointName("real_node_" + identifier);
                point.setDeviceId(deviceId);
                point.setAddress("ns=" + namespace + ";i=" + identifier);
                point.setDataType("DOUBLE");
                point.setReadWrite("RW");
                point.setStatus(1);
                point.setCollectionMode("SUBSCRIPTION");
                points.add(point);
            }
            return points;
        }

        private static URI normalizeUri(String endpoint) {
            String normalized = endpoint;
            if (normalized.startsWith("opc.tcp://")) {
                normalized = "http://" + normalized.substring("opc.tcp://".length());
            } else if (normalized.startsWith("opcua:tcp://")) {
                normalized = "http://" + normalized.substring("opcua:tcp://".length());
            }
            return URI.create(normalized);
        }
    }
}

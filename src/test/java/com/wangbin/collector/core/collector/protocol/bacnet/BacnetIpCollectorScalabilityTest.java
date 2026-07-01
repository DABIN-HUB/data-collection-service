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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BacnetIpCollectorScalabilityTest {

    @Test
    void shouldReadLargePointTableThroughReadPropertyMultiplePlans() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            List<DataPoint> points = new ArrayList<>();
            for (int i = 1; i <= 180; i++) {
                server.putReal(BacnetObjectType.ANALOG_INPUT, i, BacnetPropertyIdentifier.PRESENT_VALUE, i * 1.0f);
                points.add(point("p" + i, "analogInput:" + i + ".presentValue", "FLOAT", "dev-large"));
            }
            BacnetIpCollector collector = prepareCollector("dev-large", server.port(), points, 1000);

            Map<String, Object> values = collector.readPoints(points);

            assertEquals(180, values.size());
            assertEquals(1.0d, ((Number) values.get("p1")).doubleValue(), 1.0E-6);
            assertEquals(180.0d, ((Number) values.get("p180")).doubleValue(), 1.0E-6);
            Map<String, Object> status = collector.getDeviceStatus();
            assertEquals(0L, ((Number) status.get("readPropertyMultipleFallbackCount")).longValue());
        }
    }

    @Test
    void shouldHandleMultipleCollectorsConcurrently() throws Exception {
        try (FakeBacnetIpServer serverA = new FakeBacnetIpServer();
             FakeBacnetIpServer serverB = new FakeBacnetIpServer();
             FakeBacnetIpServer serverC = new FakeBacnetIpServer()) {
            serverA.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 11.0f);
            serverB.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 22.0f);
            serverC.putReal(BacnetObjectType.ANALOG_INPUT, 1, BacnetPropertyIdentifier.PRESENT_VALUE, 33.0f);

            BacnetIpCollector collectorA = prepareCollector("dev-a", serverA.port(),
                    List.of(point("p1", "analogInput:1.presentValue", "FLOAT", "dev-a")), 1000);
            BacnetIpCollector collectorB = prepareCollector("dev-b", serverB.port(),
                    List.of(point("p1", "analogInput:1.presentValue", "FLOAT", "dev-b")), 1000);
            BacnetIpCollector collectorC = prepareCollector("dev-c", serverC.port(),
                    List.of(point("p1", "analogInput:1.presentValue", "FLOAT", "dev-c")), 1000);

            var executor = Executors.newFixedThreadPool(3);
            try {
                CompletableFuture<Double> a = CompletableFuture.supplyAsync(() -> readAsDouble(collectorA), executor);
                CompletableFuture<Double> b = CompletableFuture.supplyAsync(() -> readAsDouble(collectorB), executor);
                CompletableFuture<Double> c = CompletableFuture.supplyAsync(() -> readAsDouble(collectorC), executor);

                assertEquals(11.0d, a.get(5, TimeUnit.SECONDS), 1.0E-6);
                assertEquals(22.0d, b.get(5, TimeUnit.SECONDS), 1.0E-6);
                assertEquals(33.0d, c.get(5, TimeUnit.SECONDS), 1.0E-6);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void shouldRemainStableAcrossRepeatedPollingCycles() throws Exception {
        try (FakeBacnetIpServer server = new FakeBacnetIpServer()) {
            List<DataPoint> points = new ArrayList<>();
            for (int i = 1; i <= 24; i++) {
                server.putReal(BacnetObjectType.ANALOG_INPUT, i, BacnetPropertyIdentifier.PRESENT_VALUE, i * 10.0f);
                points.add(point("p" + i, "analogInput:" + i + ".presentValue", "FLOAT", "dev-soak"));
            }
            BacnetIpCollector collector = prepareCollector("dev-soak", server.port(), points, 1000);

            for (int cycle = 0; cycle < 120; cycle++) {
                Map<String, Object> values = collector.readPoints(points);
                assertEquals(24, values.size(), "cycle=" + cycle);
                assertEquals(10.0d, ((Number) values.get("p1")).doubleValue(), 1.0E-6, "cycle=" + cycle);
                assertEquals(240.0d, ((Number) values.get("p24")).doubleValue(), 1.0E-6, "cycle=" + cycle);
            }

            Map<String, Object> status = collector.getDeviceStatus();
            assertTrue(((Number) status.get("totalReadCount")).longValue() >= 120L);
            assertEquals(0L, ((Number) status.get("requestTimeoutCount")).longValue());
            assertEquals(0L, ((Number) status.get("readPropertyMultipleFallbackCount")).longValue());
        }
    }

    private double readAsDouble(BacnetIpCollector collector) {
        try {
            Object value = collector.readPoint(point("p1", "analogInput:1.presentValue", "FLOAT",
                    collector.getDeviceInfo().getDeviceId()));
            return ((Number) value).doubleValue();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
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

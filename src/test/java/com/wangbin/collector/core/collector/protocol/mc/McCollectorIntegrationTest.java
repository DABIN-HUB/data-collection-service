package com.wangbin.collector.core.collector.protocol.mc;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode;
import com.wangbin.collector.core.collector.protocol.mc.support.FakeMcServer;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.connection.adapter.MitsubishiMcConnectionAdapter;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McCollectorIntegrationTest {

    @Test
    void shouldReadScalarPointThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            server.memory().putWord(McDeviceCode.D, 100, 321);

            DataPoint point = point("p1", "D100", "INT", "R");
            McCollector collector = prepareCollector(server, List.of(point));

            Object value = collector.readPoint(point);

            assertEquals(321.0, value);
            assertNotNull(collector.getLatestProcessResult("p1"));
            collector.disconnect();
        }
    }

    @Test
    void shouldBatchReadContiguousPointsThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            server.memory().putWord(McDeviceCode.D, 100, 11);
            server.memory().putWord(McDeviceCode.D, 101, 12);

            DataPoint p1 = point("p1", "D100", "INT", "R");
            DataPoint p2 = point("p2", "D101", "INT", "R");
            McCollector collector = prepareCollector(server, List.of(p1, p2));
            collector.rebuildReadPlans("dev-1", List.of(p1, p2));

            Map<String, Object> values = collector.readPoints(List.of(p1, p2));

            assertEquals(11.0, values.get("p1"));
            assertEquals(12.0, values.get("p2"));
            collector.disconnect();
        }
    }

    @Test
    void shouldRandomReadSparseWordPointsThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            server.memory().putWord(McDeviceCode.D, 100, 11);
            server.memory().putWord(McDeviceCode.D, 200, 22);

            DataPoint p1 = point("p1", "D100", "INT", "R");
            DataPoint p2 = point("p2", "D200", "INT", "R");
            McCollector collector = prepareCollector(server, List.of(p1, p2), 1000, "3E_BINARY", true);

            Map<String, Object> values = collector.readPoints(List.of(p1, p2));

            assertEquals(11.0, values.get("p1"));
            assertEquals(22.0, values.get("p2"));
            assertEquals(true, collector.getDeviceStatus().get("randomReadEnabled"));
            collector.disconnect();
        }
    }

    @Test
    void shouldWriteSinglePointThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            DataPoint point = point("p1", "D100", "INT", "RW");
            McCollector collector = prepareCollector(server, List.of(point));

            boolean result = collector.writePoint(point, 55);

            assertEquals(true, result);
            assertEquals(List.of(55), server.memory().snapshotWords(McDeviceCode.D, 100, 1));
            collector.disconnect();
        }
    }

    @Test
    void shouldWriteContiguousPointsThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            DataPoint p1 = point("p1", "D100", "INT", "RW");
            DataPoint p2 = point("p2", "D101", "INT", "RW");
            McCollector collector = prepareCollector(server, List.of(p1, p2));

            Map<DataPoint, Object> writes = new LinkedHashMap<>();
            writes.put(p1, 41);
            writes.put(p2, 42);

            Map<String, Boolean> result = collector.writePoints(writes);

            assertEquals(Map.of("p1", true, "p2", true), result);
            assertEquals(List.of(41, 42), server.memory().snapshotWords(McDeviceCode.D, 100, 2));
            collector.disconnect();
        }
    }

    @Test
    void shouldRandomWriteSparseWordPointsThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            DataPoint p1 = point("p1", "D100", "INT", "RW");
            DataPoint p2 = point("p2", "D200", "INT", "RW");
            McCollector collector = prepareCollector(server, List.of(p1, p2), 1000, "3E_BINARY", false, true);

            Map<DataPoint, Object> writes = new LinkedHashMap<>();
            writes.put(p1, 41);
            writes.put(p2, 42);

            Map<String, Boolean> result = collector.writePoints(writes);

            assertEquals(Map.of("p1", true, "p2", true), result);
            assertEquals(List.of(41), server.memory().snapshotWords(McDeviceCode.D, 100, 1));
            assertEquals(List.of(42), server.memory().snapshotWords(McDeviceCode.D, 200, 1));
            assertEquals(true, collector.getDeviceStatus().get("randomWriteEnabled"));
            collector.disconnect();
        }
    }

    @Test
    void shouldReadAndWriteWordBitOffsetThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            server.memory().putWord(McDeviceCode.D, 100, 0b0000);
            DataPoint point = point("p1", "D100.3", "boolean", "RW");
            McCollector collector = prepareCollector(server, List.of(point));

            assertEquals(false, collector.readPoint(point));
            assertEquals(true, collector.writePoint(point, true));
            assertEquals(true, collector.readPoint(point));
            assertEquals(List.of(0b1000), server.memory().snapshotWords(McDeviceCode.D, 100, 1));
            collector.disconnect();
        }
    }

    @Test
    void shouldReadStringPointThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            server.memory().putWord(McDeviceCode.D, 100, ('A' & 0xFF) | (('B' & 0xFF) << 8));
            server.memory().putWord(McDeviceCode.D, 101, ('C' & 0xFF) | (('D' & 0xFF) << 8));

            DataPoint point = stringPoint("p1", "D100", "R", 4);
            McCollector collector = prepareCollector(server, List.of(point));

            Object value = collector.readPoint(point);

            assertEquals("ABCD", value);
            collector.disconnect();
        }
    }

    @Test
    void shouldReadFloat64PointThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            putFloat64(server, 100, 123.456789d);
            DataPoint point = float64Point("p1", "D100", "R");
            McCollector collector = prepareCollector(server, List.of(point));

            Object value = collector.readPoint(point);

            assertEquals(123.456789d, ((Number) value).doubleValue(), 1.0E-9);
            collector.disconnect();
        }
    }

    @Test
    void shouldBatchReadContiguousFloat64PointsThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            putFloat64(server, 100, 10.25d);
            putFloat64(server, 104, -99.875d);
            DataPoint p1 = float64Point("p1", "D100", "R");
            DataPoint p2 = float64Point("p2", "D104", "R");
            McCollector collector = prepareCollector(server, List.of(p1, p2));
            collector.rebuildReadPlans("dev-1", List.of(p1, p2));

            Map<String, Object> values = collector.readPoints(List.of(p1, p2));

            assertEquals(10.25d, ((Number) values.get("p1")).doubleValue(), 1.0E-9);
            assertEquals(-99.875d, ((Number) values.get("p2")).doubleValue(), 1.0E-9);
            collector.disconnect();
        }
    }

    @Test
    void shouldWriteFloat64PointThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            DataPoint point = float64Point("p1", "D100", "RW");
            McCollector collector = prepareCollector(server, List.of(point));

            boolean result = collector.writePoint(point, 9876.54321d);

            assertTrue(result);
            assertEquals(float64Words(9876.54321d), server.memory().snapshotWords(McDeviceCode.D, 100, 4));
            collector.disconnect();
        }
    }

    @Test
    void shouldBatchWriteContiguousFloat64PointsThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            DataPoint p1 = float64Point("p1", "D100", "RW");
            DataPoint p2 = float64Point("p2", "D104", "RW");
            McCollector collector = prepareCollector(server, List.of(p1, p2));

            Map<DataPoint, Object> writes = new LinkedHashMap<>();
            writes.put(p1, 1.5d);
            writes.put(p2, -2.75d);

            Map<String, Boolean> result = collector.writePoints(writes);

            assertEquals(Map.of("p1", true, "p2", true), result);
            List<Integer> expected = new ArrayList<>();
            expected.addAll(float64Words(1.5d));
            expected.addAll(float64Words(-2.75d));
            assertEquals(expected, server.memory().snapshotWords(McDeviceCode.D, 100, 8));
            collector.disconnect();
        }
    }

    @Test
    void shouldWriteStringPointThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            DataPoint point = stringPoint("p1", "D100", "RW", 4);
            McCollector collector = prepareCollector(server, List.of(point));

            boolean result = collector.writePoint(point, "ABCD");

            assertTrue(result);
            assertEquals(List.of(
                    ('A' & 0xFF) | (('B' & 0xFF) << 8),
                    ('C' & 0xFF) | (('D' & 0xFF) << 8)
            ), server.memory().snapshotWords(McDeviceCode.D, 100, 2));
            collector.disconnect();
        }
    }

    @Test
    void shouldBatchWriteStringPointThroughRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            DataPoint point = stringPoint("p1", "D100", "RW", 4);
            McCollector collector = prepareCollector(server, List.of(point));

            Map<String, Boolean> result = collector.writePoints(Map.of(point, "ABCD"));

            assertEquals(Map.of("p1", true), result);
            assertEquals(List.of(
                    ('A' & 0xFF) | (('B' & 0xFF) << 8),
                    ('C' & 0xFF) | (('D' & 0xFF) << 8)
            ), server.memory().snapshotWords(McDeviceCode.D, 100, 2));
            collector.disconnect();
        }
    }

    @Test
    void shouldExposeMcEndCodeFailureFromRealSocketAdapter() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            server.forceEndCode(0x0051);
            DataPoint point = point("p1", "D100", "INT", "R");
            McCollector collector = prepareCollector(server, List.of(point));

            CollectorException exception = assertThrows(CollectorException.class, () -> collector.readPoint(point));

            assertNotNull(exception.getMessage());
            collector.disconnect();
        }
    }

    @Test
    void shouldFailOnUnexpectedResponseSubheader() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            server.forceUnexpectedSubheader(true);
            DataPoint point = point("p1", "D100", "INT", "R");
            McCollector collector = prepareCollector(server, List.of(point));

            CollectorException exception = assertThrows(CollectorException.class, () -> collector.readPoint(point));

            assertNotNull(exception.getMessage());
            collector.disconnect();
        }
    }

    @Test
    void shouldFailWhenResponseBodyIsShorterThanDeclaredLength() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            server.forceLengthMismatch(true);
            DataPoint point = point("p1", "D100", "INT", "R");
            McCollector collector = prepareCollector(server, List.of(point));

            CollectorException exception = assertThrows(CollectorException.class, () -> collector.readPoint(point));

            assertNotNull(exception.getMessage());
            assertFalse(collector.isConnected());
            assertEquals("DISCONNECTED", collector.getConnectionStatus());
            collector.disconnect();
        }
    }

    @Test
    void shouldTimeoutWhenServerRespondsTooSlowly() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            server.setResponseDelayMs(1500);
            DataPoint point = point("p1", "D100", "INT", "R");
            McCollector collector = prepareCollector(server, List.of(point), 200);

            CollectorException exception = assertThrows(CollectorException.class, () -> collector.readPoint(point));

            assertNotNull(exception.getMessage());
            assertFalse(collector.isConnected());
            collector.disconnect();
        }
    }

    @Test
    void shouldReadScalarPointThroughAsciiFrame() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            server.memory().putWord(McDeviceCode.D, 100, 321);
            DataPoint point = point("p1", "D100", "INT", "R");
            McCollector collector = prepareCollector(server, List.of(point), 1000, "3E_ASCII");

            Object value = collector.readPoint(point);

            assertEquals(321.0, value);
            collector.disconnect();
        }
    }

    @Test
    void shouldWriteScalarPointThroughAsciiFrame() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            DataPoint point = point("p1", "D100", "INT", "RW");
            McCollector collector = prepareCollector(server, List.of(point), 1000, "3E_ASCII");

            boolean result = collector.writePoint(point, 55);

            assertEquals(true, result);
            assertEquals(List.of(55), server.memory().snapshotWords(McDeviceCode.D, 100, 1));
            collector.disconnect();
        }
    }

    @Test
    void shouldReadScalarPointThrough4eBinaryFrame() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            server.memory().putWord(McDeviceCode.D, 100, 432);
            DataPoint point = point("p1", "D100", "INT", "R");
            McCollector collector = prepareCollector(server, List.of(point), 1000, "4E_BINARY");

            Object value = collector.readPoint(point);

            assertEquals(432.0, value);
            collector.disconnect();
        }
    }

    @Test
    void shouldFailAndDisconnectWhen4eResponseSerialMismatches() throws Exception {
        try (FakeMcServer server = new FakeMcServer()) {
            server.force4eSerialMismatch(true);
            DataPoint point = point("p1", "D100", "INT", "R");
            McCollector collector = prepareCollector(server, List.of(point), 1000, "4E_BINARY");

            CollectorException exception = assertThrows(CollectorException.class, () -> collector.readPoint(point));

            assertNotNull(exception.getMessage());
            assertFalse(collector.isConnected());
            assertEquals("DISCONNECTED", collector.getConnectionStatus());
            collector.disconnect();
        }
    }

    private McCollector prepareCollector(FakeMcServer server, List<DataPoint> points) throws Exception {
        return prepareCollector(server, points, 1000);
    }

    private McCollector prepareCollector(FakeMcServer server, List<DataPoint> points, int readTimeoutMs) throws Exception {
        return prepareCollector(server, points, readTimeoutMs, "3E_BINARY", false, false);
    }

    private McCollector prepareCollector(FakeMcServer server, List<DataPoint> points, int readTimeoutMs, String frameType) throws Exception {
        return prepareCollector(server, points, readTimeoutMs, frameType, false, false);
    }

    private McCollector prepareCollector(FakeMcServer server,
                                         List<DataPoint> points,
                                         int readTimeoutMs,
                                         String frameType,
                                         boolean randomReadEnabled) throws Exception {
        return prepareCollector(server, points, readTimeoutMs, frameType, randomReadEnabled, false);
    }

    private McCollector prepareCollector(FakeMcServer server,
                                         List<DataPoint> points,
                                         int readTimeoutMs,
                                         String frameType,
                                         boolean randomReadEnabled,
                                         boolean randomWriteEnabled) throws Exception {
        DeviceInfo deviceInfo = device();
        DeviceConnection connection = connection(server.port(), readTimeoutMs, frameType, randomReadEnabled, randomWriteEnabled);
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getDeviceContext("dev-1")).thenReturn(DeviceContext.of(deviceInfo, connection, points));
        when(configManager.getDataPoints("dev-1")).thenReturn(points);

        MitsubishiMcConnectionAdapter adapter = new MitsubishiMcConnectionAdapter(deviceInfo, connection);
        adapter.connect();

        McCollector collector = new McCollector();
        collector.init(deviceInfo);
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "connectionAdapter", adapter);
        ReflectionTestUtils.setField(collector, "connected", true);
        ReflectionTestUtils.setField(collector, "connectionStatus", "CONNECTED");
        ReflectionTestUtils.setField(collector, "timeout", connection.getReadTimeout());
        ReflectionTestUtils.setField(collector, "maxWordsPerRequest", connection.getInt("maxWordsPerRequest", 120));
        ReflectionTestUtils.setField(collector, "maxBitsPerRequest", connection.getInt("maxBitsPerRequest", 256));
        ReflectionTestUtils.setField(collector, "randomReadEnabled", connection.getBool("randomReadEnabled", false));
        ReflectionTestUtils.setField(collector, "maxRandomReadPoints", connection.getInt("maxRandomReadPoints", 8));
        ReflectionTestUtils.setField(collector, "randomWriteEnabled", connection.getBool("randomWriteEnabled", false));
        ReflectionTestUtils.setField(collector, "maxRandomWritePoints", connection.getInt("maxRandomWritePoints", 8));
        return collector;
    }

    private DeviceInfo device() {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-1");
        deviceInfo.setDeviceName("mc-device");
        deviceInfo.setProtocolType("MITSUBISHI_MC");
        deviceInfo.setCollectionInterval(1000);
        deviceInfo.setIpAddress("127.0.0.1");
        return deviceInfo;
    }

    private DeviceConnection connection(int port,
                                        int readTimeoutMs,
                                        String frameType,
                                        boolean randomReadEnabled,
                                        boolean randomWriteEnabled) {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");
        connection.setPort(port);
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setTimeout(readTimeoutMs);
        connection.setConnectionType("MITSUBISHI_MC");
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("networkNo", 0);
        ext.put("pcNo", 255);
        ext.put("ioNo", 1023);
        ext.put("stationNo", 0);
        ext.put("monitoringTimer", 16);
        ext.put("frameType", frameType);
        ext.put("randomReadEnabled", randomReadEnabled);
        ext.put("maxRandomReadPoints", 8);
        ext.put("randomWriteEnabled", randomWriteEnabled);
        ext.put("maxRandomWritePoints", 8);
        ext.put("maxWordsPerRequest", 120);
        ext.put("maxBitsPerRequest", 256);
        connection.setExtJson(ext);
        return connection;
    }

    private DataPoint point(String pointId, String address, String dataType, String readWrite) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setPointName(pointId);
        point.setDeviceId("dev-1");
        point.setAddress(address);
        point.setDataType(dataType);
        point.setReadWrite(readWrite);
        point.setStatus(1);
        point.setAdditionalConfig(new LinkedHashMap<>());
        return point;
    }

    private DataPoint stringPoint(String pointId, String address, String readWrite, int stringLength) {
        DataPoint point = point(pointId, address, "STRING", readWrite);
        Map<String, Object> additionalConfig = new LinkedHashMap<>();
        additionalConfig.put("stringLength", stringLength);
        additionalConfig.put("driverDataType", "STRING");
        point.setAdditionalConfig(additionalConfig);
        return point;
    }

    private DataPoint float64Point(String pointId, String address, String readWrite) {
        DataPoint point = point(pointId, address, "DOUBLE", readWrite);
        Map<String, Object> additionalConfig = new LinkedHashMap<>();
        additionalConfig.put("driverDataType", "FLOAT64");
        point.setAdditionalConfig(additionalConfig);
        return point;
    }

    private void putFloat64(FakeMcServer server, int startWord, double value) {
        List<Integer> words = float64Words(value);
        for (int i = 0; i < words.size(); i++) {
            server.memory().putWord(McDeviceCode.D, startWord + i, words.get(i));
        }
    }

    private List<Integer> float64Words(double value) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putDouble(value);
        byte[] payload = buffer.array();
        List<Integer> words = new ArrayList<>(4);
        for (int i = 0; i < payload.length; i += 2) {
            int low = payload[i] & 0xFF;
            int high = payload[i + 1] & 0xFF;
            words.add(low | (high << 8));
        }
        return words;
    }
}

package com.wangbin.collector.core.collector.protocol.fins;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsMemoryArea;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.protocol.OmronProtocolDescriptorProvider;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorRegistry;
import com.wangbin.collector.core.config.validator.ProtocolConnectionValidator;
import com.wangbin.collector.core.connection.factory.ConnectionFactory;
import com.wangbin.collector.core.connection.factory.provider.OmronConnectionAdapterProvider;
import com.wangbin.collector.core.connection.manager.ConnectionManager;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OmronFinsCollectorIntegrationTest {

    @Test
    void shouldReadWriteAndReadBitPointOverUdp() throws Exception {
        try (FakeFinsUdpServer server = new FakeFinsUdpServer()) {
            server.putWord(FinsMemoryArea.DM, 100, 123);
            server.putWord(FinsMemoryArea.DM, 200, 0);

            DataPoint wordPoint = point("p1", "DM:100", "INT16", "RW");
            DataPoint bitPoint = point("p2", "DM:200.3", "BOOLEAN", "RW");
            OmronFinsCollector collector = prepareCollector(server, List.of(wordPoint, bitPoint), 1000, true);
            try {
                assertEquals(123, collector.readPoint(wordPoint));
                assertTrue(collector.writePoint(wordPoint, 456));
                assertEquals(456, server.getWord(FinsMemoryArea.DM, 100));

                assertTrue(collector.writePoint(bitPoint, true));
                assertEquals(0b1000, server.getWord(FinsMemoryArea.DM, 200));
                assertEquals(true, collector.readPoint(bitPoint));
            } finally {
                collector.disconnect();
            }
        }
    }

    @Test
    void shouldFallbackBatchReadToSingleReadsWhenMergedRequestFails() throws Exception {
        try (FakeFinsUdpServer server = new FakeFinsUdpServer()) {
            server.putWord(FinsMemoryArea.DM, 100, 11);
            server.putWord(FinsMemoryArea.DM, 101, 12);
            server.rejectWordReadUnitCountAbove(1);

            DataPoint p1 = point("p1", "DM:100", "INT16", "R");
            DataPoint p2 = point("p2", "DM:101", "INT16", "R");
            OmronFinsCollector collector = prepareCollector(server, List.of(p1, p2), 1000, true);
            try {
                collector.rebuildReadPlans("dev-fins", List.of(p1, p2));

                Map<String, Object> values = collector.readPoints(List.of(p1, p2));
                Map<String, Object> status = collector.getDeviceStatus();

                assertEquals(11, ((Number) values.get("p1")).intValue());
                assertEquals(12, ((Number) values.get("p2")).intValue());
                assertEquals(3L, metric(status, "requestCount"));
                assertEquals(2L, metric(status, "requestSuccessCount"));
                assertEquals(1L, metric(status, "requestErrorCount"));
                assertEquals(2L, metric(status, "requestRetryCount"));
                assertEquals(1L, metric(status, "batchReadCount"));
                assertEquals(2L, metric(status, "mergedPointCount"));
                assertEquals(2L, metric(status, "singlePointFallbackCount"));
                assertEquals(1L, metric(status, "lastFallbackCount"));
                assertEquals(1L, metric(status, "batchFallbackCount"));
            } finally {
                collector.disconnect();
            }
        }
    }

    @Test
    void shouldBatchWriteContiguousWordPointsOverUdp() throws Exception {
        try (FakeFinsUdpServer server = new FakeFinsUdpServer()) {
            DataPoint p1 = point("p1", "DM:100", "INT16", "RW");
            DataPoint p2 = point("p2", "DM:101", "INT16", "RW");
            OmronFinsCollector collector = prepareCollector(server, List.of(p1, p2), 1000, true);
            try {
                Map<DataPoint, Object> writes = new LinkedHashMap<>();
                writes.put(p1, 41);
                writes.put(p2, 42);

                Map<String, Boolean> result = collector.writePoints(writes);
                Map<String, Object> status = collector.getDeviceStatus();

                assertEquals(Map.of("p1", true, "p2", true), result);
                assertEquals(41, server.getWord(FinsMemoryArea.DM, 100));
                assertEquals(42, server.getWord(FinsMemoryArea.DM, 101));
                assertEquals(1L, metric(status, "requestCount"));
                assertEquals(1L, metric(status, "requestSuccessCount"));
                assertEquals(0L, metric(status, "requestErrorCount"));
                assertEquals(1L, metric(status, "batchWriteCount"));
                assertEquals(2L, metric(status, "mergedPointCount"));
                assertEquals(2L, metric(status, "lastRequestUnitCount"));
            } finally {
                collector.disconnect();
            }
        }
    }

    @Test
    void shouldProtectSameWordBitWritesInSingleBatch() throws Exception {
        try (FakeFinsUdpServer server = new FakeFinsUdpServer()) {
            server.putWord(FinsMemoryArea.DM, 200, 0);
            DataPoint b1 = point("b1", "DM:200.1", "BOOLEAN", "RW");
            DataPoint b2 = point("b2", "DM:200.3", "BOOLEAN", "RW");
            OmronFinsCollector collector = prepareCollector(server, List.of(b1, b2), 1000, true);
            try {
                Map<DataPoint, Object> writes = new LinkedHashMap<>();
                writes.put(b1, true);
                writes.put(b2, true);

                Map<String, Boolean> result = collector.writePoints(writes);
                Map<String, Object> status = collector.getDeviceStatus();

                assertEquals(Map.of("b1", true, "b2", true), result);
                assertEquals(0b1010, server.getWord(FinsMemoryArea.DM, 200));
                assertEquals(2L, metric(status, "requestCount"));
                assertEquals(2L, metric(status, "requestSuccessCount"));
                assertEquals(0L, metric(status, "requestErrorCount"));
                assertEquals(1L, metric(status, "batchWriteCount"));
                assertEquals(2L, metric(status, "mergedPointCount"));
                assertEquals(1L, metric(status, "lastRequestUnitCount"));
            } finally {
                collector.disconnect();
            }
        }
    }

    @Test
    void shouldFallbackBatchWriteToSingleWritesWhenMergedRequestFails() throws Exception {
        try (FakeFinsUdpServer server = new FakeFinsUdpServer()) {
            server.rejectWordWriteUnitCountAbove(1);
            DataPoint p1 = point("p1", "DM:100", "INT16", "RW");
            DataPoint p2 = point("p2", "DM:101", "INT16", "RW");
            OmronFinsCollector collector = prepareCollector(server, List.of(p1, p2), 1000, true);
            try {
                Map<DataPoint, Object> writes = new LinkedHashMap<>();
                writes.put(p1, 51);
                writes.put(p2, 52);

                Map<String, Boolean> result = collector.writePoints(writes);
                Map<String, Object> status = collector.getDeviceStatus();

                assertEquals(Map.of("p1", true, "p2", true), result);
                assertEquals(51, server.getWord(FinsMemoryArea.DM, 100));
                assertEquals(52, server.getWord(FinsMemoryArea.DM, 101));
                assertEquals(3L, metric(status, "requestCount"));
                assertEquals(2L, metric(status, "requestSuccessCount"));
                assertEquals(1L, metric(status, "requestErrorCount"));
                assertEquals(2L, metric(status, "requestRetryCount"));
                assertEquals(1L, metric(status, "batchWriteCount"));
                assertEquals(2L, metric(status, "mergedPointCount"));
                assertEquals(2L, metric(status, "singlePointFallbackCount"));
                assertEquals(1L, metric(status, "lastFallbackCount"));
                assertEquals(1L, metric(status, "batchFallbackCount"));
            } finally {
                collector.disconnect();
            }
        }
    }

    @Test
    void shouldExecuteReadOnlyOperationalCommands() throws Exception {
        try (FakeFinsUdpServer server = new FakeFinsUdpServer()) {
            OmronFinsCollector collector = prepareCollector(server, List.of(), 1000, true);
            try {
                Map<?, ?> cpuStatus = (Map<?, ?>) collector.executeCommand("CPU_STATUS_READ", Map.of());
                Map<?, ?> clock = (Map<?, ?>) collector.executeCommand("CLOCK_READ", Map.of());

                assertEquals(1, cpuStatus.get("status"));
                assertEquals(2, cpuStatus.get("mode"));
                assertEquals("2026-07-17T09:30", clock.get("clock"));
                assertEquals(0, cpuStatus.get("endCode"));
            } finally {
                collector.disconnect();
            }
        }
    }

    private OmronFinsCollector prepareCollector(FakeFinsUdpServer server,
                                                List<DataPoint> points,
                                                int readTimeoutMs,
                                                boolean batchReadEnabled) throws Exception {
        DeviceInfo deviceInfo = deviceInfo(server.port());
        DeviceConnection connection = connection(server.port(), readTimeoutMs, batchReadEnabled);

        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getDeviceContext("dev-fins")).thenReturn(DeviceContext.of(deviceInfo, connection, points));
        when(configManager.getDataPoints("dev-fins")).thenReturn(points);

        ConnectionFactory connectionFactory = new ConnectionFactory(
                new ProtocolDescriptorRegistry(List.of(new OmronProtocolDescriptorProvider())),
                new ProtocolConnectionValidator(),
                List.of(new OmronConnectionAdapterProvider()));
        ConnectionManager connectionManager = new ConnectionManager(connectionFactory, configManager, null, null);

        OmronFinsCollector collector = new OmronFinsCollector();
        collector.init(deviceInfo);
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "connectionManager", connectionManager);
        collector.connect();
        return collector;
    }

    private DeviceInfo deviceInfo(int port) {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-fins");
        deviceInfo.setDeviceName("fins-device");
        deviceInfo.setProtocolType("OMRON_FINS");
        deviceInfo.setConnectionType("OMRON_FINS");
        deviceInfo.setCollectionInterval(1000);
        deviceInfo.setIpAddress("127.0.0.1");
        deviceInfo.setPort(port);
        return deviceInfo;
    }

    private DeviceConnection connection(int port, int readTimeoutMs, boolean batchReadEnabled) {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("OMRON_FINS");
        connection.setHost("127.0.0.1");
        connection.setPort(port);
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setTimeout(readTimeoutMs);
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("plcNetwork", 0);
        ext.put("plcNode", 1);
        ext.put("plcUnit", 0);
        ext.put("localNetwork", 0);
        ext.put("localNode", 10);
        ext.put("localUnit", 0);
        ext.put("serviceIdSeed", 1);
        ext.put("maxWordsPerRequest", 120);
        ext.put("maxBitsPerRequest", 256);
        ext.put("batchReadEnabled", batchReadEnabled);
        connection.setExtJson(ext);
        return connection;
    }

    private DataPoint point(String pointId, String address, String dataType, String readWrite) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setPointName(pointId);
        point.setDeviceId("dev-fins");
        point.setAddress(address);
        point.setDataType(dataType);
        point.setReadWrite(readWrite);
        point.setStatus(1);
        point.setAdditionalConfig(new LinkedHashMap<>());
        return point;
    }

    private long metric(Map<String, Object> status, String key) {
        return ((Number) status.get(key)).longValue();
    }

    private static final class FakeFinsUdpServer implements AutoCloseable {

        private final DatagramSocket socket;
        private final Thread serverThread;
        private final Map<String, Integer> words = new ConcurrentHashMap<>();
        private volatile boolean running = true;
        private volatile int rejectWordReadUnitCountAbove = Integer.MAX_VALUE;
        private volatile int rejectWordWriteUnitCountAbove = Integer.MAX_VALUE;
        private volatile Throwable failure;

        private FakeFinsUdpServer() throws Exception {
            this.socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            this.serverThread = new Thread(this::serve, "fake-fins-udp-server");
            this.serverThread.setDaemon(true);
            this.serverThread.start();
        }

        private int port() {
            return socket.getLocalPort();
        }

        private void putWord(FinsMemoryArea memoryArea, int wordAddress, int value) {
            words.put(key(memoryArea, wordAddress), value & 0xFFFF);
        }

        private int getWord(FinsMemoryArea memoryArea, int wordAddress) {
            return words.getOrDefault(key(memoryArea, wordAddress), 0);
        }

        private void rejectWordReadUnitCountAbove(int unitCount) {
            this.rejectWordReadUnitCountAbove = unitCount;
        }

        private void rejectWordWriteUnitCountAbove(int unitCount) {
            this.rejectWordWriteUnitCountAbove = unitCount;
        }

        @Override
        public void close() throws Exception {
            running = false;
            socket.close();
            serverThread.join(1000);
            if (failure != null) {
                throw new IllegalStateException("Fake FINS server failed", failure);
            }
        }

        private void serve() {
            byte[] buffer = new byte[4096];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    byte[] request = Arrays.copyOf(packet.getData(), packet.getLength());
                    byte[] response = handle(request);
                    DatagramPacket reply = new DatagramPacket(response, response.length, packet.getSocketAddress());
                    socket.send(reply);
                } catch (SocketException e) {
                    if (running) {
                        failure = e;
                    }
                    return;
                } catch (Exception e) {
                    failure = e;
                    running = false;
                    return;
                }
            }
        }

        private byte[] handle(byte[] request) {
            if (request.length < 12) {
                throw new IllegalArgumentException("FINS request too short");
            }
            int mainCommand = request[10] & 0xFF;
            int subCommand = request[11] & 0xFF;
            if (mainCommand != 0x01) {
                return handleOperationalCommand(request, mainCommand, subCommand);
            }
            if (request.length < 18) {
                throw new IllegalArgumentException("FINS memory request too short");
            }
            ResolvedArea area = resolveArea(request[12] & 0xFF);
            int startWord = ((request[13] & 0xFF) << 8) | (request[14] & 0xFF);
            int bitOffset = request[15] & 0xFF;
            int unitCount = ((request[16] & 0xFF) << 8) | (request[17] & 0xFF);
            int endCode = 0;
            byte[] payload = new byte[0];

            if (subCommand == 0x01) {
                if (!area.bitUnit() && unitCount > rejectWordReadUnitCountAbove) {
                    endCode = 0x2105;
                } else {
                    payload = area.bitUnit()
                            ? readBits(area.memoryArea(), startWord, bitOffset, unitCount)
                            : readWords(area.memoryArea(), startWord, unitCount);
                }
            } else if (subCommand == 0x02) {
                if (!area.bitUnit() && unitCount > rejectWordWriteUnitCountAbove) {
                    endCode = 0x2105;
                } else if (area.bitUnit()) {
                    writeBits(area.memoryArea(), startWord, bitOffset, Arrays.copyOfRange(request, 18, 18 + unitCount));
                } else {
                    writeWords(area.memoryArea(), startWord, Arrays.copyOfRange(request, 18, 18 + (unitCount * 2)));
                }
            } else {
                throw new IllegalArgumentException("Unsupported FINS sub command: " + subCommand);
            }

            byte[] response = new byte[14 + payload.length];
            System.arraycopy(request, 0, response, 0, Math.min(10, request.length));
            response[0] = (byte) 0xC0;
            response[10] = (byte) mainCommand;
            response[11] = (byte) subCommand;
            response[12] = (byte) ((endCode >> 8) & 0xFF);
            response[13] = (byte) (endCode & 0xFF);
            if (payload.length > 0) {
                System.arraycopy(payload, 0, response, 14, payload.length);
            }
            return response;
        }

        private byte[] handleOperationalCommand(byte[] request, int mainCommand, int subCommand) {
            byte[] payload;
            if (mainCommand == 0x05 && subCommand == 0x01) {
                payload = new byte[40];
                byte[] model = "CJ2M-CPU33".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                byte[] version = "V2.1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                System.arraycopy(model, 0, payload, 0, model.length);
                System.arraycopy(version, 0, payload, 20, version.length);
            } else if (mainCommand == 0x06 && subCommand == 0x01) {
                payload = new byte[]{0x01, 0x02, 0x00, 0x00, 0x00, 0x00};
            } else if (mainCommand == 0x07 && subCommand == 0x01) {
                payload = new byte[]{0x26, 0x07, 0x17, 0x09, 0x30, 0x00, 0x05};
            } else {
                throw new IllegalArgumentException("Unsupported FINS operational command");
            }
            byte[] response = new byte[14 + payload.length];
            System.arraycopy(request, 0, response, 0, 10);
            response[0] = (byte) 0xC0;
            response[10] = (byte) mainCommand;
            response[11] = (byte) subCommand;
            System.arraycopy(payload, 0, response, 14, payload.length);
            return response;
        }

        private byte[] readWords(FinsMemoryArea memoryArea, int startWord, int unitCount) {
            byte[] payload = new byte[unitCount * 2];
            for (int index = 0; index < unitCount; index++) {
                int value = getWord(memoryArea, startWord + index);
                payload[index * 2] = (byte) ((value >> 8) & 0xFF);
                payload[(index * 2) + 1] = (byte) (value & 0xFF);
            }
            return payload;
        }

        private byte[] readBits(FinsMemoryArea memoryArea, int startWord, int startBit, int unitCount) {
            byte[] payload = new byte[unitCount];
            for (int index = 0; index < unitCount; index++) {
                int absoluteBit = startBit + index;
                int wordOffset = absoluteBit / 16;
                int bitOffset = absoluteBit % 16;
                int wordValue = getWord(memoryArea, startWord + wordOffset);
                payload[index] = (byte) (((wordValue >> bitOffset) & 0x01) != 0 ? 0x01 : 0x00);
            }
            return payload;
        }

        private void writeWords(FinsMemoryArea memoryArea, int startWord, byte[] payload) {
            for (int index = 0; index + 1 < payload.length; index += 2) {
                int value = ((payload[index] & 0xFF) << 8) | (payload[index + 1] & 0xFF);
                putWord(memoryArea, startWord + (index / 2), value);
            }
        }

        private void writeBits(FinsMemoryArea memoryArea, int startWord, int startBit, byte[] payload) {
            for (int index = 0; index < payload.length; index++) {
                int absoluteBit = startBit + index;
                int wordOffset = absoluteBit / 16;
                int bitOffset = absoluteBit % 16;
                int wordAddress = startWord + wordOffset;
                int wordValue = getWord(memoryArea, wordAddress);
                if ((payload[index] & 0x01) != 0) {
                    wordValue |= (1 << bitOffset);
                } else {
                    wordValue &= ~(1 << bitOffset);
                }
                putWord(memoryArea, wordAddress, wordValue);
            }
        }

        private ResolvedArea resolveArea(int areaCode) {
            for (FinsMemoryArea memoryArea : FinsMemoryArea.values()) {
                if (memoryArea.code(false) == areaCode) {
                    return new ResolvedArea(memoryArea, false);
                }
                if (memoryArea.code(true) == areaCode) {
                    return new ResolvedArea(memoryArea, true);
                }
            }
            throw new IllegalArgumentException("Unsupported FINS memory area code: " + areaCode);
        }

        private String key(FinsMemoryArea memoryArea, int wordAddress) {
            return memoryArea.name() + ':' + wordAddress;
        }

        private record ResolvedArea(FinsMemoryArea memoryArea, boolean bitUnit) {
        }
    }
}

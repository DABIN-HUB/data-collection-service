package com.wangbin.collector.core.collector.protocol.mc;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.collector.protocol.mc.plan.McReadPlan;
import com.wangbin.collector.core.collector.protocol.mc.plan.McReadPlanItem;
import com.wangbin.collector.core.collector.protocol.mc.plan.McWritePlan;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McCollectorTest {

    private TestableMcCollector collector;

    @BeforeEach
    void setUp() throws Exception {
        collector = new TestableMcCollector();
        prepareCollector(collector);
    }

    @Test
    void shouldReuseConfiguredReadPlansForSchedulerSubBatch() throws Exception {
        DataPoint p1 = point("p1", "D100", "INT");
        DataPoint p2 = point("p2", "D101", "INT");
        DataPoint p3 = point("p3", "D102", "INT");
        List<DataPoint> configuredPoints = List.of(p1, p2, p3);

        collector.rebuildReadPlans("dev-1", configuredPoints);
        collector.primeReadValue("p2", 22);
        collector.primeReadValue("p3", 33);

        Map<String, Object> values = collector.readPoints(List.of(p2, p3));

        assertEquals(22.0, values.get("p2"));
        assertEquals(33.0, values.get("p3"));
        assertEquals(1, collector.executedPlanSegments.size());
        assertEquals("D:101-103", collector.executedPlanSegments.get(0));
        assertIterableEquals(List.of("p2", "p3"), collector.executedPlanPointIds.get(0));
    }

    @Test
    void shouldFallbackToDynamicPlanWhenRequestedPointWasNotPreconfigured() throws Exception {
        DataPoint configured = point("p1", "D100", "INT");
        collector.rebuildReadPlans("dev-1", List.of(configured));

        DataPoint runtimeOnly = point("p2", "D300", "INT");
        collector.primeReadValue("p2", 77);

        Map<String, Object> values = collector.readPoints(List.of(runtimeOnly));

        assertEquals(77.0, values.get("p2"));
        assertEquals(1, collector.executedPlanSegments.size());
        assertEquals("D:300-301", collector.executedPlanSegments.get(0));
        assertIterableEquals(List.of("p2"), collector.executedPlanPointIds.get(0));
    }

    @Test
    void shouldBatchContiguousScalarWritesIntoSingleProtocolRequest() throws Exception {
        DataPoint p1 = point("p1", "D100", "INT");
        p1.setReadWrite("RW");
        DataPoint p2 = point("p2", "D101", "INT");
        p2.setReadWrite("RW");

        Map<DataPoint, Object> writes = new LinkedHashMap<>();
        writes.put(p1, 11);
        writes.put(p2, 12);

        Map<String, Boolean> result = collector.writePoints(writes);

        assertEquals(Map.of("p1", true, "p2", true), result);
        assertEquals(1, collector.executedWriteSegments.size());
        assertEquals("D:100-102", collector.executedWriteSegments.get(0));
        assertEquals(0, collector.fallbackWritePointIds.size());
    }

    @Test
    void shouldFallbackToSingleWritesWhenBatchWritePlanFails() throws Exception {
        DataPoint p1 = point("p1", "D100", "INT");
        p1.setReadWrite("RW");
        DataPoint p2 = point("p2", "D101", "INT");
        p2.setReadWrite("RW");

        Map<DataPoint, Object> writes = new LinkedHashMap<>();
        writes.put(p1, 11);
        writes.put(p2, 12);

        collector.failBatchWrite = true;
        Map<String, Boolean> result = collector.writePoints(writes);

        assertEquals(Map.of("p1", true, "p2", true), result);
        assertEquals(1, collector.executedWriteSegments.size());
        assertEquals(List.of("p1", "p2"), collector.fallbackWritePointIds);
        assertTrue(collector.fallbackWriteValues.containsAll(List.of(11.0, 12.0)));
    }

    @Test
    void shouldReadWordBitOffsetPoint() throws Exception {
        DataPoint bitPoint = point("p1", "D100.3", "boolean");
        collector.wordValues.put("D100", 0b1000);

        Object value = collector.readPoint(bitPoint);

        assertEquals(true, value);
    }

    @Test
    void shouldWriteWordBitOffsetPointWithReadModifyWrite() throws Exception {
        DataPoint bitPoint = point("p1", "D100.2", "boolean");
        bitPoint.setReadWrite("RW");
        collector.wordValues.put("D100", 0);

        boolean result = collector.writePoint(bitPoint, true);

        assertEquals(true, result);
        assertEquals(0b0100, collector.wordValues.get("D100"));
    }

    @Test
    void shouldSerializeConcurrentBitOffsetWritesWithinSameWord() throws Exception {
        DataPoint bit2 = point("p1", "D100.2", "boolean");
        bit2.setReadWrite("RW");
        DataPoint bit3 = point("p2", "D100.3", "boolean");
        bit3.setReadWrite("RW");
        collector.wordValues.put("D100", 0);
        collector.blockFirstWordRead("D100");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> collector.writePoint(bit2, true));
            assertTrue(collector.awaitFirstWordRead("D100", 1, TimeUnit.SECONDS));
            Future<Boolean> second = executor.submit(() -> {
                secondStarted.countDown();
                return collector.writePoint(bit3, true);
            });

            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1, collector.concurrentReadCount.getOrDefault("D100", 0));
            collector.releaseBlockedWordRead("D100");

            assertEquals(true, first.get(5, TimeUnit.SECONDS));
            assertEquals(true, second.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(0b1100, collector.wordValues.get("D100"));
        assertEquals(2, collector.concurrentReadCount.getOrDefault("D100", 0));
    }

    @Test
    void shouldNotTreatMultiWordScalarAsRandomReadable() throws Exception {
        DataPoint point = point("p1", "D100", "LONG");
        Method method = McCollector.class.getDeclaredMethod("isRandomReadableAddress",
                com.wangbin.collector.core.collector.protocol.mc.domain.McAddress.class);
        method.setAccessible(true);

        boolean result = (boolean) method.invoke(collector,
                com.wangbin.collector.core.collector.protocol.mc.util.McAddressParser.parse(point));

        assertEquals(false, result);
    }

    private void prepareCollector(TestableMcCollector collector) throws Exception {
        collector.init(device());
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());
        DeviceConnection connection = new DeviceConnection();
        connection.setTimeout(1000);
        connection.setReadTimeout(1000);
        connection.setExtJson(new LinkedHashMap<>(Map.of("frameType", "3E_BINARY")));
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getDeviceContext("dev-1"))
                .thenReturn(DeviceContext.of(device(), connection, List.of()));
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "connected", true);
        ReflectionTestUtils.setField(collector, "connectionStatus", "CONNECTED");
    }

    private DeviceInfo device() {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-1");
        deviceInfo.setDeviceName("mc-device");
        deviceInfo.setProtocolType("MITSUBISHI_MC");
        deviceInfo.setCollectionInterval(1000);
        return deviceInfo;
    }

    private DataPoint point(String pointId, String address, String dataType) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setPointName(pointId);
        point.setDeviceId("dev-1");
        point.setAddress(address);
        point.setDataType(dataType);
        point.setReadWrite("R");
        point.setStatus(1);
        point.setAdditionalConfig(new LinkedHashMap<>());
        return point;
    }

    private static final class TestableMcCollector extends McCollector {

        private final Map<String, Object> readValues = new LinkedHashMap<>();
        private final List<String> executedPlanSegments = new ArrayList<>();
        private final List<List<String>> executedPlanPointIds = new ArrayList<>();
        private final List<String> executedWriteSegments = new ArrayList<>();
        private final List<String> fallbackWritePointIds = new ArrayList<>();
        private final List<Object> fallbackWriteValues = new ArrayList<>();
        private final Map<String, Integer> wordValues = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> blockingReadEntered = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> blockingReadRelease = new ConcurrentHashMap<>();
        private final Map<String, Integer> concurrentReadCount = new ConcurrentHashMap<>();
        private boolean failBatchWrite;

        private void primeReadValue(String pointId, Object value) {
            readValues.put(pointId, value);
        }

        private void blockFirstWordRead(String address) {
            String normalized = address.toUpperCase();
            blockingReadEntered.put(normalized, new CountDownLatch(1));
            blockingReadRelease.put(normalized, new CountDownLatch(1));
            concurrentReadCount.put(normalized, 0);
        }

        private boolean awaitFirstWordRead(String address, long timeout, TimeUnit unit) throws InterruptedException {
            CountDownLatch entered = blockingReadEntered.get(address.toUpperCase());
            return entered != null && entered.await(timeout, unit);
        }

        private void releaseBlockedWordRead(String address) {
            CountDownLatch release = blockingReadRelease.get(address.toUpperCase());
            if (release != null) {
                release.countDown();
            }
        }

        @Override
        protected byte[] executeBatchReadPayload(McReadPlan readPlan) {
            executedPlanSegments.add(readPlan.getSegmentKey());
            List<String> pointIds = new ArrayList<>();
            for (McReadPlanItem item : readPlan.getItems()) {
                pointIds.add(item.getPoint().getPointId());
            }
            executedPlanPointIds.add(pointIds);
            return new byte[0];
        }

        @Override
        protected void populateBatchReadResults(McReadPlan readPlan,
                                                Map<String, Object> results,
                                                byte[] payload) {
            for (McReadPlanItem item : readPlan.getItems()) {
                String pointId = item.getPoint().getPointId();
                results.put(pointId, readValues.get(pointId));
            }
        }

        @Override
        protected void executeBatchWritePlan(McWritePlan writePlan,
                                             Map<String, Object> valuesByPointKey) throws Exception {
            executedWriteSegments.add(writePlan.getSegmentKey());
            if (failBatchWrite) {
                throw new IllegalStateException("simulated batch write failure");
            }
        }

        @Override
        protected boolean doWritePoint(DataPoint point, Object value) throws Exception {
            if (point.getAddress() != null && point.getAddress().contains(".")) {
                return super.doWritePoint(point, value);
            }
            fallbackWritePointIds.add(point.getPointId());
            fallbackWriteValues.add(value);
            return true;
        }

        @Override
        protected Object doReadPoint(DataPoint point) throws Exception {
            if (point.getAddress() != null && point.getAddress().contains(".")) {
                return super.doReadPoint(point);
            }
            return super.doReadPoint(point);
        }

        @Override
        protected Object readWordContainerValue(com.wangbin.collector.core.collector.protocol.mc.domain.McAddress wordAddress)
                throws Exception {
            String normalized = wordAddress.getCanonicalAddress().toUpperCase();
            int readCount = concurrentReadCount.getOrDefault(normalized, 0) + 1;
            concurrentReadCount.put(normalized, readCount);
            CountDownLatch entered = blockingReadEntered.get(normalized);
            CountDownLatch release = blockingReadRelease.get(normalized);
            if (readCount == 1 && entered != null && release != null) {
                entered.countDown();
                if (!release.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out releasing blocked word read");
                }
            }
            return wordValues.getOrDefault(normalized, 0);
        }

        @Override
        protected void writeWordContainerValue(com.wangbin.collector.core.collector.protocol.mc.domain.McAddress wordAddress,
                                               int value,
                                               com.wangbin.collector.core.collector.protocol.mc.codec.McFrameCodec frameCodec) {
            wordValues.put(wordAddress.getCanonicalAddress().toUpperCase(), value);
        }
    }
}

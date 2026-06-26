package com.wangbin.collector.core.collector.protocol.mc;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", new DataQualityProcessor(null));
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
        private final Map<String, Integer> wordValues = new LinkedHashMap<>();
        private boolean failBatchWrite;

        private void primeReadValue(String pointId, Object value) {
            readValues.put(pointId, value);
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
        protected boolean doWritePoint(DataPoint point, Object value) {
            if (point.getAddress() != null && point.getAddress().contains(".")) {
                String[] parts = point.getAddress().split("\\.");
                String baseAddress = parts[0].toUpperCase();
                int bitIndex = Integer.parseInt(parts[1]);
                int current = wordValues.getOrDefault(baseAddress, 0);
                boolean targetBit = Boolean.TRUE.equals(value)
                        || "true".equalsIgnoreCase(String.valueOf(value))
                        || (value instanceof Number number && number.intValue() != 0);
                if (targetBit) {
                    current |= (1 << bitIndex);
                } else {
                    current &= ~(1 << bitIndex);
                }
                wordValues.put(baseAddress, current);
                return true;
            }
            fallbackWritePointIds.add(point.getPointId());
            fallbackWriteValues.add(value);
            return true;
        }

        @Override
        protected Object doReadPoint(DataPoint point) throws Exception {
            if (point.getAddress() != null && point.getAddress().contains(".")) {
                String[] parts = point.getAddress().split("\\.");
                int current = wordValues.getOrDefault(parts[0].toUpperCase(), 0);
                int bitIndex = Integer.parseInt(parts[1]);
                return ((current >> bitIndex) & 0x01) == 1;
            }
            return super.doReadPoint(point);
        }
    }
}

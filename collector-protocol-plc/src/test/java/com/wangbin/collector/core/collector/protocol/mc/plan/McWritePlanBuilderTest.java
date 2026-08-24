package com.wangbin.collector.core.collector.protocol.mc.plan;

import com.wangbin.collector.common.domain.entity.DataPoint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McWritePlanBuilderTest {

    private final McWritePlanBuilder builder = new McWritePlanBuilder();

    @Test
    void shouldMergeContiguousWordAddressesIntoSingleWritePlan() {
        Map<DataPoint, Object> pointValues = new LinkedHashMap<>();
        pointValues.put(point("p1", "D100", "INT"), 11);
        pointValues.put(point("p2", "D101", "WORD"), 12);
        pointValues.put(point("p3", "D102", "INT"), 13);

        var plans = builder.build(pointValues, 10, 10);

        assertEquals(1, plans.size());
        McWritePlan plan = plans.get(0);
        assertFalse(plan.isBitUnit());
        assertEquals("D:100-103", plan.getSegmentKey());
        assertEquals(3, plan.getTotalUnitCount());
        assertEquals(6, plan.getPayloadByteLength());
        assertEquals(3, plan.getPointCount());
    }

    @Test
    void shouldSplitWritePlansWhenAddressGapExists() {
        Map<DataPoint, Object> pointValues = new LinkedHashMap<>();
        pointValues.put(point("p1", "D100", "INT"), 11);
        pointValues.put(point("p2", "D103", "INT"), 12);

        var plans = builder.build(pointValues, 10, 10);

        assertEquals(2, plans.size());
        assertEquals("D:100-101", plans.get(0).getSegmentKey());
        assertEquals("D:103-104", plans.get(1).getSegmentKey());
    }

    @Test
    void shouldMergeContiguousBitAddressesIntoSingleWritePlan() {
        Map<DataPoint, Object> pointValues = new LinkedHashMap<>();
        pointValues.put(point("p1", "M0", "BOOLEAN"), true);
        pointValues.put(point("p2", "M1", "BOOLEAN"), false);
        pointValues.put(point("p3", "M2", "BOOLEAN"), true);

        var plans = builder.build(pointValues, 10, 10);

        assertEquals(1, plans.size());
        McWritePlan plan = plans.get(0);
        assertTrue(plan.isBitUnit());
        assertEquals("M:0-3", plan.getSegmentKey());
        assertEquals(3, plan.getTotalUnitCount());
        assertEquals(2, plan.getPayloadByteLength());
    }

    private DataPoint point(String pointId, String address, String dataType) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setAddress(address);
        point.setDataType(dataType);
        point.setAdditionalConfig(new LinkedHashMap<>());
        return point;
    }
}

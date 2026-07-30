package com.wangbin.collector.core.collector.protocol.mc.plan;

import com.wangbin.collector.common.domain.entity.DataPoint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McReadPlanBuilderTest {

    private final McReadPlanBuilder builder = new McReadPlanBuilder();

    @Test
    void shouldMergeContiguousWordAddressesIntoSinglePlan() {
        List<McReadPlan> plans = builder.build(List.of(
                point("p1", "D100", "INT"),
                point("p2", "D101", "WORD"),
                point("p3", "D102", "LONG")
        ), 10, 10);

        assertEquals(1, plans.size());
        McReadPlan plan = plans.get(0);
        assertEquals("D:100-104", plan.getSegmentKey());
        assertFalse(plan.isBitUnit());
        assertEquals(100, plan.getStartDeviceNumber());
        assertEquals(104, plan.getEndDeviceNumberExclusive());
        assertEquals(4, plan.getTotalUnitCount());
        assertEquals(8, plan.getExpectedPayloadLength());
        assertEquals(3, plan.getItems().size());
        assertEquals(0, plan.getItems().get(0).getUnitOffset());
        assertEquals(1, plan.getItems().get(1).getUnitOffset());
        assertEquals(2, plan.getItems().get(2).getUnitOffset());
        assertEquals(4, plan.getItems().get(2).getPayloadByteOffset());
        assertEquals(4, plan.getItems().get(2).getPayloadByteLength());
    }

    @Test
    void shouldSplitPlansWhenAddressGapExists() {
        List<McReadPlan> plans = builder.build(List.of(
                point("p1", "D100", "INT"),
                point("p2", "D103", "INT")
        ), 10, 10);

        assertEquals(2, plans.size());
        assertEquals("D:100-101", plans.get(0).getSegmentKey());
        assertEquals("D:103-104", plans.get(1).getSegmentKey());
    }

    @Test
    void shouldSplitPlansWhenWordCapacityWouldBeExceeded() {
        List<McReadPlan> plans = builder.build(List.of(
                point("p1", "D100", "LONG"),
                point("p2", "D102", "INT"),
                point("p3", "D103", "INT")
        ), 3, 10);

        assertEquals(2, plans.size());
        assertEquals("D:100-103", plans.get(0).getSegmentKey());
        assertEquals(3, plans.get(0).getTotalUnitCount());
        assertEquals(2, plans.get(0).getPointCount());
        assertEquals("D:103-104", plans.get(1).getSegmentKey());
        assertEquals(1, plans.get(1).getPointCount());
    }

    @Test
    void shouldMergeOverlappingWordRanges() {
        List<McReadPlan> plans = builder.build(List.of(
                point("p1", "D100", "LONG"),
                point("p2", "D101", "WORD")
        ), 10, 10);

        assertEquals(1, plans.size());
        McReadPlan plan = plans.get(0);
        assertEquals("D:100-102", plan.getSegmentKey());
        assertEquals(2, plan.getTotalUnitCount());
        assertEquals(2, plan.getItems().size());
        assertEquals(1, plan.getItems().get(1).getUnitOffset());
    }

    @Test
    void shouldMergeContiguousBitAddressesAndBitArrays() {
        List<McReadPlan> plans = builder.build(List.of(
                point("p1", "M0", "BOOLEAN"),
                point("p2", "M1", "BOOLEAN"),
                point("p3", "M2[2]", "BOOLEAN")
        ), 10, 10);

        assertEquals(1, plans.size());
        McReadPlan plan = plans.get(0);
        assertTrue(plan.isBitUnit());
        assertEquals("M:0-4", plan.getSegmentKey());
        assertEquals(4, plan.getTotalUnitCount());
        assertEquals(2, plan.getExpectedPayloadLength());
        assertEquals(3, plan.getPointCount());
        assertEquals(2, plan.getItems().get(2).getUnitOffset());
        assertEquals(2, plan.getItems().get(2).getUnitCount());
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
package com.wangbin.collector.core.collector.protocol.s7.plan;

import com.wangbin.collector.common.domain.entity.DataPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S7ReadPlanBuilderTest {

    private final S7ReadPlanBuilder builder = new S7ReadPlanBuilder();

    @Test
    void shouldGroupPointsByDbSegmentAndOffsetOrder() {
        List<S7ReadPlan> plans = builder.build(List.of(
                point("p1", "DB1.DBW0", "INT"),
                point("p2", "DB1.DBD4", "INT"),
                point("p3", "DB2.DBW0", "INT"),
                point("p4", "M10.0", "BOOL")
        ), 8);

        assertEquals(3, plans.size());
        assertEquals("DB:1", plans.get(0).getSegmentKey());
        assertEquals(2, plans.get(0).getPointCount());
        assertEquals("DB:2", plans.get(1).getSegmentKey());
        assertEquals("MERKER", plans.get(2).getSegmentKey());
    }

    @Test
    void shouldSplitPlanWhenBatchLimitIsReached() {
        List<S7ReadPlan> plans = builder.build(List.of(
                point("p1", "DB1.DBW0", "INT"),
                point("p2", "DB1.DBW2", "INT"),
                point("p3", "DB1.DBW4", "INT")
        ), 2);

        assertEquals(2, plans.size());
        assertEquals(2, plans.get(0).getPointCount());
        assertEquals(1, plans.get(1).getPointCount());
    }

    @Test
    void shouldBuildBlockReadAddressForContiguousNumericPlan() {
        List<S7ReadPlan> plans = builder.build(List.of(
                point("p1", "DB1.DBW0", "INT"),
                point("p2", "DB1.DBW2", "INT"),
                point("p3", "DB1.DBD4", "DINT")
        ), 8);

        S7ReadPlan plan = plans.get(0);
        assertTrue(plan.canUseBlockRead());
        assertTrue(plan.isBlockOptimizable());
        assertEquals("%DB1:0:BYTE[8]", plan.getBlockReadAddress());
        assertEquals(3, plan.getItems().size());
        assertEquals(0, plan.getItems().get(0).getByteOffset());
        assertEquals(2, plan.getItems().get(1).getByteOffset());
        assertEquals(4, plan.getItems().get(2).getByteOffset());
    }

    @Test
    void shouldKeepBoolPointOutOfBlockOptimizedPlan() {
        List<S7ReadPlan> plans = builder.build(List.of(
                point("p1", "DB1.DBW0", "INT"),
                point("p2", "DB1.DBX2.0", "BOOLEAN"),
                point("p3", "DB1.DBW4", "INT")
        ), 8);

        assertEquals(3, plans.size());
        assertTrue(plans.get(0).isBlockOptimizable());
        assertFalse(plans.get(1).isBlockOptimizable());
        assertFalse(plans.get(1).canUseBlockRead());
        assertTrue(plans.get(2).isBlockOptimizable());
    }

    private DataPoint point(String pointId, String address, String dataType) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setPointName(pointId);
        point.setAddress(address);
        point.setDataType(dataType);
        point.setStatus(1);
        return point;
    }
}


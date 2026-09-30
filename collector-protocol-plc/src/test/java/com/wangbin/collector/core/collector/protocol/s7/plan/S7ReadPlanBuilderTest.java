package com.wangbin.collector.core.collector.protocol.s7.plan;

import com.wangbin.collector.common.domain.entity.DataPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S7ReadPlanBuilderTest {

    private final S7ReadPlanBuilder builder = new S7ReadPlanBuilder();

    @Test
    void shouldGroupPointsByDbSegmentAndOffsetOrder() {
        List<S7ReadPlan> plans = builder.build(List.of(
                point("p1", "DB1.DBW0", "INT"),
                point("p2", "DB1.DBW2", "INT"),
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

    @Test
    void shouldSplitBlockPlanAtGapWithoutReadingUnconfiguredBytes() {
        List<S7ReadPlan> plans = builder.build(List.of(
                point("p1", "DB1.DBW0", "INT"),
                point("p2", "DB1.DBW20", "INT"),
                point("p3", "DB1.DBW22", "INT")
        ), 8);

        assertEquals(2, plans.size());
        assertFalse(plans.get(0).canUseBlockRead());
        assertEquals(2, plans.get(1).getPointCount());
        assertEquals("%DB1:20:BYTE[4]", plans.get(1).getBlockReadAddress());
        assertEquals(20, plans.get(1).getStartOffset());
        assertEquals(24, plans.get(1).getEndOffsetExclusive());
    }

    @Test
    void shouldNeverSpanDbOrMemoryAreas() {
        List<S7ReadPlan> plans = builder.build(List.of(
                point("db1a", "DB1.DBW2", "INT"),
                point("db1b", "DB1.DBW4", "INT"),
                point("db2a", "DB2.DBW2", "INT"),
                point("db2b", "DB2.DBW4", "INT"),
                point("input", "IW2", "INT"),
                point("output", "QW2", "INT"),
                point("merker", "MW2", "INT")
        ), 8);

        assertEquals(5, plans.size());
        assertEquals("%DB1:2:BYTE[4]", plans.get(0).getBlockReadAddress());
        assertEquals("%DB2:2:BYTE[4]", plans.get(1).getBlockReadAddress());
        assertEquals("INPUT", plans.get(2).getSegmentKey());
        assertEquals("MERKER", plans.get(3).getSegmentKey());
        assertEquals("OUTPUT", plans.get(4).getSegmentKey());
        for (int index = 2; index < plans.size(); index++) {
            assertEquals(1, plans.get(index).getPointCount());
            assertFalse(plans.get(index).canUseBlockRead());
        }
    }

    @Test
    void shouldTrackBoolBitAndNonzeroMultibyteOffsets() {
        List<S7ReadPlan> plans = builder.build(List.of(
                point("a", "DB1.DBX9.3", "BOOLEAN"),
                point("b", "DB1.DBX9.7", "BOOLEAN"),
                point("c", "DB1.DBD12", "REAL")
        ), 8);

        assertEquals(2, plans.size());
        S7ReadPlan bits = plans.get(0);
        assertFalse(bits.canUseBlockRead());
        assertEquals(9, bits.getStartOffset());
        assertEquals(10, bits.getEndOffsetExclusive());
        assertEquals(3, bits.getItems().get(0).getBitOffset());
        assertEquals(7, bits.getItems().get(1).getBitOffset());
        S7ReadPlan real = plans.get(1);
        assertEquals(12, real.getStartOffset());
        assertEquals(16, real.getEndOffsetExclusive());
        assertEquals(4, real.getItems().get(0).getByteLength());
    }

    @Test
    void shouldSplitAtByteSpanAndBatchBoundaries() {
        List<S7ReadPlan> plans = builder.build(List.of(
                point("a", "DB1:0:USINT[254]", "BYTE"),
                point("b", "DB1.DBW254", "INT"),
                point("c", "DB1.DBW256", "INT")
        ), 2);

        assertEquals(2, plans.size());
        assertTrue(plans.get(0).canUseBlockRead());
        assertEquals("%DB1:0:BYTE[256]", plans.get(0).getBlockReadAddress());
        assertEquals(256, plans.get(0).getEstimatedByteSpan());
        assertFalse(plans.get(1).canUseBlockRead());
        assertEquals(256, plans.get(1).getStartOffset());
        assertEquals(258, plans.get(1).getEndOffsetExclusive());

        List<S7ReadPlan> oversized = builder.build(List.of(
                point("large", "DB1:0:USINT[257]", "BYTE"),
                point("next", "DB1.DBW257", "INT")
        ), 8);
        assertFalse(oversized.get(0).canUseBlockRead());
        assertFalse(oversized.get(1).canUseBlockRead());
    }

    @Test
    void shouldMeasureEncodedStringAtNonzeroOffsetWithoutBlockRead() {
        List<S7ReadPlan> plans = builder.build(List.of(point("text", "DB1.DBB10:STRING(3)", "STRING")), 8);

        assertEquals(1, plans.size());
        assertEquals(10, plans.get(0).getStartOffset());
        assertEquals(15, plans.get(0).getEndOffsetExclusive());
        assertEquals(5, plans.get(0).getItems().get(0).getByteLength());
        assertFalse(plans.get(0).canUseBlockRead());
    }

    @Test
    void shouldRejectStringByteSpanOverflow() {
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(
                point("large", "DB1.DBB0:WSTRING(2147483647)", "STRING")
        ), 8));
    }

    @Test
    void shouldRejectExplicitNonpositiveBatchLimit() {
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(
                point("p1", "DB1.DBW0", "INT")), 0));
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(
                point("p1", "DB1.DBW0", "INT")), -100));
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


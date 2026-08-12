package com.wangbin.collector.soak;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealEnvironmentSoakITTest {

    @Test
    void fixedCapacityMustDisableAdaptiveCollection() {
        assertNull(RealEnvironmentSoakIT.fixedCapacityInvalidReason(false));
        assertTrue(RealEnvironmentSoakIT.fixedCapacityInvalidReason(true)
                .contains("collector.adaptive-collection.enabled=false"));
    }

    @Test
    void r1TheoreticalRateMustBe2000() {
        assertEquals(2000.0d, RealEnvironmentSoakIT.theoreticalCollectorRate(10_000, 5_000L), 0.0001d);
    }

    @Test
    void r2TheoreticalRateMustBe1000() {
        assertEquals(1000.0d, RealEnvironmentSoakIT.theoreticalCollectorRate(10_000, 10_000L), 0.0001d);
    }

    @Test
    void measurementMustBeInvalidWhenRateDeviationExceedsThreshold() {
        RealEnvironmentSoakIT.LoadProfileResult result =
                RealEnvironmentSoakIT.evaluateLoadProfile(2_223.0d, 2_000.0d, 5.0d);

        assertFalse(result.valid());
        assertEquals("INVALID_LOAD_PROFILE", result.invalidReason());
    }

    @Test
    void measurementMustRemainValidWithinRateTolerance() {
        RealEnvironmentSoakIT.LoadProfileResult result =
                RealEnvironmentSoakIT.evaluateLoadProfile(2_080.0d, 2_000.0d, 5.0d);

        assertTrue(result.valid());
        assertNull(result.invalidReason());
    }

    @Test
    void warmupAndDrainMustNotEnterCollectorMeasurementDelta() {
        long warmupAndPrevious = 10_000L;
        long measurementRows = 600_000L;
        long drainRows = 20_000L;

        assertEquals(measurementRows,
                RealEnvironmentSoakIT.measurementCounterDelta(warmupAndPrevious, warmupAndPrevious + measurementRows));
        assertEquals(measurementRows,
                RealEnvironmentSoakIT.measurementCounterDelta(warmupAndPrevious + drainRows,
                        warmupAndPrevious + drainRows + measurementRows));
    }

    @Test
    void fixedCadenceMustNotUseAdaptiveInterval() {
        RealEnvironmentSoakIT.LoadProfileResult result =
                RealEnvironmentSoakIT.evaluateLoadProfile(2_200.0d, 2_000.0d, 5.0d);

        assertFalse(result.valid());
    }

    @Test
    void capacitySummaryMustReportTheoreticalAndActualRate() {
        double theoretical = RealEnvironmentSoakIT.theoreticalCollectorRate(10_000, 5_000L);
        double actual = 1_980.0d;

        assertEquals(2000.0d, theoretical, 0.0001d);
        assertEquals(1.0d, RealEnvironmentSoakIT.rateDeviationPercent(actual, theoretical), 0.0001d);
    }
}

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

    @Test
    void s1TheoreticalRateMustBeAbout1500() {
        assertEquals(1_499.925d, RealEnvironmentSoakIT.theoreticalCollectorRate(10_000, 6_667L), 0.001d);
    }

    @Test
    void overallHealthMustUseWorstModuleStatus() {
        assertEquals("FAILED", RealEnvironmentSoakIT.overallHealth(java.util.Map.of(
                "Stream", new RealEnvironmentSoakIT.HealthStatus("HEALTHY", "ok", null, 0, 0, true),
                "History", new RealEnvironmentSoakIT.HealthStatus("FAILED", "pending", "t1", 1, 1, false))));
        assertEquals("DEGRADED", RealEnvironmentSoakIT.overallHealth(java.util.Map.of(
                "JVM", new RealEnvironmentSoakIT.HealthStatus("DEGRADED", "heap trend", "t1", 1, 2, false))));
        assertEquals("HEALTHY", RealEnvironmentSoakIT.overallHealth(java.util.Map.of(
                "Pipeline", new RealEnvironmentSoakIT.HealthStatus("HEALTHY", "ok", null, 0, 0, true))));
    }

    @Test
    void boundedSamplesMustOverwriteOldValues() {
        RealEnvironmentSoakIT.BoundedIntSamples samples = new RealEnvironmentSoakIT.BoundedIntSamples(3);

        samples.add(1);
        samples.add(2);
        samples.add(3);
        samples.add(4);

        assertEquals(java.util.List.of(2, 3, 4), samples.snapshot());
        assertEquals(3L, samples.stats().get("sampleCount"));
        assertEquals(4L, samples.stats().get("totalRecorded"));
        assertEquals(1L, samples.stats().get("overwrittenSamples"));
    }

    @Test
    void burstTrackerMustKeepPeakWithoutGrowingBuckets() {
        RealEnvironmentSoakIT.BurstTracker tracker = new RealEnvironmentSoakIT.BurstTracker(100);

        tracker.record(0, 10);
        tracker.record(99, 20);
        tracker.record(100, 5);
        tracker.record(200, 6);

        assertEquals(30L, tracker.max());
        tracker.clear();
        assertEquals(0L, tracker.max());
    }
}

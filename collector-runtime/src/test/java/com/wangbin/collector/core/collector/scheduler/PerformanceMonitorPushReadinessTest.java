package com.wangbin.collector.core.collector.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceMonitorPushReadinessTest {
    @Test
    void pushReadinessUsesSourceGenerationWithoutChangingBatchMetrics() {
        PerformanceMonitor monitor = new PerformanceMonitor();
        monitor.resetDeviceRuntimeWindow("dev-1", 10L);

        monitor.recordSuccessfulData("dev-1", 10L, 100L);
        DevicePerformance performance = monitor.devicePerformance.get("dev-1");
        assertTrue(performance.firstSuccessTime > 0L);
        assertEquals(0L, performance.successfulBatches.get());
        assertEquals(0L, performance.totalPoints.get());
        assertEquals(0L, performance.totalExecutionTime.get());
        assertTrue(performance.recentResponseTimes.isEmpty());

        monitor.resetDeviceRuntimeWindow("dev-1", 11L);
        monitor.recordSuccessfulData("dev-1", 10L, 200L);
        assertEquals(0L, performance.firstSuccessTime);
        assertEquals(0L, performance.lastSuccessTime);

        monitor.recordSuccessfulData("dev-1", 11L, 300L);
        assertTrue(performance.firstSuccessTime > 0L);
        assertEquals(0L, performance.successfulBatches.get());
        assertEquals(0L, performance.totalPoints.get());
        assertEquals(0L, performance.totalExecutionTime.get());
        assertTrue(performance.recentResponseTimes.isEmpty());
    }

    @Test
    void pollingSuccessStillUpdatesBatchMetrics() {
        PerformanceMonitor monitor = new PerformanceMonitor();
        monitor.resetDeviceRuntimeWindow("dev-1", 10L);

        monitor.recordBatchSuccess("dev-1", 10L, 3, 25L);

        DevicePerformance performance = monitor.devicePerformance.get("dev-1");
        assertEquals(1L, performance.successfulBatches.get());
        assertEquals(3L, performance.totalPoints.get());
        assertEquals(25L, performance.totalExecutionTime.get());
        assertEquals(1, performance.recentResponseTimes.size());
        assertTrue(performance.firstSuccessTime > 0L);
    }
}

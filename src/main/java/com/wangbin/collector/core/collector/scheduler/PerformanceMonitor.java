package com.wangbin.collector.core.collector.scheduler;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduler performance monitor.
 */
@Slf4j
public class PerformanceMonitor {
    final Map<String, DevicePerformance> devicePerformance = new ConcurrentHashMap<>();
    private final AtomicLong totalProcessedPoints = new AtomicLong(0);
    private final AtomicLong totalSuccessfulBatches = new AtomicLong(0);
    private final AtomicLong totalFailedBatches = new AtomicLong(0);
    private final Map<Integer, Long> timeSliceExecutionTimes = new ConcurrentHashMap<>();

    private final AtomicLong peakMemoryUsage = new AtomicLong(0);
    private final AtomicLong cpuUsage = new AtomicLong(0);

    private final Map<String, Long> slowestDevices = new ConcurrentHashMap<>();
    private final Map<Integer, Long> overloadedSlices = new ConcurrentHashMap<>();
    private final AtomicBoolean recentTimeSliceTimeout = new AtomicBoolean(false);

    private long lastStatisticsTime = System.currentTimeMillis();

    void initializeDeviceBatchSize(String deviceId, int initialBatchSize, int maxBatchSize) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        devicePerformance.computeIfAbsent(deviceId, DevicePerformance::new)
                .initializeBatchWindow(initialBatchSize, maxBatchSize);
    }

    void recordTimeSliceExecution(int sliceIndex, long executionTime, AtomicInteger timeSliceInterval) {
        timeSliceExecutionTimes.put(sliceIndex, executionTime);
        if (executionTime > timeSliceInterval.get()) {
            overloadedSlices.put(sliceIndex, executionTime);
            recentTimeSliceTimeout.set(true);
        }
    }

    void recordBatchSuccess(String deviceId, int pointCount, long executionTime) {
        totalProcessedPoints.addAndGet(pointCount);
        totalSuccessfulBatches.incrementAndGet();

        DevicePerformance perf = devicePerformance.computeIfAbsent(
                deviceId, DevicePerformance::new
        );
        perf.recordSuccess(pointCount, executionTime);

        if (executionTime > 200) {
            slowestDevices.put(deviceId, executionTime);
        }
    }

    void recordBatchFailure(String deviceId) {
        totalFailedBatches.incrementAndGet();

        DevicePerformance perf = devicePerformance.computeIfAbsent(
                deviceId, DevicePerformance::new
        );
        perf.recordFailure();
    }

    void recordDataProcessed(String deviceId) {
        DevicePerformance perf = devicePerformance.get(deviceId);
        if (perf != null) {
            perf.recordDataProcessed();
        }
    }

    void adjustBatchSize(String deviceId, int percentChange) {
        DevicePerformance perf = devicePerformance.get(deviceId);
        if (perf != null) {
            perf.adjustBatchSize(percentChange);
        }
    }

    void logStatistics(AtomicInteger timeSliceInterval) {
        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - lastStatisticsTime;
        lastStatisticsTime = currentTime;

        long totalPoints = totalProcessedPoints.getAndSet(0);
        long successfulBatches = totalSuccessfulBatches.getAndSet(0);
        long failedBatches = totalFailedBatches.getAndSet(0);

        double pointsPerSecond = elapsedTime > 0 ? totalPoints / (elapsedTime / 1000.0) : 0;
        double batchSuccessRate = successfulBatches + failedBatches > 0 ?
                successfulBatches * 100.0 / (successfulBatches + failedBatches) : 0;

        log.info("performance stats - points={}, pointsPerSecond={}, batchSuccessRate={}%, activeDevices={}",
                totalPoints,
                String.format("%.2f", pointsPerSecond),
                String.format("%.2f", batchSuccessRate),
                devicePerformance.size());

        StringBuilder sliceInfo = new StringBuilder("time-slice execution: ");
        for (Map.Entry<Integer, Long> entry : timeSliceExecutionTimes.entrySet()) {
            sliceInfo.append(String.format("[%d:%dms]", entry.getKey(), entry.getValue()));
            if (entry.getValue() > timeSliceInterval.get()) {
                sliceInfo.append("(OVERLOAD)");
            }
            sliceInfo.append(", ");
        }
        if (!timeSliceExecutionTimes.isEmpty()) {
            sliceInfo.setLength(sliceInfo.length() - 2);
        }
        log.debug(sliceInfo.toString());

        analyzeBottlenecks();
        reportDeviceHealth();
    }

    private void analyzeBottlenecks() {
        if (!slowestDevices.isEmpty()) {
            List<Map.Entry<String, Long>> sortedSlowest = slowestDevices.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5)
                    .toList();

            StringBuilder slowDeviceInfo = new StringBuilder("slow devices (>200ms): ");
            for (Map.Entry<String, Long> entry : sortedSlowest) {
                slowDeviceInfo.append(String.format("%s:%dms, ", entry.getKey(), entry.getValue()));
            }
            slowDeviceInfo.setLength(slowDeviceInfo.length() - 2);
            log.warn(slowDeviceInfo.toString());
            slowestDevices.clear();
        }

        if (!overloadedSlices.isEmpty()) {
            StringBuilder overloadInfo = new StringBuilder("overloaded time slices: ");
            for (Map.Entry<Integer, Long> entry : overloadedSlices.entrySet()) {
                overloadInfo.append(String.format("%d:%dms, ", entry.getKey(), entry.getValue()));
            }
            overloadInfo.setLength(overloadInfo.length() - 2);
            log.warn(overloadInfo.toString());
            overloadedSlices.clear();
        }

        Runtime runtime = Runtime.getRuntime();
        long currentMemory = runtime.totalMemory() - runtime.freeMemory();
        if (currentMemory > peakMemoryUsage.get()) {
            peakMemoryUsage.set(currentMemory);
        }

        log.debug("system resources: heapUsed={}MB, peakHeap={}MB, processors={}",
                currentMemory / (1024 * 1024),
                peakMemoryUsage.get() / (1024 * 1024),
                Runtime.getRuntime().availableProcessors());
    }

    private void reportDeviceHealth() {
        long healthyDevices = 0;
        long warningDevices = 0;
        long criticalDevices = 0;

        for (DevicePerformance perf : devicePerformance.values()) {
            double healthScore = perf.calculateHealthScore();
            String risk = perf.predictFailureRisk();

            if (healthScore > 80 && "NONE".equals(risk)) {
                healthyDevices++;
            } else if (healthScore > 60 || "LOW".equals(risk)) {
                warningDevices++;
            } else {
                criticalDevices++;
            }

            if ("HIGH".equals(risk) || healthScore < 50) {
                log.warn("device {} health degraded: score={}%, risk={}, consecutiveFailures={}",
                        perf.deviceId,
                        String.format("%.1f", healthScore),
                        risk,
                        perf.consecutiveFailureCount);
            }
        }

        log.info("device health summary: healthy={}, warning={}, critical={}", healthyDevices, warningDevices, criticalDevices);
    }

    Map<String, Object> getDevicePerformance(String deviceId) {
        DevicePerformance perf = devicePerformance.get(deviceId);
        if (perf != null) {
            return perf.getStatistics();
        }
        return Collections.emptyMap();
    }

    long getAverageTimeSliceExecution() {
        if (timeSliceExecutionTimes.isEmpty()) {
            return 0;
        }
        long sum = 0;
        int count = 0;
        for (Long value : timeSliceExecutionTimes.values()) {
            if (value != null && value > 0) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    boolean consumeTimeSliceTimeout() {
        return recentTimeSliceTimeout.getAndSet(false);
    }

    Map<Integer, Long> getTimeSliceExecutionTimesSnapshot() {
        return new ConcurrentHashMap<>(timeSliceExecutionTimes);
    }

    Map<Integer, Long> getOverloadedSlicesSnapshot() {
        return new ConcurrentHashMap<>(overloadedSlices);
    }

    Map<String, Long> getSlowestDevicesSnapshot() {
        return new ConcurrentHashMap<>(slowestDevices);
    }

    Map<String, Map<String, Object>> getAllDevicePerformance() {
        Map<String, Map<String, Object>> stats = new ConcurrentHashMap<>();
        devicePerformance.forEach((deviceId, perf) -> stats.put(deviceId, perf.getStatistics()));
        return stats;
    }
}

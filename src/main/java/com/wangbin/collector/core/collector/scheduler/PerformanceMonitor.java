package com.wangbin.collector.core.collector.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调度器性能监控器。
 */
@Slf4j
@Component
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

    /**
     * 处理组件生命周期。
     */
    void initializeDeviceBatchSize(String deviceId, int initialBatchSize, int maxBatchSize) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        devicePerformance.computeIfAbsent(deviceId, DevicePerformance::new)
                .initializeBatchWindow(initialBatchSize, maxBatchSize);
    }

    /**
     * 记录或统计业务状态。
     */
    void recordTimeSliceExecution(int sliceIndex, long executionTime, int timeSliceIntervalMs) {
        timeSliceExecutionTimes.put(sliceIndex, executionTime);
        if (executionTime > timeSliceIntervalMs) {
            overloadedSlices.put(sliceIndex, executionTime);
            recentTimeSliceTimeout.set(true);
        }
    }

    /**
     * 记录或统计业务状态。
     */
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

    /**
     * 记录或统计业务状态。
     */
    void recordBatchFailure(String deviceId) {
        totalFailedBatches.incrementAndGet();

        DevicePerformance perf = devicePerformance.computeIfAbsent(
                deviceId, DevicePerformance::new
        );
        perf.recordFailure();
    }

    /**
     * 记录或统计业务状态。
     */
    void recordDataProcessed(String deviceId) {
        DevicePerformance perf = devicePerformance.get(deviceId);
        if (perf != null) {
            perf.recordDataProcessed();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    void adjustBatchSize(String deviceId, int percentChange) {
        DevicePerformance perf = devicePerformance.get(deviceId);
        if (perf != null) {
            perf.adjustBatchSize(percentChange);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    void logStatistics(int timeSliceIntervalMs) {
        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - lastStatisticsTime;
        lastStatisticsTime = currentTime;

        long totalPoints = totalProcessedPoints.getAndSet(0);
        long successfulBatches = totalSuccessfulBatches.getAndSet(0);
        long failedBatches = totalFailedBatches.getAndSet(0);

        double pointsPerSecond = elapsedTime > 0 ? totalPoints / (elapsedTime / 1000.0) : 0;
        double batchSuccessRate = successfulBatches + failedBatches > 0 ?
                successfulBatches * 100.0 / (successfulBatches + failedBatches) : 0;

        log.info("performance stats - 点位={}, pointsPerSecond={}, batchSuccessRate={}%, activeDevices={}",
                totalPoints,
                String.format("%.2f", pointsPerSecond),
                String.format("%.2f", batchSuccessRate),
                devicePerformance.size());

        StringBuilder sliceInfo = new StringBuilder("time-slice execution: ");
        for (Map.Entry<Integer, Long> entry : timeSliceExecutionTimes.entrySet()) {
            sliceInfo.append(String.format("[%d:%dms]", entry.getKey(), entry.getValue()));
            if (entry.getValue() > timeSliceIntervalMs) {
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

    /**
     * 执行当前业务逻辑。
     */
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

        log.debug("系统资源快照：堆已用={}MB，峰值堆={}MB，处理器数量={}",
                currentMemory / (1024 * 1024),
                peakMemoryUsage.get() / (1024 * 1024),
                Runtime.getRuntime().availableProcessors());
    }

    /**
     * 执行当前业务逻辑。
     */
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
                log.warn("设备 {} 健康状态 degraded:score={}%, 风险={}, consecutiveFailures={}",
                        perf.deviceId,
                        String.format("%.1f", healthScore),
                        risk,
                        perf.consecutiveFailureCount);
            }
        }

        log.info("设备 健康状态 summary:健康={}, 警告={}, 严重={}", healthyDevices, warningDevices, criticalDevices);
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

    /**
     * 执行当前业务逻辑。
     */
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

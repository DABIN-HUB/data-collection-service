package com.wangbin.collector.core.collector.scheduler;


import com.wangbin.collector.common.constant.CommonMapKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-设备 performance statistics.
 */
class DevicePerformance {

    private static final Logger log = LoggerFactory.getLogger(DevicePerformance.class);

    final String deviceId;
    final AtomicLong totalPoints = new AtomicLong(0);
    final AtomicLong successfulBatches = new AtomicLong(0);
    final AtomicLong failedBatches = new AtomicLong(0);
    final AtomicLong totalExecutionTime = new AtomicLong(0);
    volatile int currentBatchSize = 30;
    volatile int minBatchSize = 10;
    volatile int maxBatchSize = 100;
    long lastAdjustTime = System.currentTimeMillis();

    double healthScore = 100.0;
    long lastHealthCheckTime = System.currentTimeMillis();
    int consecutiveFailureCount = 0;
    long lastSuccessTime;

    final List<Long> recentResponseTimes = new ArrayList<>();
    static final int MAX_RESPONSE_TIME_HISTORY = 10;

    final Map<String, Double> pointChangeRates = new ConcurrentHashMap<>();
    final Map<String, Object> lastValues = new ConcurrentHashMap<>();

    /**
     * 创建当前组件实例。
     */
    DevicePerformance(String deviceId) {
        this.deviceId = deviceId;
    }

    /**
     * 处理组件生命周期。
     */
    void initializeBatchWindow(int initialBatchSize, int protocolMaxBatchSize) {
        int normalizedMax = Math.max(10, protocolMaxBatchSize);
        int normalizedInitial = Math.max(10, Math.min(initialBatchSize, normalizedMax));
        this.maxBatchSize = normalizedMax;
        this.currentBatchSize = normalizedInitial;
    }

    /**
     * 记录或统计业务状态。
     */
    void recordSuccess(int pointCount, long executionTime) {
        totalPoints.addAndGet(pointCount);
        successfulBatches.incrementAndGet();
        totalExecutionTime.addAndGet(executionTime);
        updateResponseTimeHistory(executionTime);
        consecutiveFailureCount = 0;
        lastSuccessTime = System.currentTimeMillis();
    }

    /**
     * 记录或统计业务状态。
     */
    void recordFailure() {
        failedBatches.incrementAndGet();
        consecutiveFailureCount++;
        updateResponseTimeHistory(-1);
    }

    /**
     * 记录或统计业务状态。
     */
    void recordDataProcessed() {
        // 预留后续扩展钩子。
    }

    /**
     * 更新或刷新业务状态。
     */
    void updateResponseTimeHistory(long executionTime) {
        synchronized (recentResponseTimes) {
            recentResponseTimes.add(executionTime);
            if (recentResponseTimes.size() > MAX_RESPONSE_TIME_HISTORY) {
                recentResponseTimes.remove(0);
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    double calculateHealthScore() {
        double score = 100.0;
        double successRate = successfulBatches.get() /
                Math.max(1.0, successfulBatches.get() + failedBatches.get());
        score *= (successRate * 0.5 + 0.5);

        if (consecutiveFailureCount > 0) {
            double failurePenalty = Math.max(0.1, 1.0 - consecutiveFailureCount * 0.1);
            score *= (failurePenalty * 0.2 + 0.8);
        }

        double avgResponseTime = getAverageResponseTime();
        if (avgResponseTime > 200) {
            double responsePenalty = Math.max(0.5, 1.0 - (avgResponseTime - 200) / 1000.0);
            score *= (responsePenalty * 0.2 + 0.8);
        }

        long inactiveTime = System.currentTimeMillis() - lastSuccessTime;
        if (inactiveTime > 300000) {
            double inactivePenalty = Math.max(0.5, 1.0 - (inactiveTime - 300000) / 300000.0);
            score *= (inactivePenalty * 0.1 + 0.9);
        }

        healthScore = Math.max(0.0, Math.min(100.0, score));
        lastHealthCheckTime = System.currentTimeMillis();
        return healthScore;
    }

    long getAverageResponseTime() {
        synchronized (recentResponseTimes) {
            if (recentResponseTimes.isEmpty()) {
                return 0;
            }
            return (long) recentResponseTimes.stream()
                    .filter(time -> time > 0)
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(0.0);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    String predictFailureRisk() {
        if (consecutiveFailureCount >= 3) {
            return "HIGH";
        } else if (calculateHealthScore() < 60) {
            return "MEDIUM";
        } else if (getAverageResponseTime() > 300) {
            return "LOW";
        }
        return "NONE";
    }

    /**
     * 执行当前业务逻辑。
     */
    void adjustBatchSize(int percentChange) {
        long now = System.currentTimeMillis();
        if (now - lastAdjustTime < 10000) {
            return;
        }

        int oldSize = currentBatchSize;
        currentBatchSize = Math.max(minBatchSize, Math.min(maxBatchSize,
                currentBatchSize * (100 + percentChange) / 100));

        if (oldSize != currentBatchSize) {
            log.debug("设备 {} 批量 数量 adjusted {} -> {}", deviceId, oldSize, currentBatchSize);
            lastAdjustTime = now;
        }
    }

    Map<String, Object> getStatistics() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put(CommonMapKeys.DEVICE_ID, deviceId);
        stats.put("totalPoints", totalPoints.get());
        stats.put("successfulBatches", successfulBatches.get());
        stats.put("failedBatches", failedBatches.get());
        stats.put("averageBatchTime", successfulBatches.get() > 0 ?
                totalExecutionTime.get() / successfulBatches.get() : 0);
        stats.put("currentBatchSize", currentBatchSize);
        stats.put("maxBatchSize", maxBatchSize);
        stats.put(CommonMapKeys.SUCCESS_RATE, (successfulBatches.get() + failedBatches.get()) > 0 ?
                successfulBatches.get() * 100.0 / (successfulBatches.get() + failedBatches.get()) : 0);
        stats.put("healthScore", calculateHealthScore());
        stats.put("failureRisk", predictFailureRisk());
        stats.put("consecutiveFailures", consecutiveFailureCount);
        stats.put("averageResponseTime", getAverageResponseTime());
        stats.put("recentResponseTimes", new ArrayList<>(recentResponseTimes));
        return stats;
    }
}

package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设备批次任务
 */
class DeviceBatchTask {

    private static final long INITIAL_BACKOFF_MILLIS = 1_000L;
    private static final long MAX_BACKOFF_MILLIS = 30_000L;
    private static final int MAX_BACKOFF_EXPONENT = 5;

    final String deviceId;
    final List<DataPoint> points;
    final int timeSliceIndex;
    final long generation;
    final long timeSliceRevision;
    long lastExecutionTime;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Future<?>> inFlightFutures = ConcurrentHashMap.newKeySet();
    private volatile long nextAllowedExecutionTime;

    /**
     * 创建当前组件实例。
     */
    DeviceBatchTask(String deviceId, List<DataPoint> points, int timeSliceIndex, long generation, long timeSliceRevision) {
        this.deviceId = deviceId;
        this.points = points;
        this.timeSliceIndex = timeSliceIndex;
        this.generation = generation;
        this.timeSliceRevision = timeSliceRevision;
    }

    /**
     * 执行当前业务逻辑。
     */
    boolean shouldSkip() {
        return cancelled.get() || running.get() || System.currentTimeMillis() < nextAllowedExecutionTime;
    }

    /**
     * 执行当前业务逻辑。
     */
    boolean tryStartExecution() {
        if (cancelled.get() || System.currentTimeMillis() < nextAllowedExecutionTime) {
            return false;
        }
        return running.compareAndSet(false, true);
    }

    /**
     * 执行当前业务逻辑。
     */
    void finishExecution() {
        running.set(false);
    }

    /**
     * 记录或统计业务状态。
     */
    void recordFailure() {
        int failures = failureCount.incrementAndGet();
        int exponent = Math.min(Math.max(0, failures - 1), MAX_BACKOFF_EXPONENT);
        long backoffMillis = Math.min(MAX_BACKOFF_MILLIS, INITIAL_BACKOFF_MILLIS << exponent);
        nextAllowedExecutionTime = System.currentTimeMillis() + backoffMillis;
    }

    /**
     * 记录或统计业务状态。
     */
    void recordSuccess() {
        failureCount.set(0);
        nextAllowedExecutionTime = 0L;
    }

    /**
     * 执行当前业务逻辑。
     */
    void cancel() {
        cancelled.set(true);
        cancelInFlight();
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    long getNextAllowedExecutionTime() {
        return nextAllowedExecutionTime;
    }

    /**
     * 维护注册或订阅关系。
     */
    void registerInFlight(Future<?> future) {
        if (future != null) {
            inFlightFutures.add(future);
        }
    }

    /**
     * 维护注册或订阅关系。
     */
    void unregisterInFlight(Future<?> future) {
        if (future != null) {
            inFlightFutures.remove(future);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    void cancelInFlight() {
        for (Future<?> future : inFlightFutures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
        inFlightFutures.clear();
    }
}

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

    DeviceBatchTask(String deviceId, List<DataPoint> points, int timeSliceIndex, long generation, long timeSliceRevision) {
        this.deviceId = deviceId;
        this.points = points;
        this.timeSliceIndex = timeSliceIndex;
        this.generation = generation;
        this.timeSliceRevision = timeSliceRevision;
    }

    boolean shouldSkip() {
        return cancelled.get() || running.get() || System.currentTimeMillis() < nextAllowedExecutionTime;
    }

    boolean tryStartExecution() {
        if (cancelled.get() || System.currentTimeMillis() < nextAllowedExecutionTime) {
            return false;
        }
        return running.compareAndSet(false, true);
    }

    void finishExecution() {
        running.set(false);
    }

    void recordFailure() {
        int failures = failureCount.incrementAndGet();
        int exponent = Math.min(Math.max(0, failures - 1), MAX_BACKOFF_EXPONENT);
        long backoffMillis = Math.min(MAX_BACKOFF_MILLIS, INITIAL_BACKOFF_MILLIS << exponent);
        nextAllowedExecutionTime = System.currentTimeMillis() + backoffMillis;
    }

    void recordSuccess() {
        failureCount.set(0);
        nextAllowedExecutionTime = 0L;
    }

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

    void registerInFlight(Future<?> future) {
        if (future != null) {
            inFlightFutures.add(future);
        }
    }

    void unregisterInFlight(Future<?> future) {
        if (future != null) {
            inFlightFutures.remove(future);
        }
    }

    void cancelInFlight() {
        for (Future<?> future : inFlightFutures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
        inFlightFutures.clear();
    }
}

package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * 设备批次任务
 */
class DeviceBatchTask {

    private static final long INITIAL_BACKOFF_MILLIS = 1_000L;
    private static final long MAX_BACKOFF_MILLIS = 30_000L;
    private static final int MAX_BACKOFF_EXPONENT = 5;

    final String deviceId;
    final List<DataPoint> points;
    int timeSliceIndex;
    final long generation;
    final long timeSliceRevision;
    long lastExecutionTime;
    private final ToLongFunction<DataPoint> collectionIntervalResolver;
    private final LongSupplier nanoTimeSupplier;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Future<?>> inFlightFutures = ConcurrentHashMap.newKeySet();
    private volatile long nextAllowedExecutionTime;
    private volatile long firstEligibleNanos;

    /**
     * 创建当前组件实例。
     */
    DeviceBatchTask(String deviceId, List<DataPoint> points, int timeSliceIndex, long generation, long timeSliceRevision) {
        this(deviceId, points, timeSliceIndex, generation, timeSliceRevision,
                DeviceBatchTask::defaultCollectionInterval, System::nanoTime);
    }

    /**
     * 创建带可控时钟和采集间隔解析器的批次任务，用于生产动态间隔和确定性测试。
     */
    DeviceBatchTask(String deviceId,
                    List<DataPoint> points,
                    int timeSliceIndex,
                    long generation,
                    long timeSliceRevision,
                    ToLongFunction<DataPoint> collectionIntervalResolver,
                    LongSupplier nanoTimeSupplier) {
        this.deviceId = deviceId;
        this.points = points;
        this.timeSliceIndex = timeSliceIndex;
        this.generation = generation;
        this.timeSliceRevision = timeSliceRevision;
        this.collectionIntervalResolver = collectionIntervalResolver != null
                ? collectionIntervalResolver : DeviceBatchTask::defaultCollectionInterval;
        this.nanoTimeSupplier = nanoTimeSupplier != null ? nanoTimeSupplier : System::nanoTime;
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
     * 选出当前真正到期的点位。时间片只负责检查频率，业务采集周期由点位运行间隔控制。
     */
    List<DataPoint> selectDuePoints(SchedulerRuntimeState runtimeState) {
        if (runtimeState == null) {
            return List.of();
        }
        return runtimeState.selectDuePoints(deviceId, generation, points, collectionIntervalResolver,
                nanoTimeSupplier.getAsLong());
    }

    /**
     * 在任务取得执行权后推进 due 状态，避免慢采集结束后补执行历史周期形成突发。
     */
    SchedulerRuntimeState.PointDispatchClaim claimDuePoints(SchedulerRuntimeState runtimeState,
                                                            List<DataPoint> candidates) {
        return claimDuePoints(runtimeState, candidates, nanoTimeSupplier.getAsLong());
    }

    SchedulerRuntimeState.PointDispatchClaim claimDuePoints(SchedulerRuntimeState runtimeState,
                                                            List<DataPoint> candidates,
                                                            long nowNanos) {
        if (runtimeState == null) {
            return SchedulerRuntimeState.PointDispatchClaim.empty();
        }
        SchedulerRuntimeState.PointDispatchClaim claim = runtimeState.claimDuePoints(
                deviceId,
                generation,
                timeSliceRevision,
                candidates,
                collectionIntervalResolver,
                nowNanos,
                firstEligibleNanos);
        if (!claim.isEmpty()) {
            lastExecutionTime = System.currentTimeMillis();
        }
        return claim;
    }

    SchedulerRuntimeState.PointDispatchClaim claimDuePoints(SchedulerRuntimeState runtimeState) {
        return claimDuePoints(runtimeState, points);
    }

    int estimatedPointCount() {
        return points == null ? 0 : points.size();
    }

    long minimumCollectionIntervalMs() {
        if (points == null || points.isEmpty()) {
            return Long.MAX_VALUE;
        }
        long minInterval = Long.MAX_VALUE;
        for (DataPoint point : points) {
            long interval = collectionIntervalResolver.applyAsLong(point);
            minInterval = Math.min(minInterval, Math.max(1L, interval));
        }
        return minInterval;
    }

    void assignTimeSlice(int timeSliceIndex) {
        this.timeSliceIndex = Math.max(0, timeSliceIndex);
    }

    void assignTimeSlice(int timeSliceIndex, int phaseIntervalMs, long baseNanos) {
        assignTimeSlice(timeSliceIndex);
        long phaseDelayMs = (long) this.timeSliceIndex * Math.max(0, phaseIntervalMs);
        this.firstEligibleNanos = baseNanos + TimeUnit.MILLISECONDS.toNanos(phaseDelayMs);
    }

    /**
     * 执行当前业务逻辑。
     */
    void finishExecution() {
        running.set(false);
    }

    void finishExecution(SchedulerRuntimeState runtimeState, SchedulerRuntimeState.PointDispatchClaim claim) {
        if (runtimeState != null && claim != null && !claim.isEmpty()) {
            runtimeState.completeClaim(claim);
        }
        finishExecution();
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

    private static long defaultCollectionInterval(DataPoint point) {
        if (point != null && point.getBaseCollectionInterval() != null && point.getBaseCollectionInterval() > 0) {
            return point.getBaseCollectionInterval();
        }
        return AdaptiveCollectionUtil.DEFAULT_BASE_COLLECTION_INTERVAL;
    }
}

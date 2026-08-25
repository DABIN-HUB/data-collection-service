package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.core.config.CollectorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 管理 phase-wheel 时间片扫描任务的启动、取消和旧 revision 隔离。
 */
@Slf4j
@Component
public class TimeSliceSchedulingCoordinator {

    private static final int MIN_PHASE_WHEEL_TICK_MS = 50;

    private final CollectorProperties collectorProperties;
    private final SchedulerRuntimeState runtimeState;
    private final PerformanceMonitor performanceMonitor;
    private final TimeSliceExecutionCoordinator timeSliceExecutionCoordinator;
    private final ScheduledExecutorService timeSliceScheduler;
    private final LongSupplier nanoTimeSupplier;
    private final Map<Integer, ScheduledFuture<?>> timeSliceScheduleFutures = new ConcurrentHashMap<>();

    @Autowired
    public TimeSliceSchedulingCoordinator(CollectorProperties collectorProperties,
                                          SchedulerRuntimeState runtimeState,
                                          PerformanceMonitor performanceMonitor,
                                          TimeSliceExecutionCoordinator timeSliceExecutionCoordinator,
                                          @Qualifier("timeSliceScheduler") ScheduledExecutorService timeSliceScheduler) {
        this(collectorProperties,
                runtimeState,
                performanceMonitor,
                timeSliceExecutionCoordinator,
                timeSliceScheduler,
                System::nanoTime);
    }

    TimeSliceSchedulingCoordinator(CollectorProperties collectorProperties,
                                   SchedulerRuntimeState runtimeState,
                                   PerformanceMonitor performanceMonitor,
                                   TimeSliceExecutionCoordinator timeSliceExecutionCoordinator,
                                   ScheduledExecutorService timeSliceScheduler,
                                   LongSupplier nanoTimeSupplier) {
        this.collectorProperties = collectorProperties;
        this.runtimeState = runtimeState;
        this.performanceMonitor = performanceMonitor;
        this.timeSliceExecutionCoordinator = timeSliceExecutionCoordinator;
        this.timeSliceScheduler = timeSliceScheduler;
        this.nanoTimeSupplier = nanoTimeSupplier != null ? nanoTimeSupplier : System::nanoTime;
    }

    void startTimeSliceScheduling() {
        cancelTimeSliceScheduling();
        int sliceCount = Math.max(1, runtimeState.getTimeSliceCount());
        int phaseWheelTickMs = resolvePhaseWheelTickIntervalMs(sliceCount);
        long revision = runtimeState.getTimeSliceRevision();
        ScheduledFuture<?> future = timeSliceScheduler.scheduleWithFixedDelay(
                new PhaseWheelScanTask(sliceCount, revision, phaseWheelTickMs),
                0L,
                phaseWheelTickMs,
                TimeUnit.MILLISECONDS);
        timeSliceScheduleFutures.put(0, future);
    }

    void cancelTimeSliceScheduling() {
        timeSliceScheduleFutures.values().forEach(future -> future.cancel(false));
        timeSliceScheduleFutures.clear();
    }

    boolean isSchedulingActive() {
        return !timeSliceScheduleFutures.isEmpty();
    }

    int resolveDueScanIntervalMs() {
        int minInterval = Math.max(1, collectorProperties.getScheduler().getMinTimeSliceIntervalMs());
        return Math.max(minInterval, collectorProperties.getScheduler().getDueScanIntervalMs());
    }

    int resolvePhaseWheelTickIntervalMs(int sliceCount) {
        int normalizedSliceCount = Math.max(1, sliceCount);
        int dueScanInterval = resolveDueScanIntervalMs();
        int distributedTick = (dueScanInterval + normalizedSliceCount - 1) / normalizedSliceCount;
        return Math.max(MIN_PHASE_WHEEL_TICK_MS, distributedTick);
    }

    private final class PhaseWheelScanTask implements Runnable {

        private final int sliceCount;
        private final long revision;
        private final int expectedTickMs;
        private int nextSliceIndex;

        private PhaseWheelScanTask(int sliceCount, long revision, int expectedTickMs) {
            this.sliceCount = Math.max(1, sliceCount);
            this.revision = revision;
            this.expectedTickMs = Math.max(1, expectedTickMs);
        }

        @Override
        public void run() {
            int currentSlice = nextSliceIndex;
            nextSliceIndex = (nextSliceIndex + 1) % sliceCount;
            performanceMonitor.recordPhaseWheelTick(currentSlice, nanoTimeSupplier.getAsLong(), expectedTickMs);
            try {
                timeSliceExecutionCoordinator.executeTimeSlice(currentSlice, revision);
            } catch (Exception e) {
                log.error("时间片执行失败, 分片={}", currentSlice, e);
            }
        }
    }
}

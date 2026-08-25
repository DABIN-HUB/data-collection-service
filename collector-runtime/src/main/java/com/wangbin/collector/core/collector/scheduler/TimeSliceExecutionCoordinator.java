package com.wangbin.collector.core.collector.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * 执行单个时间片中的批量任务分发。
 *
 * 本类只负责判断时间片任务是否仍然有效并提交到批量执行器，不承载具体采集、处理和上报逻辑。
 */
@Slf4j
@Component
public class TimeSliceExecutionCoordinator {

    private final SchedulerRuntimeState runtimeState;
    private final PerformanceMonitor performanceMonitor;
    private final DeviceBatchExecutor deviceBatchExecutor;
    private final LongSupplier nanoTimeSupplier;

    @Autowired
    public TimeSliceExecutionCoordinator(SchedulerRuntimeState runtimeState,
                                         PerformanceMonitor performanceMonitor,
                                         DeviceBatchExecutor deviceBatchExecutor) {
        this(runtimeState, performanceMonitor, deviceBatchExecutor, System::nanoTime);
    }

    TimeSliceExecutionCoordinator(SchedulerRuntimeState runtimeState,
                                  PerformanceMonitor performanceMonitor,
                                  DeviceBatchExecutor deviceBatchExecutor,
                                  LongSupplier nanoTimeSupplier) {
        this.runtimeState = runtimeState;
        this.performanceMonitor = performanceMonitor;
        this.deviceBatchExecutor = deviceBatchExecutor;
        this.nanoTimeSupplier = nanoTimeSupplier != null ? nanoTimeSupplier : System::nanoTime;
    }

    void executeTimeSlice(int sliceIndex, long revision) {
        long startTime = System.currentTimeMillis();
        try {
            if (revision != runtimeState.getTimeSliceRevision()) {
                return;
            }
            List<DeviceBatchTask> tasks = runtimeState.getSliceTasks(sliceIndex);
            if (tasks.isEmpty()) {
                return;
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            long sliceNowNanos = nanoTimeSupplier.getAsLong();
            for (DeviceBatchTask task : tasks) {
                if (task.shouldSkip() || !deviceBatchExecutor.isBatchTaskActive(task) || task.timeSliceRevision != revision) {
                    continue;
                }
                CompletableFuture<Void> future = deviceBatchExecutor.submit(task, sliceNowNanos);
                if (future != null) {
                    futures.add(future);
                }
            }

            if (!futures.isEmpty()) {
                observeTimeSliceFutures(sliceIndex, futures);
            }
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            performanceMonitor.recordTimeSliceExecution(sliceIndex, executionTime, runtimeState.getTimeSliceInterval());
        }
    }

    private void observeTimeSliceFutures(int sliceIndex, List<CompletableFuture<Void>> futures) {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .exceptionally(throwable -> {
                    log.error("时间片异步执行失败, 分片={}", sliceIndex, throwable);
                    return null;
                });
    }
}

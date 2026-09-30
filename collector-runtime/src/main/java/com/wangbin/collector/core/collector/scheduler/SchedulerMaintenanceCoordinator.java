package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.core.config.CollectorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一管理调度器维护类定时任务。
 */
@Slf4j
@Component
public class SchedulerMaintenanceCoordinator {

    private final CollectorProperties collectorProperties;
    private final SchedulerRuntimeState runtimeState;
    private final PerformanceMonitor performanceMonitor;
    private final DeviceLifecycleCoordinator deviceLifecycleCoordinator;
    private final TimeSliceConfigCoordinator timeSliceConfigCoordinator;
    private final ScheduledExecutorService timeSliceScheduler;
    private final List<ScheduledFuture<?>> periodicMaintenanceFutures = new CopyOnWriteArrayList<>();
    private ScheduledFuture<?> pendingStartAllFuture;

    public SchedulerMaintenanceCoordinator(CollectorProperties collectorProperties,
                                           SchedulerRuntimeState runtimeState,
                                           PerformanceMonitor performanceMonitor,
                                           DeviceLifecycleCoordinator deviceLifecycleCoordinator,
                                           TimeSliceConfigCoordinator timeSliceConfigCoordinator,
                                           @Qualifier("timeSliceScheduler") ScheduledExecutorService timeSliceScheduler) {
        this.collectorProperties = collectorProperties;
        this.runtimeState = runtimeState;
        this.performanceMonitor = performanceMonitor;
        this.deviceLifecycleCoordinator = deviceLifecycleCoordinator;
        this.timeSliceConfigCoordinator = timeSliceConfigCoordinator;
        this.timeSliceScheduler = timeSliceScheduler;
    }

    void start() {
        cancel();
        startDynamicTimeSliceAdjustment();
        startPerformanceMonitoring();
        scheduleStartAllDevices(5, TimeUnit.SECONDS);
    }

    synchronized void cancel() {
        periodicMaintenanceFutures.forEach(future -> future.cancel(false));
        periodicMaintenanceFutures.clear();
        cancelPendingStartAllFutureLocked();
    }

    void scheduleStartAllDevices(long delay, TimeUnit unit) {
        AtomicReference<ScheduledFuture<?>> selfReference = new AtomicReference<>();
        Runnable startTask = () -> {
            try {
                autoStartAllDevices();
            } finally {
                clearPendingStartAllFuture(selfReference.get());
            }
        };
        synchronized (this) {
            cancelPendingStartAllFutureLocked();
            ScheduledFuture<?> future = timeSliceScheduler.schedule(startTask, delay, unit);
            selfReference.set(future);
            pendingStartAllFuture = future;
            if (future.isDone()) {
                clearPendingStartAllFuture(future);
            }
        }
    }

    void adjustTimeSlicesAfterWorkloadChange() {
        timeSliceConfigCoordinator.adjustTimeSlicesAfterWorkloadChange();
    }

    synchronized int pendingStartAllFutureCountForTest() {
        return pendingStartAllFuture == null || pendingStartAllFuture.isCancelled() || pendingStartAllFuture.isDone() ? 0 : 1;
    }

    private void autoStartAllDevices() {
        try {
            deviceLifecycleCoordinator.startAllDevices();
            adjustTimeSlicesAfterWorkloadChange();
        } catch (Exception e) {
            log.error("自动启动全部设备失败", e);
        }
    }

    private void startPerformanceMonitoring() {
        ScheduledFuture<?> future = timeSliceScheduler.scheduleAtFixedRate(
                () -> performanceMonitor.logStatistics(runtimeState.getTimeSliceInterval()),
                60, 60, TimeUnit.SECONDS
        );
        periodicMaintenanceFutures.add(future);
    }

    private void startDynamicTimeSliceAdjustment() {
        int interval = collectorProperties.getScheduler().getDynamicAdjustIntervalMs();
        ScheduledFuture<?> future = timeSliceScheduler.scheduleAtFixedRate(
                timeSliceConfigCoordinator::adjustTimeSlicesDynamically,
                interval,
                interval,
                TimeUnit.MILLISECONDS
        );
        periodicMaintenanceFutures.add(future);
    }

    private synchronized void cancelPendingStartAllFutureLocked() {
        if (pendingStartAllFuture != null && !pendingStartAllFuture.isDone()) {
            pendingStartAllFuture.cancel(false);
        }
        pendingStartAllFuture = null;
    }

    private synchronized void clearPendingStartAllFuture(ScheduledFuture<?> expectedFuture) {
        if (expectedFuture != null && pendingStartAllFuture == expectedFuture) {
            pendingStartAllFuture = null;
        }
    }
}

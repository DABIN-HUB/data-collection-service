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
    private final List<ScheduledFuture<?>> maintenanceScheduleFutures = new CopyOnWriteArrayList<>();

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

    void cancel() {
        maintenanceScheduleFutures.forEach(future -> future.cancel(false));
        maintenanceScheduleFutures.clear();
    }

    void scheduleStartAllDevices(long delay, TimeUnit unit) {
        ScheduledFuture<?> future = timeSliceScheduler.schedule(this::autoStartAllDevices, delay, unit);
        maintenanceScheduleFutures.add(future);
    }

    void adjustTimeSlicesAfterWorkloadChange() {
        timeSliceConfigCoordinator.adjustTimeSlicesAfterWorkloadChange();
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
        maintenanceScheduleFutures.add(future);
    }

    private void startDynamicTimeSliceAdjustment() {
        int interval = collectorProperties.getScheduler().getDynamicAdjustIntervalMs();
        ScheduledFuture<?> future = timeSliceScheduler.scheduleAtFixedRate(
                timeSliceConfigCoordinator::adjustTimeSlicesDynamically,
                interval,
                interval,
                TimeUnit.MILLISECONDS
        );
        maintenanceScheduleFutures.add(future);
    }
}

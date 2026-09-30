package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.port.SystemResourceProbe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 管理时间片数量、间隔调优和重建分配。
 */
@Slf4j
@Component
public class TimeSliceConfigCoordinator {

    private final CollectorProperties collectorProperties;
    private final ConfigManager configManager;
    @Nullable
    private final SystemResourceProbe systemResourceProbe;
    private final SchedulerRuntimeState runtimeState;
    private final PerformanceMonitor performanceMonitor;
    private final DeviceLifecycleCoordinator deviceLifecycleCoordinator;
    private final DeviceBatchExecutor deviceBatchExecutor;
    private final TimeSliceSchedulingCoordinator timeSliceSchedulingCoordinator;
    private TimeSliceTuner timeSliceTuner;

    public TimeSliceConfigCoordinator(CollectorProperties collectorProperties,
                                      ConfigManager configManager,
                                      @Nullable SystemResourceProbe systemResourceProbe,
                                      SchedulerRuntimeState runtimeState,
                                      PerformanceMonitor performanceMonitor,
                                      DeviceLifecycleCoordinator deviceLifecycleCoordinator,
                                      DeviceBatchExecutor deviceBatchExecutor,
                                      TimeSliceSchedulingCoordinator timeSliceSchedulingCoordinator) {
        this.collectorProperties = collectorProperties;
        this.configManager = configManager;
        this.systemResourceProbe = systemResourceProbe;
        this.runtimeState = runtimeState;
        this.performanceMonitor = performanceMonitor;
        this.deviceLifecycleCoordinator = deviceLifecycleCoordinator;
        this.deviceBatchExecutor = deviceBatchExecutor;
        this.timeSliceSchedulingCoordinator = timeSliceSchedulingCoordinator;
    }

    void initializeTimeSlices() {
        int normalizedSliceCount = Math.max(1, Math.min(
                collectorProperties.getScheduler().getInitialTimeSliceCount(),
                collectorProperties.getScheduler().getMaxTimeSliceCount()
        ));
        int normalizedInterval = Math.max(
                collectorProperties.getScheduler().getMinTimeSliceIntervalMs(),
                collectorProperties.getScheduler().getInitialTimeSliceIntervalMs()
        );
        int maxInterval = Math.max(
                collectorProperties.getScheduler().getDefaultTimeSliceIntervalMs() * 2,
                normalizedInterval
        );
        this.timeSliceTuner = new TimeSliceTuner(
                collectorProperties.getScheduler().getMinTimeSliceIntervalMs(),
                maxInterval,
                normalizedInterval
        );
        runtimeState.initializeTimeSlices(normalizedSliceCount, normalizedInterval);
    }

    void adjustTimeSlicesAfterWorkloadChange() {
        adjustTimeSlicesDynamically();
    }

    void adjustTimeSlicesDynamically() {
        try {
            double cpuLoad = getSystemCpuLoad();
            int activeDevices = runtimeState.getRunningDeviceCount();
            long totalTasks = runtimeState.getTotalTaskCount();
            long estimatedPoints = runtimeState.getTotalEstimatedPointCount();
            int newSliceCount = calculateOptimalSliceCount(activeDevices, totalTasks, estimatedPoints, cpuLoad);
            long avgExecution = performanceMonitor.getAverageTimeSliceExecution();
            boolean timeoutDetected = performanceMonitor.consumeTimeSliceTimeout();
            int tunedInterval = timeSliceTuner != null
                    ? timeSliceTuner.adjustInterval(runtimeState.getTimeSliceInterval(), avgExecution, timeoutDetected)
                    : runtimeState.getTimeSliceInterval();
            long minimumCollectionIntervalMs = runtimeState.getMinimumCollectionIntervalMs();
            newSliceCount = capSliceCountForCadence(newSliceCount, minimumCollectionIntervalMs);
            int boundedInterval = capTimeSliceIntervalForCadence(
                    tunedInterval,
                    newSliceCount,
                    minimumCollectionIntervalMs);
            applyTimeSliceConfigUpdate(newSliceCount, boundedInterval);
        } catch (Exception e) {
            log.error("调整时间片失败", e);
        }
    }

    int calculateOptimalSliceCount(int activeDevices, long totalTasks, double cpuLoad) {
        return calculateOptimalSliceCount(activeDevices, totalTasks, 0L, cpuLoad);
    }

    int calculateOptimalSliceCount(int activeDevices, long totalTasks, long estimatedPoints, double cpuLoad) {
        int maxSlices = Math.max(1, collectorProperties.getScheduler().getMaxTimeSliceCount());
        int activeDeviceSlices = Math.max(1, activeDevices / 5 + 1);
        int taskSlices = requiredSlices(totalTasks, collectorProperties.getScheduler().getTargetTasksPerTimeSlice());
        int pointSlices = requiredSlices(estimatedPoints, collectorProperties.getScheduler().getTargetPointsPerTimeSlice());
        int baseSlices = Math.max(activeDeviceSlices, Math.max(taskSlices, pointSlices));
        baseSlices = Math.max(1, Math.min(baseSlices, maxSlices));
        if (cpuLoad > 0.8) {
            baseSlices = Math.min(maxSlices, baseSlices + 2);
        }
        return baseSlices;
    }

    int capTimeSliceIntervalForCadence(int proposedIntervalMs, int sliceCount, long minimumCollectionIntervalMs) {
        int normalizedInterval = Math.max(1, proposedIntervalMs);
        if (sliceCount <= 1 || minimumCollectionIntervalMs <= 0L) {
            return normalizedInterval;
        }
        int minInterval = Math.max(1, collectorProperties.getScheduler().getMinTimeSliceIntervalMs());
        long cadenceAlignedInterval = (minimumCollectionIntervalMs + Math.max(1, sliceCount) - 1L)
                / Math.max(1, sliceCount);
        long boundedInterval = Math.max(minInterval, cadenceAlignedInterval);
        return (int) Math.min(Integer.MAX_VALUE, boundedInterval);
    }

    int capSliceCountForCadence(int requestedSliceCount, long minimumCollectionIntervalMs) {
        int normalizedSliceCount = Math.max(1, requestedSliceCount);
        if (minimumCollectionIntervalMs <= 0L) {
            return normalizedSliceCount;
        }
        int minInterval = Math.max(1, collectorProperties.getScheduler().getMinTimeSliceIntervalMs());
        long maxSlicesByCadence = Math.max(1L, minimumCollectionIntervalMs / minInterval);
        return (int) Math.min(normalizedSliceCount, Math.min(Integer.MAX_VALUE, maxSlicesByCadence));
    }

    private int requiredSlices(long workload, int targetPerSlice) {
        if (workload <= 0L) {
            return 1;
        }
        int normalizedTarget = Math.max(1, targetPerSlice);
        return (int) Math.min(Integer.MAX_VALUE, (workload + normalizedTarget - 1L) / normalizedTarget);
    }

    void applyTimeSliceConfigUpdate(int newSliceCount, int newSliceInterval) {
        int normalizedSliceCount = Math.max(1, newSliceCount);
        int normalizedSliceInterval = Math.max(
                collectorProperties.getScheduler().getMinTimeSliceIntervalMs(),
                newSliceInterval
        );
        int oldSliceCount = runtimeState.getTimeSliceCount();
        int oldSliceInterval = runtimeState.getTimeSliceInterval();
        boolean changed = normalizedSliceCount != oldSliceCount || normalizedSliceInterval != oldSliceInterval;
        boolean schedulingActive = timeSliceSchedulingCoordinator.isSchedulingActive();
        if (changed) {
            runtimeState.updateTimeSliceConfig(normalizedSliceCount, normalizedSliceInterval);
            rebuildTimeSliceAssignments();
        }
        if (changed && schedulingActive) {
            timeSliceSchedulingCoordinator.startTimeSliceScheduling();
        }
    }

    void rebuildTimeSliceAssignments() {
        runtimeState.resetTimeSliceBuckets(runtimeState.getTimeSliceCount());
        List<DeviceScheduleInfo> schedules = runtimeState.getRunningScheduleSnapshot();
        for (DeviceScheduleInfo scheduleInfo : schedules) {
            String deviceId = scheduleInfo.getDeviceId();
            try {
                List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
                if (dataPoints == null || dataPoints.isEmpty()) {
                    continue;
                }
                deviceLifecycleCoordinator.scheduleDevicePoints(deviceId, scheduleInfo.getGeneration(), dataPoints);
            } catch (Exception e) {
                log.error("重建时间片分配失败, 设备={}", deviceId, e);
            }
        }
    }

    double getSystemCpuLoad() {
        double processCpuLoad = resolveProcessCpuLoad();
        if (processCpuLoad >= 0D) {
            return Math.min(1.0, processCpuLoad / 100.0);
        }
        return deviceBatchExecutor.estimateWorkerLoad();
    }

    double resolveProcessCpuLoad() {
        if (systemResourceProbe == null) {
            return -1D;
        }
        try {
            return systemResourceProbe.getProcessCpuLoad();
        } catch (Exception e) {
            log.debug("读取进程 CPU 负载失败", e);
            return -1D;
        }
    }
}

package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimePhase;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 采集任务调度顶层编排器。
 *
 * 本类只负责调度生命周期、设备生命周期入口和运行快照查询，具体时间片、维护任务和配置重启由专门组件处理。
 */
@Service
public class CollectionScheduler {

    private final CollectionManager collectionManager;
    private final CollectionStatistics collectionStatistics;
    private final SchedulerRuntimeState runtimeState;
    private final PerformanceMonitor performanceMonitor;
    private final DeviceLifecycleCoordinator deviceLifecycleCoordinator;
    private final DeviceBatchExecutor deviceBatchExecutor;
    private final ReconnectCoordinator reconnectCoordinator;
    private final TimeSliceSchedulingCoordinator timeSliceSchedulingCoordinator;
    private final TimeSliceExecutionCoordinator timeSliceExecutionCoordinator;
    private final TimeSliceConfigCoordinator timeSliceConfigCoordinator;
    private final SchedulerMaintenanceCoordinator schedulerMaintenanceCoordinator;
    private final ConfigRestartCoordinator configRestartCoordinator;

    @Autowired
    public CollectionScheduler(CollectionManager collectionManager,
                               CollectionStatistics collectionStatistics,
                               SchedulerRuntimeState runtimeState,
                               PerformanceMonitor performanceMonitor,
                               DeviceLifecycleCoordinator deviceLifecycleCoordinator,
                               DeviceBatchExecutor deviceBatchExecutor,
                               ReconnectCoordinator reconnectCoordinator,
                               TimeSliceSchedulingCoordinator timeSliceSchedulingCoordinator,
                               TimeSliceExecutionCoordinator timeSliceExecutionCoordinator,
                               TimeSliceConfigCoordinator timeSliceConfigCoordinator,
                               SchedulerMaintenanceCoordinator schedulerMaintenanceCoordinator,
                               ConfigRestartCoordinator configRestartCoordinator) {
        this.collectionManager = collectionManager;
        this.collectionStatistics = collectionStatistics;
        this.runtimeState = runtimeState;
        this.performanceMonitor = performanceMonitor;
        this.deviceLifecycleCoordinator = deviceLifecycleCoordinator;
        this.deviceBatchExecutor = deviceBatchExecutor;
        this.reconnectCoordinator = reconnectCoordinator;
        this.timeSliceSchedulingCoordinator = timeSliceSchedulingCoordinator;
        this.timeSliceExecutionCoordinator = timeSliceExecutionCoordinator;
        this.timeSliceConfigCoordinator = timeSliceConfigCoordinator;
        this.schedulerMaintenanceCoordinator = schedulerMaintenanceCoordinator;
        this.configRestartCoordinator = configRestartCoordinator;
    }

    @PostConstruct
    public void init() {
        timeSliceConfigCoordinator.initializeTimeSlices();
        timeSliceSchedulingCoordinator.startTimeSliceScheduling();
        schedulerMaintenanceCoordinator.start();
    }

    @PreDestroy
    public void destroy() {
        configRestartCoordinator.cancelAll();
        stopAllDevices();
        timeSliceSchedulingCoordinator.cancelTimeSliceScheduling();
        schedulerMaintenanceCoordinator.cancel();
        reconnectCoordinator.clearAll();
        runtimeState.clear();
    }

    public PerformanceStatsSnapshot getPerformanceSnapshot() {
        return PerformanceStatsSnapshot.builder()
                .timeSliceCount(runtimeState.getTimeSliceCount())
                .timeSliceIntervalMs(runtimeState.getTimeSliceInterval())
                .timeSliceExecutionTimes(performanceMonitor.getTimeSliceExecutionTimesSnapshot())
                .overloadedSlices(performanceMonitor.getOverloadedSlicesSnapshot())
                .slowestDevices(performanceMonitor.getSlowestDevicesSnapshot())
                .deviceStats(performanceMonitor.getAllDevicePerformance())
                .processCpuLoad(resolveProcessCpuLoad())
                .batchDispatchRejectedCount(deviceBatchExecutor.getBatchDispatchRejectedCount())
                .collectRejectedCount(deviceBatchExecutor.getCollectRejectedCount())
                .processRejectedCount(deviceBatchExecutor.getProcessRejectedCount())
                .reconnectAttemptCount(reconnectCoordinator.getAttemptCount())
                .reconnectSuccessCount(reconnectCoordinator.getSuccessCount())
                .reconnectFailureCount(reconnectCoordinator.getFailureCount())
                .reconnectingDevices(reconnectCoordinator.getReconnectingDeviceCount())
                .build();
    }

    public PerformanceMonitor.PhaseWheelStatsSnapshot getPhaseWheelStatsSnapshot() {
        return performanceMonitor.getPhaseWheelStatsSnapshot();
    }

    public void resetPhaseWheelStats() {
        performanceMonitor.resetPhaseWheelStats();
    }

    void startTimeSliceScheduling() {
        timeSliceSchedulingCoordinator.startTimeSliceScheduling();
    }

    void cancelTimeSliceScheduling() {
        timeSliceSchedulingCoordinator.cancelTimeSliceScheduling();
    }

    void executeTimeSlice(int sliceIndex, long revision) {
        timeSliceExecutionCoordinator.executeTimeSlice(sliceIndex, revision);
    }

    void adjustTimeSlicesDynamically() {
        timeSliceConfigCoordinator.adjustTimeSlicesDynamically();
    }

    int calculateOptimalSliceCount(int activeDevices, long totalTasks, double cpuLoad) {
        return timeSliceConfigCoordinator.calculateOptimalSliceCount(activeDevices, totalTasks, cpuLoad);
    }

    int calculateOptimalSliceCount(int activeDevices, long totalTasks, long estimatedPoints, double cpuLoad) {
        return timeSliceConfigCoordinator.calculateOptimalSliceCount(activeDevices, totalTasks, estimatedPoints, cpuLoad);
    }

    int capTimeSliceIntervalForCadence(int proposedIntervalMs, int sliceCount, long minimumCollectionIntervalMs) {
        return timeSliceConfigCoordinator.capTimeSliceIntervalForCadence(
                proposedIntervalMs,
                sliceCount,
                minimumCollectionIntervalMs);
    }

    int capSliceCountForCadence(int requestedSliceCount, long minimumCollectionIntervalMs) {
        return timeSliceConfigCoordinator.capSliceCountForCadence(requestedSliceCount, minimumCollectionIntervalMs);
    }

    int resolveDueScanIntervalMs() {
        return timeSliceSchedulingCoordinator.resolveDueScanIntervalMs();
    }

    int resolvePhaseWheelTickIntervalMs(int sliceCount) {
        return timeSliceSchedulingCoordinator.resolvePhaseWheelTickIntervalMs(sliceCount);
    }

    void applyTimeSliceConfigUpdate(int newSliceCount, int newSliceInterval) {
        timeSliceConfigCoordinator.applyTimeSliceConfigUpdate(newSliceCount, newSliceInterval);
    }

    void rebuildTimeSliceAssignments() {
        timeSliceConfigCoordinator.rebuildTimeSliceAssignments();
    }

    double getSystemCpuLoad() {
        return timeSliceConfigCoordinator.getSystemCpuLoad();
    }

    double resolveProcessCpuLoad() {
        return timeSliceConfigCoordinator.resolveProcessCpuLoad();
    }

    public Map<String, Object> getDeviceScheduleStatus(String deviceId) {
        Map<String, Object> status = new HashMap<>();
        status.put(CommonMapKeys.DEVICE_ID, deviceId);
        DeviceScheduleInfo info = runtimeState.getScheduleInfo(deviceId);
        status.put("isRunning", info != null && info.isRunning());
        status.put("isStarting", runtimeState.isStarting(deviceId));
        status.put(CommonMapKeys.CONNECTED, collectionManager.isDeviceConnected(deviceId));
        status.put("reconnecting", reconnectCoordinator.isReconnecting(deviceId));
        status.put("reconnectNextRetryAt", reconnectCoordinator.getNextRetryAt(deviceId));
        status.put("statistics", collectionStatistics.getDeviceStatistics(deviceId));
        status.put("performance", performanceMonitor.getDevicePerformance(deviceId));
        return status;
    }

    public List<DeviceRuntimeSnapshot> getDeviceRuntimeSnapshots() {
        Set<String> deviceIds = new HashSet<>(collectionManager.getAllDeviceIds());
        deviceIds.addAll(runtimeState.getKnownDeviceIds());
        deviceIds.addAll(reconnectCoordinator.getKnownDeviceIds());
        return deviceIds.stream()
                .sorted()
                .map(this::buildRuntimeSnapshot)
                .toList();
    }

    DeviceRuntimeSnapshot buildRuntimeSnapshot(String deviceId) {
        DeviceScheduleInfo scheduleInfo = runtimeState.getScheduleInfo(deviceId);
        boolean running = scheduleInfo != null && scheduleInfo.isRunning();
        boolean starting = runtimeState.isStarting(deviceId);
        boolean connected = collectionManager.isDeviceConnected(deviceId);
        boolean reconnecting = reconnectCoordinator.isReconnecting(deviceId);
        DevicePerformance performance = performanceMonitor.devicePerformance.get(deviceId);
        int consecutiveFailures = performance != null ? performance.consecutiveFailureCount : 0;
        long lastSuccessfulCollectionAt = performance != null ? performance.lastSuccessTime : 0L;
        long backoffUntil = runtimeState.getDeviceBackoffUntil(deviceId);
        DeviceRuntimePhase phase;
        String degradedReason = null;
        if (consecutiveFailures >= 5) {
            phase = DeviceRuntimePhase.FAILED;
            degradedReason = "连续采集失败";
        } else if (consecutiveFailures > 0) {
            phase = DeviceRuntimePhase.DEGRADED;
            degradedReason = "采集存在连续失败";
        } else if (connected) {
            phase = DeviceRuntimePhase.ONLINE;
        } else if (reconnecting) {
            phase = DeviceRuntimePhase.RECONNECTING;
        } else if (starting) {
            phase = DeviceRuntimePhase.STARTING;
        } else if (running) {
            phase = DeviceRuntimePhase.RUNNING;
        } else {
            phase = DeviceRuntimePhase.STOPPED;
        }
        return new DeviceRuntimeSnapshot(
                deviceId,
                phase,
                running,
                starting,
                connected,
                reconnecting,
                reconnectCoordinator.getNextRetryAt(deviceId),
                scheduleInfo != null ? scheduleInfo.getStartTime() : 0L,
                scheduleInfo != null ? scheduleInfo.getGeneration() : 0L,
                lastSuccessfulCollectionAt,
                consecutiveFailures,
                backoffUntil,
                degradedReason,
                System.currentTimeMillis());
    }

    public boolean startDevice(String deviceId) {
        boolean started = deviceLifecycleCoordinator.startDevice(deviceId);
        if (started) {
            schedulerMaintenanceCoordinator.adjustTimeSlicesAfterWorkloadChange();
        }
        return started;
    }

    public boolean stopDevice(String deviceId) {
        return deviceLifecycleCoordinator.stopDevice(deviceId);
    }

    public void startAllDevices() {
        deviceLifecycleCoordinator.startAllDevices();
        schedulerMaintenanceCoordinator.adjustTimeSlicesAfterWorkloadChange();
    }

    public void stopAllDevices() {
        deviceLifecycleCoordinator.stopAllDevices();
    }

    public List<String> getRunningDevices() {
        return deviceLifecycleCoordinator.getRunningDevices();
    }

    public boolean isDeviceRunning(String deviceId) {
        return deviceLifecycleCoordinator.isDeviceRunning(deviceId);
    }

    public void reloadAllDevices() {
        stopAllDevices();
        schedulerMaintenanceCoordinator.scheduleStartAllDevices(2, TimeUnit.SECONDS);
    }

    @EventListener
    public void handleConfigUpdate(ConfigUpdateEvent event) {
        configRestartCoordinator.handleConfigUpdate(event);
    }
}

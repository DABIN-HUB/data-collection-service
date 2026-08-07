package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimePhase;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.monitor.metrics.SystemResourceMonitorService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 采集任务调度器。
 *
 * 本类只保留时间片调度、动态调整、运行快照和配置变更编排；设备生命周期、批量采集和重连细节由协作组件处理。
 */
@Slf4j
@Service
public class CollectionScheduler {

    private static final long CONFIG_RESTART_DEBOUNCE_MS = 1000L;

    private final CollectionManager collectionManager;
    private final ConfigManager configManager;
    private final CollectionStatistics collectionStatistics;
    private final CollectorProperties collectorProperties;
    @Nullable
    private final SystemResourceMonitorService systemResourceMonitorService;
    private final SchedulerRuntimeState runtimeState;
    private final PerformanceMonitor performanceMonitor;
    private final DeviceLifecycleCoordinator deviceLifecycleCoordinator;
    private final DeviceBatchExecutor deviceBatchExecutor;
    private final ReconnectCoordinator reconnectCoordinator;
    private final ScheduledExecutorService timeSliceScheduler;
    private final Map<Integer, ScheduledFuture<?>> timeSliceScheduleFutures = new ConcurrentHashMap<>();
    private final List<ScheduledFuture<?>> maintenanceScheduleFutures = new CopyOnWriteArrayList<>();
    private final Map<String, ScheduledFuture<?>> pendingConfigRestartTasks = new ConcurrentHashMap<>();
    private TimeSliceTuner timeSliceTuner;

    public CollectionScheduler(CollectionManager collectionManager,
                               ConfigManager configManager,
                               CollectionStatistics collectionStatistics,
                               CollectorProperties collectorProperties,
                               @Nullable SystemResourceMonitorService systemResourceMonitorService,
                               SchedulerRuntimeState runtimeState,
                               PerformanceMonitor performanceMonitor,
                               DeviceLifecycleCoordinator deviceLifecycleCoordinator,
                               DeviceBatchExecutor deviceBatchExecutor,
                               ReconnectCoordinator reconnectCoordinator,
                               @Qualifier("timeSliceScheduler") ScheduledExecutorService timeSliceScheduler) {
        this.collectionManager = collectionManager;
        this.configManager = configManager;
        this.collectionStatistics = collectionStatistics;
        this.collectorProperties = collectorProperties;
        this.systemResourceMonitorService = systemResourceMonitorService;
        this.runtimeState = runtimeState;
        this.performanceMonitor = performanceMonitor;
        this.deviceLifecycleCoordinator = deviceLifecycleCoordinator;
        this.deviceBatchExecutor = deviceBatchExecutor;
        this.reconnectCoordinator = reconnectCoordinator;
        this.timeSliceScheduler = timeSliceScheduler;
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

    @PostConstruct
    public void init() {
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
        startTimeSliceScheduling();
        startDynamicTimeSliceAdjustment();
        startPerformanceMonitoring();
        maintenanceScheduleFutures.add(timeSliceScheduler.schedule(this::autoStartAllDevices, 5, TimeUnit.SECONDS));
    }

    @PreDestroy
    public void destroy() {
        stopAllDevices();
        cancelTimeSliceScheduling();
        cancelMaintenanceScheduling();
        pendingConfigRestartTasks.values().forEach(future -> future.cancel(false));
        pendingConfigRestartTasks.clear();
        reconnectCoordinator.clearAll();
        runtimeState.clear();
    }

    void startTimeSliceScheduling() {
        cancelTimeSliceScheduling();
        int sliceCount = Math.max(1, runtimeState.getTimeSliceCount());
        int sliceInterval = Math.max(1, runtimeState.getTimeSliceInterval());
        long revision = runtimeState.getTimeSliceRevision();
        for (int sliceIndex = 0; sliceIndex < sliceCount; sliceIndex++) {
            final int currentSlice = sliceIndex;
            final long currentRevision = revision;
            ScheduledFuture<?> future = timeSliceScheduler.scheduleAtFixedRate(() -> {
                try {
                    executeTimeSlice(currentSlice, currentRevision);
                } catch (Exception e) {
                    log.error("时间片执行失败, 分片={}", currentSlice, e);
                }
            }, (long) sliceIndex * sliceInterval, (long) sliceInterval * sliceCount, TimeUnit.MILLISECONDS);
            timeSliceScheduleFutures.put(currentSlice, future);
        }
    }

    void cancelTimeSliceScheduling() {
        timeSliceScheduleFutures.values().forEach(future -> future.cancel(false));
        timeSliceScheduleFutures.clear();
    }

    private void cancelMaintenanceScheduling() {
        maintenanceScheduleFutures.forEach(future -> future.cancel(false));
        maintenanceScheduleFutures.clear();
    }

    void executeTimeSlice(int sliceIndex, long revision) {
        long startTime = System.currentTimeMillis();
        int currentSliceInterval = runtimeState.getTimeSliceInterval();
        try {
            if (revision != runtimeState.getTimeSliceRevision()) {
                return;
            }
            List<DeviceBatchTask> tasks = runtimeState.getSliceTasks(sliceIndex);
            if (tasks.isEmpty()) {
                return;
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (DeviceBatchTask task : tasks) {
                if (task.shouldSkip() || !deviceBatchExecutor.isBatchTaskActive(task) || task.timeSliceRevision != revision) {
                    continue;
                }
                CompletableFuture<Void> future = deviceBatchExecutor.submit(task);
                if (future != null) {
                    futures.add(future);
                }
            }

            if (!futures.isEmpty()) {
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(Math.max(1, currentSliceInterval - 10L), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    log.warn("时间片执行超时，保留周期任务继续运行, 分片={}", sliceIndex);
                } catch (Exception e) {
                    log.error("时间片执行失败, 分片={}", sliceIndex, e);
                }
            }
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            performanceMonitor.recordTimeSliceExecution(sliceIndex, executionTime, runtimeState.getTimeSliceInterval());
        }
    }

    private void autoStartAllDevices() {
        try {
            startAllDevices();
        } catch (Exception e) {
            log.error("自动启动全部设备失败", e);
        }
    }

    void startPerformanceMonitoring() {
        ScheduledFuture<?> future = timeSliceScheduler.scheduleAtFixedRate(
                () -> performanceMonitor.logStatistics(runtimeState.getTimeSliceInterval()),
                60, 60, TimeUnit.SECONDS
        );
        maintenanceScheduleFutures.add(future);
    }

    void startDynamicTimeSliceAdjustment() {
        int interval = collectorProperties.getScheduler().getDynamicAdjustIntervalMs();
        ScheduledFuture<?> future = timeSliceScheduler.scheduleAtFixedRate(
                this::adjustTimeSlicesDynamically,
                interval,
                interval,
                TimeUnit.MILLISECONDS
        );
        maintenanceScheduleFutures.add(future);
    }

    void adjustTimeSlicesDynamically() {
        try {
            double cpuLoad = getSystemCpuLoad();
            int activeDevices = runtimeState.getRunningDeviceCount();
            long totalTasks = runtimeState.getTotalTaskCount();
            int newSliceCount = calculateOptimalSliceCount(activeDevices, totalTasks, cpuLoad);
            long avgExecution = performanceMonitor.getAverageTimeSliceExecution();
            boolean timeoutDetected = performanceMonitor.consumeTimeSliceTimeout();
            int tunedInterval = timeSliceTuner != null
                    ? timeSliceTuner.adjustInterval(runtimeState.getTimeSliceInterval(), avgExecution, timeoutDetected)
                    : runtimeState.getTimeSliceInterval();
            applyTimeSliceConfigUpdate(newSliceCount, tunedInterval);
        } catch (Exception e) {
            log.error("调整时间片失败", e);
        }
    }

    int calculateOptimalSliceCount(int activeDevices, long totalTasks, double cpuLoad) {
        int baseSlices = Math.max(1, Math.min(
                activeDevices / 5 + 1,
                collectorProperties.getScheduler().getMaxTimeSliceCount()
        ));
        if (cpuLoad > 0.8) {
            baseSlices = Math.min(collectorProperties.getScheduler().getMaxTimeSliceCount(), baseSlices + 2);
        } else if (cpuLoad < 0.3) {
            baseSlices = Math.max(2, baseSlices - 1);
        }
        return baseSlices;
    }

    void applyTimeSliceConfigUpdate(int newSliceCount, int newSliceInterval) {
        int normalizedSliceCount = Math.max(1, newSliceCount);
        int normalizedSliceInterval = Math.max(
                collectorProperties.getScheduler().getMinTimeSliceIntervalMs(),
                newSliceInterval
        );
        boolean changed = runtimeState.callExclusive(() -> {
            int oldSliceCount = runtimeState.getTimeSliceCount();
            int oldSliceInterval = runtimeState.getTimeSliceInterval();
            boolean sliceCountChanged = normalizedSliceCount != oldSliceCount;
            boolean intervalChanged = normalizedSliceInterval != oldSliceInterval;
            if (!sliceCountChanged && !intervalChanged) {
                return false;
            }

            runtimeState.updateTimeSliceConfig(normalizedSliceCount, normalizedSliceInterval);
            rebuildTimeSliceAssignmentsLocked();
            return true;
        });
        if (changed) {
            startTimeSliceScheduling();
        }
    }

    void rebuildTimeSliceAssignments() {
        runtimeState.runExclusive(this::rebuildTimeSliceAssignmentsLocked);
    }

    private void rebuildTimeSliceAssignmentsLocked() {
        runtimeState.resetTimeSliceBuckets(runtimeState.getTimeSliceCount());
        List<String> deviceIds = new ArrayList<>(runtimeState.getRunningDevices());
        for (String deviceId : deviceIds) {
            try {
                List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
                DeviceScheduleInfo scheduleInfo = runtimeState.getScheduleInfo(deviceId);
                if (scheduleInfo == null || !scheduleInfo.isRunning() || dataPoints == null || dataPoints.isEmpty()) {
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
        if (systemResourceMonitorService == null) {
            return -1D;
        }
        try {
            return systemResourceMonitorService.getResources().getProcessCpuLoad();
        } catch (Exception e) {
            log.debug("读取进程 CPU 负载失败", e);
            return -1D;
        }
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
        return deviceLifecycleCoordinator.startDevice(deviceId);
    }

    public boolean stopDevice(String deviceId) {
        return deviceLifecycleCoordinator.stopDevice(deviceId);
    }

    public void startAllDevices() {
        deviceLifecycleCoordinator.startAllDevices();
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
        ScheduledFuture<?> future = timeSliceScheduler.schedule(this::startAllDevices, 2, TimeUnit.SECONDS);
        maintenanceScheduleFutures.add(future);
    }

    @EventListener
    public void handleConfigUpdate(ConfigUpdateEvent event) {
        String deviceId = event.getDeviceId();
        if ("local-delete".equals(event.getConfigType())) {
            if (deviceId != null && isDeviceRunning(deviceId)) {
                stopDevice(deviceId);
            }
            return;
        }
        if (deviceId != null && isDeviceRunning(deviceId)) {
            ScheduledFuture<?> oldTask = pendingConfigRestartTasks.get(deviceId);
            if (oldTask != null && !oldTask.isDone()) {
                oldTask.cancel(false);
            }
            ScheduledFuture<?> restartTask = timeSliceScheduler.schedule(() -> {
                stopDevice(deviceId);
                startDevice(deviceId);
                pendingConfigRestartTasks.remove(deviceId);
            }, CONFIG_RESTART_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            pendingConfigRestartTasks.put(deviceId, restartTask);
        }
    }
}

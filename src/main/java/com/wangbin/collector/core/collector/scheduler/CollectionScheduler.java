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
import com.wangbin.collector.core.port.SystemResourceProbe;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.function.LongSupplier;

/**
 * 采集任务调度器。
 *
 * 本类只保留时间片调度、动态调整、运行快照和配置变更编排；设备生命周期、批量采集和重连细节由协作组件处理。
 */
@Slf4j
@Service
public class CollectionScheduler {

    private static final long CONFIG_RESTART_DEBOUNCE_MS = 1000L;
    private static final int MIN_PHASE_WHEEL_TICK_MS = 50;

    private final CollectionManager collectionManager;
    private final ConfigManager configManager;
    private final CollectionStatistics collectionStatistics;
    private final CollectorProperties collectorProperties;
    @Nullable
    private final SystemResourceProbe systemResourceProbe;
    private final SchedulerRuntimeState runtimeState;
    private final PerformanceMonitor performanceMonitor;
    private final DeviceLifecycleCoordinator deviceLifecycleCoordinator;
    private final DeviceBatchExecutor deviceBatchExecutor;
    private final ReconnectCoordinator reconnectCoordinator;
    private final ScheduledExecutorService timeSliceScheduler;
    private final LongSupplier nanoTimeSupplier;
    private final Map<Integer, ScheduledFuture<?>> timeSliceScheduleFutures = new ConcurrentHashMap<>();
    private final List<ScheduledFuture<?>> maintenanceScheduleFutures = new CopyOnWriteArrayList<>();
    private final Map<String, ScheduledFuture<?>> pendingConfigRestartTasks = new ConcurrentHashMap<>();
    private TimeSliceTuner timeSliceTuner;

    @Autowired
    public CollectionScheduler(CollectionManager collectionManager,
                               ConfigManager configManager,
                               CollectionStatistics collectionStatistics,
                               CollectorProperties collectorProperties,
                               @Nullable SystemResourceProbe systemResourceProbe,
                               SchedulerRuntimeState runtimeState,
                               PerformanceMonitor performanceMonitor,
                               DeviceLifecycleCoordinator deviceLifecycleCoordinator,
                               DeviceBatchExecutor deviceBatchExecutor,
                               ReconnectCoordinator reconnectCoordinator,
                               @Qualifier("timeSliceScheduler") ScheduledExecutorService timeSliceScheduler) {
        this(collectionManager,
                configManager,
                collectionStatistics,
                collectorProperties,
                systemResourceProbe,
                runtimeState,
                performanceMonitor,
                deviceLifecycleCoordinator,
                deviceBatchExecutor,
                reconnectCoordinator,
                timeSliceScheduler,
                System::nanoTime);
    }

    CollectionScheduler(CollectionManager collectionManager,
                        ConfigManager configManager,
                        CollectionStatistics collectionStatistics,
                        CollectorProperties collectorProperties,
                        @Nullable SystemResourceProbe systemResourceProbe,
                        SchedulerRuntimeState runtimeState,
                        PerformanceMonitor performanceMonitor,
                        DeviceLifecycleCoordinator deviceLifecycleCoordinator,
                        DeviceBatchExecutor deviceBatchExecutor,
                        ReconnectCoordinator reconnectCoordinator,
                        ScheduledExecutorService timeSliceScheduler,
                        LongSupplier nanoTimeSupplier) {
        this.collectionManager = collectionManager;
        this.configManager = configManager;
        this.collectionStatistics = collectionStatistics;
        this.collectorProperties = collectorProperties;
        this.systemResourceProbe = systemResourceProbe;
        this.runtimeState = runtimeState;
        this.performanceMonitor = performanceMonitor;
        this.deviceLifecycleCoordinator = deviceLifecycleCoordinator;
        this.deviceBatchExecutor = deviceBatchExecutor;
        this.reconnectCoordinator = reconnectCoordinator;
        this.timeSliceScheduler = timeSliceScheduler;
        this.nanoTimeSupplier = nanoTimeSupplier != null ? nanoTimeSupplier : System::nanoTime;
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

    private void cancelMaintenanceScheduling() {
        maintenanceScheduleFutures.forEach(future -> future.cancel(false));
        maintenanceScheduleFutures.clear();
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
        boolean schedulingActive = !timeSliceScheduleFutures.isEmpty();
        if (changed) {
            runtimeState.updateTimeSliceConfig(normalizedSliceCount, normalizedSliceInterval);
            rebuildTimeSliceAssignments();
        }
        if (changed && schedulingActive) {
            startTimeSliceScheduling();
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
                executeTimeSlice(currentSlice, revision);
            } catch (Exception e) {
                log.error("时间片执行失败, 分片={}", currentSlice, e);
            }
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
        boolean started = deviceLifecycleCoordinator.startDevice(deviceId);
        if (started) {
            adjustTimeSlicesAfterWorkloadChange();
        }
        return started;
    }

    public boolean stopDevice(String deviceId) {
        return deviceLifecycleCoordinator.stopDevice(deviceId);
    }

    public void startAllDevices() {
        deviceLifecycleCoordinator.startAllDevices();
        adjustTimeSlicesAfterWorkloadChange();
    }

    private void adjustTimeSlicesAfterWorkloadChange() {
        adjustTimeSlicesDynamically();
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

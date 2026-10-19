package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;
import com.wangbin.collector.core.collector.runtime.AcquisitionRuntimeTracker;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.port.CollectionHealthReporter;
import com.wangbin.collector.core.config.manager.ConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 编排设备启动、停止和启动失败清理，避免调度器直接承载设备生命周期细节。
 */
@Slf4j
@Component
public class DeviceLifecycleCoordinator {

    private final CollectionManager collectionManager;
    private final CollectionStatistics collectionStatistics;
    private final CollectionHealthReporter collectionHealthReporter;
    private final DeviceBatchPlanner deviceBatchPlanner;
    private final ProtocolBatchStrategy protocolBatchStrategy;
    private final CollectionTaskGuard collectionTaskGuard;
    private final SchedulerRuntimeState runtimeState;
    private final PerformanceMonitor performanceMonitor;
    private final DeviceStartPreparer deviceStartPreparer;
    private final DeviceLifecycleCleanup deviceLifecycleCleanup;
    private final AcquisitionRuntimeTracker acquisitionRuntimeTracker;
    private final ThreadPoolExecutor deviceStartExecutor;
    private final Map<String, StartFuture> startingFutures = new ConcurrentHashMap<>();
    private final Map<String, DeviceLifecycleLock> lifecycleLocks = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<String> cancelConfigTasks = ignored -> { };
    private volatile java.util.function.Predicate<String> hasPendingConfigRestart = ignored -> false;

    @Autowired
    public DeviceLifecycleCoordinator(CollectionManager collectionManager,
                                      CollectionStatistics collectionStatistics,
                                      CollectionHealthReporter collectionHealthReporter,
                                      DeviceBatchPlanner deviceBatchPlanner,
                                      ProtocolBatchStrategy protocolBatchStrategy,
                                      CollectionTaskGuard collectionTaskGuard,
                                      SchedulerRuntimeState runtimeState,
                                      PerformanceMonitor performanceMonitor,
                                      DeviceStartPreparer deviceStartPreparer,
                                      DeviceLifecycleCleanup deviceLifecycleCleanup,
                                      @Qualifier("deviceStartExecutor") ThreadPoolExecutor deviceStartExecutor,
                                      AcquisitionRuntimeTracker acquisitionRuntimeTracker) {
        this.collectionManager = collectionManager;
        this.collectionStatistics = collectionStatistics;
        this.collectionHealthReporter = collectionHealthReporter;
        this.deviceBatchPlanner = deviceBatchPlanner;
        this.protocolBatchStrategy = protocolBatchStrategy;
        this.collectionTaskGuard = collectionTaskGuard;
        this.runtimeState = runtimeState;
        this.performanceMonitor = performanceMonitor;
        this.deviceStartPreparer = deviceStartPreparer;
        this.deviceLifecycleCleanup = deviceLifecycleCleanup;
        this.deviceStartExecutor = deviceStartExecutor;
        this.acquisitionRuntimeTracker = acquisitionRuntimeTracker;
    }

    /** 保留已有的手工构造调用方式。 */
    public DeviceLifecycleCoordinator(CollectionManager collectionManager,
                                      CollectionStatistics collectionStatistics,
                                      CollectionHealthReporter collectionHealthReporter,
                                      DeviceBatchPlanner deviceBatchPlanner,
                                      ProtocolBatchStrategy protocolBatchStrategy,
                                      CollectionTaskGuard collectionTaskGuard,
                                      SchedulerRuntimeState runtimeState,
                                      PerformanceMonitor performanceMonitor,
                                      DeviceStartPreparer deviceStartPreparer,
                                      DeviceLifecycleCleanup deviceLifecycleCleanup,
                                      ThreadPoolExecutor deviceStartExecutor) {
        this(collectionManager, collectionStatistics, collectionHealthReporter, deviceBatchPlanner,
                protocolBatchStrategy, collectionTaskGuard, runtimeState, performanceMonitor,
                deviceStartPreparer, deviceLifecycleCleanup, deviceStartExecutor, null);
    }

    public boolean startDevice(String deviceId) {
        try {
            StartReservation reservation;
            DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
            try {
                if (runtimeState.isClosed()) return false;
                if (runtimeState.getDesiredState(deviceId) == SchedulerRuntimeState.DesiredState.RUNNING
                        && (runtimeState.isRunning(deviceId) || runtimeState.isStarting(deviceId)
                        || hasPendingConfigRestart.test(deviceId))) return true;
                if (!runtimeState.requestRunning(deviceId)) return false;
                reservation = deviceStartPreparer.reserve(deviceId);
            } finally {
                releaseLifecycleLock(deviceId, lifecycleLock);
            }
            return reservation != null && continueReservedStart(reservation);
        } catch (Exception e) {
            log.error("启动设备失败, 设备={}", deviceId, e);
            return false;
        }
    }

    /** 保存后启动只沿用已有 RUNNING 意图；并发 STOP 优先于保存请求。 */
    public StartAfterConfigSaveResult startDeviceAfterConfigSave(String deviceId, long expectedIntentRevision) {
        StartReservation reservation;
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            if (runtimeState.isClosed()) return new StartAfterConfigSaveResult(false, "FAILED");
            if (getIntentRevision(deviceId) != expectedIntentRevision
                    && getDesiredState(deviceId) == SchedulerRuntimeState.DesiredState.STOPPED) {
                return new StartAfterConfigSaveResult(false, "STOP_SUPERSEDED");
            }
            if (getDesiredState(deviceId) == SchedulerRuntimeState.DesiredState.RUNNING) {
                if (isDeviceConfigurationChanged(deviceId)) {
                    boolean pending = hasPendingConfigRestart.test(deviceId);
                    return new StartAfterConfigSaveResult(pending, pending ? "RESTART_PENDING" : "FAILED");
                }
                if (runtimeState.isRunning(deviceId)) return new StartAfterConfigSaveResult(true, "ALREADY_RUNNING");
                if (runtimeState.isStarting(deviceId)) return new StartAfterConfigSaveResult(true, "ACCEPTED");
            }
            if (!runtimeState.requestRunning(deviceId)) return new StartAfterConfigSaveResult(false, "FAILED");
            reservation = deviceStartPreparer.reserve(deviceId);
        } catch (Exception exception) {
            log.error("保存后预留设备启动失败, 设备={}", deviceId, exception);
            return new StartAfterConfigSaveResult(false, "FAILED");
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
        boolean accepted = reservation != null && continueReservedStart(reservation);
        return new StartAfterConfigSaveResult(accepted, accepted ? "ACCEPTED" : "FAILED");
    }

    public record StartAfterConfigSaveResult(boolean accepted, String status) { }

    /** 只读取既有重启任务的所有权，不在设备生命周期锁中取得协调器全局锁。 */
    void setConfigRestartPending(java.util.function.Predicate<String> pending) {
        hasPendingConfigRestart = pending;
    }

    StartReservation reserveStartForConfigRestart(String deviceId) throws Exception {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            if (!runtimeState.isRunningIntent(deviceId, getIntentRevision(deviceId))) return null;
            return deviceStartPreparer.reserve(deviceId);
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    /** 配置重启的停止与预留在同一个设备锁中完成，绝不改写用户意图。 */
    StartReservation reserveStartForConfigRestart(String deviceId, long revision, boolean wasRunning, boolean wasStarting)
            throws Exception {
        return reserveStartForConfigRestart(deviceId, revision, wasRunning, wasStarting, () -> true);
    }

    StartReservation reserveStartForConfigRestart(String deviceId, long revision, boolean wasRunning, boolean wasStarting,
                                                  java.util.function.BooleanSupplier taskIsCurrent) throws Exception {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            if (!runtimeState.isRunningIntent(deviceId, revision) || !taskIsCurrent.getAsBoolean()) return null;
            if (!stopDevice(deviceId, wasRunning, wasStarting)) return null;
            if (!runtimeState.isRunningIntent(deviceId, revision) || !taskIsCurrent.getAsBoolean()) return null;
            log.info("配置重启预留 deviceId={} action=START source=CONFIG_RESTART intentRevision={} desiredState={}",
                    deviceId, revision, runtimeState.getDesiredState(deviceId));
            return deviceStartPreparer.reserve(deviceId);
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    public SchedulerRuntimeState.DesiredState getDesiredState(String deviceId) {
        return runtimeState.getDesiredState(deviceId);
    }

    public long getIntentRevision(String deviceId) {
        return runtimeState.getIntentRevision(deviceId);
    }

    boolean isRunningIntent(String deviceId, long revision) {
        return runtimeState.isRunningIntent(deviceId, revision);
    }

    void setConfigTaskCancellation(java.util.function.Consumer<String> cancellation) {
        cancelConfigTasks = cancellation;
    }

    void beginShutdown() {
        runtimeState.beginShutdown();
    }



    boolean continueReservedStart(StartReservation reservation) {
        if (reservation == null) {
            return false;
        }
        String deviceId = reservation.deviceId();
        StartPreparation preparation;
        try {
            preparation = prepareReservedStart(reservation);
            if (preparation == null) {
                return false;
            }
        } catch (Exception e) {
            log.error("启动设备失败, 设备={}", deviceId, e);
            cleanupFailedStart(deviceId, reservation.generation());
            return false;
        }
        return continuePreparedStart(deviceId, preparation);
    }

    private StartPreparation prepareReservedStart(StartReservation reservation) throws Exception {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(reservation.deviceId());
        try {
            return deviceStartPreparer.prepareReserved(reservation);
        } finally {
            releaseLifecycleLock(reservation.deviceId(), lifecycleLock);
        }
    }

    private boolean continuePreparedStart(String deviceId, StartPreparation preparation) {
        if (preparation == null) {
            return false;
        }
        try {
            if (!registerPreparedDevice(deviceId, preparation)) {
                discardStaleStart(deviceId, preparation.generation());
                return false;
            }

            if (!isStartGenerationCurrent(deviceId, preparation.generation())) {
                discardStaleStart(deviceId, preparation.generation());
                return false;
            }
            if (!connectDevice(deviceId, preparation.connectTimeoutMs(), preparation.generation())) {
                cleanupFailedStart(deviceId, preparation.generation());
                return false;
            }

            if (!isStartGenerationCurrent(deviceId, preparation.generation())) {
                discardStaleStart(deviceId, preparation.generation());
                return false;
            }
            return completeStartAfterConnect(deviceId, preparation);
        } catch (Exception e) {
            log.error("启动设备失败, 设备={}", deviceId, e);
            cleanupFailedStart(deviceId, preparation.generation());
            return false;
        }
    }

    private boolean registerPreparedDevice(String deviceId, StartPreparation preparation) {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            if (!isStartGenerationCurrent(deviceId, preparation.generation())) {
                return false;
            }
            collectionManager.registerDevice(preparation.deviceInfo());
            collectionManager.bindRuntimeGeneration(deviceId, preparation.generation());
            collectionManager.bindRuntimeConfigurationVersion(deviceId, preparation.generation(),
                    runtimeState.getAppliedConfigVersion(deviceId));
            log.info("采集器注册 deviceId={} generation={} configVersion={} desiredState={} actualState=STARTING",
                    deviceId, preparation.generation(), runtimeState.getAppliedConfigVersion(deviceId),
                    runtimeState.getDesiredState(deviceId));
            performanceMonitor.resetDeviceRuntimeWindow(deviceId, preparation.generation());
            if (acquisitionRuntimeTracker != null) {
                acquisitionRuntimeTracker.open(deviceId, preparation.generation(), preparation.deviceInfo(), preparation.dataPoints());
            }
            return isStartGenerationCurrent(deviceId, preparation.generation());
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    void scheduleDevicePoints(String deviceId, long generation, List<DataPoint> points) {
        List<DeviceBatchTask> batchTasks = buildDeviceBatchTasks(deviceId, generation, points);
        if (!isStartGenerationCurrent(deviceId, generation) && !isRunningGenerationCurrent(deviceId, generation)) {
            batchTasks.forEach(DeviceBatchTask::cancel);
            return;
        }
        if (!runtimeState.addBatchTasksIfRunning(deviceId, generation, batchTasks)) {
            batchTasks.forEach(DeviceBatchTask::cancel);
        }
    }

    List<DeviceBatchTask> buildDeviceBatchTasks(String deviceId, long generation, List<DataPoint> points) {
        List<DataPoint> scheduledPoints = points;
        ProtocolCollector collector = collectionManager.getCollector(deviceId);
        if (collector instanceof ProtocolPointSelectionSupport pointSelectionSupport) {
            scheduledPoints = pointSelectionSupport.filterPollingPoints(points);
        }
        return deviceBatchPlanner.plan(
                deviceId,
                scheduledPoints,
                runtimeState.getTimeSliceCount(),
                generation,
                runtimeState.getTimeSliceRevision()
        );
    }

    void autoSubscribeIfSupported(String deviceId, List<DataPoint> points) {
        ProtocolCollector collector = collectionManager.getCollector(deviceId);
        if (!(collector instanceof ProtocolPointSelectionSupport pointSelectionSupport)) {
            return;
        }
        List<DataPoint> subscriptionPoints = pointSelectionSupport.filterAutoSubscriptionPoints(points);
        if (subscriptionPoints.isEmpty()) {
            return;
        }
        try {
            collectionManager.subscribePoints(deviceId, subscriptionPoints);
        } catch (Exception ex) {
            log.warn("自动订阅 BACnet 点位失败，设备={}，点位数量={}", deviceId, subscriptionPoints.size(), ex);
        }
    }

    boolean connectDevice(String deviceId, long timeoutMs, long generation) {
        StartFuture startFuture = submitConnectFuture(deviceId, generation);
        if (startFuture == null) {
            return false;
        }
        try {
            startFuture.future().get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            startFuture.future().cancel(true);
            log.error("连接设备超时, 设备={}, 超时毫秒={}", deviceId, timeoutMs);
            return false;
        } catch (InterruptedException e) {
            startFuture.future().cancel(true);
            Thread.currentThread().interrupt();
            log.error("连接设备被中断, 设备={}", deviceId, e);
            return false;
        } catch (CancellationException e) {
            log.debug("连接设备已取消, 设备={}, 运行代次={}", deviceId, generation);
            return false;
        } catch (Exception e) {
            log.error("连接设备失败, 设备={}", deviceId, e);
            return false;
        } finally {
            startingFutures.remove(deviceId, startFuture);
        }
    }

    private StartFuture submitConnectFuture(String deviceId, long generation) {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            if (!isStartGenerationCurrent(deviceId, generation)) {
                return null;
            }
            Future<?> connectFuture = deviceStartExecutor.submit(() -> {
                collectionManager.connectDevice(deviceId, generation);
                // 点位准备已在设备锁中完成，迟到连接不再触发全设备或点位配置重载。
            });
            StartFuture startFuture = new StartFuture(connectFuture, generation);
            startingFutures.put(deviceId, startFuture);
            return startFuture;
        } catch (RejectedExecutionException e) {
            log.error("连接设备被拒绝, 设备={}, 队列长度={}", deviceId, deviceStartExecutor.getQueue().size(), e);
            return null;
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    public boolean stopDevice(String deviceId) {
        // 取消操作放在设备锁之外，避免配置协调器锁与设备锁反向嵌套。
        cancelConfigTasks.accept(deviceId);
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            runtimeState.requestStopped(deviceId);
            log.info("设备停止意图 deviceId={} action=STOP desiredState={} intentRevision={}",
                    deviceId, runtimeState.getDesiredState(deviceId), runtimeState.getIntentRevision(deviceId));
            return stopDevice(deviceId, false, false);
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
            // 覆盖取消与写入 STOP 意图之间刚刚入队的配置任务。
            cancelConfigTasks.accept(deviceId);
        }
    }

    long invalidateDeviceForDeletion(String deviceId) {
        cancelConfigTasks.accept(deviceId);
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            long revision = runtimeState.requestStopped(deviceId);
            invalidateDeviceForConfigChange(deviceId);
            return revision;
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    /** 删除事件必须在单设备生命周期锁内同时核对版本墓碑和当前缺失状态。 */
    long invalidateDeviceForDeletion(String deviceId, long configVersion, ConfigManager configManager) {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            long[] revision = {-1L};
            configManager.runIfConfigurationCurrent(deviceId, configVersion, () -> {
                if (!configManager.containsDevice(deviceId)) revision[0] = runtimeState.requestStopped(deviceId);
            });
            if (revision[0] < 0L) return -1L;
            // 先释放配置读锁再进入采集门，避免与 telemetry 的采集门→配置读锁形成反向嵌套。
            invalidateDeviceForConfigChange(deviceId);
            return revision[0];
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    boolean stopDeletedDevice(String deviceId, long revision, boolean wasRunning, boolean wasStarting,
                              long configVersion, ConfigManager configManager) {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            boolean[] currentDeletion = {false};
            configManager.runIfConfigurationCurrent(deviceId, configVersion, () ->
                    currentDeletion[0] = !configManager.containsDevice(deviceId)
                            && getDesiredState(deviceId) == SchedulerRuntimeState.DesiredState.STOPPED
                            && getIntentRevision(deviceId) == revision);
            if (!currentDeletion[0]) return true;
            // 配置读锁不包围采集门或资源清理；新代次启动仍被当前生命周期锁阻挡。
            boolean stopped = stopDevice(deviceId, wasRunning, wasStarting);
            if (stopped) runtimeState.forgetStoppedIntentAfterDeletion(deviceId, revision);
            return stopped;
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    boolean stopDeletedDevice(String deviceId, long revision, boolean wasRunning, boolean wasStarting) {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            if (runtimeState.getDesiredState(deviceId) != SchedulerRuntimeState.DesiredState.STOPPED
                    || getIntentRevision(deviceId) != revision) return true;
            boolean stopped = stopDevice(deviceId, wasRunning, wasStarting);
            if (stopped) runtimeState.forgetStoppedIntentAfterDeletion(deviceId, revision);
            return stopped;
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    boolean stopDeviceAfterConfigInvalidation(String deviceId,
                                              boolean wasRunningBeforeInvalidation,
                                              boolean wasStartingBeforeInvalidation) {
        return stopDevice(deviceId, wasRunningBeforeInvalidation, wasStartingBeforeInvalidation);
    }

    private boolean stopDevice(String deviceId,
                               boolean wasRunningBeforeInvalidation,
                               boolean wasStartingBeforeInvalidation) {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            boolean wasStarting = wasStartingBeforeInvalidation || runtimeState.isStarting(deviceId);
            boolean wasRunning = wasRunningBeforeInvalidation || runtimeState.isRunning(deviceId);
            // 先使 generation 失效，确保阻塞中的 start/collect/reconnect 结果不能再提交运行态。
            boolean criticalCleanupSucceeded = true;
            try {
                collectionTaskGuard.clearDevice(deviceId);
            } catch (Exception e) {
                criticalCleanupSucceeded = false;
                log.error("停止设备时清除运行代次失败, 设备={}", deviceId, e);
            }
            try {
                cancelStartingFuture(deviceId);
            } catch (Exception e) {
                criticalCleanupSucceeded = false;
                log.error("停止设备时取消启动任务失败, 设备={}", deviceId, e);
            }
            DeviceLifecycleCleanup.DeviceCleanupResult cleanupResult = deviceLifecycleCleanup.cleanupStoppedDevice(
                    deviceId,
                    wasRunning,
                    wasStarting);
            if (acquisitionRuntimeTracker != null) {
                acquisitionRuntimeTracker.clear(deviceId);
            }
            return criticalCleanupSucceeded && cleanupResult.criticalCleanupSucceeded();
        } catch (Exception e) {
            log.error("停止设备失败, 设备={}", deviceId, e);
            return false;
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    public void startAllDevices() {
        for (String deviceId : deviceStartPreparer.getStartableDeviceIds()) {
            try {
                startDevice(deviceId);
            } catch (Exception e) {
                log.error("启动设备失败, 设备={}", deviceId, e);
            }
        }
    }

    public void stopAllDevices() {
        java.util.Set<String> targets = new java.util.HashSet<>(runtimeState.getKnownDeviceIds());
        targets.addAll(runtimeState.getDesiredRunningDeviceIds());
        List<String> activeDevices = new ArrayList<>(targets);
        for (String deviceId : activeDevices) {
            try {
                stopDevice(deviceId);
            } catch (Exception e) {
                log.error("停止设备失败, 设备={}", deviceId, e);
            }
        }
    }

    void cleanupFailedStart(String deviceId, long generation) {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            cancelStartingFutureIfGeneration(deviceId, generation);
            deviceLifecycleCleanup.cleanupFailedStart(deviceId, generation);
            if (acquisitionRuntimeTracker != null) {
                acquisitionRuntimeTracker.clearIfGeneration(deviceId, generation);
            }
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    void initializeBatchSizing(String deviceId, DeviceInfo deviceInfo) {
        String protocol = deviceInfo != null ? deviceInfo.getProtocolType() : null;
        int defaultBatchSize = protocolBatchStrategy.defaultBatchSize(protocol);
        int maxBatchSize = protocolBatchStrategy.maxBatchSize(protocol);
        performanceMonitor.initializeDeviceBatchSize(deviceId, defaultBatchSize, maxBatchSize);
    }

    boolean isDeviceConfigurationChanged(String deviceId) {
        return !deviceStartPreparer.containsDevice(deviceId)
                || runtimeState.getAppliedConfigVersion(deviceId) != deviceStartPreparer.getConfigVersion(deviceId);
    }

    List<String> getChangedConfigDeviceIds() {
        return runtimeState.getDesiredRunningDeviceIds().stream()
                .filter(this::isDeviceConfigurationChanged)
                .toList();
    }

    boolean hasDeviceConfiguration(String deviceId) {
        return deviceStartPreparer.containsDevice(deviceId);
    }

    public List<String> getRunningDevices() {
        return runtimeState.getRunningDevices();
    }

    public boolean isDeviceRunning(String deviceId) {
        return runtimeState.isRunning(deviceId);
    }

    public boolean isDeviceStarting(String deviceId) {
        return runtimeState.isStarting(deviceId);
    }

    void invalidateDeviceForConfigChange(String deviceId) {
        long generation = runtimeState.getStartingGeneration(deviceId);
        DeviceScheduleInfo info = runtimeState.getScheduleInfo(deviceId);
        if (generation == 0L && info != null) generation = info.getGeneration();
        if (generation == 0L) return;
        collectionTaskGuard.clearDeviceIfCurrent(deviceId, generation);
        cancelStartingFutureIfGeneration(deviceId, generation);
        runtimeState.clearStartingIfGeneration(deviceId, generation);
    }

    boolean invalidateDeviceForConfigChange(String deviceId, long revision) {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            if (!runtimeState.isRunningIntent(deviceId, revision)) return false;
            invalidateDeviceForConfigChange(deviceId);
            return true;
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    private boolean completeStartAfterConnect(String deviceId, StartPreparation preparation) {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        long generation = preparation.generation();
        List<DeviceBatchTask> batchTasks = List.of();
        try {
            if (!isStartGenerationCurrent(deviceId, generation)) {
                discardStaleStart(deviceId, generation);
                return false;
            }

            initializeBatchSizing(deviceId, preparation.deviceInfo());
            List<DataPoint> readablePoints = acquisitionRuntimeTracker == null ? preparation.dataPoints()
                    : preparation.dataPoints().stream().filter(point -> point != null
                    && acquisitionRuntimeTracker.isValidPoint(deviceId, generation, point.getPointId())).toList();
            batchTasks = buildDeviceBatchTasks(deviceId, generation, readablePoints);
            if (!isStartGenerationCurrent(deviceId, generation)) {
                batchTasks.forEach(DeviceBatchTask::cancel);
                discardStaleStart(deviceId, generation);
                return false;
            }

            collectionManager.rebuildReadPlans(deviceId, readablePoints);
            if (!isStartGenerationCurrent(deviceId, generation)) {
                batchTasks.forEach(DeviceBatchTask::cancel);
                discardStaleStart(deviceId, generation);
                return false;
            }

            autoSubscribeIfSupported(deviceId, readablePoints);
            if (!isStartGenerationCurrent(deviceId, generation)) {
                batchTasks.forEach(DeviceBatchTask::cancel);
                discardStaleStart(deviceId, generation);
                return false;
            }

            if (!runtimeState.commitRunning(deviceId, generation, batchTasks)) {
                batchTasks.forEach(DeviceBatchTask::cancel);
                return false;
            }
            if (acquisitionRuntimeTracker != null) {
                java.util.Set<String> pollingPointIds = new java.util.HashSet<>();
                for (DeviceBatchTask task : batchTasks) {
                    for (DataPoint point : task.points) {
                        if (point != null) pollingPointIds.add(point.getPointId());
                    }
                }
                acquisitionRuntimeTracker.markPollingPlan(deviceId, generation, pollingPointIds);
            }
            collectionStatistics.startCollection(deviceId, preparation.dataPoints().size());
            collectionHealthReporter.markDeviceStarted(deviceId);
            log.info("设备连接完成并注册任务 deviceId={} generation={} desiredState={} running=true configVersion={} batchTasks={}",
                    deviceId, generation, runtimeState.getDesiredState(deviceId),
                    runtimeState.getAppliedConfigVersion(deviceId), batchTasks.size());
            return true;
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    private boolean isStartGenerationCurrent(String deviceId, long generation) {
        return !runtimeState.isClosed()
                && runtimeState.getDesiredState(deviceId) == SchedulerRuntimeState.DesiredState.RUNNING
                && collectionTaskGuard.isCurrent(deviceId, generation)
                && runtimeState.isStartingGeneration(deviceId, generation);
    }

    private boolean isRunningGenerationCurrent(String deviceId, long generation) {
        DeviceScheduleInfo scheduleInfo = runtimeState.getScheduleInfo(deviceId);
        return scheduleInfo != null
                && scheduleInfo.isRunning()
                && scheduleInfo.getGeneration() == generation
                && collectionTaskGuard.isCurrent(deviceId, generation);
    }

    private void discardStaleStart(String deviceId, long generation) {
        cancelStartingFutureIfGeneration(deviceId, generation);
        collectionTaskGuard.clearDeviceIfCurrent(deviceId, generation);
        deviceLifecycleCleanup.discardStaleStart(deviceId, generation);
        if (acquisitionRuntimeTracker != null) {
            acquisitionRuntimeTracker.clearIfGeneration(deviceId, generation);
        }
        log.debug("丢弃旧代次启动结果, 设备={}, 运行代次={}", deviceId, generation);
    }

    private void cancelStartingFuture(String deviceId) {
        StartFuture startFuture = startingFutures.remove(deviceId);
        if (startFuture != null && !startFuture.future().isDone()) {
            startFuture.future().cancel(true);
        }
    }

    private void cancelStartingFutureIfGeneration(String deviceId, long generation) {
        StartFuture startFuture = startingFutures.get(deviceId);
        if (startFuture == null || startFuture.generation() != generation) {
            return;
        }
        if (startingFutures.remove(deviceId, startFuture) && !startFuture.future().isDone()) {
            startFuture.future().cancel(true);
        }
    }

    int startingFutureCountForTest() {
        return startingFutures.size();
    }

    Object acquireLifecycleLockForTest(String deviceId) {
        return acquireLifecycleLock(deviceId);
    }

    void releaseLifecycleLockForTest(String deviceId, Object lifecycleLock) {
        releaseLifecycleLock(deviceId, (DeviceLifecycleLock) lifecycleLock);
    }

    Object lifecycleLockHolderForTest(String deviceId) {
        return lifecycleLocks.get(deviceId);
    }

    int lifecycleLockReferenceCountForTest(String deviceId) {
        DeviceLifecycleLock lifecycleLock = lifecycleLocks.get(deviceId);
        return lifecycleLock == null ? 0 : lifecycleLock.references;
    }

    int lifecycleLockHolderCountForTest() {
        return lifecycleLocks.size();
    }

    private DeviceLifecycleLock acquireLifecycleLock(String deviceId) {
        DeviceLifecycleLock lifecycleLock = lifecycleLocks.compute(deviceId, (key, existing) -> {
            DeviceLifecycleLock current = existing != null ? existing : new DeviceLifecycleLock();
            current.references++;
            return current;
        });
        lifecycleLock.lock.lock();
        return lifecycleLock;
    }

    private void releaseLifecycleLock(String deviceId, DeviceLifecycleLock lifecycleLock) {
        try {
            lifecycleLock.lock.unlock();
        } finally {
            lifecycleLocks.computeIfPresent(deviceId, (key, existing) -> {
                if (existing != lifecycleLock) {
                    return existing;
                }
                existing.references--;
                if (existing.references == 0
                        && !runtimeState.isStarting(deviceId)
                        && !runtimeState.isRunning(deviceId)) {
                    return null;
                }
                return existing;
            });
        }
    }

    private record StartFuture(Future<?> future, long generation) {
    }

    /**
     * 启动预留只表示设备已经进入 stopAllDevices 可见的 starting 状态，尚未执行网络连接。
     */
    record StartReservation(String deviceId, long generation) {
    }

    private static final class DeviceLifecycleLock {
        private final ReentrantLock lock = new ReentrantLock();
        private volatile int references;
    }
}

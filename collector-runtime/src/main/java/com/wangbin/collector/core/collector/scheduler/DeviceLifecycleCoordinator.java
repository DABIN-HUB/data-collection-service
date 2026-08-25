package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.port.CollectionHealthReporter;
import lombok.extern.slf4j.Slf4j;
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
    private final ThreadPoolExecutor deviceStartExecutor;
    private final Map<String, StartFuture> startingFutures = new ConcurrentHashMap<>();
    private final Map<String, DeviceLifecycleLock> lifecycleLocks = new ConcurrentHashMap<>();

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
                                      @Qualifier("deviceStartExecutor") ThreadPoolExecutor deviceStartExecutor) {
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
    }

    public boolean startDevice(String deviceId) {
        StartPreparation preparation;
        try {
            preparation = prepareStart(deviceId);
            if (preparation == null) {
                return false;
            }
        } catch (Exception e) {
            log.error("启动设备失败, 设备={}", deviceId, e);
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

    private StartPreparation prepareStart(String deviceId) throws Exception {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            return deviceStartPreparer.prepare(deviceId);
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    private boolean registerPreparedDevice(String deviceId, StartPreparation preparation) {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            if (!isStartGenerationCurrent(deviceId, preparation.generation())) {
                return false;
            }
            try {
                collectionManager.registerDevice(preparation.deviceInfo());
            } catch (Exception e) {
                log.debug("register 设备 skipped, 设备={}", deviceId, e);
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
                collectionManager.connectDevice(deviceId);
                deviceStartPreparer.loadDataPointsAndAdaptiveConfig(deviceId);
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
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            boolean wasStarting = runtimeState.isStarting(deviceId);
            boolean wasRunning = runtimeState.isRunning(deviceId);
            // 先使 generation 失效，确保阻塞中的 start/collect/reconnect 结果不能再提交运行态。
            collectionTaskGuard.clearDevice(deviceId);
            cancelStartingFuture(deviceId);
            deviceLifecycleCleanup.cleanupStoppedDevice(deviceId, wasRunning, wasStarting);
            return true;
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
        List<String> activeDevices = new ArrayList<>(runtimeState.getActiveDeviceIds());
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

    public List<String> getRunningDevices() {
        return runtimeState.getRunningDevices();
    }

    public boolean isDeviceRunning(String deviceId) {
        return runtimeState.isRunning(deviceId);
    }

    public boolean isDeviceStarting(String deviceId) {
        return runtimeState.isStarting(deviceId);
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
            batchTasks = buildDeviceBatchTasks(deviceId, generation, preparation.dataPoints());
            if (!isStartGenerationCurrent(deviceId, generation)) {
                batchTasks.forEach(DeviceBatchTask::cancel);
                discardStaleStart(deviceId, generation);
                return false;
            }

            collectionManager.rebuildReadPlans(deviceId, preparation.dataPoints());
            if (!isStartGenerationCurrent(deviceId, generation)) {
                batchTasks.forEach(DeviceBatchTask::cancel);
                discardStaleStart(deviceId, generation);
                return false;
            }

            autoSubscribeIfSupported(deviceId, preparation.dataPoints());
            if (!isStartGenerationCurrent(deviceId, generation)) {
                batchTasks.forEach(DeviceBatchTask::cancel);
                discardStaleStart(deviceId, generation);
                return false;
            }

            if (!runtimeState.commitRunning(deviceId, generation, batchTasks)) {
                batchTasks.forEach(DeviceBatchTask::cancel);
                return false;
            }
            collectionStatistics.startCollection(deviceId, preparation.dataPoints().size());
            collectionHealthReporter.markDeviceStarted(deviceId);
            return true;
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    private boolean isStartGenerationCurrent(String deviceId, long generation) {
        return collectionTaskGuard.isCurrent(deviceId, generation)
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
        deviceLifecycleCleanup.discardStaleStart(deviceId, generation);
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

    private static final class DeviceLifecycleLock {
        private final ReentrantLock lock = new ReentrantLock();
        private volatile int references;
    }
}

package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.protocol.bacnet.BacnetIpCollector;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.monitor.health.CollectionServiceHealthTracker;
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
    private final ConfigManager configManager;
    private final CollectionStatistics collectionStatistics;
    private final CollectorProperties collectorProperties;
    private final CollectionServiceHealthTracker collectionServiceHealthTracker;
    private final DeviceBatchPlanner deviceBatchPlanner;
    private final ProtocolBatchStrategy protocolBatchStrategy;
    private final CollectionTaskGuard collectionTaskGuard;
    private final PointRuntimeStateService pointRuntimeStateService;
    private final SchedulerRuntimeState runtimeState;
    private final PerformanceMonitor performanceMonitor;
    private final DeviceBatchExecutor deviceBatchExecutor;
    private final ReconnectCoordinator reconnectCoordinator;
    private final ThreadPoolExecutor deviceStartExecutor;
    private final Map<String, StartFuture> startingFutures = new ConcurrentHashMap<>();
    private final Map<String, DeviceLifecycleLock> lifecycleLocks = new ConcurrentHashMap<>();

    public DeviceLifecycleCoordinator(CollectionManager collectionManager,
                                      ConfigManager configManager,
                                      CollectionStatistics collectionStatistics,
                                      CollectorProperties collectorProperties,
                                      CollectionServiceHealthTracker collectionServiceHealthTracker,
                                      DeviceBatchPlanner deviceBatchPlanner,
                                      ProtocolBatchStrategy protocolBatchStrategy,
                                      CollectionTaskGuard collectionTaskGuard,
                                      PointRuntimeStateService pointRuntimeStateService,
                                      SchedulerRuntimeState runtimeState,
                                      PerformanceMonitor performanceMonitor,
                                      DeviceBatchExecutor deviceBatchExecutor,
                                      ReconnectCoordinator reconnectCoordinator,
                                      @Qualifier("deviceStartExecutor") ThreadPoolExecutor deviceStartExecutor) {
        this.collectionManager = collectionManager;
        this.configManager = configManager;
        this.collectionStatistics = collectionStatistics;
        this.collectorProperties = collectorProperties;
        this.collectionServiceHealthTracker = collectionServiceHealthTracker;
        this.deviceBatchPlanner = deviceBatchPlanner;
        this.protocolBatchStrategy = protocolBatchStrategy;
        this.collectionTaskGuard = collectionTaskGuard;
        this.pointRuntimeStateService = pointRuntimeStateService;
        this.runtimeState = runtimeState;
        this.performanceMonitor = performanceMonitor;
        this.deviceBatchExecutor = deviceBatchExecutor;
        this.reconnectCoordinator = reconnectCoordinator;
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

            long connectTimeoutMs = resolveDeviceStartTimeoutMs(deviceId);
            if (!isStartGenerationCurrent(deviceId, preparation.generation())) {
                discardStaleStart(deviceId, preparation.generation());
                return false;
            }
            if (!connectDevice(deviceId, connectTimeoutMs, preparation.generation())) {
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

    private StartPreparation prepareStart(String deviceId) throws Exception {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        long generation = 0L;
        boolean generationActivated = false;
        try {
            if (!runtimeState.markStartingIfNotActive(deviceId)) {
                return null;
            }

            DeviceInfo deviceInfo = configManager.getDevice(deviceId);
            if (deviceInfo == null) {
                runtimeState.clearStarting(deviceId);
                return null;
            }
            List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
            if (dataPoints == null || dataPoints.isEmpty()) {
                runtimeState.clearStarting(deviceId);
                return null;
            }

            if (collectorProperties.getAdaptiveCollection().isEnabled()) {
                pointRuntimeStateService.initializeDevice(deviceId, dataPoints);
            }

            generation = collectionTaskGuard.activateNextGeneration(deviceId);
            generationActivated = true;
            runtimeState.markStartingGeneration(deviceId, generation);
            reconnectCoordinator.clear(deviceId);
            return new StartPreparation(deviceInfo, List.copyOf(dataPoints), generation);
        } catch (Exception e) {
            if (generationActivated) {
                collectionTaskGuard.clearDeviceIfCurrent(deviceId, generation);
            }
            runtimeState.clearStarting(deviceId);
            reconnectCoordinator.clear(deviceId);
            throw e;
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
        if (collector instanceof BacnetIpCollector bacnetCollector) {
            scheduledPoints = bacnetCollector.filterPollingPoints(points);
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
        if (!(collector instanceof BacnetIpCollector bacnetCollector)) {
            return;
        }
        List<DataPoint> subscriptionPoints = bacnetCollector.filterAutoSubscriptionPoints(points);
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
                configManager.getDataPointsAndAdaptiveConfig(deviceId);
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
            runtimeState.removeDeviceTasks(deviceId);
            deviceBatchExecutor.cancelDeviceInFlightTasks(deviceId);
            runtimeState.removeDevice(deviceId);
            reconnectCoordinator.clear(deviceId);
            collectionStatistics.stopCollection(deviceId);
            collectionServiceHealthTracker.markDeviceStopped(deviceId);
            disconnectOrCleanupDevice(deviceId, wasRunning, wasStarting);
            return true;
        } catch (Exception e) {
            log.error("停止设备失败, 设备={}", deviceId, e);
            return false;
        } finally {
            releaseLifecycleLock(deviceId, lifecycleLock);
        }
    }

    public void startAllDevices() {
        List<String> deviceIds = configManager.getAllDeviceIds();
        for (String deviceId : deviceIds) {
            try {
                DeviceContext context = configManager.getDeviceContext(deviceId);
                if (context != null
                        && context.getDeviceInfo() != null
                        && context.getConnectionConfig() != null) {
                    startDevice(deviceId);
                }
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

    long resolveDeviceStartTimeoutMs(String deviceId) {
        long defaultTimeoutMs = Math.max(1000L, collectorProperties.getScheduler().getDeviceStartTimeoutMs());
        DeviceConnection connection = configManager.getConnectionConfig(deviceId);
        if (connection == null) {
            return defaultTimeoutMs;
        }

        Long configuredTimeout = firstPositive(
                toLong(connection.getConnectTimeout()),
                toLong(connection.getInt("connectTimeoutMs", null)),
                toLong(connection.getInt("connectTimeout", null)),
                toLong(connection.getTimeout()));
        if (configuredTimeout == null) {
            return defaultTimeoutMs;
        }
        return Math.max(1000L, Math.min(configuredTimeout, defaultTimeoutMs));
    }

    void cleanupFailedStart(String deviceId, long generation) {
        DeviceLifecycleLock lifecycleLock = acquireLifecycleLock(deviceId);
        try {
            cancelStartingFutureIfGeneration(deviceId, generation);
            boolean clearedGeneration = collectionTaskGuard.clearDeviceIfCurrent(deviceId, generation);
            boolean removedRuntimeState = runtimeState.removeDeviceIfGeneration(deviceId, generation);
            runtimeState.removeDeviceTasksIfGeneration(deviceId, generation);
            if (!clearedGeneration && !removedRuntimeState) {
                return;
            }
            reconnectCoordinator.clear(deviceId);
            try {
                collectionManager.cleanupDevice(deviceId);
            } catch (Exception e) {
                log.warn("启动失败后清理资源失败, 设备={}", deviceId, e);
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

    public List<String> getRunningDevices() {
        return runtimeState.getRunningDevices();
    }

    public boolean isDeviceRunning(String deviceId) {
        return runtimeState.isRunning(deviceId);
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
            collectionServiceHealthTracker.markDeviceStarted(deviceId);
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
        runtimeState.clearStartingIfGeneration(deviceId, generation);
        cancelStartingFutureIfGeneration(deviceId, generation);
        log.debug("丢弃旧代次启动结果, 设备={}, 运行代次={}", deviceId, generation);
    }

    private void disconnectOrCleanupDevice(String deviceId, boolean wasRunning, boolean wasStarting) {
        try {
            if (wasStarting && !wasRunning) {
                collectionManager.cleanupDevice(deviceId);
                return;
            }
            collectionManager.disconnectDevice(deviceId);
        } catch (Exception e) {
            log.warn("断开或清理设备失败, 设备={}", deviceId, e);
        }
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

    private Long firstPositive(Long... candidates) {
        if (candidates == null) {
            return null;
        }
        for (Long candidate : candidates) {
            if (candidate != null && candidate > 0) {
                return candidate;
            }
        }
        return null;
    }

    private Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    private record StartPreparation(DeviceInfo deviceInfo, List<DataPoint> dataPoints, long generation) {
    }

    private record StartFuture(Future<?> future, long generation) {
    }

    private static final class DeviceLifecycleLock {
        private final ReentrantLock lock = new ReentrantLock();
        private volatile int references;
    }
}

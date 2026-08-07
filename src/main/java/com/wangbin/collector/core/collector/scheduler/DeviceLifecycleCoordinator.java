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
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
            preparation = runtimeState.callExclusive(() -> prepareStart(deviceId));
            if (preparation == null) {
                return false;
            }
        } catch (Exception e) {
            log.error("启动设备失败, 设备={}", deviceId, e);
            return false;
        }

        try {
            try {
                collectionManager.registerDevice(preparation.deviceInfo());
            } catch (Exception e) {
                log.debug("register 设备 skipped, 设备={}", deviceId, e);
            }

            long connectTimeoutMs = resolveDeviceStartTimeoutMs(deviceId);
            if (!connectDevice(deviceId, connectTimeoutMs)) {
                cleanupFailedStart(deviceId);
                return false;
            }
            initializeBatchSizing(deviceId, preparation.deviceInfo());

            runtimeState.runExclusive(() -> {
                scheduleDevicePoints(deviceId, preparation.generation(), preparation.dataPoints());
                try {
                    collectionManager.rebuildReadPlans(deviceId, preparation.dataPoints());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                autoSubscribeIfSupported(deviceId, preparation.dataPoints());
                runtimeState.markRunning(deviceId, preparation.generation());
            });

            collectionStatistics.startCollection(deviceId, preparation.dataPoints().size());
            collectionServiceHealthTracker.markDeviceStarted(deviceId);
            return true;
        } catch (Exception e) {
            log.error("启动设备失败, 设备={}", deviceId, e);
            cleanupFailedStart(deviceId);
            return false;
        } finally {
            runtimeState.clearStarting(deviceId);
        }
    }

    private StartPreparation prepareStart(String deviceId) {
        DeviceScheduleInfo scheduleInfo = runtimeState.getScheduleInfo(deviceId);
        if ((scheduleInfo != null && scheduleInfo.isRunning()) || runtimeState.isStarting(deviceId)) {
            return null;
        }

        DeviceInfo deviceInfo = configManager.getDevice(deviceId);
        if (deviceInfo == null) {
            return null;
        }
        List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
        if (dataPoints == null || dataPoints.isEmpty()) {
            return null;
        }

        if (collectorProperties.getAdaptiveCollection().isEnabled()) {
            pointRuntimeStateService.initializeDevice(deviceId, dataPoints);
        }

        long generation = collectionTaskGuard.activateNextGeneration(deviceId);
        reconnectCoordinator.clear(deviceId);
        runtimeState.markStarting(deviceId);
        return new StartPreparation(deviceInfo, dataPoints, generation);
    }

    void scheduleDevicePoints(String deviceId, long generation, List<DataPoint> points) {
        List<DataPoint> scheduledPoints = points;
        ProtocolCollector collector = collectionManager.getCollector(deviceId);
        if (collector instanceof BacnetIpCollector bacnetCollector) {
            scheduledPoints = bacnetCollector.filterPollingPoints(points);
        }
        List<DeviceBatchTask> batchTasks = deviceBatchPlanner.plan(
                deviceId,
                scheduledPoints,
                runtimeState.getTimeSliceCount(),
                generation,
                runtimeState.getTimeSliceRevision()
        );
        runtimeState.addBatchTasks(batchTasks);
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

    boolean connectDevice(String deviceId, long timeoutMs) {
        Future<?> connectFuture = null;
        try {
            connectFuture = deviceStartExecutor.submit(() -> {
                collectionManager.connectDevice(deviceId);
                configManager.getDataPointsAndAdaptiveConfig(deviceId);
            });
            connectFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException e) {
            log.error("连接设备被拒绝, 设备={}, 队列长度={}", deviceId, deviceStartExecutor.getQueue().size(), e);
            return false;
        } catch (TimeoutException e) {
            if (connectFuture != null) {
                connectFuture.cancel(true);
            }
            log.error("连接设备超时, 设备={}, 超时毫秒={}", deviceId, timeoutMs);
            return false;
        } catch (InterruptedException e) {
            if (connectFuture != null) {
                connectFuture.cancel(true);
            }
            Thread.currentThread().interrupt();
            log.error("连接设备被中断, 设备={}", deviceId, e);
            return false;
        } catch (Exception e) {
            log.error("连接设备失败, 设备={}", deviceId, e);
            return false;
        }
    }

    public boolean stopDevice(String deviceId) {
        return runtimeState.callExclusive(() -> {
            try {
                runtimeState.removeDeviceTasks(deviceId);
                deviceBatchExecutor.cancelDeviceInFlightTasks(deviceId);
                try {
                    collectionManager.disconnectDevice(deviceId);
                } catch (Exception e) {
                    log.warn("断开设备失败, 设备={}", deviceId, e);
                }
                runtimeState.removeDevice(deviceId);
                collectionTaskGuard.clearDevice(deviceId);
                reconnectCoordinator.clear(deviceId);
                collectionStatistics.stopCollection(deviceId);
                collectionServiceHealthTracker.markDeviceStopped(deviceId);
                return true;
            } catch (Exception e) {
                log.error("停止设备失败, 设备={}", deviceId, e);
                return false;
            }
        });
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
        List<String> runningDevices = new ArrayList<>(runtimeState.getRunningDevices());
        for (String deviceId : runningDevices) {
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

    void cleanupFailedStart(String deviceId) {
        try {
            collectionManager.cleanupDevice(deviceId);
        } catch (Exception e) {
            log.warn("启动失败后清理资源失败, 设备={}", deviceId, e);
        } finally {
            collectionTaskGuard.clearDevice(deviceId);
            runtimeState.removeDevice(deviceId);
            reconnectCoordinator.clear(deviceId);
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
}

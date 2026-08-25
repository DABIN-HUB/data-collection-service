package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.port.CollectionHealthReporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 幂等释放某个设备运行资源，保证单步失败不阻断后续清理。
 */
@Slf4j
@Component
public class DeviceLifecycleCleanup {

    private final CollectionManager collectionManager;
    private final CollectionStatistics collectionStatistics;
    private final CollectionHealthReporter collectionHealthReporter;
    private final PointRuntimeStateService pointRuntimeStateService;
    private final SchedulerRuntimeState runtimeState;
    private final DeviceBatchExecutor deviceBatchExecutor;
    private final ReconnectCoordinator reconnectCoordinator;
    private final CollectionTaskGuard collectionTaskGuard;

    public DeviceLifecycleCleanup(CollectionManager collectionManager,
                                  CollectionStatistics collectionStatistics,
                                  CollectionHealthReporter collectionHealthReporter,
                                  PointRuntimeStateService pointRuntimeStateService,
                                  SchedulerRuntimeState runtimeState,
                                  DeviceBatchExecutor deviceBatchExecutor,
                                  ReconnectCoordinator reconnectCoordinator,
                                  CollectionTaskGuard collectionTaskGuard) {
        this.collectionManager = collectionManager;
        this.collectionStatistics = collectionStatistics;
        this.collectionHealthReporter = collectionHealthReporter;
        this.pointRuntimeStateService = pointRuntimeStateService;
        this.runtimeState = runtimeState;
        this.deviceBatchExecutor = deviceBatchExecutor;
        this.reconnectCoordinator = reconnectCoordinator;
        this.collectionTaskGuard = collectionTaskGuard;
    }

    DeviceCleanupResult cleanupStoppedDevice(String deviceId, boolean wasRunning, boolean wasStarting) {
        DeviceCleanupResult result = DeviceCleanupResult.success();
        result = result.merge(cleanupStep(deviceId, "移除设备调度任务", true,
                () -> runtimeState.removeDeviceTasks(deviceId)));
        result = result.merge(cleanupStep(deviceId, "取消在途采集任务", true,
                () -> deviceBatchExecutor.cancelDeviceInFlightTasks(deviceId)));
        result = result.merge(cleanupStep(deviceId, "移除设备运行状态", true,
                () -> runtimeState.removeDevice(deviceId)));
        result = result.merge(cleanupStep(deviceId, "清理点位运行态", false,
                () -> pointRuntimeStateService.removeDevice(deviceId)));
        result = result.merge(cleanupStep(deviceId, "清理重连状态", false,
                () -> reconnectCoordinator.clear(deviceId)));
        result = result.merge(cleanupStep(deviceId, "停止采集统计", false,
                () -> collectionStatistics.stopCollection(deviceId)));
        result = result.merge(cleanupStep(deviceId, "标记设备停止", false,
                () -> collectionHealthReporter.markDeviceStopped(deviceId)));
        result = result.merge(disconnectOrCleanupDevice(deviceId, wasRunning, wasStarting));
        return result;
    }

    boolean cleanupFailedStart(String deviceId, long generation) {
        boolean clearedGeneration = collectionTaskGuard.clearDeviceIfCurrent(deviceId, generation);
        boolean removedRuntimeState = runtimeState.removeDeviceIfGeneration(deviceId, generation);
        cleanupStep(deviceId, "移除启动失败调度任务", () -> runtimeState.removeDeviceTasksIfGeneration(deviceId, generation));
        if (!clearedGeneration && !removedRuntimeState) {
            return false;
        }
        cleanupStep(deviceId, "清理启动失败点位运行态", () -> pointRuntimeStateService.removeDevice(deviceId));
        cleanupStep(deviceId, "清理启动失败重连状态", () -> reconnectCoordinator.clear(deviceId));
        cleanupStep(deviceId, "清理启动失败采集器", () -> collectionManager.cleanupDevice(deviceId));
        return true;
    }

    void discardStaleStart(String deviceId, long generation) {
        runtimeState.clearStartingIfGeneration(deviceId, generation);
        cleanupStep(deviceId, "移除旧代次启动调度任务", () -> runtimeState.removeDeviceTasksIfGeneration(deviceId, generation));
    }

    private DeviceCleanupResult disconnectOrCleanupDevice(String deviceId, boolean wasRunning, boolean wasStarting) {
        if (!wasRunning && !wasStarting) {
            return DeviceCleanupResult.success();
        }
        if (wasStarting && !wasRunning) {
            return cleanupStep(deviceId, "清理启动中采集器", true, () -> collectionManager.cleanupDevice(deviceId));
        }
        try {
            collectionManager.disconnectDevice(deviceId);
            return DeviceCleanupResult.success();
        } catch (Exception e) {
            log.warn("断开采集器失败，尝试清理采集器防止旧实例污染后续启动, 设备={}", deviceId, e);
            cleanupStep(deviceId, "断开失败后的采集器兜底清理", true, () -> collectionManager.cleanupDevice(deviceId));
            return new DeviceCleanupResult(false, true);
        }
    }

    private void cleanupStep(String deviceId, String action, CleanupStep cleanupStep) {
        cleanupStep(deviceId, action, false, cleanupStep);
    }

    private DeviceCleanupResult cleanupStep(String deviceId, String action, boolean critical, CleanupStep cleanupStep) {
        try {
            cleanupStep.run();
            return DeviceCleanupResult.success();
        } catch (Exception e) {
            if (critical) {
                log.error("设备生命周期关键清理步骤失败, 设备={}, 步骤={}", deviceId, action, e);
            } else {
                log.warn("设备生命周期非关键清理步骤失败, 设备={}, 步骤={}", deviceId, action, e);
            }
            return new DeviceCleanupResult(!critical, true);
        }
    }

    record DeviceCleanupResult(boolean criticalCleanupSucceeded, boolean hasWarnings) {

        private static DeviceCleanupResult success() {
            return new DeviceCleanupResult(true, false);
        }

        private DeviceCleanupResult merge(DeviceCleanupResult other) {
            return new DeviceCleanupResult(
                    criticalCleanupSucceeded && other.criticalCleanupSucceeded,
                    hasWarnings || other.hasWarnings);
        }
    }

    @FunctionalInterface
    private interface CleanupStep {
        void run() throws Exception;
    }
}

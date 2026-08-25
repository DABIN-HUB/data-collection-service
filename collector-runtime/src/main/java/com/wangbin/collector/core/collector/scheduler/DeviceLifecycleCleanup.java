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

    void cleanupStoppedDevice(String deviceId, boolean wasRunning, boolean wasStarting) {
        cleanupStep(deviceId, "移除设备调度任务", () -> runtimeState.removeDeviceTasks(deviceId));
        cleanupStep(deviceId, "取消在途采集任务", () -> deviceBatchExecutor.cancelDeviceInFlightTasks(deviceId));
        cleanupStep(deviceId, "移除设备运行状态", () -> runtimeState.removeDevice(deviceId));
        cleanupStep(deviceId, "清理点位运行态", () -> pointRuntimeStateService.removeDevice(deviceId));
        cleanupStep(deviceId, "清理重连状态", () -> reconnectCoordinator.clear(deviceId));
        cleanupStep(deviceId, "停止采集统计", () -> collectionStatistics.stopCollection(deviceId));
        cleanupStep(deviceId, "标记设备停止", () -> collectionHealthReporter.markDeviceStopped(deviceId));
        cleanupStep(deviceId, "断开或清理采集器", () -> disconnectOrCleanupDevice(deviceId, wasRunning, wasStarting));
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

    private void disconnectOrCleanupDevice(String deviceId, boolean wasRunning, boolean wasStarting) {
        if (wasStarting && !wasRunning) {
            collectionManager.cleanupDevice(deviceId);
            return;
        }
        collectionManager.disconnectDevice(deviceId);
    }

    private void cleanupStep(String deviceId, String action, CleanupStep cleanupStep) {
        try {
            cleanupStep.run();
        } catch (Exception e) {
            log.warn("设备生命周期清理步骤失败, 设备={}, 步骤={}", deviceId, action, e);
        }
    }

    @FunctionalInterface
    private interface CleanupStep {
        void run() throws Exception;
    }
}

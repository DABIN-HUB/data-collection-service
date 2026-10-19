package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.config.manager.ConfigManager;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 处理配置变更后的设备 debounce 重启。
 */
@Slf4j
@Component
public class ConfigRestartCoordinator {

    private static final long CONFIG_RESTART_DEBOUNCE_MS = 1000L;

    private final DeviceLifecycleCoordinator deviceLifecycleCoordinator;
    private final ConfigManager configManager;
    private final TimeSliceConfigCoordinator timeSliceConfigCoordinator;
    private final ScheduledExecutorService timeSliceScheduler;
    private final Map<String, ScheduledFuture<?>> pendingConfigRestartTasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingConfigStopTasks = new ConcurrentHashMap<>();
    private final Map<String, Runnable> pendingDeletionReleases = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ConfigRestartCoordinator(DeviceLifecycleCoordinator deviceLifecycleCoordinator,
                                    TimeSliceConfigCoordinator timeSliceConfigCoordinator,
                                    @Qualifier("timeSliceScheduler") ScheduledExecutorService timeSliceScheduler) {
        this(deviceLifecycleCoordinator, timeSliceConfigCoordinator, timeSliceScheduler, null);
    }

    @Autowired
    public ConfigRestartCoordinator(DeviceLifecycleCoordinator deviceLifecycleCoordinator,
                                    TimeSliceConfigCoordinator timeSliceConfigCoordinator,
                                    @Qualifier("timeSliceScheduler") ScheduledExecutorService timeSliceScheduler,
                                    ConfigManager configManager) {
        this.configManager = configManager;
        this.deviceLifecycleCoordinator = deviceLifecycleCoordinator;
        this.timeSliceConfigCoordinator = timeSliceConfigCoordinator;
        this.timeSliceScheduler = timeSliceScheduler;
        deviceLifecycleCoordinator.setConfigTaskCancellation(this::cancelPendingDevice);
        deviceLifecycleCoordinator.setConfigRestartPending(this::hasPendingRestart);
    }

    void handleConfigUpdate(ConfigUpdateEvent event) {
        if (closed.get()) {
            log.debug("配置重启协调器已关闭，忽略配置变更事件, 设备={}", event.getDeviceId());
            return;
        }
        String deviceId = event.getDeviceId();
        if (deviceId == null) {
            reloadChangedDevices();
            return;
        }
        boolean running = deviceLifecycleCoordinator.isDeviceRunning(deviceId);
        boolean starting = deviceLifecycleCoordinator.isDeviceStarting(deviceId);
        if ("local-delete".equals(event.getConfigType())) {
            if (configManager != null) {
                long version = event.getConfigVersion() == null ? configManager.getDeviceConfigVersion(deviceId) : event.getConfigVersion();
                Runnable release = configManager.retainDeletedConfigurationVersion(deviceId, version);
                if (release == null) return;
                try {
                    long revision = deviceLifecycleCoordinator.invalidateDeviceForDeletion(deviceId, version, configManager);
                    if (revision < 0L) {
                        release.run();
                        return;
                    }
                    scheduleStopDevice(deviceId, running, starting, revision, version, release);
                } catch (RuntimeException exception) {
                    release.run();
                    throw exception;
                }
            } else {
                cancelPendingDevice(deviceId);
                long revision = deviceLifecycleCoordinator.invalidateDeviceForDeletion(deviceId);
                scheduleStopDevice(deviceId, running, starting, revision, 0L, () -> {});
            }
            return;
        }
        long revision = deviceLifecycleCoordinator.getIntentRevision(deviceId);
        if (!deviceLifecycleCoordinator.isRunningIntent(deviceId, revision)
                || !deviceLifecycleCoordinator.isDeviceConfigurationChanged(deviceId)) return;
        if ((running || starting) && !deviceLifecycleCoordinator.invalidateDeviceForConfigChange(deviceId, revision)) return;
        scheduleRestart(deviceId, running, starting, revision);
    }

    /** 全量配置变更只处理配置版本有差异且用户期望运行的设备。 */
    void reloadChangedDevices() {
        if (closed.get()) return;
        for (String deviceId : deviceLifecycleCoordinator.getChangedConfigDeviceIds()) {
            ConfigUpdateEvent event = new ConfigUpdateEvent();
            event.setDeviceId(deviceId);
            event.setConfigType(deviceLifecycleCoordinator.hasDeviceConfiguration(deviceId) ? "all" : "local-delete");
            handleConfigUpdate(event);
        }
    }

    private void scheduleStopDevice(String deviceId, boolean wasRunning, boolean wasStarting, long revision, long configVersion, Runnable release) {
        if (closed.get()) {
            log.debug("配置重启协调器已关闭，拒绝调度删除停止任务, 设备={}", deviceId);
            release.run();
            return;
        }
        synchronized (lifecycleLock) {
            pendingConfigStopTasks.compute(deviceId, (key, oldTask) -> {
                if (closed.get()) {
                    cancelIfPending(oldTask);
                    releasePendingDeletion(deviceId);
                    release.run();
                    return null;
                }
                cancelIfPending(oldTask);
                releasePendingDeletion(deviceId);
                pendingDeletionReleases.put(deviceId, release);
                AtomicReference<ScheduledFuture<?>> selfReference = new AtomicReference<>();
                try {
                    ScheduledFuture<?> stopTask = timeSliceScheduler.schedule(
                            () -> stopDeviceAfterConfigDelete(deviceId, wasRunning, wasStarting, revision, configVersion, selfReference, release),
                            0L,
                            TimeUnit.MILLISECONDS);
                    selfReference.set(stopTask);
                    return stopTask.isDone() ? null : stopTask;
                } catch (Exception e) {
                    log.error("配置删除后调度停止设备失败, 设备={}", deviceId, e);
                    pendingDeletionReleases.remove(deviceId, release);
                    release.run();
                    return null;
                }
            });
        }
    }

    private void stopDeviceAfterConfigDelete(String deviceId,
                                             boolean wasRunning,
                                             boolean wasStarting,
                                             long revision,
                                             long configVersion,
                                             AtomicReference<ScheduledFuture<?>> selfReference,
                                             Runnable release) {
        try {
            if (!closed.get()) {
                if (configManager == null) deviceLifecycleCoordinator.stopDeletedDevice(deviceId, revision, wasRunning, wasStarting);
                else deviceLifecycleCoordinator.stopDeletedDevice(deviceId, revision, wasRunning, wasStarting, configVersion, configManager);
            }
        } catch (Exception e) {
            log.error("配置删除后停止设备失败, 设备={}", deviceId, e);
        } finally {
            pendingDeletionReleases.remove(deviceId, release);
            release.run();
            ScheduledFuture<?> self = selfReference.get();
            if (self != null) {
                pendingConfigStopTasks.remove(deviceId, self);
            }
        }
    }

    private void scheduleRestart(String deviceId,
                                 boolean wasRunningBeforeInvalidation,
                                 boolean wasStartingBeforeInvalidation,
                                 long revision) {
        if (closed.get() || !deviceLifecycleCoordinator.isRunningIntent(deviceId, revision)) {
            log.debug("配置重启协调器已关闭，拒绝调度重启任务, 设备={}", deviceId);
            return;
        }
        synchronized (lifecycleLock) {
            pendingConfigRestartTasks.compute(deviceId, (key, oldTask) -> {
                if (closed.get() || !deviceLifecycleCoordinator.isRunningIntent(deviceId, revision)) {
                    cancelIfPending(oldTask);
                    return null;
                }
                cancelIfPending(oldTask);
                AtomicReference<ScheduledFuture<?>> selfReference = new AtomicReference<>();
                ScheduledFuture<?> restartTask = timeSliceScheduler.schedule(
                        () -> restartDevice(
                                deviceId,
                                selfReference,
                                wasRunningBeforeInvalidation,
                                wasStartingBeforeInvalidation,
                                revision),
                        CONFIG_RESTART_DEBOUNCE_MS,
                        TimeUnit.MILLISECONDS);
                selfReference.set(restartTask);
                return restartTask.isDone() ? null : restartTask;
            });
        }
    }

    private void restartDevice(String deviceId,
                               AtomicReference<ScheduledFuture<?>> selfReference,
                               boolean wasRunningBeforeInvalidation,
                               boolean wasStartingBeforeInvalidation,
                               long revision) {
        try {
            if (!isRestartCurrent(deviceId, selfReference)) return;
            startDeviceIfOpen(deviceId, revision, wasRunningBeforeInvalidation, wasStartingBeforeInvalidation, selfReference);
        } catch (Exception e) {
            log.error("配置变更后重启设备失败, 设备={}", deviceId, e);
        } finally {
            ScheduledFuture<?> self = selfReference.get();
            if (self != null) {
                pendingConfigRestartTasks.remove(deviceId, self);
            }
        }
    }

    private void cancelPendingRestart(String deviceId) {
        synchronized (lifecycleLock) {
            pendingConfigRestartTasks.computeIfPresent(deviceId, (key, future) -> {
                cancelIfPending(future);
                return null;
            });
        }
    }

    private void startDeviceIfOpen(String deviceId, long revision, boolean wasRunning, boolean wasStarting,
                                   AtomicReference<ScheduledFuture<?>> selfReference) throws Exception {
        if (!isRestartCurrent(deviceId, selfReference)
                || !deviceLifecycleCoordinator.isRunningIntent(deviceId, revision)) return;
        // 全局协调器锁不包围设备停止、网络连接或设备生命周期锁。
        DeviceLifecycleCoordinator.StartReservation reservation = deviceLifecycleCoordinator.reserveStartForConfigRestart(
                deviceId, revision, wasRunning, wasStarting, () -> isRestartCurrent(deviceId, selfReference));
        if (reservation == null) return;
        beforeReservedStartContinuationForTest(deviceId, reservation);
        if (!isRestartCurrent(deviceId, selfReference)
                || !deviceLifecycleCoordinator.isRunningIntent(deviceId, revision)) return;
        if (deviceLifecycleCoordinator.continueReservedStart(reservation) && !closed.get()) {
            timeSliceConfigCoordinator.adjustTimeSlicesAfterWorkloadChange();
        }
    }

    private boolean isRestartCurrent(String deviceId, AtomicReference<ScheduledFuture<?>> selfReference) {
        ScheduledFuture<?> self = selfReference.get();
        return !closed.get() && self != null && !self.isCancelled() && pendingConfigRestartTasks.get(deviceId) == self;
    }

    void cancelPendingDevice(String deviceId) {
        cancelPendingRestart(deviceId);
        synchronized (lifecycleLock) {
            pendingConfigStopTasks.computeIfPresent(deviceId, (key, future) -> {
                cancelIfPending(future);
                return null;
            });
            releasePendingDeletion(deviceId);
        }
    }

    private void releasePendingDeletion(String deviceId) {
        Runnable release = pendingDeletionReleases.remove(deviceId);
        if (release != null) release.run();
    }

    /**
     * 测试专用钩子，用于稳定复现启动预留成功后、继续启动前的并发窗口。
     */
    void beforeReservedStartContinuationForTest(String deviceId, DeviceLifecycleCoordinator.StartReservation reservation) {
    }

    private void cancelIfPending(ScheduledFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    void cancelAll() {
        deviceLifecycleCoordinator.beginShutdown();
        synchronized (lifecycleLock) {
            closed.set(true);
            pendingConfigRestartTasks.values().forEach(this::cancelIfPending);
            pendingConfigRestartTasks.clear();
            pendingConfigStopTasks.values().forEach(this::cancelIfPending);
            pendingConfigStopTasks.clear();
            pendingDeletionReleases.values().forEach(Runnable::run);
            pendingDeletionReleases.clear();
        }
    }

    int pendingTaskCountForTest() {
        return pendingConfigRestartTasks.size();
    }

    private boolean hasPendingRestart(String deviceId) {
        ScheduledFuture<?> pending = pendingConfigRestartTasks.get(deviceId);
        return !closed.get() && pending != null && !pending.isCancelled() && !pending.isDone();
    }

    int pendingStopTaskCountForTest() {
        return pendingConfigStopTasks.size();
    }

}

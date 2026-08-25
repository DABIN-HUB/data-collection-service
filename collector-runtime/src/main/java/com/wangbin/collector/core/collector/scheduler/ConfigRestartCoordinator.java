package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
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
    private final TimeSliceConfigCoordinator timeSliceConfigCoordinator;
    private final ScheduledExecutorService timeSliceScheduler;
    private final Map<String, ScheduledFuture<?>> pendingConfigRestartTasks = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ConfigRestartCoordinator(DeviceLifecycleCoordinator deviceLifecycleCoordinator,
                                    TimeSliceConfigCoordinator timeSliceConfigCoordinator,
                                    @Qualifier("timeSliceScheduler") ScheduledExecutorService timeSliceScheduler) {
        this.deviceLifecycleCoordinator = deviceLifecycleCoordinator;
        this.timeSliceConfigCoordinator = timeSliceConfigCoordinator;
        this.timeSliceScheduler = timeSliceScheduler;
    }

    void handleConfigUpdate(ConfigUpdateEvent event) {
        if (closed.get()) {
            log.debug("配置重启协调器已关闭，忽略配置变更事件, 设备={}", event.getDeviceId());
            return;
        }
        String deviceId = event.getDeviceId();
        if (deviceId == null) {
            return;
        }
        boolean running = deviceLifecycleCoordinator.isDeviceRunning(deviceId);
        boolean starting = deviceLifecycleCoordinator.isDeviceStarting(deviceId);
        if ("local-delete".equals(event.getConfigType())) {
            cancelPendingRestart(deviceId);
            if (running || starting) {
                deviceLifecycleCoordinator.invalidateDeviceForConfigChange(deviceId);
                scheduleStopDevice(deviceId, running, starting);
            }
            return;
        }
        if (running) {
            scheduleRestart(deviceId, true);
            return;
        }
        if (starting) {
            deviceLifecycleCoordinator.invalidateDeviceForConfigChange(deviceId);
            scheduleRestart(deviceId, true, running, true);
        }
    }

    private void scheduleStopDevice(String deviceId, boolean wasRunning, boolean wasStarting) {
        try {
            timeSliceScheduler.schedule(
                    () -> stopDeviceAfterConfigDelete(deviceId, wasRunning, wasStarting),
                    0L,
                    TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("配置删除后调度停止设备失败, 设备={}", deviceId, e);
        }
    }

    private void stopDeviceAfterConfigDelete(String deviceId, boolean wasRunning, boolean wasStarting) {
        try {
            deviceLifecycleCoordinator.stopDeviceAfterConfigInvalidation(deviceId, wasRunning, wasStarting);
        } catch (Exception e) {
            log.error("配置删除后停止设备失败, 设备={}", deviceId, e);
        }
    }

    private void scheduleRestart(String deviceId, boolean stopBeforeStart) {
        scheduleRestart(deviceId, stopBeforeStart, false, false);
    }

    private void scheduleRestart(String deviceId,
                                 boolean stopBeforeStart,
                                 boolean wasRunningBeforeInvalidation,
                                 boolean wasStartingBeforeInvalidation) {
        if (closed.get()) {
            log.debug("配置重启协调器已关闭，拒绝调度重启任务, 设备={}", deviceId);
            return;
        }
        synchronized (lifecycleLock) {
            pendingConfigRestartTasks.compute(deviceId, (key, oldTask) -> {
                if (closed.get()) {
                    cancelIfPending(oldTask);
                    return null;
                }
                cancelIfPending(oldTask);
                AtomicReference<ScheduledFuture<?>> selfReference = new AtomicReference<>();
                ScheduledFuture<?> restartTask = timeSliceScheduler.schedule(
                        () -> restartDevice(
                                deviceId,
                                selfReference,
                                stopBeforeStart,
                                wasRunningBeforeInvalidation,
                                wasStartingBeforeInvalidation),
                        CONFIG_RESTART_DEBOUNCE_MS,
                        TimeUnit.MILLISECONDS);
                selfReference.set(restartTask);
                return restartTask;
            });
        }
    }

    private void restartDevice(String deviceId,
                               AtomicReference<ScheduledFuture<?>> selfReference,
                               boolean stopBeforeStart,
                               boolean wasRunningBeforeInvalidation,
                               boolean wasStartingBeforeInvalidation) {
        try {
            if (stopBeforeStart) {
                boolean stopped = stopDevice(
                        deviceId,
                        wasRunningBeforeInvalidation,
                        wasStartingBeforeInvalidation);
                if (!stopped) {
                    log.warn("配置变更后停止设备失败，跳过重新启动, 设备={}", deviceId);
                    return;
                }
            }
            startDeviceIfOpen(deviceId);
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

    private boolean stopDevice(String deviceId,
                               boolean wasRunningBeforeInvalidation,
                               boolean wasStartingBeforeInvalidation) {
        if (wasRunningBeforeInvalidation || wasStartingBeforeInvalidation) {
            return deviceLifecycleCoordinator.stopDeviceAfterConfigInvalidation(
                    deviceId,
                    wasRunningBeforeInvalidation,
                    wasStartingBeforeInvalidation);
        }
        return deviceLifecycleCoordinator.stopDevice(deviceId);
    }

    private void startDeviceIfOpen(String deviceId) {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                log.debug("配置重启协调器已关闭，跳过已运行重启任务的启动阶段, 设备={}", deviceId);
                return;
            }
            if (deviceLifecycleCoordinator.startDevice(deviceId)) {
                timeSliceConfigCoordinator.adjustTimeSlicesAfterWorkloadChange();
            }
        }
    }

    private void cancelIfPending(ScheduledFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    void cancelAll() {
        synchronized (lifecycleLock) {
            closed.set(true);
            pendingConfigRestartTasks.values().forEach(this::cancelIfPending);
            pendingConfigRestartTasks.clear();
        }
    }

    int pendingTaskCountForTest() {
        return pendingConfigRestartTasks.size();
    }
}

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

    public ConfigRestartCoordinator(DeviceLifecycleCoordinator deviceLifecycleCoordinator,
                                    TimeSliceConfigCoordinator timeSliceConfigCoordinator,
                                    @Qualifier("timeSliceScheduler") ScheduledExecutorService timeSliceScheduler) {
        this.deviceLifecycleCoordinator = deviceLifecycleCoordinator;
        this.timeSliceConfigCoordinator = timeSliceConfigCoordinator;
        this.timeSliceScheduler = timeSliceScheduler;
    }

    void handleConfigUpdate(ConfigUpdateEvent event) {
        String deviceId = event.getDeviceId();
        if (deviceId == null) {
            return;
        }
        boolean running = deviceLifecycleCoordinator.isDeviceRunning(deviceId);
        boolean starting = deviceLifecycleCoordinator.isDeviceStarting(deviceId);
        if ("local-delete".equals(event.getConfigType())) {
            cancelPendingRestart(deviceId);
            if (running || starting) {
                deviceLifecycleCoordinator.stopDevice(deviceId);
            }
            return;
        }
        if (running) {
            scheduleRestart(deviceId, true);
            return;
        }
        if (starting) {
            if (deviceLifecycleCoordinator.stopDevice(deviceId)) {
                scheduleRestart(deviceId, false);
            } else {
                log.warn("配置变更时停止启动中设备失败，跳过延迟重启, 设备={}", deviceId);
            }
        }
    }

    private void scheduleRestart(String deviceId, boolean stopBeforeStart) {
        pendingConfigRestartTasks.compute(deviceId, (key, oldTask) -> {
            cancelIfPending(oldTask);
            AtomicReference<ScheduledFuture<?>> selfReference = new AtomicReference<>();
            ScheduledFuture<?> restartTask = timeSliceScheduler.schedule(
                    () -> restartDevice(deviceId, selfReference, stopBeforeStart),
                    CONFIG_RESTART_DEBOUNCE_MS,
                    TimeUnit.MILLISECONDS);
            selfReference.set(restartTask);
            return restartTask;
        });
    }

    private void restartDevice(String deviceId,
                               AtomicReference<ScheduledFuture<?>> selfReference,
                               boolean stopBeforeStart) {
        try {
            if (stopBeforeStart) {
                deviceLifecycleCoordinator.stopDevice(deviceId);
            }
            if (deviceLifecycleCoordinator.startDevice(deviceId)) {
                timeSliceConfigCoordinator.adjustTimeSlicesAfterWorkloadChange();
            }
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
        pendingConfigRestartTasks.computeIfPresent(deviceId, (key, future) -> {
            cancelIfPending(future);
            return null;
        });
    }

    private void cancelIfPending(ScheduledFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    void cancelAll() {
        pendingConfigRestartTasks.values().forEach(this::cancelIfPending);
        pendingConfigRestartTasks.clear();
    }

    int pendingTaskCountForTest() {
        return pendingConfigRestartTasks.size();
    }
}

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
        if ("local-delete".equals(event.getConfigType())) {
            if (deviceId != null && deviceLifecycleCoordinator.isDeviceRunning(deviceId)) {
                deviceLifecycleCoordinator.stopDevice(deviceId);
            }
            return;
        }
        if (deviceId != null && deviceLifecycleCoordinator.isDeviceRunning(deviceId)) {
            scheduleRestart(deviceId);
        }
    }

    private void scheduleRestart(String deviceId) {
        ScheduledFuture<?> oldTask = pendingConfigRestartTasks.get(deviceId);
        if (oldTask != null && !oldTask.isDone()) {
            oldTask.cancel(false);
        }
        AtomicReference<ScheduledFuture<?>> selfReference = new AtomicReference<>();
        ScheduledFuture<?> restartTask = timeSliceScheduler.schedule(() -> {
            try {
                deviceLifecycleCoordinator.stopDevice(deviceId);
                if (deviceLifecycleCoordinator.startDevice(deviceId)) {
                    timeSliceConfigCoordinator.adjustTimeSlicesAfterWorkloadChange();
                }
            } catch (Exception e) {
                log.error("配置变更后重启设备失败, 设备={}", deviceId, e);
            } finally {
                ScheduledFuture<?> self = selfReference.get();
                if (self != null) {
                    pendingConfigRestartTasks.remove(deviceId, self);
                } else {
                    pendingConfigRestartTasks.remove(deviceId);
                }
            }
        }, CONFIG_RESTART_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        selfReference.set(restartTask);
        pendingConfigRestartTasks.put(deviceId, restartTask);
    }

    void cancelAll() {
        pendingConfigRestartTasks.values().forEach(future -> future.cancel(false));
        pendingConfigRestartTasks.clear();
    }

    int pendingTaskCountForTest() {
        return pendingConfigRestartTasks.size();
    }
}

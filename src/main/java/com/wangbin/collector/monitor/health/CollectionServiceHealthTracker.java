package com.wangbin.collector.monitor.health;

import com.wangbin.collector.monitor.health.HealthStatus.Status;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 跟踪采集服务运行状态，供健康检查判断采集链路是否正在运行。
 */
@Component
public class CollectionServiceHealthTracker {

    private final Set<String> runningDevices = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Status> currentStatus = new AtomicReference<>(Status.DOWN);
    private final AtomicLong lastStateChange = new AtomicLong(System.currentTimeMillis());

    /**
     * 标记设备已开始采集。至少存在一个运行设备时，采集服务状态为正常。
     */
    public void markDeviceStarted(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        boolean added = runningDevices.add(deviceId);
        if (added || currentStatus.get() != Status.UP) {
            updateStatus(Status.UP);
        }
    }

    /**
     * 标记设备已停止采集。没有运行设备时，采集服务状态为停止。
     */
    public void markDeviceStopped(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        runningDevices.remove(deviceId);
        if (runningDevices.isEmpty()) {
            updateStatus(Status.DOWN);
        }
    }

    /**
     * 将跟踪器重置为完全停止状态。
     */
    public void reset() {
        runningDevices.clear();
        updateStatus(Status.DOWN);
    }

    public Status getCurrentStatus() {
        return currentStatus.get();
    }

    public int getRunningDeviceCount() {
        return runningDevices.size();
    }

    public Set<String> getRunningDevicesSnapshot() {
        return Collections.unmodifiableSet(new HashSet<>(runningDevices));
    }

    public long getLastStateChange() {
        return lastStateChange.get();
    }

    private void updateStatus(Status newStatus) {
        Status previous = currentStatus.getAndSet(newStatus);
        if (previous != newStatus) {
            lastStateChange.set(System.currentTimeMillis());
        }
    }
}

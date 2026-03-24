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
 * Tracks the runtime state of the collection service so that the health endpoint
 * can expose whether the overall collection workflow is running or stopped.
 */
@Component
public class CollectionServiceHealthTracker {

    private final Set<String> runningDevices = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Status> currentStatus = new AtomicReference<>(Status.DOWN);
    private final AtomicLong lastStateChange = new AtomicLong(System.currentTimeMillis());

    /**
     * Mark a device as actively collecting. The collection service is considered UP
     * as soon as at least one device is running.
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
     * Mark a device as stopped. When no devices are running the service transitions
     * to DOWN.
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
     * Resets the tracker to a fully stopped state.
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

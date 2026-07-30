package com.wangbin.collector.core.collector;

import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.manager.ConfigManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;

/**
 * Unified collection service facade.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionService {

    private final CollectionScheduler collectionScheduler;
    private final CollectionStatistics collectionStatistics;
    private final ConfigManager configManager;

    public boolean startDevice(String deviceId) {
        boolean prepared = configManager.refreshDeviceConfig(deviceId);
        if (!prepared) {
            log.warn("Device {} config reload failed, skip start", deviceId);
            return false;
        }
        return collectionScheduler.startDevice(deviceId);
    }

    /**
     * Start a local temporary device from the in-memory config cache.
     * This intentionally bypasses remote refresh to avoid deleting local-only configs.
     */
    public boolean startLocalDevice(String deviceId) {
        if (!configManager.isLocalTemporaryDevice(deviceId)) {
            log.warn("Device {} is not a local temporary device, skip local start", deviceId);
            return false;
        }
        return collectionScheduler.startDevice(deviceId);
    }

    public boolean stopDevice(String deviceId) {
        return collectionScheduler.stopDevice(deviceId);
    }

    public void reloadAllDevices() {
        collectionScheduler.reloadAllDevices();
    }

    public Map<String, Object> getDeviceStatus(String deviceId) {
        return collectionScheduler.getDeviceScheduleStatus(deviceId);
    }

    public Map<String, Map<String, Object>> getAllStatistics() {
        return collectionStatistics.getAllStatistics();
    }

    public List<String> getRunningDevices() {
        return collectionScheduler.getRunningDevices();
    }

    public boolean isDeviceRunning(String deviceId) {
        return collectionScheduler.isDeviceRunning(deviceId);
    }

    public List<DeviceRuntimeSnapshot> getDeviceRuntimeSnapshots() {
        return collectionScheduler.getDeviceRuntimeSnapshots();
    }
}

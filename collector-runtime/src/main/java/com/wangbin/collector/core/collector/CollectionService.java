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
 * 统一采集服务门面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionService {

    private final CollectionScheduler collectionScheduler;
    private final CollectionStatistics collectionStatistics;
    private final ConfigManager configManager;

    /**
     * 处理组件生命周期。
     */
    public boolean startDevice(String deviceId) {
        if (configManager.isLocalTemporaryDevice(deviceId)) {
            return collectionScheduler.startDevice(deviceId);
        }
        boolean prepared = configManager.refreshDeviceConfig(deviceId);
        if (!prepared) {
            log.warn("设备 {} 配置重载失败，跳过启动", deviceId);
            return false;
        }
        return collectionScheduler.startDevice(deviceId);
    }

    /**
     * 从内存配置缓存启动本地临时设备。
     * 该流程会避开远端刷新，避免删除仅存在于本地的配置。
     */
    public boolean startLocalDevice(String deviceId) {
        if (!configManager.isLocalTemporaryDevice(deviceId)) {
            log.warn("设备 {} 不是本地临时设备，跳过本地启动", deviceId);
            return false;
        }
        return collectionScheduler.startDevice(deviceId);
    }

    /**
     * 处理组件生命周期。
     */
    public boolean stopDevice(String deviceId) {
        return collectionScheduler.stopDevice(deviceId);
    }

    /**
     * 更新或刷新业务状态。
     */
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

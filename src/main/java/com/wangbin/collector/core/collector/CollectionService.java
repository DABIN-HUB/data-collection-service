package com.wangbin.collector.core.collector;

import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.manager.ConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 采集服务 - 对外提供统一的采集接口
 */
@Slf4j
@Service
public class CollectionService {

    @Autowired
    private CollectionScheduler collectionScheduler;

    @Autowired
    private CollectionStatistics collectionStatistics;

    @Autowired
    private ConfigManager configManager;

    /**
     * 启动设备采集
     */
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

    /**
     * 停止设备采集
     */
    public boolean stopDevice(String deviceId) {
        return collectionScheduler.stopDevice(deviceId);
    }

    /**
     * 重新加载所有设�?
     */
    public void reloadAllDevices() {
        collectionScheduler.reloadAllDevices();
    }

    /**
     * 获取设备状�?
     */
    public Map<String, Object> getDeviceStatus(String deviceId) {
        return collectionScheduler.getDeviceScheduleStatus(deviceId);
    }

    /**
     * 获取所有设备统�?
     */
    public Map<String, Map<String, Object>> getAllStatistics() {
        return collectionStatistics.getAllStatistics();
    }

    /**
     * 获取运行中的设备列表
     */
    public List<String> getRunningDevices() {
        return collectionScheduler.getRunningDevices();
    }

    /**
     * 检查设备是否在运行
     */
    public boolean isDeviceRunning(String deviceId) {
        return collectionScheduler.isDeviceRunning(deviceId);
    }
}

package com.wangbin.collector.core.config.manager;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.loader.ConfigLoader;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Coordinates config sync and delegates actual loading to ConfigLoader implementations.
 */
@Slf4j
@Service
public class ConfigSyncService {

    @Value("${collector.config.yun-url:http://localhost:8080/admin-api}")
    private String runUrl;

    @Value("${collector.config.sync-interval:30000}")
    private long syncInterval;

    @Value("${collector.config.service-id:collector-1}")
    private String serviceId;

    private final ConfigLoader configLoader;
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    private final List<Consumer<ConfigUpdateEvent>> configListeners = new ArrayList<>();
    private final Map<String, DeviceInfo> deviceConfigs = new ConcurrentHashMap<>();
    private final Map<String, List<DataPoint>> pointConfigs = new ConcurrentHashMap<>();
    private final Map<String, DeviceConnection> connectionConfigs = new ConcurrentHashMap<>();

    private volatile long lastSyncTime;

    public ConfigSyncService(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @PostConstruct
    public void init() {
        log.info("配置同步服务初始化，serviceId={}, source={}", serviceId, configLoader.getClass().getSimpleName());
    }

    public void startSyncTask() {
        try {
            syncAllConfig();
            log.info("首次配置同步完成");
        } catch (Exception e) {
            log.error("首次配置同步失败", e);
        }
        log.info("配置同步任务已启动，同步间隔: {}ms, source={}", syncInterval, runUrl);
    }

    @Scheduled(fixedDelayString = "${collector.config.sync-interval:30000}",
            initialDelayString = "${collector.config.sync-initial-delay:30000}")
    public void scheduledSync() {
        syncAllConfig();
    }

    public void registerConfigListener(Consumer<ConfigUpdateEvent> listener) {
        if (listener != null) {
            configListeners.add(listener);
            log.debug("注册配置监听器成功，当前监听器数量={}", configListeners.size());
        }
    }

    public void unregisterConfigListener(Consumer<ConfigUpdateEvent> listener) {
        if (listener != null) {
            configListeners.remove(listener);
            log.debug("注销配置监听器成功，当前监听器数量={}", configListeners.size());
        }
    }

    public void syncAllConfig() {
        if (!syncing.compareAndSet(false, true)) {
            log.debug("配置同步正在执行，跳过本次触发");
            return;
        }
        try {
            log.debug("开始执行配置同步");
            notifyConfigUpdate("all", null);
            log.info("配置同步完成");
        } catch (Exception e) {
            log.error("配置定时同步失败", e);
        } finally {
            syncing.set(false);
        }
    }

    public List<DeviceInfo> loadAllDevices() {
        List<DeviceInfo> devices = configLoader.loadAllDevices();
        deviceConfigs.clear();
        for (DeviceInfo device : devices) {
            if (device != null && device.getDeviceId() != null) {
                deviceConfigs.put(device.getDeviceId(), device);
            }
        }
        log.info("成功加载 {} 个设备配置", devices.size());
        return devices;
    }

    public DeviceInfo loadDevice(String deviceId) {
        DeviceInfo device = configLoader.loadDevice(deviceId);
        if (device != null) {
            deviceConfigs.put(deviceId, device);
            log.debug("成功加载设备配置: {}", deviceId);
        }
        return device;
    }

    public List<DataPoint> loadDataPoints(String deviceId) {
        List<DataPoint> points = configLoader.loadDataPoints(deviceId);
        pointConfigs.put(deviceId, points);
        log.debug("成功加载设备 {} 的数据点配置，共 {} 个点", deviceId, points.size());
        return points;
    }

    public DeviceConnection loadConnectionConfig(String deviceId) {
        DeviceConnection connection = configLoader.loadConnectionConfig(deviceId);
        if (connection != null) {
            connectionConfigs.put(deviceId, connection);
            log.debug("成功加载连接配置: {}", deviceId);
        } else {
            connectionConfigs.remove(deviceId);
        }
        return connection;
    }

    public void notifyConfigUpdate(String configType, String deviceId) {
        log.info("开始同步配置类型: {}", configType);
        ConfigUpdateEvent event = ConfigUpdateEvent.builder()
                .configType(configType)
                .deviceId(deviceId)
                .updateTime(new Date())
                .build();
        notifyConfigListeners(event);
        lastSyncTime = System.currentTimeMillis();
        log.info("配置类型 {} 同步完成", configType);
    }

    private void notifyConfigListeners(ConfigUpdateEvent event) {
        if (configListeners.isEmpty()) {
            log.debug("没有配置监听器需要通知");
            return;
        }

        int successCount = 0;
        int failCount = 0;
        for (Consumer<ConfigUpdateEvent> listener : configListeners) {
            try {
                listener.accept(event);
                successCount++;
            } catch (Exception e) {
                log.error("配置监听器执行失败", e);
                failCount++;
            }
        }
        log.debug("配置变更通知完成，成功={}, 失败={}", successCount, failCount);
    }

    public Map<String, DeviceInfo> getDeviceConfigs() {
        return Collections.unmodifiableMap(deviceConfigs);
    }

    public Map<String, List<DataPoint>> getPointConfigs() {
        return Collections.unmodifiableMap(pointConfigs);
    }

    public Map<String, DeviceConnection> getConnectionConfigs() {
        return Collections.unmodifiableMap(connectionConfigs);
    }

    public long getLastSyncTime() {
        return lastSyncTime;
    }

    public long getSyncInterval() {
        return syncInterval;
    }

    public String getServiceId() {
        return serviceId;
    }

    public int getListenerCount() {
        return configListeners.size();
    }

    public void clearCache() {
        deviceConfigs.clear();
        pointConfigs.clear();
        connectionConfigs.clear();
        log.info("配置缓存已清空");
    }

    public void triggerManualSync() {
        log.info("手动触发配置同步");
        Thread thread = new Thread(() -> {
            try {
                syncAllConfig();
            } catch (Exception e) {
                log.error("手动配置同步失败", e);
            }
        }, "manual-sync-thread");
        thread.setDaemon(true);
        thread.start();
    }
}

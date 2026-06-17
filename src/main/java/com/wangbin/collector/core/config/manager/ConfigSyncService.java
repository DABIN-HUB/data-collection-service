package com.wangbin.collector.core.config.manager;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.loader.ConfigLoader;
import com.wangbin.collector.core.config.model.ConfigDiff;
import com.wangbin.collector.core.config.model.ConfigSnapshot;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
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
    private final Executor syncExecutor;
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final Object cacheLock = new Object();

    private final List<Consumer<ConfigUpdateEvent>> configListeners = new CopyOnWriteArrayList<>();
    private final Map<String, DeviceInfo> deviceConfigs = new ConcurrentHashMap<>();
    private final Map<String, List<DataPoint>> pointConfigs = new ConcurrentHashMap<>();
    private final Map<String, DeviceConnection> connectionConfigs = new ConcurrentHashMap<>();

    private volatile long lastSyncTime;

    public ConfigSyncService(ConfigLoader configLoader,
                             @Qualifier("ioIntensiveExecutor") Executor syncExecutor) {
        this.configLoader = configLoader;
        this.syncExecutor = syncExecutor;
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
            ConfigSnapshot previousSnapshot = snapshotCurrentConfig();
            ConfigSnapshot latestSnapshot = configLoader.loadSnapshot();
            applySnapshot(latestSnapshot);

            ConfigDiff diff = ConfigDiff.between(previousSnapshot, latestSnapshot);
            lastSyncTime = System.currentTimeMillis();
            if (!diff.hasChanges()) {
                log.debug("配置同步完成，未检测到增量变更");
                return;
            }

            publishIncrementalEvents(diff, previousSnapshot, latestSnapshot);
            log.info("配置同步完成: added={}, removed={}, deviceChanged={}, pointChanged={}, connectionChanged={}",
                    diff.addedDevices().size(),
                    diff.removedDevices().size(),
                    diff.changedDevices().size(),
                    diff.changedPoints().size(),
                    diff.changedConnections().size());
        } catch (Exception e) {
            log.error("配置定时同步失败", e);
        } finally {
            syncing.set(false);
        }
    }

    public List<DeviceInfo> loadAllDevices() {
        List<DeviceInfo> devices = sanitizeDevices(configLoader.loadAllDevices());
        synchronized (cacheLock) {
            deviceConfigs.clear();
            Set<String> activeDeviceIds = new LinkedHashSet<>();
            for (DeviceInfo device : devices) {
                deviceConfigs.put(device.getDeviceId(), device);
                activeDeviceIds.add(device.getDeviceId());
            }
            pruneStaleDeviceState(activeDeviceIds);
        }
        log.info("成功加载 {} 个设备配置", devices.size());
        return devices;
    }

    public DeviceInfo loadDevice(String deviceId) {
        DeviceInfo device = configLoader.loadDevice(deviceId);
        synchronized (cacheLock) {
            if (device != null) {
                deviceConfigs.put(deviceId, device);
                log.debug("成功加载设备配置: {}", deviceId);
            } else {
                removeDeviceState(deviceId);
            }
        }
        return device;
    }

    public List<DataPoint> loadDataPoints(String deviceId) {
        List<DataPoint> points = sanitizePoints(configLoader.loadDataPoints(deviceId));
        synchronized (cacheLock) {
            pointConfigs.put(deviceId, points);
        }
        log.debug("成功加载设备 {} 的数据点配置，共 {} 个点", deviceId, points.size());
        return points;
    }

    public DeviceConnection loadConnectionConfig(String deviceId) {
        DeviceConnection connection = configLoader.loadConnectionConfig(deviceId);
        synchronized (cacheLock) {
            if (connection != null) {
                connectionConfigs.put(deviceId, connection);
                log.debug("成功加载连接配置: {}", deviceId);
            } else {
                connectionConfigs.remove(deviceId);
            }
        }
        return connection;
    }

    public void notifyConfigUpdate(String configType, String deviceId) {
        log.info("开始同步配置类型 {}", configType);
        publishConfigEvent(createManualEvent(configType, deviceId));
        lastSyncTime = System.currentTimeMillis();
        log.info("配置类型 {} 同步完成", configType);
    }

    private void publishConfigEvent(ConfigUpdateEvent event) {
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
        return snapshotCurrentConfig().devices();
    }

    public Map<String, List<DataPoint>> getPointConfigs() {
        return snapshotCurrentConfig().points();
    }

    public Map<String, DeviceConnection> getConnectionConfigs() {
        return snapshotCurrentConfig().connections();
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
        synchronized (cacheLock) {
            deviceConfigs.clear();
            pointConfigs.clear();
            connectionConfigs.clear();
        }
        log.info("配置缓存已清空");
    }

    public void triggerManualSync() {
        log.info("手动触发配置同步");
        try {
            syncExecutor.execute(this::safeSyncAllConfig);
        } catch (RejectedExecutionException e) {
            log.warn("手动配置同步任务提交失败，回退当前线程执行", e);
            safeSyncAllConfig();
        }
    }

    private void safeSyncAllConfig() {
        try {
            syncAllConfig();
        } catch (Exception e) {
            log.error("手动配置同步失败", e);
        }
    }

    private void publishIncrementalEvents(ConfigDiff diff,
                                          ConfigSnapshot previousSnapshot,
                                          ConfigSnapshot latestSnapshot) {
        for (String deviceId : diff.deviceEventIds()) {
            boolean connectionChanged = diff.changedConnections().contains(deviceId);
            publishConfigEvent(createDeviceEvent(deviceId, connectionChanged));
        }

        Set<String> connectionEvents = new LinkedHashSet<>(diff.changedConnections());
        connectionEvents.removeAll(diff.removedDevices());
        for (String deviceId : connectionEvents) {
            publishConfigEvent(createConnectionEvent(deviceId));
        }

        Set<String> pointEvents = new LinkedHashSet<>(diff.changedPoints());
        pointEvents.removeAll(diff.removedDevices());
        for (String deviceId : pointEvents) {
            int pointCountChange = latestSnapshot.points(deviceId).size() - previousSnapshot.points(deviceId).size();
            publishConfigEvent(createPointsEvent(deviceId, pointCountChange));
        }
    }

    private ConfigSnapshot snapshotCurrentConfig() {
        synchronized (cacheLock) {
            return new ConfigSnapshot(deviceConfigs, pointConfigs, connectionConfigs);
        }
    }

    private void applySnapshot(ConfigSnapshot snapshot) {
        synchronized (cacheLock) {
            deviceConfigs.clear();
            deviceConfigs.putAll(snapshot.devices());
            pointConfigs.clear();
            pointConfigs.putAll(snapshot.points());
            connectionConfigs.clear();
            connectionConfigs.putAll(snapshot.connections());
        }
    }

    private void pruneStaleDeviceState(Set<String> activeDeviceIds) {
        List<String> stalePointDeviceIds = new ArrayList<>();
        for (String deviceId : pointConfigs.keySet()) {
            if (!activeDeviceIds.contains(deviceId)) {
                stalePointDeviceIds.add(deviceId);
            }
        }
        for (String deviceId : stalePointDeviceIds) {
            pointConfigs.remove(deviceId);
        }

        List<String> staleConnectionDeviceIds = new ArrayList<>();
        for (String deviceId : connectionConfigs.keySet()) {
            if (!activeDeviceIds.contains(deviceId)) {
                staleConnectionDeviceIds.add(deviceId);
            }
        }
        for (String deviceId : staleConnectionDeviceIds) {
            connectionConfigs.remove(deviceId);
        }
    }

    private void removeDeviceState(String deviceId) {
        if (!hasText(deviceId)) {
            return;
        }
        deviceConfigs.remove(deviceId);
        pointConfigs.remove(deviceId);
        connectionConfigs.remove(deviceId);
    }

    private List<DeviceInfo> sanitizeDevices(List<DeviceInfo> devices) {
        if (devices == null || devices.isEmpty()) {
            return Collections.emptyList();
        }
        List<DeviceInfo> safeDevices = new ArrayList<>();
        for (DeviceInfo device : devices) {
            if (device != null && hasText(device.getDeviceId())) {
                safeDevices.add(device);
            }
        }
        return Collections.unmodifiableList(safeDevices);
    }

    private List<DataPoint> sanitizePoints(List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }
        List<DataPoint> safePoints = new ArrayList<>();
        for (DataPoint point : points) {
            if (point != null) {
                safePoints.add(point);
            }
        }
        return Collections.unmodifiableList(safePoints);
    }

    private ConfigUpdateEvent createManualEvent(String configType, String deviceId) {
        ConfigUpdateEvent event;
        switch (configType) {
            case "device" -> event = createDeviceEvent(deviceId, false);
            case "points" -> event = createPointsEvent(deviceId, 0);
            case "connection" -> event = createConnectionEvent(deviceId);
            case "collection" -> event = ConfigUpdateEvent.createCollectionUpdateEvent(deviceId);
            case "all" -> event = ConfigUpdateEvent.createAllUpdateEvent();
            default -> event = ConfigUpdateEvent.builder()
                    .configType(configType)
                    .deviceId(deviceId)
                    .createTime(new Date())
                    .status("pending")
                    .build();
        }
        event.setSource("manual");
        event.setUpdateTime(new Date());
        return event;
    }

    private ConfigUpdateEvent createDeviceEvent(String deviceId, boolean connectionChanged) {
        ConfigUpdateEvent event = ConfigUpdateEvent.createDeviceUpdateEvent(deviceId, connectionChanged);
        event.setSource("config-sync");
        event.setUpdateTime(new Date());
        return event;
    }

    private ConfigUpdateEvent createPointsEvent(String deviceId, int pointCountChange) {
        ConfigUpdateEvent event = ConfigUpdateEvent.createPointsUpdateEvent(deviceId, pointCountChange);
        event.setSource("config-sync");
        event.setUpdateTime(new Date());
        return event;
    }

    private ConfigUpdateEvent createConnectionEvent(String deviceId) {
        ConfigUpdateEvent event = ConfigUpdateEvent.createConnectionUpdateEvent(deviceId);
        event.setSource("config-sync");
        event.setUpdateTime(new Date());
        return event;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

package com.wangbin.collector.core.config.manager;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.loader.ConfigLoader;
import com.wangbin.collector.core.config.model.ConfigDiff;
import com.wangbin.collector.core.config.model.ConfigLoadResult;
import com.wangbin.collector.core.config.model.ConfigLoadStatus;
import com.wangbin.collector.core.config.model.ConfigSnapshot;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.config.model.ConfigUpdateType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 协调配置同步流程，并委托具体 ConfigLoader 执行配置加载。
 */
@Slf4j
@Service
public class ConfigSyncService {

    private final CollectorProperties.ConfigConfig configProperties;
    private final ConfigLoader configLoader;
    private final Executor syncExecutor;
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final Object cacheLock = new Object();

    private final List<Consumer<ConfigUpdateEvent>> configListeners = new CopyOnWriteArrayList<>();
    private final Map<String, DeviceInfo> deviceConfigs = new ConcurrentHashMap<>();
    private final Map<String, List<DataPoint>> pointConfigs = new ConcurrentHashMap<>();
    private final Map<String, DeviceConnection> connectionConfigs = new ConcurrentHashMap<>();

    private volatile long lastSyncTime;
    private volatile long lastFailureTime;
    private volatile String sourceVersion;

    /**
     * 创建当前组件实例。
     */
    public ConfigSyncService(ConfigLoader configLoader,
                             @Qualifier("ioIntensiveExecutor") Executor syncExecutor,
                             CollectorProperties collectorProperties) {
        this.configLoader = configLoader;
        this.syncExecutor = syncExecutor;
        this.configProperties = collectorProperties.getConfig();
    }

    /**
     * 处理组件生命周期。
     */
    @PostConstruct
    public void init() {
        log.info("配置同步服务初始化，服务={}，来源={}", configProperties.getServiceId(), configLoader.getClass().getSimpleName());
    }

    /**
     * 处理组件生命周期。
     */
    public void startSyncTask() {
        try {
            syncAllConfig();
            log.info("首次配置同步完成");
        } catch (Exception e) {
            log.error("首次配置同步失败", e);
        }
        log.info("配置同步任务已启动，同步间隔毫秒={}，来源={}", configProperties.getSyncInterval(), configProperties.getYunUrl());
    }

    @Scheduled(fixedDelayString = "${collector.config.sync-interval:30000}",
            initialDelayString = "${collector.config.sync-initial-delay:30000}")
    /**
     * 处理当前业务流程。
     */
    public void scheduledSync() {
        syncAllConfig();
    }

    /**
     * 维护注册或订阅关系。
     */
    public void registerConfigListener(Consumer<ConfigUpdateEvent> listener) {
        if (listener != null) {
            configListeners.add(listener);
            log.debug("注册配置监听器成功，当前监听器数量={}", configListeners.size());
        }
    }

    /**
     * 维护注册或订阅关系。
     */
    public void unregisterConfigListener(Consumer<ConfigUpdateEvent> listener) {
        if (listener != null) {
            configListeners.remove(listener);
            log.debug("注销配置监听器成功，当前监听器数量={}", configListeners.size());
        }
    }

    /**
     * 更新或刷新业务状态。
     */
    public void syncAllConfig() {
        if (!syncing.compareAndSet(false, true)) {
            log.debug("配置同步正在执行，跳过本次触发");
            return;
        }
        try {
            log.debug("开始执行配置同步");
            ConfigSnapshot previousSnapshot = snapshotCurrentConfig();
            ConfigLoadResult loadResult = configLoader.loadSnapshotResult();
            if (loadResult == null || loadResult.status() == ConfigLoadStatus.FAILED) {
                recordSyncFailure(loadResult == null ? "配置加载结果为空" : loadResult.errorMessage());
                return;
            }
            if (loadResult.status() == ConfigLoadStatus.NOT_MODIFIED) {
                recordSyncSuccess();
                log.debug("配置同步完成，远端配置未发生变化");
                return;
            }
            ConfigSnapshot latestSnapshot = loadResult.snapshot();
            if (latestSnapshot == null) {
                recordSyncFailure("配置加载成功但快照为空");
                return;
            }
            applySnapshot(latestSnapshot);
            sourceVersion = loadResult.sourceVersion();

            ConfigDiff diff = ConfigDiff.between(previousSnapshot, latestSnapshot);
            recordSyncSuccess();
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
            recordSyncFailure(e.getMessage());
            log.error("配置定时同步失败", e);
        } finally {
            syncing.set(false);
        }
    }

    /**
     * 查询并返回业务数据。
     */
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

    /**
     * 查询并返回业务数据。
     */
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

    /**
     * 查询并返回业务数据。
     */
    public List<DataPoint> loadDataPoints(String deviceId) {
        List<DataPoint> points = sanitizePoints(configLoader.loadDataPoints(deviceId));
        synchronized (cacheLock) {
            pointConfigs.put(deviceId, points);
        }
        log.debug("成功加载设备 {} 的数据点配置，共 {} 个点", deviceId, points.size());
        return points;
    }

    /**
     * 查询并返回业务数据。
     */
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

    /**
     * 执行当前业务逻辑。
     */
    public void notifyConfigUpdate(String configType, String deviceId) {
        log.info("开始同步配置类型 {}", configType);
        publishConfigEvent(createManualEvent(configType, deviceId));
        lastSyncTime = System.currentTimeMillis();
        log.info("配置类型 {} 同步完成", configType);
    }

    /**
     * 执行当前业务逻辑。
     */
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

    public long getLastFailureTime() {
        return lastFailureTime;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public int getSnapshotDeviceCount() {
        synchronized (cacheLock) {
            return deviceConfigs.size();
        }
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public long getSyncInterval() {
        return configProperties.getSyncInterval();
    }

    public String getServiceId() {
        return configProperties.getServiceId();
    }

    public int getListenerCount() {
        return configListeners.size();
    }

    /**
     * 清理或删除业务数据。
     */
    public void clearCache() {
        synchronized (cacheLock) {
            deviceConfigs.clear();
            pointConfigs.clear();
            connectionConfigs.clear();
        }
        log.info("配置缓存已清空");
    }

    /**
     * 更新或刷新业务状态。
     */
    public void triggerManualSync() {
        log.info("手动触发配置同步");
        try {
            syncExecutor.execute(this::safeSyncAllConfig);
        } catch (RejectedExecutionException e) {
            log.warn("手动配置同步任务提交失败，回退当前线程执行", e);
            safeSyncAllConfig();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void safeSyncAllConfig() {
        try {
            syncAllConfig();
        } catch (Exception e) {
            log.error("手动配置同步失败", e);
        }
    }

    /**
     * 记录或统计业务状态。
     */
    private void recordSyncSuccess() {
        lastSyncTime = System.currentTimeMillis();
        consecutiveFailures.set(0);
    }

    /**
     * 记录或统计业务状态。
     */
    private void recordSyncFailure(String errorMessage) {
        lastFailureTime = System.currentTimeMillis();
        int failureCount = consecutiveFailures.incrementAndGet();
        log.warn("配置同步失败，保留最后有效快照，连续失败次数={}，原因={}",
                failureCount, errorMessage);
    }

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 查询并返回业务数据。
     */
    private ConfigSnapshot snapshotCurrentConfig() {
        synchronized (cacheLock) {
            return new ConfigSnapshot(deviceConfigs, pointConfigs, connectionConfigs);
        }
    }

    /**
     * 处理当前业务流程。
     */
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

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 清理或删除业务数据。
     */
    private void removeDeviceState(String deviceId) {
        if (!hasText(deviceId)) {
            return;
        }
        deviceConfigs.remove(deviceId);
        pointConfigs.remove(deviceId);
        connectionConfigs.remove(deviceId);
    }

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 创建并返回业务对象。
     */
    private ConfigUpdateEvent createManualEvent(String configType, String deviceId) {
        ConfigUpdateType updateType = ConfigUpdateType.fromValue(configType).orElse(null);
        ConfigUpdateEvent event;
        if (updateType == null) {
            event = ConfigUpdateEvent.builder()
                    .configType(configType)
                    .deviceId(deviceId)
                    .createTime(new Date())
                    .status("pending")
                    .build();
        } else {
            event = switch (updateType) {
                case DEVICE -> createDeviceEvent(deviceId, false);
                case POINTS -> createPointsEvent(deviceId, 0);
                case CONNECTION -> createConnectionEvent(deviceId);
                case COLLECTION -> ConfigUpdateEvent.createCollectionUpdateEvent(deviceId);
                case ALL -> ConfigUpdateEvent.createAllUpdateEvent();
                default -> ConfigUpdateEvent.builder()
                        .configType(updateType.getValue())
                        .deviceId(deviceId)
                        .createTime(new Date())
                        .status("pending")
                        .build();
            };
        }
        event.setSource("manual");
        event.setUpdateTime(new Date());
        return event;
    }

    /**
     * 创建并返回业务对象。
     */
    private ConfigUpdateEvent createDeviceEvent(String deviceId, boolean connectionChanged) {
        ConfigUpdateEvent event = ConfigUpdateEvent.createDeviceUpdateEvent(deviceId, connectionChanged);
        event.setSource("config-sync");
        event.setUpdateTime(new Date());
        return event;
    }

    /**
     * 创建并返回业务对象。
     */
    private ConfigUpdateEvent createPointsEvent(String deviceId, int pointCountChange) {
        ConfigUpdateEvent event = ConfigUpdateEvent.createPointsUpdateEvent(deviceId, pointCountChange);
        event.setSource("config-sync");
        event.setUpdateTime(new Date());
        return event;
    }

    /**
     * 创建并返回业务对象。
     */
    private ConfigUpdateEvent createConnectionEvent(String deviceId) {
        ConfigUpdateEvent event = ConfigUpdateEvent.createConnectionUpdateEvent(deviceId);
        event.setSource("config-sync");
        event.setUpdateTime(new Date());
        return event;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

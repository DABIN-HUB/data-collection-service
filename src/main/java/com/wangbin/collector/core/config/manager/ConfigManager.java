package com.wangbin.collector.core.config.manager;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.scheduler.AdaptiveCollectionUtil;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.validator.ProtocolConnectionValidator;
import com.wangbin.collector.core.report.validator.FieldUniquenessValidator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 配置管理器 - 负责配置的加载、缓存和更新
 *
 * 主要职责：
 * 1. 管理所有配置的缓存
 * 2. 提供线程安全的配置访问接口
 * 3. 处理配置变更事件
 * 4. 协调配置的重新加载
 */
@Slf4j
@Component
public class ConfigManager {

    public static final String CONFIG_SOURCE_LOCAL = "local";
    public static final String CONFIG_SOURCE_KEY = "configSource";
    public static final String TEMPORARY_CONFIG_KEY = "temporaryConfig";

    /**
     * 设备配置缓存 key:设备ID value:设备信息
     */
    private final Map<String, DeviceInfo> deviceCache = new ConcurrentHashMap<>();

    /**
     * 数据点配置缓存 key:设备ID value:数据点列表
     */
    private final Map<String, List<DataPoint>> pointCache = new ConcurrentHashMap<>();

    /**
     * 连接配置缓存 key:设备ID value:连接信息
     */
    private final Map<String, DeviceConnection> connectionCache = new ConcurrentHashMap<>();

    /**
     * 聚合配置缓存 key:设备ID value:DeviceContext
     */
    private final Map<String, DeviceContext> deviceContextCache = new ConcurrentHashMap<>();

    /**
     * 读写锁，保证配置读写的线程安全
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Autowired
    private ConfigSyncService configSyncService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private FieldUniquenessValidator fieldUniquenessValidator;

    @Autowired(required = false)
    private ProtocolConnectionValidator protocolConnectionValidator = new ProtocolConnectionValidator();

    /**
     * 初始化方法
     */
    @PostConstruct
    public void init() {
        log.info("配置管理器初始化开始...");
        loadAllConfig();
        startConfigSync();
        log.info("配置管理器初始化完成");
    }

    /**
     * 加载所有配置
     */
    private void loadAllConfig() {
        try {
            lock.writeLock().lock();
            log.info("开始加载所有配置...");

            List<DeviceContext> localTemporaryContexts = snapshotLocalTemporaryContexts();

            deviceCache.clear();
            pointCache.clear();
            connectionCache.clear();
            deviceContextCache.clear();

            // 从远程服务加载配置
            List<DeviceInfo> devices = configSyncService.loadAllDevices();
            for (DeviceInfo device : devices) {
                String deviceId = device.getDeviceId();

                if (deviceId == null || deviceId.trim().isEmpty()) {
                    log.warn("设备ID为空，跳过设备: {}", device.getDeviceName());
                    continue;
                }

                // 缓存设备信息
                deviceCache.put(deviceId, device);

                try {
                    // 加载设备的数据点
                    List<DataPoint> points = configSyncService.loadDataPoints(deviceId);
                    List<DataPoint> safePoints = points != null ? new ArrayList<>(points) : new ArrayList<>();
                    normalizeDataPointCollectionPolicy(device, safePoints);
                    pointCache.put(deviceId, safePoints);

                    // 加载连接配置
                    DeviceConnection connection = configSyncService.loadConnectionConfig(deviceId);
                    if (connection != null) {
                        connectionCache.put(deviceId, connection);
                    } else {
                        connectionCache.remove(deviceId);
                    }

                    deviceContextCache.put(deviceId, DeviceContext.of(device, connection, safePoints));

                    log.debug("设备配置加载成功: {} - {}", deviceId, device.getDeviceName());
                } catch (Exception e) {
                    log.error("加载设备相关配置失败: {}", deviceId, e);
                }
            }

            restoreLocalTemporaryContexts(localTemporaryContexts);

            log.info("配置加载完成，共加载 {} 个设备配置", devices.size());
        } catch (Exception e) {
            log.error("加载所有配置失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 根据设备ID获取设备信息
     *
     * @param deviceId 设备ID
     * @return 设备信息，不存在返回null
     */
    public DeviceInfo getDevice(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");

        lock.readLock().lock();
        try {
            return deviceCache.get(deviceId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取所有设备信息
     *
     * @return 设备信息列表
     */
    public List<DeviceInfo> getAllDevices() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(deviceCache.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取设备上下文
     *
     * @param deviceId 设备ID
     * @return 设备上下文，不存在返回null
     */
    public DeviceContext getDeviceContext(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");

        lock.readLock().lock();
        try {
            return deviceContextCache.get(deviceId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取全部设备上下文
     *
     * @return 上下文列表
     */
    public List<DeviceContext> getAllDeviceContexts() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(deviceContextCache.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取设备的数据点列表
     *
     * @param deviceId 设备ID
     * @return 数据点列表，如果设备不存在返回空列表
     */
    public List<DataPoint> getDataPoints(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");

        lock.readLock().lock();
        try {
            List<DataPoint> points = pointCache.get(deviceId);
            return points != null ? points : Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取点位信息并重置数据自适应时间
     * @param deviceId
     * @return
     */
    public List<DataPoint> getDataPointsAndAdaptiveConfig(String deviceId) {
        List<DataPoint> dataPoints = getDataPoints(deviceId);
        if(CollectionUtils.isEmpty(dataPoints)){
            return Collections.emptyList();
        }
        dataPoints.forEach(AdaptiveCollectionUtil::resetAdaptiveConfig);
        return dataPoints;
    }

    /**
     * 获取单个数据点配置
     *
     * @param deviceId  设备ID
     * @param pointCode 点位编码
     * @return 数据点配置，不存在返回null
     */
    public DataPoint getDataPoint(String deviceId, String pointCode) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        Objects.requireNonNull(pointCode, "点位编码不能为空");

        lock.readLock().lock();
        try {
            List<DataPoint> points = pointCache.get(deviceId);
            if (points != null) {
                return points.stream()
                        .filter(p -> pointCode.equals(p.getPointCode()))
                        .findFirst()
                        .orElse(null);
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 根据pointId获取单个数据点配置
     *
     * @param deviceId 设备ID
     * @param pointId  数据点ID
     * @return 数据点配置，不存在返回null
     */
    public DataPoint getDataPointByPointId(String deviceId, String pointId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        Objects.requireNonNull(pointId, "数据点ID不能为空");

        lock.readLock().lock();
        try {
            List<DataPoint> points = pointCache.get(deviceId);
            if (points != null) {
                return points.stream()
                        .filter(p -> pointId.equals(p.getPointId()))
                        .findFirst()
                        .orElse(null);
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取连接配置
     *
     * @param deviceId 设备ID
     * @return 连接信息，不存在返回null
     */
    public DeviceConnection getConnectionConfig(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");

        lock.readLock().lock();
        try {
            return connectionCache.get(deviceId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 更新设备配置
     *
     * @param device 设备信息
     * @return 是否更新成功
     */
    public boolean updateDeviceConfig(DeviceInfo device) {
        Objects.requireNonNull(device, "设备信息不能为空");

        try {
            lock.writeLock().lock();

            String deviceId = device.getDeviceId();
            if (deviceId == null || deviceId.trim().isEmpty()) {
                log.error("设备ID为空，无法更新配置");
                return false;
            }

            DeviceInfo oldDevice = deviceCache.get(deviceId);

            // 检查是否需要更新连接
            boolean connectionChanged = false;
            if (oldDevice != null) {
                connectionChanged = isConnectionChanged(oldDevice, device);
            }

            // 更新缓存
            deviceCache.put(deviceId, device);
            rebuildDeviceContext(deviceId);

            // 发布配置更新事件
            ConfigUpdateEvent event = ConfigUpdateEvent.builder()
                    .deviceId(deviceId)
                    .configType("device")
                    .connectionChanged(connectionChanged)
                    .updateTime(new Date())
                    .build();

            eventPublisher.publishEvent(event);
            log.info("设备配置已更新: {} - {}", deviceId, device.getDeviceName());

            return true;
        } catch (Exception e) {
            log.error("更新设备配置失败: {}", device.getDeviceId(), e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 更新数据点配置
     *
     * @param deviceId 设备ID
     * @param points   数据点列表
     * @return 是否更新成功
     */
    public boolean updateDataPoints(String deviceId, List<DataPoint> points) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        Objects.requireNonNull(points, "数据点列表不能为空");

        try {
            lock.writeLock().lock();

            if (!deviceCache.containsKey(deviceId)) {
                log.warn("设备不存在，无法更新数据点: {}", deviceId);
                return false;
            }

            List<DataPoint> safePoints = new ArrayList<>(points);
            normalizeDataPointCollectionPolicy(deviceCache.get(deviceId), safePoints);

            if (fieldUniquenessValidator != null) {
                fieldUniquenessValidator.validate(deviceId, safePoints);
            }

            pointCache.put(deviceId, safePoints);
            rebuildDeviceContext(deviceId);

            // 发布配置更新事件
            ConfigUpdateEvent event = ConfigUpdateEvent.builder()
                    .deviceId(deviceId)
                    .configType("points")
                    .updateTime(new Date())
                    .build();

            eventPublisher.publishEvent(event);
            log.info("数据点配置已更新: {}, 共 {} 个点", deviceId, points.size());

            return true;
        } catch (Exception e) {
            log.error("更新数据点配置失败: {}", deviceId, e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 更新连接配置
     *
     * @param deviceId 设备ID
     * @param connection 连接信息
     * @return 是否更新成功
     */
    public boolean updateConnectionConfig(String deviceId, DeviceConnection connection) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");

        try {
            lock.writeLock().lock();

            if (!deviceCache.containsKey(deviceId)) {
                log.warn("设备不存在，无法更新连接配置: {}", deviceId);
                return false;
            }

            if (connection != null) {
                connection.setDeviceId(deviceId);
                protocolConnectionValidator.validate(deviceCache.get(deviceId), connection);
                connectionCache.put(deviceId, connection);
            } else {
                connectionCache.remove(deviceId);
            }

            rebuildDeviceContext(deviceId);

            ConfigUpdateEvent event = ConfigUpdateEvent.builder()
                    .deviceId(deviceId)
                    .configType("connection")
                    .updateTime(new Date())
                    .build();
            eventPublisher.publishEvent(event);
            log.info("连接配置已更新: {}", deviceId);
            return true;
        } catch (Exception e) {
            log.error("更新连接配置失败: {}", deviceId, e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Save a complete local temporary device bundle without touching the remote sync source.
     */
    public boolean saveLocalDeviceConfig(DeviceInfo device,
                                         DeviceConnection connection,
                                         List<DataPoint> points,
                                         boolean overwrite) {
        Objects.requireNonNull(device, "device config is required");
        Objects.requireNonNull(connection, "connection config is required");

        String deviceId = normalizeDeviceId(device.getDeviceId());
        validateLocalDevice(device, deviceId);

        List<DataPoint> safePoints = points != null ? new ArrayList<>(points) : new ArrayList<>();
        validateLocalPoints(deviceId, safePoints);

        lock.writeLock().lock();
        try {
            DeviceInfo existing = deviceCache.get(deviceId);
            if (existing != null && !isLocalTemporaryDeviceInfo(existing)) {
                throw new IllegalArgumentException("device already exists from non-local config source: " + deviceId);
            }
            if (existing != null && !overwrite) {
                throw new IllegalArgumentException("local temporary device already exists: " + deviceId);
            }

            normalizeLocalDevice(device, existing);
            normalizeLocalConnection(device, connection);
            normalizeLocalPoints(device, safePoints);

            protocolConnectionValidator.validate(device, connection);
            if (fieldUniquenessValidator != null) {
                fieldUniquenessValidator.validate(deviceId, safePoints);
            }

            deviceCache.put(deviceId, device);
            connectionCache.put(deviceId, connection);
            pointCache.put(deviceId, safePoints);
            rebuildDeviceContext(deviceId);

            ConfigUpdateEvent event = ConfigUpdateEvent.builder()
                    .deviceId(deviceId)
                    .configType("local")
                    .connectionChanged(true)
                    .updateTime(new Date())
                    .build();
            eventPublisher.publishEvent(event);
            log.info("Local temporary device config saved: {}, points={}", deviceId, safePoints.size());
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Delete only local temporary device configs. Remote-synced configs are protected.
     */
    public boolean deleteLocalDeviceConfig(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");

        lock.writeLock().lock();
        try {
            DeviceInfo existing = deviceCache.get(deviceId);
            if (existing == null) {
                return false;
            }
            if (!isLocalTemporaryDeviceInfo(existing)) {
                throw new IllegalArgumentException("refuse to delete non-local device config: " + deviceId);
            }
            deviceCache.remove(deviceId);
            pointCache.remove(deviceId);
            connectionCache.remove(deviceId);
            deviceContextCache.remove(deviceId);

            ConfigUpdateEvent event = ConfigUpdateEvent.builder()
                    .deviceId(deviceId)
                    .configType("local-delete")
                    .updateTime(new Date())
                    .build();
            eventPublisher.publishEvent(event);
            log.info("Local temporary device config deleted: {}", deviceId);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isLocalTemporaryDevice(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        lock.readLock().lock();
        try {
            return isLocalTemporaryDeviceInfo(deviceCache.get(deviceId));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取所有设备ID列表
     *
     * @return 设备ID列表
     */
    public List<String> getAllDeviceIds() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(deviceCache.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 检查设备是否在缓存中
     *
     * @param deviceId 设备ID
     * @return 是否存在
     */
    public boolean containsDevice(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");

        lock.readLock().lock();
        try {
            return deviceCache.containsKey(deviceId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Force reload of device-related configs, used before manual start requests.
     *
     * @param deviceId device identifier
     * @return true if full config is available after refresh, otherwise false
     */
    public boolean refreshDeviceConfig(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        reloadDeviceConfig(deviceId);
        reloadDataPoints(deviceId);
        reloadConnectionConfig(deviceId);

        lock.readLock().lock();
        try {
            DeviceInfo device = deviceCache.get(deviceId);
            List<DataPoint> points = pointCache.get(deviceId);
            return device != null && points != null && !points.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取缓存统计信息
     *
     * @return 缓存统计信息
     */
    public Map<String, Object> getCacheStats() {
        lock.readLock().lock();
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("deviceCount", deviceCache.size());
            stats.put("pointCount", pointCache.values().stream()
                    .mapToInt(List::size)
                    .sum());
            stats.put("connectionCount", connectionCache.size());
            stats.put("contextCount", deviceContextCache.size());
            return stats;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 清空指定设备的配置缓存
     *
     * @param deviceId 设备ID
     * @return 是否存在并已清空
     */
    public boolean clearDeviceConfig(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");

        boolean existed;
        lock.readLock().lock();
        try {
            existed = deviceCache.containsKey(deviceId)
                    || pointCache.containsKey(deviceId)
                    || connectionCache.containsKey(deviceId)
                    || deviceContextCache.containsKey(deviceId);
        } finally {
            lock.readLock().unlock();
        }

        if (!existed) {
            log.warn("设备配置不存在，跳过清空: {}", deviceId);
            return false;
        }

        removeDeviceConfig(deviceId);
        log.info("设备配置缓存已清空: {}", deviceId);
        return true;
    }

    /**
     * 清空所有配置缓存
     */
    public void clearAllCache() {
        try {
            lock.writeLock().lock();
            deviceCache.clear();
            pointCache.clear();
            connectionCache.clear();
            deviceContextCache.clear();
            log.info("所有配置缓存已清空");
        } finally {
            lock.writeLock().unlock();
        }
    }

    private List<DeviceContext> snapshotLocalTemporaryContexts() {
        if (deviceContextCache.isEmpty()) {
            return Collections.emptyList();
        }
        List<DeviceContext> snapshots = new ArrayList<>();
        for (DeviceContext context : deviceContextCache.values()) {
            if (context != null && isLocalTemporaryDeviceInfo(context.getDeviceInfo())) {
                snapshots.add(context);
            }
        }
        return snapshots;
    }

    private void restoreLocalTemporaryContexts(List<DeviceContext> contexts) {
        if (CollectionUtils.isEmpty(contexts)) {
            return;
        }
        int restored = 0;
        for (DeviceContext context : contexts) {
            DeviceInfo device = context.getDeviceInfo();
            if (device == null || !StringUtils.hasText(device.getDeviceId())) {
                continue;
            }
            String deviceId = device.getDeviceId();
            if (deviceCache.containsKey(deviceId)) {
                log.warn("Skip restoring local temporary device because remote config now exists: {}", deviceId);
                continue;
            }
            deviceCache.put(deviceId, device);
            DeviceConnection connection = context.copyConnectionConfig();
            if (connection != null) {
                connectionCache.put(deviceId, connection);
            }
            pointCache.put(deviceId, context.copyDataPoints());
            rebuildDeviceContext(deviceId);
            restored++;
        }
        if (restored > 0) {
            log.info("Restored {} local temporary device configs after remote sync", restored);
        }
    }

    private void validateLocalDevice(DeviceInfo device, String deviceId) {
        if (!StringUtils.hasText(deviceId)) {
            throw new IllegalArgumentException("deviceId is required");
        }
        if (!StringUtils.hasText(device.getDeviceName())) {
            throw new IllegalArgumentException("deviceName is required");
        }
        if (!StringUtils.hasText(device.getProtocolType())) {
            throw new IllegalArgumentException("protocolType is required");
        }
    }

    private void validateLocalPoints(String deviceId, List<DataPoint> points) {
        if (CollectionUtils.isEmpty(points)) {
            throw new IllegalArgumentException("at least one data point is required for local device: " + deviceId);
        }
        for (DataPoint point : points) {
            if (point == null) {
                throw new IllegalArgumentException("data point cannot be null");
            }
            if (!StringUtils.hasText(point.getPointCode())) {
                throw new IllegalArgumentException("pointCode is required");
            }
            if (!StringUtils.hasText(point.getAddress())) {
                throw new IllegalArgumentException("address is required for point: " + point.getPointCode());
            }
            if (!StringUtils.hasText(point.getDataType())) {
                throw new IllegalArgumentException("dataType is required for point: " + point.getPointCode());
            }
        }
    }

    private void normalizeLocalDevice(DeviceInfo device, DeviceInfo existing) {
        Date now = new Date();
        device.setDeviceId(normalizeDeviceId(device.getDeviceId()));
        device.setProtocolType(device.getProtocolType().trim().toUpperCase(Locale.ROOT));
        if (!StringUtils.hasText(device.getConnectionType())) {
            device.setConnectionType(device.getProtocolType());
        }
        if (device.getCollectionInterval() == null || device.getCollectionInterval() <= 0) {
            device.setCollectionInterval(2000);
        }
        if (device.getReportInterval() == null || device.getReportInterval() <= 0) {
            device.setReportInterval(5);
        }
        if (!StringUtils.hasText(device.getStatus())) {
            device.setStatus("OFFLINE");
        }
        if (device.getCreateTime() == null) {
            device.setCreateTime(existing != null ? existing.getCreateTime() : now);
        }
        device.setUpdateTime(now);
        device.setConfigSource(CONFIG_SOURCE_LOCAL);
        device.setTemporaryConfig(true);
    }

    private void normalizeLocalConnection(DeviceInfo device, DeviceConnection connection) {
        connection.setDeviceId(device.getDeviceId());
        connection.setDeviceName(device.getDeviceName());
        if (!StringUtils.hasText(connection.getConnectionType())) {
            connection.setConnectionType(device.getProtocolType());
        }
        if (!StringUtils.hasText(connection.getHost()) && StringUtils.hasText(device.getIpAddress())) {
            connection.setHost(device.getIpAddress());
        }
        if (!StringUtils.hasText(device.getIpAddress()) && StringUtils.hasText(connection.getHost())) {
            device.setIpAddress(connection.getHost());
        }
        if (connection.getPort() == null && device.getPort() != null) {
            connection.setPort(device.getPort());
        }
        if (device.getPort() == null && connection.getPort() != null) {
            device.setPort(connection.getPort());
        }
        Map<String, Object> extJson = connection.getExtJson() != null
                ? new LinkedHashMap<>(connection.getExtJson())
                : new LinkedHashMap<>();
        extJson.put(CONFIG_SOURCE_KEY, CONFIG_SOURCE_LOCAL);
        extJson.put(TEMPORARY_CONFIG_KEY, true);
        connection.setExtJson(extJson);
        Date now = new Date();
        if (connection.getCreateTime() == null) {
            connection.setCreateTime(now);
        }
        connection.setUpdateTime(now);
    }

    private void normalizeLocalPoints(DeviceInfo device, List<DataPoint> points) {
        Date now = new Date();
        for (DataPoint point : points) {
            point.setDeviceId(device.getDeviceId());
            point.setDeviceName(device.getDeviceName());
            if (!StringUtils.hasText(point.getPointId())) {
                point.setPointId(device.getDeviceId() + ":" + point.getPointCode());
            }
            if (!StringUtils.hasText(point.getPointName())) {
                point.setPointName(point.getPointCode());
            }
            if (!StringUtils.hasText(point.getReadWrite())) {
                point.setReadWrite("R");
            }
            if (!StringUtils.hasText(point.getCollectionMode())) {
                point.setCollectionMode("POLLING");
            }
            if (point.getStatus() == null) {
                point.setStatus(1);
            }
            if (point.getCacheEnabled() == null) {
                point.setCacheEnabled(1);
            }
            if (point.getCreateTime() == null) {
                point.setCreateTime(now);
            }
            point.setUpdateTime(now);
            Map<String, Object> additionalConfig = point.getAdditionalConfig();
            removePointCloudIdentity(additionalConfig);
            additionalConfig.put(CONFIG_SOURCE_KEY, CONFIG_SOURCE_LOCAL);
            additionalConfig.put(TEMPORARY_CONFIG_KEY, true);
            point.setAdditionalConfig(additionalConfig);
        }
        normalizeDataPointCollectionPolicy(device, points);
    }

    private void removePointCloudIdentity(Map<String, Object> additionalConfig) {
        if (additionalConfig == null || additionalConfig.isEmpty()) {
            return;
        }
        // 云设备身份只能配置在 DeviceInfo.cloudTarget，点位只保留 reportField。
        additionalConfig.remove("reportDeviceName");
        additionalConfig.remove("reportProductKey");
        additionalConfig.remove("productKey");
        additionalConfig.remove("cloudBindings");
    }

    private void normalizeDataPointCollectionPolicy(DeviceInfo device, List<DataPoint> points) {
        if (CollectionUtils.isEmpty(points)) {
            return;
        }
        long defaultBaseInterval = device != null
                && device.getCollectionInterval() != null
                && device.getCollectionInterval() > 0
                ? device.getCollectionInterval()
                : AdaptiveCollectionUtil.DEFAULT_BASE_COLLECTION_INTERVAL;
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            Map<String, Object> additionalConfig = point.getAdditionalConfig();
            removePointCloudIdentity(additionalConfig);
            point.setAdditionalConfig(additionalConfig);
            long minInterval = normalizePositive(point.getMinCollectionInterval(),
                    AdaptiveCollectionUtil.DEFAULT_MIN_COLLECTION_INTERVAL);
            long maxInterval = normalizePositive(point.getMaxCollectionInterval(),
                    AdaptiveCollectionUtil.DEFAULT_MAX_COLLECTION_INTERVAL);
            if (minInterval > maxInterval) {
                long tmp = minInterval;
                minInterval = maxInterval;
                maxInterval = tmp;
            }
            long baseInterval = normalizePositive(point.getBaseCollectionInterval(), defaultBaseInterval);
            baseInterval = Math.max(minInterval, Math.min(baseInterval, maxInterval));

            point.setBaseCollectionInterval(baseInterval);
            if (point.getCurrentCollectionInterval() <= 0) {
                point.setCurrentCollectionInterval(baseInterval);
            }
            point.setMinCollectionInterval(minInterval);
            point.setMaxCollectionInterval(maxInterval);
            if (point.getPointChangeThreshold() == null) {
                point.setPointChangeThreshold(AdaptiveCollectionUtil.DEFAULT_CHANGE_THRESHOLD);
            }
        }
    }

    private long normalizePositive(Long value, long defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private boolean isLocalTemporaryDeviceInfo(DeviceInfo device) {
        return device != null
                && CONFIG_SOURCE_LOCAL.equalsIgnoreCase(device.getConfigSource())
                && Boolean.TRUE.equals(device.getTemporaryConfig());
    }

    private String normalizeDeviceId(String deviceId) {
        return deviceId != null ? deviceId.trim() : null;
    }

    /**
     * 检查连接配置是否发生变化
     *
     * @param oldDevice 旧设备信息
     * @param newDevice 新设备信息
     * @return 连接是否变化
     */
    private boolean isConnectionChanged(DeviceInfo oldDevice, DeviceInfo newDevice) {
        return !Objects.equals(oldDevice.getIpAddress(), newDevice.getIpAddress()) ||
                !Objects.equals(oldDevice.getPort(), newDevice.getPort()) ||
                !Objects.equals(oldDevice.getProtocolType(), newDevice.getProtocolType()) ||
                !Objects.equals(oldDevice.getConnectionType(), newDevice.getConnectionType()) ||
                !Objects.equals(oldDevice.getAuthConfig(), newDevice.getAuthConfig());
    }

    /**
     * 启动配置同步监听
     */
    private void startConfigSync() {
        // 启动定时同步任务
        configSyncService.startSyncTask();

        // 注册配置变更监听
        configSyncService.registerConfigListener(this::handleConfigChange);

        log.info("配置同步监听已启动");
    }

    /**
     * 处理配置变更事件
     *
     * @param event 配置更新事件
     */
    private void handleConfigChange(ConfigUpdateEvent event) {
        log.info("收到配置变更通知: {}", event);

        String deviceId = event.getDeviceId();
        String configType = event.getConfigType();

        try {
            // 根据变更类型重新加载配置
            switch (configType) {
                case "device":
                    reloadDeviceConfig(deviceId);
                    break;
                case "points":
                    reloadDataPoints(deviceId);
                    break;
                case "connection":
                    reloadConnectionConfig(deviceId);
                    break;
                case "collection":
                    if (StringUtils.hasText(deviceId)) {
                        reloadDeviceConfig(deviceId);
                        reloadDataPoints(deviceId);
                    } else {
                        loadAllConfig();
                    }
                    break;
                case "all":
                    loadAllConfig();
                    break;
                default:
                    log.warn("未知的配置类型: {}", configType);
            }
        } catch (Exception e) {
            log.error("处理配置变更失败: {}", configType, e);
        }
    }

    /**
     * 重新加载设备配置
     *
     * @param deviceId 设备ID
     */
    private void reloadDeviceConfig(String deviceId) {
        if (deviceId == null) {
            log.warn("设备ID为空，跳过设备配置重载");
            return;
        }

        try {
            DeviceInfo device = configSyncService.loadDevice(deviceId);
            if (device != null) {
                updateDeviceConfig(device);
                log.info("设备配置重载成功: {}", deviceId);
            } else {
                // 设备可能被删除
                removeDeviceConfig(deviceId);
                log.info("设备可能已删除，从缓存中移除: {}", deviceId);
            }
        } catch (Exception e) {
            log.error("重新加载设备配置失败: {}", deviceId, e);
        }
    }

    /**
     * 从缓存中移除设备配置
     *
     * @param deviceId 设备ID
     */
    private void removeDeviceConfig(String deviceId) {
        try {
            lock.writeLock().lock();
            deviceCache.remove(deviceId);
            pointCache.remove(deviceId);
            connectionCache.remove(deviceId);
            deviceContextCache.remove(deviceId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 刷新设备上下文
     *
     * @param deviceId 设备ID
     */
    private void rebuildDeviceContext(String deviceId) {
        if (deviceId == null) {
            return;
        }
        DeviceInfo device = deviceCache.get(deviceId);
        if (device == null) {
            deviceContextCache.remove(deviceId);
            return;
        }
        List<DataPoint> points = pointCache.getOrDefault(deviceId, Collections.emptyList());
        DeviceConnection connection = connectionCache.get(deviceId);
        deviceContextCache.put(deviceId, DeviceContext.of(device, connection, points));
    }

    /**
     * 重新加载数据点配置
     *
     * @param deviceId 设备ID
     */
    private void reloadDataPoints(String deviceId) {
        if (deviceId == null) {
            log.warn("设备ID为空，跳过数据点重载");
            return;
        }

        try {
            List<DataPoint> points = configSyncService.loadDataPoints(deviceId);
            if (points != null) {
                updateDataPoints(deviceId, points);
                log.info("数据点配置重载成功: {}", deviceId);
            }
        } catch (Exception e) {
            log.error("重新加载数据点配置失败: {}", deviceId, e);
        }
    }

    /**
     * 重新加载连接配置
     *
     * @param deviceId 设备ID
     */
    private void reloadConnectionConfig(String deviceId) {
        if (deviceId == null) {
            log.warn("设备ID为空，跳过连接配置重载");
            return;
        }

        try {
            DeviceConnection connection = configSyncService.loadConnectionConfig(deviceId);
            lock.writeLock().lock();
            try {
                if (connection != null) {
                    connectionCache.put(deviceId, connection);
                    log.info("连接配置重载成功: {}", deviceId);
                } else {
                    connectionCache.remove(deviceId);
                    log.info("连接配置已删除，已从缓存中移除: {}", deviceId);
                }
                rebuildDeviceContext(deviceId);
            } finally {
                lock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.error("重新加载连接配置失败: {}", deviceId, e);
        }
    }
}


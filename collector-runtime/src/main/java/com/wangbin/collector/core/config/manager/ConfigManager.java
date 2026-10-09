package com.wangbin.collector.core.config.manager;


import com.alibaba.fastjson2.JSON;
import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.scheduler.AdaptiveCollectionUtil;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.config.model.ConfigUpdateType;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.store.LocalDeviceConfigStore;
import com.wangbin.collector.core.config.validator.ProtocolConnectionValidator;
import com.wangbin.collector.core.config.validator.ProtocolPointValidator;
import com.wangbin.collector.core.report.validator.FieldUniquenessValidator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
    private final Map<String, Long> deviceConfigVersions = new ConcurrentHashMap<>();
    /** 删除墓碑只覆盖正在发布的事件及显式持有的异步消费者，不按历史设备数永久累积。 */
    private final Map<Long, DeletedConfigurationVersion> deletedConfigurationVersions = new HashMap<>();
    private final AtomicLong configVersionSequence = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong configMutationRevision = new AtomicLong();

    /**
     * 读写锁，保证配置读写的线程安全
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final ConfigSyncService configSyncService;
    private final ApplicationEventPublisher eventPublisher;
    private final FieldUniquenessValidator fieldUniquenessValidator;
    private final ProtocolConnectionValidator protocolConnectionValidator;
    private final List<ProtocolPointValidator> pointValidators;
    private final LocalDeviceConfigStore localDeviceConfigStore;

    /** 保留旧的四参数构造方式，供嵌入式调用方兼容。 */
    public ConfigManager(ConfigSyncService configSyncService,
                         ApplicationEventPublisher eventPublisher,
                         FieldUniquenessValidator fieldUniquenessValidator,
                         LocalDeviceConfigStore localDeviceConfigStore) {
        this(configSyncService, eventPublisher, fieldUniquenessValidator, localDeviceConfigStore, null);
    }
    public ConfigManager(ConfigSyncService configSyncService,
                         ApplicationEventPublisher eventPublisher,
                         FieldUniquenessValidator fieldUniquenessValidator,
                         LocalDeviceConfigStore localDeviceConfigStore,
                         ObjectProvider<ProtocolConnectionValidator> protocolConnectionValidatorProvider) {
        this(configSyncService, eventPublisher, fieldUniquenessValidator, localDeviceConfigStore,
                protocolConnectionValidatorProvider, null);
    }

    @Autowired
    public ConfigManager(ConfigSyncService configSyncService,
                         ApplicationEventPublisher eventPublisher,
                         FieldUniquenessValidator fieldUniquenessValidator,
                         LocalDeviceConfigStore localDeviceConfigStore,
                         ObjectProvider<ProtocolConnectionValidator> protocolConnectionValidatorProvider,
                         ObjectProvider<ProtocolPointValidator> pointValidatorProvider) {
        this.configSyncService = configSyncService;
        this.eventPublisher = eventPublisher;
        this.fieldUniquenessValidator = fieldUniquenessValidator;
        this.localDeviceConfigStore = localDeviceConfigStore;
        this.protocolConnectionValidator = protocolConnectionValidatorProvider != null
                ? protocolConnectionValidatorProvider.getIfAvailable(ProtocolConnectionValidator::new)
                : new ProtocolConnectionValidator();
        this.pointValidators = pointValidatorProvider != null
                ? pointValidatorProvider.orderedStream().toList() : List.of();
    }

    /**
     * 初始化方法
     */
    @PostConstruct
    public void init() {
        log.info("配置管理器初始化开始...");
        restorePersistedLocalTemporaryContexts();
        loadAllConfig();
        startConfigSync();
        log.info("配置管理器初始化完成");
    }

    /**
     * 从本地快照恢复非远端托管设备。
     */
    private void restorePersistedLocalTemporaryContexts() {
        if (localDeviceConfigStore == null) {
            return;
        }

        List<DeviceContext> contexts = localDeviceConfigStore.load();
        if (contexts.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            restoreLocalTemporaryContexts(contexts);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 加载所有配置
     */
    private void loadAllConfig() {
        log.info("开始加载所有配置...");
        long startRevision = configMutationRevision.get();
        Map<String, DeviceContext> previousContexts;
        Map<String, Long> previousVersions;
        List<DeviceContext> localTemporaryContexts;
        lock.readLock().lock();
        try {
            previousContexts = new HashMap<>(deviceContextCache);
            previousVersions = new HashMap<>(deviceConfigVersions);
            localTemporaryContexts = snapshotLocalTemporaryContexts();
        } finally {
            lock.readLock().unlock();
        }

        Map<String, DeviceInfo> candidateDevices = new HashMap<>();
        Map<String, DeviceConnection> candidateConnections = new HashMap<>();
        Map<String, List<DataPoint>> candidatePoints = new HashMap<>();
        Map<String, DeviceContext> candidateContexts = new HashMap<>();
        List<ConfigUpdateEvent> committedEvents = new ArrayList<>();
        Set<String> localIds = new HashSet<>();
        localTemporaryContexts.forEach(context -> localIds.add(context.getDeviceId()));
        try {
            List<DeviceInfo> devices = configSyncService.loadAllDevices();
            if (devices == null) {
                throw new IllegalStateException("远端设备配置返回为空");
            }
            for (DeviceInfo device : devices) {
                String deviceId = device == null ? null : device.getDeviceId();
                if (!StringUtils.hasText(deviceId)) {
                    log.warn("远端设备ID为空，跳过设备");
                    continue;
                }
                if (localIds.contains(deviceId)) {
                    log.warn("远端设备身份与本地配置冲突，保留本地配置: {}", deviceId);
                    continue;
                }
                if (candidateDevices.putIfAbsent(deviceId, device) != null) {
                    throw new IllegalStateException("远端配置包含重复设备ID: " + deviceId);
                }
                try {
                    List<DataPoint> points = configSyncService.loadDataPoints(deviceId);
                    List<DataPoint> safePoints = points == null ? new ArrayList<>() : new ArrayList<>(points);
                    validatePointIdentities(deviceId, safePoints, false);
                    safePoints.forEach(point -> point.setDeviceId(deviceId));
                    normalizeDataPointCollectionPolicy(device, safePoints);
                    if (fieldUniquenessValidator != null) {
                        fieldUniquenessValidator.validate(deviceId, safePoints);
                    }
                    DeviceConnection connection = configSyncService.loadConnectionConfig(deviceId);
                    if (connection != null) {
                        connection.setDeviceId(deviceId);
                        protocolConnectionValidator.validate(device, connection);
                        candidateConnections.put(deviceId, connection);
                    }
                    candidatePoints.put(deviceId, safePoints);
                    candidateContexts.put(deviceId, DeviceContext.of(device, connection, safePoints));
                } catch (Exception exception) {
                    log.error("full configuration refresh aborted, deviceId={}, reason={}",
                            deviceId, exception.getMessage(), exception);
                    throw new IllegalStateException("远端设备配置加载失败: " + deviceId, exception);
                }
            }

            // 完整远端候选校验完成后再合并本地配置，远端不得夺取本地身份。
            for (DeviceContext localContext : localTemporaryContexts) {
                if (localContext == null || localContext.getDeviceInfo() == null) continue;
                String deviceId = localContext.getDeviceId();
                if (candidateDevices.containsKey(deviceId)) {
                    log.warn("跳过恢复本地临时设备，原因=远端配置已存在：{}", deviceId);
                    continue;
                }
                DeviceConnection connection = localContext.copyConnectionConfig();
                List<DataPoint> points = localContext.copyDataPoints();
                candidateDevices.put(deviceId, localContext.getDeviceInfo());
                candidatePoints.put(deviceId, points);
                if (connection != null) candidateConnections.put(deviceId, connection);
                candidateContexts.put(deviceId, DeviceContext.of(localContext.getDeviceInfo(), connection, points));
            }

            lock.writeLock().lock();
            try {
                if (configMutationRevision.get() != startRevision) {
                    log.warn("full configuration refresh aborted because live config changed during remote load");
                    return;
                }
                mergeLocalTemporaryCandidates(candidateDevices, candidateConnections, candidatePoints, candidateContexts,
                        snapshotLocalTemporaryContexts());
                for (String deviceId : candidateContexts.keySet()) {
                    if (sameDeviceConfiguration(previousContexts.get(deviceId), candidateContexts.get(deviceId))) {
                        DeviceContext previous = previousContexts.get(deviceId);
                        candidateContexts.put(deviceId, previous);
                        candidateDevices.put(deviceId, deviceCache.get(deviceId));
                        candidatePoints.put(deviceId, pointCache.getOrDefault(deviceId, List.of()));
                        if (connectionCache.containsKey(deviceId)) candidateConnections.put(deviceId, connectionCache.get(deviceId));
                        else candidateConnections.remove(deviceId);
                    }
                }
                deviceCache.clear();
                deviceCache.putAll(candidateDevices);
                connectionCache.clear();
                connectionCache.putAll(candidateConnections);
                pointCache.clear();
                pointCache.putAll(candidatePoints);
                deviceContextCache.clear();
                deviceContextCache.putAll(candidateContexts);
                reconcileConfigVersions(previousContexts, previousVersions);
                for (String deviceId : candidateContexts.keySet()) {
                    if (!sameDeviceConfiguration(previousContexts.get(deviceId), candidateContexts.get(deviceId))) {
                        committedEvents.add(configChangeEvent(deviceId, ConfigUpdateType.ALL, "config-sync",
                                previousVersions.getOrDefault(deviceId, 0L), deviceConfigVersions.get(deviceId),
                                !sameConnectionConfiguration(previousContexts.get(deviceId) == null ? null
                                        : previousContexts.get(deviceId).getConnectionConfig(), candidateConnections.get(deviceId))));
                    }
                }
                for (String deviceId : previousContexts.keySet()) {
                    if (!candidateContexts.containsKey(deviceId)) {
                        committedEvents.add(configChangeEvent(deviceId, ConfigUpdateType.LOCAL_DELETE, "config-sync",
                                previousVersions.getOrDefault(deviceId, 0L), deviceConfigVersions.get(deviceId), true));
                    }
                }
                for (ConfigUpdateEvent event : committedEvents) {
                    event.setRetiredPointIds(retiredPointIds(previousContexts.get(event.getDeviceId()),
                            candidateContexts.get(event.getDeviceId())));
                }
                if (!committedEvents.isEmpty()) markConfigMutation();
            } finally {
                lock.writeLock().unlock();
            }
            committedEvents.forEach(this::publishCommittedEvent);
            log.info("配置加载完成，共加载 {} 个设备配置", candidateContexts.size());
        } catch (RuntimeException exception) {
            log.error("full configuration refresh aborted, live cache preserved, reason={}", exception.getMessage(), exception);
        }
    }

    private void mergeLocalTemporaryCandidates(Map<String, DeviceInfo> candidateDevices,
                                                Map<String, DeviceConnection> candidateConnections,
                                                Map<String, List<DataPoint>> candidatePoints,
                                                Map<String, DeviceContext> candidateContexts,
                                                List<DeviceContext> localContexts) {
        for (DeviceContext localContext : localContexts) {
            if (localContext == null || localContext.getDeviceInfo() == null) continue;
            String deviceId = localContext.getDeviceId();
            if (candidateDevices.containsKey(deviceId)
                    && !isLocalTemporaryDeviceInfo(candidateDevices.get(deviceId))) {
                log.warn("配置来源身份冲突，拒绝远端覆盖本地设备: {}", deviceId);
            }
            DeviceConnection connection = localContext.copyConnectionConfig();
            List<DataPoint> points = localContext.copyDataPoints();
            candidateDevices.put(deviceId, localContext.getDeviceInfo());
            candidatePoints.put(deviceId, points);
            if (connection != null) candidateConnections.put(deviceId, connection);
            else candidateConnections.remove(deviceId);
            candidateContexts.put(deviceId, DeviceContext.of(localContext.getDeviceInfo(), connection, points));
        }
    }

    private void reconcileConfigVersions(Map<String, DeviceContext> previousContexts,
                                         Map<String, Long> previousVersions) {
        Set<String> currentIds = new HashSet<>(deviceContextCache.keySet());
        // 删除版本覆盖事件发布窗口，重建同 ID 始终获得更大的版本。
        for (String deviceId : previousContexts.keySet()) {
            if (!currentIds.contains(deviceId)) registerDeletedConfigurationVersion(deviceId, nextConfigVersion());
        }
        for (String deviceId : currentIds) {
            DeviceContext current = deviceContextCache.get(deviceId);
            DeviceContext previous = previousContexts.get(deviceId);
            Long previousVersion = previousVersions.get(deviceId);
            if (previousVersion == null || !sameDeviceConfiguration(previous, current)) {
                deviceConfigVersions.put(deviceId, nextConfigVersion());
            } else {
                deviceConfigVersions.put(deviceId, previousVersion);
            }
        }
    }

    private boolean sameDeviceConfiguration(DeviceContext left, DeviceContext right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return Objects.equals(effectiveDevice(left.getDeviceInfo()), effectiveDevice(right.getDeviceInfo()))
                && sameConnectionConfiguration(left.getConnectionConfig(), right.getConnectionConfig())
                && Objects.equals(effectivePoints(left), effectivePoints(right));
    }

    /** 有效配置比较排除运行状态和时间；点位启用状态、身份与业务参数仍参与比较。 */
    private DeviceInfo effectiveDevice(DeviceInfo source) {
        if (source == null) return null;
        DeviceInfo device = DeviceContext.of(source, null, List.of()).getDeviceInfo();
        device.setStatus(null);
        device.setLastOnlineTime(null);
        device.setLastOfflineTime(null);
        device.setLastError(null);
        device.setRetryCount(null);
        device.setCreateTime(null);
        device.setUpdateTime(null);
        return device;
    }

    private boolean sameConnectionConfiguration(DeviceConnection left, DeviceConnection right) {
        return Objects.equals(effectiveConnection(left), effectiveConnection(right));
    }

    private DeviceConnection effectiveConnection(DeviceConnection source) {
        if (source == null) return null;
        DeviceConnection connection = DeviceContext.of(null, source, List.of()).copyConnectionConfig();
        connection.setStatus(null);
        connection.setConnectTime(null);
        connection.setDisconnectTime(null);
        connection.setDuration(null);
        connection.setLastError(null);
        connection.setStats(null);
        connection.setLastHeartbeatTime(null);
        connection.setLastDataTime(null);
        connection.setConnectionStats(null);
        connection.setCreateTime(null);
        connection.setUpdateTime(null);
        return connection;
    }

    private List<DataPoint> effectivePoints(DeviceContext context) {
        List<DataPoint> points = new ArrayList<>(context.copyDataPoints());
        for (DataPoint point : points) {
            point.setCreateTime(null);
            point.setUpdateTime(null);
            point.setCurrentCollectionInterval(0L);
            point.setStableCount(0);
            point.setLastValue(null);
            point.setChangeRate(0D);
            point.setLastAdjustTime(0L);
        }
        points.sort(Comparator.comparing(point -> Objects.toString(point.getPointId(), "")
                + "\u0000" + Objects.toString(point.getPointCode(), "")));
        return points;
    }

    /** 相同 ID 只能更新其原有来源，不能通过保存或导入转移来源。 */
    private void validateConfigSource(DeviceInfo existing, DeviceInfo candidate) {
        if (existing == null) return;
        if (isLocalTemporaryDeviceInfo(existing) != isLocalTemporaryDeviceInfo(candidate)
                || !Objects.equals(existing.getConfigSource(), candidate.getConfigSource())
                || !Objects.equals(existing.getTemporaryConfig(), candidate.getTemporaryConfig())) {
            throw new IllegalArgumentException("设备配置来源冲突，禁止转移来源: " + candidate.getDeviceId());
        }
    }

    private void validateDeviceIdentity(String deviceId, String candidateId) {
        if (StringUtils.hasText(candidateId) && !Objects.equals(deviceId, candidateId)) {
            throw new IllegalArgumentException("路径与配置设备身份冲突: " + deviceId);
        }
    }

    /** 新写入必须具备稳定 pointId；历史加载只保留缺失 ID，不重建历史身份。 */
    private void validatePointIdentities(String deviceId, List<DataPoint> points, boolean requirePointId) {
        Set<String> ids = new HashSet<>();
        for (DataPoint point : points == null ? List.<DataPoint>of() : points) {
            if (point == null) throw new IllegalArgumentException("点位不能为空");
            validateDeviceIdentity(deviceId, point.getDeviceId());
            if (!StringUtils.hasText(point.getPointId())) {
                if (requirePointId) throw new IllegalArgumentException("点位 pointId 不能为空，请保留历史身份或为新点位分配 UUID");
            } else if (!ids.add(point.getPointId())) {
                throw new IllegalArgumentException("点位 pointId 重复: " + point.getPointId());
            }
        }
    }

    /** 只传递失效旧身份，不携带连接口令、点位参数或完整旧配置。 */
    private Set<String> retiredPointIds(DeviceContext previous, DeviceContext current) {
        if (previous == null) return Set.of();
        Map<String, DataPoint> currentPoints = new HashMap<>();
        if (current != null) for (DataPoint point : effectivePoints(current)) currentPoints.put(point.getPointId(), point);
        boolean deviceChanged = current == null
                || isConnectionChanged(previous.getDeviceInfo(), current.getDeviceInfo())
                || !sameConnectionConfiguration(previous.getConnectionConfig(), current.getConnectionConfig());
        Set<String> retired = new LinkedHashSet<>();
        for (DataPoint point : effectivePoints(previous)) {
            if (StringUtils.hasText(point.getPointId())
                    && (deviceChanged || !Objects.equals(point, currentPoints.get(point.getPointId())))) retired.add(point.getPointId());
        }
        return Set.copyOf(retired);
    }

    private ConfigUpdateEvent configChangeEvent(String deviceId, ConfigUpdateType type, String source,
                                                long previousVersion, long configVersion, boolean connectionChanged) {
        return ConfigUpdateEvent.builder().deviceId(deviceId).configType(type.getValue()).source(source)
                .previousVersion(previousVersion).configVersion(configVersion).connectionChanged(connectionChanged)
                .updateTime(new Date()).build();
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

    /** 获取当前 JVM 内设备配置版本。 */
    public long getDeviceConfigVersion(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        lock.readLock().lock();
        try {
            return deviceConfigVersions.getOrDefault(deviceId, 0L);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 配置与版本在同一读锁中取得，返回对象不共享缓存中的可变字段。 */
    public DeviceConfigurationSnapshot getDeviceConfigurationSnapshot(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        lock.readLock().lock();
        try {
            DeviceContext context = deviceContextCache.get(deviceId);
            DeviceContext copy = context == null ? null : DeviceContext.of(
                    JSON.parseObject(JSON.toJSONString(context.getDeviceInfo()), DeviceInfo.class),
                    JSON.parseObject(JSON.toJSONString(context.getConnectionConfig()), DeviceConnection.class),
                    JSON.parseArray(JSON.toJSONString(context.getDataPoints()), DataPoint.class));
            return new DeviceConfigurationSnapshot(copy, deviceConfigVersions.getOrDefault(deviceId, 0L));
        } finally {
            lock.readLock().unlock();
        }
    }

    public record DeviceConfigurationSnapshot(DeviceContext context, long configVersion) { }

    /**
     * 异步删除消费者在同步事件回调内保留墓碑；返回幂等释放操作，过期或设备已重建时返回 null。
     * 不得在 runIfConfigurationCurrent 的读锁回调内调用；任务完成、取消、拒绝均须释放。
     */
    public Runnable retainDeletedConfigurationVersion(String deviceId, long configVersion) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        lock.writeLock().lock();
        try {
            DeletedConfigurationVersion deleted = deletedConfigurationVersions.get(configVersion);
            if (deleted == null || !deviceId.equals(deleted.deviceId)
                    || deviceCache.containsKey(deviceId)
                    || deviceConfigVersions.getOrDefault(deviceId, 0L) != configVersion) return null;
            deleted.references++;
            java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
            return () -> {
                if (released.compareAndSet(false, true)) releaseDeletedConfigurationVersion(deviceId, configVersion);
            };
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 调用方已经持有配置写锁；发布过程先拥有一个引用，防止快速停止任务提前回收。 */
    private void registerDeletedConfigurationVersion(String deviceId, long configVersion) {
        deviceConfigVersions.put(deviceId, configVersion);
        deletedConfigurationVersions.put(configVersion, new DeletedConfigurationVersion(deviceId));
    }

    private void releaseDeletedConfigurationVersion(String deviceId, long configVersion) {
        lock.writeLock().lock();
        try {
            DeletedConfigurationVersion deleted = deletedConfigurationVersions.get(configVersion);
            if (deleted == null || !deviceId.equals(deleted.deviceId)) return;
            if (--deleted.references == 0) {
                deletedConfigurationVersions.remove(configVersion);
                if (!deviceCache.containsKey(deviceId)) deviceConfigVersions.remove(deviceId, configVersion);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static final class DeletedConfigurationVersion {
        private final String deviceId;
        private int references = 1;

        private DeletedConfigurationVersion(String deviceId) {
            this.deviceId = deviceId;
        }
    }

    /**
     * 在读锁内核对版本（包括删除墓碑）并执行副作用，过期时不执行。
     * 调用方先取得设备采集门；action 不得写配置或再取得生命周期锁。
     */
    public boolean runIfConfigurationCurrent(String deviceId, long expectedVersion, Runnable action) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        Objects.requireNonNull(action, "操作不能为空");
        lock.readLock().lock();
        try {
            if (deviceConfigVersions.getOrDefault(deviceId, 0L) != expectedVersion) return false;
            action.run();
            return true;
        } finally {
            lock.readLock().unlock();
        }
    }

    private long nextConfigVersion() {
        return configVersionSequence.updateAndGet(current ->
                Math.max(System.currentTimeMillis(), current + 1));
    }

    private long markConfigMutation() {
        return configMutationRevision.incrementAndGet();
    }
    /** 获取全部设备上下文。 */
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
     * @param deviceId 本地设备 ID
     * @return 点位列表
     */
    public List<DataPoint> getDataPointsAndAdaptiveConfig(String deviceId) {
        List<DataPoint> dataPoints = getDataPoints(deviceId);
        if(CollectionUtils.isEmpty(dataPoints)){
            return Collections.emptyList();
        }
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
        ConfigUpdateEvent event;

        try {
            lock.writeLock().lock();

            String deviceId = device.getDeviceId();
            if (deviceId == null || deviceId.trim().isEmpty()) {
                log.error("设备ID为空，无法更新配置");
                return false;
            }

            DeviceInfo oldDevice = deviceCache.get(deviceId);
            validateConfigSource(oldDevice, device);
            DeviceContext previousContext = deviceContextCache.get(deviceId);
            DeviceContext candidate = DeviceContext.of(device, connectionCache.get(deviceId), pointCache.get(deviceId));
            if (sameDeviceConfiguration(previousContext, candidate)) return true;
            long previousVersion = deviceConfigVersions.getOrDefault(deviceId, 0L);

            // 检查是否需要更新连接
            boolean connectionChanged = false;
            if (oldDevice != null) {
                connectionChanged = isConnectionChanged(oldDevice, device);
            }

            // 更新缓存
            deviceCache.put(deviceId, device);
            rebuildDeviceContext(deviceId);
            if (isLocalTemporaryDeviceInfo(device)) {
                try {
                    persistLocalTemporaryContexts();
                } catch (RuntimeException exception) {
                    restoreDeviceContext(deviceId, previousContext);
                    throw exception;
                }
            }

            long newVersion = nextConfigVersion();
            deviceConfigVersions.put(deviceId, newVersion);
            // 发布配置更新事件
            event = ConfigUpdateEvent.builder()
                    .deviceId(deviceId)
                    .retiredPointIds(retiredPointIds(previousContext, deviceContextCache.get(deviceId)))
                    .configType(ConfigUpdateType.DEVICE.getValue())
                    .previousVersion(previousVersion)
                    .configVersion(newVersion)
                    .connectionChanged(connectionChanged)
                    .updateTime(new Date())
                    .build();

            markConfigMutation();
            log.info("设备配置已更新: {} - {}", deviceId, device.getDeviceName());
        } catch (Exception e) {
            log.error("更新设备配置失败: {}", device.getDeviceId(), e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
        publishCommittedEvent(event);
        return true;
    }

    /**
     * 更新数据点配置
     *
     * @param deviceId 设备ID
     * @param points   数据点列表
     * @return 是否更新成功
     */
    public boolean updateDataPoints(String deviceId, List<DataPoint> points) {
        return updateDataPoints(deviceId, points, true);
    }

    /** 远端历史快照由运行期逐点隔离；新写入仍进行严格协议校验。 */
    private boolean updateDataPoints(String deviceId, List<DataPoint> points, boolean validateNewPoints) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        Objects.requireNonNull(points, "数据点列表不能为空");
        ConfigUpdateEvent event;

        try {
            lock.writeLock().lock();

            if (!deviceCache.containsKey(deviceId)) {
                log.warn("设备不存在，无法更新数据点: {}", deviceId);
                return false;
            }
            if (!validateNewPoints && isLocalTemporaryDeviceInfo(deviceCache.get(deviceId))) return false;
            long previousVersion = deviceConfigVersions.getOrDefault(deviceId, 0L);
            DeviceContext previousContext = deviceContextCache.get(deviceId);

            validatePointIdentities(deviceId, points, validateNewPoints);
            List<DataPoint> safePoints = new ArrayList<>(DeviceContext.of(deviceCache.get(deviceId), null, points)
                    .copyDataPoints());
            validatePointIdentities(deviceId, safePoints, validateNewPoints);
            safePoints.forEach(point -> point.setDeviceId(deviceId));
            normalizeDataPointCollectionPolicy(deviceCache.get(deviceId), safePoints);
            if (validateNewPoints) {
                validateProtocolPoints(deviceCache.get(deviceId), safePoints);
            }

            if (fieldUniquenessValidator != null) {
                fieldUniquenessValidator.validate(deviceId, safePoints);
            }

            if (sameDeviceConfiguration(previousContext,
                    DeviceContext.of(deviceCache.get(deviceId), connectionCache.get(deviceId), safePoints))) return true;
            pointCache.put(deviceId, safePoints);
            rebuildDeviceContext(deviceId);
            if (isLocalTemporaryDeviceInfo(deviceCache.get(deviceId))) {
                try {
                    persistLocalTemporaryContexts();
                } catch (RuntimeException exception) {
                    restoreDeviceContext(deviceId, previousContext);
                    throw exception;
                }
            }

            long newVersion = nextConfigVersion();
            deviceConfigVersions.put(deviceId, newVersion);

            // 发布配置更新事件
            event = ConfigUpdateEvent.builder()
                    .deviceId(deviceId)
                    .retiredPointIds(retiredPointIds(previousContext, deviceContextCache.get(deviceId)))
                    .configType(ConfigUpdateType.POINTS.getValue())
                    .previousVersion(previousVersion)
                    .configVersion(newVersion)
                    .updateTime(new Date())
                    .build();

            markConfigMutation();
            log.info("数据点配置已更新: {}, 共 {} 个点", deviceId, points.size());
        } catch (Exception e) {
            log.error("更新数据点配置失败: {}", deviceId, e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
        publishCommittedEvent(event);
        return true;
    }

    /**
     * 更新连接配置
     *
     * @param deviceId 设备ID
     * @param connection 连接信息
     * @return 是否更新成功
     */
    public boolean updateConnectionConfig(String deviceId, DeviceConnection connection) {
        return updateConnectionConfig(deviceId, connection, false);
    }

    private boolean updateConnectionConfig(String deviceId, DeviceConnection connection, boolean remoteReload) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        ConfigUpdateEvent event;

        try {
            lock.writeLock().lock();

            if (!deviceCache.containsKey(deviceId)) {
                log.warn("设备不存在，无法更新连接配置: {}", deviceId);
                return false;
            }
            if (remoteReload && isLocalTemporaryDeviceInfo(deviceCache.get(deviceId))) return false;
            long previousVersion = deviceConfigVersions.getOrDefault(deviceId, 0L);
            DeviceContext previousContext = deviceContextCache.get(deviceId);

            if (connection != null) {
                validateDeviceIdentity(deviceId, connection.getDeviceId());
                connection = DeviceContext.of(null, connection, List.of()).copyConnectionConfig();
                connection.setDeviceId(deviceId);
                protocolConnectionValidator.validate(deviceCache.get(deviceId), connection);
            }
            if (sameConnectionConfiguration(connectionCache.get(deviceId), connection)) return true;
            if (connection != null) {
                connectionCache.put(deviceId, connection);
            } else {
                connectionCache.remove(deviceId);
            }

            rebuildDeviceContext(deviceId);
            if (isLocalTemporaryDeviceInfo(deviceCache.get(deviceId))) {
                try {
                    persistLocalTemporaryContexts();
                } catch (RuntimeException exception) {
                    restoreDeviceContext(deviceId, previousContext);
                    throw exception;
                }
            }

            long newVersion = nextConfigVersion();
            deviceConfigVersions.put(deviceId, newVersion);

            event = ConfigUpdateEvent.builder()
                    .deviceId(deviceId)
                    .retiredPointIds(retiredPointIds(previousContext, deviceContextCache.get(deviceId)))
                    .configType(ConfigUpdateType.CONNECTION.getValue())
                    .connectionChanged(true)
                    .previousVersion(previousVersion)
                    .configVersion(newVersion)
                    .updateTime(new Date())
                    .build();
            markConfigMutation();
            log.info("连接配置已更新: {}", deviceId);
        } catch (Exception e) {
            log.error("更新连接配置失败: {}", deviceId, e);
            return false;
        } finally {
            lock.writeLock().unlock();
        }
        publishCommittedEvent(event);
        return true;
    }

    private void publishCommittedEvent(ConfigUpdateEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException exception) {
            log.error("配置已提交，但配置变更事件发布失败: {}", event.getDeviceId(), exception);
        } finally {
            if (ConfigUpdateType.LOCAL_DELETE.getValue().equals(event.getConfigType()) && event.getConfigVersion() != null) {
                releaseDeletedConfigurationVersion(event.getDeviceId(), event.getConfigVersion());
            }
        }
    }

    /** 单设备原子提交结果。 */
    public record DeviceConfigCommitResult(String deviceId, long previousVersion, long configVersion, int pointCount) {}

    /** 无副作用校验完整设备配置。 */
    public void validateDeviceContext(String deviceId, DeviceInfo device, DeviceConnection connection, List<DataPoint> points) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (device == null) throw new IllegalArgumentException("设备信息不能为空");
        validateDeviceIdentity(deviceId, device.getDeviceId());
        if (connection != null) validateDeviceIdentity(deviceId, connection.getDeviceId());
        validatePointIdentities(deviceId, points, true);
        DeviceContext snapshot = DeviceContext.of(device, connection, points);
        device = snapshot.getDeviceInfo();
        connection = snapshot.copyConnectionConfig();
        device.setDeviceId(deviceId);
        if (connection != null) connection.setDeviceId(deviceId);
        List<DataPoint> safePoints = new ArrayList<>(snapshot.copyDataPoints());
        validatePointIdentities(deviceId, safePoints, true);
        safePoints.forEach(point -> point.setDeviceId(deviceId));
        DeviceContext candidate = DeviceContext.of(device, connection, safePoints);
        validateImportContext(candidate, new HashSet<>(), new HashMap<>());
        validateProtocolPoints(candidate.getDeviceInfo(), candidate.getDataPoints());
    }


    /**
     * 使用设备级 CAS 原子替换完整配置。
     */
    public DeviceConfigCommitResult replaceDeviceContextAtomically(String deviceId,
                                                                     DeviceInfo device,
                                                                     DeviceConnection connection,
                                                                     List<DataPoint> points,
                                                                     long expectedVersion) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        ConfigUpdateEvent pendingEvent;
        DeviceConfigCommitResult commitResult;
        lock.writeLock().lock();
        Map<String, DeviceInfo> deviceBackup = new HashMap<>(deviceCache);
        Map<String, DeviceConnection> connectionBackup = new HashMap<>(connectionCache);
        Map<String, List<DataPoint>> pointBackup = new HashMap<>(pointCache);
        Map<String, DeviceContext> contextBackup = new HashMap<>(deviceContextCache);
        Map<String, Long> versionBackup = new HashMap<>(deviceConfigVersions);
        try {
            long previousVersion = deviceConfigVersions.getOrDefault(deviceId, 0L);
            // CAS 优先于归一化、比较和写入，旧版本的相同配置也必须拒绝。
            if (previousVersion != expectedVersion) {
                throw new ConfigVersionConflictException(deviceId, expectedVersion, previousVersion);
            }
            if (device == null) throw new IllegalArgumentException("设备信息不能为空");
            validateDeviceIdentity(deviceId, device.getDeviceId());
            if (connection != null) validateDeviceIdentity(deviceId, connection.getDeviceId());
            validatePointIdentities(deviceId, points, true);
            DeviceContext snapshot = DeviceContext.of(device, connection, points);
            DeviceInfo candidateDevice = snapshot.getDeviceInfo();
            candidateDevice.setDeviceId(deviceId);
            DeviceConnection candidateConnection = snapshot.copyConnectionConfig();
            if (candidateConnection != null) candidateConnection.setDeviceId(deviceId);
            DeviceContext candidate = DeviceContext.of(candidateDevice, candidateConnection, snapshot.copyDataPoints());
            Map<String, List<DataPoint>> normalized = new HashMap<>();
            validateImportContext(candidate, new HashSet<>(), normalized);
            validateProtocolPoints(candidateDevice, normalized.get(deviceId));
            DeviceContext normalizedCandidate = DeviceContext.of(candidateDevice, candidateConnection, normalized.get(deviceId));
            if (sameDeviceConfiguration(contextBackup.get(deviceId), normalizedCandidate)) {
                return new DeviceConfigCommitResult(deviceId, previousVersion, previousVersion, normalized.get(deviceId).size());
            }
            deviceCache.put(deviceId, candidateDevice);
            if (candidateConnection == null) connectionCache.remove(deviceId);
            else connectionCache.put(deviceId, candidateConnection);
            pointCache.put(deviceId, normalized.get(deviceId));
            deviceContextCache.put(deviceId, normalizedCandidate);
            if (isLocalTemporaryDeviceInfo(candidateDevice)) persistLocalTemporaryContexts();
            long newVersion = nextConfigVersion();
            deviceConfigVersions.put(deviceId, newVersion);
            pendingEvent = configChangeEvent(deviceId, ConfigUpdateType.ALL, "config-bundle", previousVersion,
                    newVersion, !sameConnectionConfiguration(connectionBackup.get(deviceId), candidateConnection));
            pendingEvent.setRetiredPointIds(retiredPointIds(contextBackup.get(deviceId), normalizedCandidate));
            commitResult = new DeviceConfigCommitResult(deviceId, previousVersion, newVersion, normalized.get(deviceId).size());
            markConfigMutation();
        } catch (RuntimeException exception) {
            restoreCache(deviceCache, deviceBackup);
            restoreCache(connectionCache, connectionBackup);
            restoreCache(pointCache, pointBackup);
            restoreCache(deviceContextCache, contextBackup);
            restoreCache(deviceConfigVersions, versionBackup);
            throw exception;
        } finally {
            lock.writeLock().unlock();
        }
        publishCommittedEvent(pendingEvent);
        return commitResult;
    }
    /** 原子替换一组设备上下文，任一配置校验失败时不修改现有缓存。 */
    public boolean replaceDeviceContextsAtomically(List<DeviceContext> contexts) {
        if (CollectionUtils.isEmpty(contexts)) return false;
        List<ConfigUpdateEvent> events = new ArrayList<>();
        lock.writeLock().lock();
        try {
            Set<String> deviceIds = new HashSet<>();
            Map<String, List<DataPoint>> normalizedPoints = new HashMap<>();
            for (DeviceContext context : contexts) {
                validateImportContext(context, deviceIds, normalizedPoints);
                validateProtocolPoints(context.getDeviceInfo(), normalizedPoints.get(context.getDeviceId()));
            }
            Map<String, DeviceInfo> deviceBackup = new HashMap<>(deviceCache);
            Map<String, DeviceConnection> connectionBackup = new HashMap<>(connectionCache);
            Map<String, List<DataPoint>> pointBackup = new HashMap<>(pointCache);
            Map<String, DeviceContext> contextBackup = new HashMap<>(deviceContextCache);
            Map<String, Long> versionBackup = new HashMap<>(deviceConfigVersions);
            try {
                for (DeviceContext context : contexts) {
                    String deviceId = context.getDeviceId();
                    DeviceContext candidate = DeviceContext.of(context.getDeviceInfo(), context.copyConnectionConfig(),
                            normalizedPoints.get(deviceId));
                    if (sameDeviceConfiguration(contextBackup.get(deviceId), candidate)) continue;
                    deviceCache.put(deviceId, candidate.getDeviceInfo());
                    if (candidate.getConnectionConfig() == null) connectionCache.remove(deviceId);
                    else connectionCache.put(deviceId, candidate.copyConnectionConfig());
                    pointCache.put(deviceId, candidate.copyDataPoints());
                    deviceContextCache.put(deviceId, candidate);
                    long version = nextConfigVersion();
                    deviceConfigVersions.put(deviceId, version);
                    events.add(configChangeEvent(deviceId, ConfigUpdateType.ALL, "config-import",
                            versionBackup.getOrDefault(deviceId, 0L), version,
                            !sameConnectionConfiguration(connectionBackup.get(deviceId), candidate.getConnectionConfig())));
                    events.get(events.size() - 1).setRetiredPointIds(retiredPointIds(contextBackup.get(deviceId), candidate));
                }
                if (events.isEmpty()) return true;
                persistLocalTemporaryContexts();
                markConfigMutation();
            } catch (RuntimeException exception) {
                restoreCache(deviceCache, deviceBackup);
                restoreCache(connectionCache, connectionBackup);
                restoreCache(pointCache, pointBackup);
                restoreCache(deviceContextCache, contextBackup);
                restoreCache(deviceConfigVersions, versionBackup);
                throw exception;
            }
        } catch (RuntimeException exception) {
            log.warn("设备配置批量导入失败，现有缓存未修改: {}", exception.getMessage());
            return false;
        } finally {
            lock.writeLock().unlock();
        }
        events.forEach(this::publishCommittedEvent);
        log.info("设备配置批量原子导入完成，变更设备数量: {}", events.size());
        return true;
    }

    /**
     * 执行当前业务逻辑。
     */
    private <T> void restoreCache(Map<String, T> target, Map<String, T> backup) {
        target.clear();
        target.putAll(backup);
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void validateImportContext(DeviceContext context,
                                       Set<String> deviceIds,
                                       Map<String, List<DataPoint>> normalizedPoints) {
        if (context == null || context.getDeviceInfo() == null
                || !StringUtils.hasText(context.getDeviceId())) {
            throw new IllegalArgumentException("导入设备及设备ID不能为空");
        }
        String deviceId = context.getDeviceId();
        validateConfigSource(deviceCache.get(deviceId), context.getDeviceInfo());
        if (context.getConnectionConfig() != null) validateDeviceIdentity(deviceId, context.getConnectionConfig().getDeviceId());
        if (!deviceIds.add(deviceId)) {
            throw new IllegalArgumentException("导入内容包含重复设备ID: " + deviceId);
        }

        DeviceConnection connection = context.copyConnectionConfig();
        if (connection != null) {
            connection.setDeviceId(deviceId);
            protocolConnectionValidator.validate(context.getDeviceInfo(), connection);
        }
        List<DataPoint> points = new ArrayList<>(context.copyDataPoints());
        validatePointIdentities(deviceId, points, true);
        points.forEach(point -> point.setDeviceId(deviceId));
        normalizeDataPointCollectionPolicy(context.getDeviceInfo(), points);
        if (fieldUniquenessValidator != null) {
            fieldUniquenessValidator.validate(deviceId, points);
        }
        normalizedPoints.put(deviceId, points);
    }

    /**
     * 保存完整的本地临时设备配置，不修改远程同步源。
     */
    public boolean saveLocalDeviceConfig(DeviceInfo device,
                                         DeviceConnection connection,
                                         List<DataPoint> points,
                                         boolean overwrite) {
        return saveLocalDeviceConfigWithResult(device, connection, points, overwrite) != null;
    }

    /** 返回本次锁内提交版本，响应不得再拼接并发操作的版本。 */
    public DeviceConfigCommitResult saveLocalDeviceConfigWithResult(DeviceInfo device,
                                                                   DeviceConnection connection,
                                                                   List<DataPoint> points,
                                                                   boolean overwrite) {
        Objects.requireNonNull(device, "device config is required");
        Objects.requireNonNull(connection, "connection config is required");

        String deviceId = normalizeDeviceId(device.getDeviceId());
        validateLocalDevice(device, deviceId);
        validateDeviceIdentity(deviceId, connection.getDeviceId());

        List<DataPoint> safePoints = points != null ? new ArrayList<>(points) : new ArrayList<>();
        validateLocalPoints(deviceId, safePoints);
        validatePointIdentities(deviceId, safePoints, true);
        // 写入前复制候选配置，失败时不能修改调用方持有的对象或旧缓存。
        DeviceContext inputSnapshot = DeviceContext.of(device, connection, safePoints);
        device = inputSnapshot.getDeviceInfo();
        connection = inputSnapshot.copyConnectionConfig();
        safePoints = new ArrayList<>(inputSnapshot.copyDataPoints());
        ConfigUpdateEvent event;
        DeviceConfigCommitResult result;

        lock.writeLock().lock();
        try {
            DeviceInfo existing = deviceCache.get(deviceId);
            DeviceContext previousContext = deviceContextCache.get(deviceId);
            Long previousVersion = deviceConfigVersions.get(deviceId);
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
            validateProtocolPoints(device, safePoints);
            if (fieldUniquenessValidator != null) {
                fieldUniquenessValidator.validate(deviceId, safePoints);
            }

            if (sameDeviceConfiguration(previousContext, DeviceContext.of(device, connection, safePoints))) {
                long version = previousVersion == null ? 0L : previousVersion;
                return new DeviceConfigCommitResult(deviceId, version, version, safePoints.size());
            }
            deviceCache.put(deviceId, device);
            connectionCache.put(deviceId, connection);
            pointCache.put(deviceId, safePoints);
            rebuildDeviceContext(deviceId);
            try {
                persistLocalTemporaryContexts();
            } catch (RuntimeException exception) {
                restoreDeviceContext(deviceId, previousContext);
                if (previousVersion == null) deviceConfigVersions.remove(deviceId);
                else deviceConfigVersions.put(deviceId, previousVersion);
                throw exception;
            }

            long previousConfigVersion = previousVersion != null ? previousVersion : 0L;
            long newConfigVersion = nextConfigVersion();
            deviceConfigVersions.put(deviceId, newConfigVersion);
            markConfigMutation();
            event = ConfigUpdateEvent.builder()
                    .deviceId(deviceId)
                    .retiredPointIds(retiredPointIds(previousContext, deviceContextCache.get(deviceId)))
                    .configType(ConfigUpdateType.LOCAL.getValue())
                    .previousVersion(previousConfigVersion)
                    .configVersion(newConfigVersion)
                    .connectionChanged(true)
                    .updateTime(new Date())
                    .build();
            result = new DeviceConfigCommitResult(deviceId, previousConfigVersion, newConfigVersion, safePoints.size());
            log.info("本地临时设备配置已保存：{}，点位={}", deviceId, safePoints.size());
        } finally {
            lock.writeLock().unlock();
        }
        publishCommittedEvent(event);
        return result;
    }

    /**
     * 只删除本地临时设备配置，远端同步配置不允许通过该入口删除。
     */
    public boolean deleteLocalDeviceConfig(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        ConfigUpdateEvent event;

        lock.writeLock().lock();
        try {
            DeviceInfo existing = deviceCache.get(deviceId);
            if (existing == null) {
                return false;
            }
            if (!isLocalTemporaryDeviceInfo(existing)) {
                throw new IllegalArgumentException("refuse to delete non-local device config: " + deviceId);
            }
            DeviceContext previousContext = deviceContextCache.get(deviceId);
            Long previousVersion = deviceConfigVersions.get(deviceId);
            deviceCache.remove(deviceId);
            pointCache.remove(deviceId);
            connectionCache.remove(deviceId);
            deviceContextCache.remove(deviceId);
            try {
                persistLocalTemporaryContexts();
            } catch (RuntimeException exception) {
                restoreDeviceContext(deviceId, previousContext);
                if (previousVersion != null) deviceConfigVersions.put(deviceId, previousVersion);
                throw exception;
            }

            long previousConfigVersion = previousVersion != null ? previousVersion : 0L;
            long newConfigVersion = nextConfigVersion();
            registerDeletedConfigurationVersion(deviceId, newConfigVersion);
            markConfigMutation();
            event = ConfigUpdateEvent.builder()
                    .deviceId(deviceId)
                    .retiredPointIds(retiredPointIds(previousContext, null))
                    .configType(ConfigUpdateType.LOCAL_DELETE.getValue())
                    .previousVersion(previousConfigVersion)
                    .configVersion(newConfigVersion)
                    .updateTime(new Date())
                    .build();
            log.info("本地临时设备配置已删除：{}", deviceId);
        } finally {
            lock.writeLock().unlock();
        }
        publishCommittedEvent(event);
        return true;
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
     * 强制重新加载设备相关配置，用于手动启动前刷新最新设备信息。
     *
     * @param deviceId 设备标识
     * @return 刷新后完整设备配置是否可用
     */
    public boolean refreshDeviceConfig(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (isLocalTemporaryDevice(deviceId)) {
            return isCachedDeviceConfigAvailable(deviceId);
        }
        reloadDeviceConfig(deviceId);
        if (!containsDevice(deviceId)) {
            return false;
        }
        reloadDataPoints(deviceId);
        reloadConnectionConfig(deviceId);

        return isCachedDeviceConfigAvailable(deviceId);
    }

    /**
     * 检查缓存中是否具备启动前刷新要求的设备和点位配置。
     */
    private boolean isCachedDeviceConfigAvailable(String deviceId) {
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
            stats.put(CommonMapKeys.POINT_COUNT, pointCache.values().stream()
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
            boolean changed = !deviceCache.isEmpty() || !pointCache.isEmpty()
                    || !connectionCache.isEmpty() || !deviceContextCache.isEmpty()
                    || !deviceConfigVersions.isEmpty();
            deviceCache.clear();
            pointCache.clear();
            connectionCache.clear();
            deviceContextCache.clear();
            deviceConfigVersions.clear();
            deletedConfigurationVersions.clear();
            if (changed) markConfigMutation();
            log.info("所有配置缓存已清空");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 将当前所有本地设备配置持久化为可恢复快照。
     */
    private void persistLocalTemporaryContexts() {
        if (localDeviceConfigStore != null) {
            localDeviceConfigStore.save(snapshotLocalTemporaryContexts());
        }
    }

    /**
     * 恢复一次因本地快照保存失败而回滚的设备上下文。
     */
    private void restoreDeviceContext(String deviceId, DeviceContext context) {
        if (context == null) {
            deviceCache.remove(deviceId);
            pointCache.remove(deviceId);
            connectionCache.remove(deviceId);
            deviceContextCache.remove(deviceId);
            return;
        }
        deviceCache.put(deviceId, context.getDeviceInfo());
        DeviceConnection connection = context.copyConnectionConfig();
        if (connection == null) {
            connectionCache.remove(deviceId);
        } else {
            connectionCache.put(deviceId, connection);
        }
        pointCache.put(deviceId, context.copyDataPoints());
        deviceContextCache.put(deviceId, context);
    }

    /**
     * 查询并返回业务数据。
     */
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

    /**
     * 执行当前业务逻辑。
     */
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
                log.warn("跳过恢复本地临时设备，原因=远端配置已存在：{}", deviceId);
                continue;
            }
            deviceCache.put(deviceId, device);
            DeviceConnection connection = context.copyConnectionConfig();
            if (connection != null) {
                connectionCache.put(deviceId, connection);
            }
            pointCache.put(deviceId, context.copyDataPoints());
            rebuildDeviceContext(deviceId);
            deviceConfigVersions.put(deviceId, nextConfigVersion());
            restored++;
        }
        if (restored > 0) {
            log.info("远端同步后已恢复本地临时设备配置，数量={}", restored);
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void validateLocalDevice(DeviceInfo device, String deviceId) {
        if (!StringUtils.hasText(deviceId)) {
            throw new IllegalArgumentException("deviceId is required");
        }
        if ((StringUtils.hasText(device.getConfigSource()) && !CONFIG_SOURCE_LOCAL.equals(device.getConfigSource()))
                || Boolean.FALSE.equals(device.getTemporaryConfig())) {
            throw new IllegalArgumentException("本地保存不能转移配置来源: " + deviceId);
        }
        if (!StringUtils.hasText(device.getDeviceName())) {
            throw new IllegalArgumentException("deviceName is required");
        }
        if (!StringUtils.hasText(device.getProtocolType())) {
            throw new IllegalArgumentException("protocolType is required");
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
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

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 解析或转换业务数据。
     */
    private void normalizeLocalPoints(DeviceInfo device, List<DataPoint> points) {
        Date now = new Date();
        for (DataPoint point : points) {
            point.setDeviceId(device.getDeviceId());
            point.setDeviceName(device.getDeviceName());

            if (!StringUtils.hasText(point.getPointName())) {
                point.setPointName(point.getPointCode());
            }
            if (!StringUtils.hasText(point.getReadWrite())) {
                point.setReadWrite("R");
            }
            if (!StringUtils.hasText(point.getCollectionMode())) {
                point.setCollectionMode(isMqttProtocol(device) ? "SUBSCRIPTION" : "POLLING");
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

    /**
     * 清理或删除业务数据。
     */
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

    /** 新候选配置在写入缓存前校验；历史数据加载不走此入口。 */
    private void validateProtocolPoints(DeviceInfo device, List<DataPoint> points) {
        for (ProtocolPointValidator validator : pointValidators) {
            if (validator.supports(device)) {
                validator.validate(points);
            }
        }
    }

    /**
     * 解析或转换业务数据。
     */
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
            if (isMqttProtocol(device) && !StringUtils.hasText(point.getCollectionMode())) {
                point.setCollectionMode("SUBSCRIPTION");
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
            point.setMinCollectionInterval(minInterval);
            point.setMaxCollectionInterval(maxInterval);
            if (point.getPointChangeThreshold() == null) {
                point.setPointChangeThreshold(AdaptiveCollectionUtil.DEFAULT_CHANGE_THRESHOLD);
            }
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private long normalizePositive(Long value, long defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private boolean isMqttProtocol(DeviceInfo device) {
        if (device == null || !StringUtils.hasText(device.getProtocolType())) {
            return false;
        }
        String protocol = device.getProtocolType().trim();
        return "MQTT".equalsIgnoreCase(protocol) || "MQTT_SSL".equalsIgnoreCase(protocol);
    }

    private boolean isLocalTemporaryDeviceInfo(DeviceInfo device) {
        return device != null
                && CONFIG_SOURCE_LOCAL.equalsIgnoreCase(device.getConfigSource())
                && Boolean.TRUE.equals(device.getTemporaryConfig());
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeDeviceId(String deviceId) {
        return deviceId;
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
        ConfigUpdateType updateType = ConfigUpdateType.fromValue(configType).orElse(null);

        try {
            // 根据变更类型重新加载配置
            if (updateType == null) {
                log.warn("未知的配置类型: {}", configType);
                return;
            }
            switch (updateType) {
                case DEVICE:
                    reloadDeviceConfig(deviceId);
                    break;
                case POINTS:
                    reloadDataPoints(deviceId);
                    break;
                case CONNECTION:
                    reloadConnectionConfig(deviceId);
                    break;
                case COLLECTION:
                    if (StringUtils.hasText(deviceId)) {
                        reloadDeviceConfig(deviceId);
                        reloadDataPoints(deviceId);
                    } else {
                        loadAllConfig();
                    }
                    break;
                case ALL:
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
        if (deviceId == null || isLocalTemporaryDevice(deviceId)) return;
        try {
            DeviceInfo device = configSyncService.loadDevice(deviceId);
            if (device != null) {
                validateDeviceIdentity(deviceId, device.getDeviceId());
                if (isLocalTemporaryDevice(deviceId)) return;
                updateDeviceConfig(device);
                log.info("设备配置重载成功: {}", deviceId);
            } else {
                // 读取期间若身份转为本地，不接受远端删除。
                removeDeviceConfig(deviceId, true);
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
        removeDeviceConfig(deviceId, false);
    }

    private void removeDeviceConfig(String deviceId, boolean remoteReload) {
        ConfigUpdateEvent event;
        lock.writeLock().lock();
        try {
            if (remoteReload && isLocalTemporaryDeviceInfo(deviceCache.get(deviceId))) return;
            long previousVersion = deviceConfigVersions.getOrDefault(deviceId, 0L);
            DeviceContext previousContext = deviceContextCache.get(deviceId);
            boolean changed = deviceCache.remove(deviceId) != null;
            changed |= pointCache.remove(deviceId) != null;
            changed |= connectionCache.remove(deviceId) != null;
            changed |= deviceContextCache.remove(deviceId) != null;

            if (!changed) return;
            markConfigMutation();
            long deletedVersion = nextConfigVersion();
            registerDeletedConfigurationVersion(deviceId, deletedVersion);
            event = configChangeEvent(deviceId, ConfigUpdateType.LOCAL_DELETE, "config-remove",
                    previousVersion, deletedVersion, true);
            event.setRetiredPointIds(retiredPointIds(previousContext, null));
        } finally {
            lock.writeLock().unlock();
        }
        publishCommittedEvent(event);
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

        if (isLocalTemporaryDevice(deviceId)) {
            log.debug("本地临时设备不接受远端点位重载: {}", deviceId);
            return;
        }
        try {
            List<DataPoint> points = configSyncService.loadDataPoints(deviceId);
            if (points != null && !isLocalTemporaryDevice(deviceId)) {
                if (updateDataPoints(deviceId, points, false)) {
                    log.info("数据点配置重载成功: {}", deviceId);
                }
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
        if (deviceId == null || !containsDevice(deviceId) || isLocalTemporaryDevice(deviceId)) return;
        try {
            DeviceConnection connection = configSyncService.loadConnectionConfig(deviceId);
            updateConnectionConfig(deviceId, connection, true);
        } catch (RuntimeException exception) {
            log.error("重新加载连接配置失败: {}", deviceId, exception);
        }
    }

}


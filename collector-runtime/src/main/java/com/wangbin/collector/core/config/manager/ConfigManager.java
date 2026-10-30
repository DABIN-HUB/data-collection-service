package com.wangbin.collector.core.config.manager;



import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.scheduler.AdaptiveCollectionUtil;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.config.model.ConfigUpdateType;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.persistence.StoredDeviceConfiguration;
import com.wangbin.collector.core.config.persistence.ConfigurationConflictException;
import com.wangbin.collector.core.config.persistence.ConfigurationCommitUncertainException;
import com.wangbin.collector.core.config.persistence.DeviceConfiguration;
import com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException;
import com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException.Outcome;
import java.util.function.Supplier;
import com.wangbin.collector.core.config.persistence.WangbinLocalConfigurationPersistence;
import com.wangbin.collector.core.config.store.LocalDeviceConfigStore;
import com.wangbin.collector.core.config.security.SensitiveConfigSanitizer;
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
    private final WangbinLocalConfigurationPersistence localConfigurationPersistence;
    /** 持久化修订仅由现有读写锁保护，不与JVM配置版本或远端版本共用。 */
    private final Map<String, Long> localConfigurationRevisions = new HashMap<>();

    /** 对账失败时只封闭目标设备，缓存清理不能解除该保护。 */
    private final Set<String> localRecoveryRequired = new HashSet<>();
    /** 仅记录本地运行缓存的生命周期代次，不承载第二份配置数据。 */
    private final Map<String, LocalRuntimeGeneration> localRuntimeGenerations = new HashMap<>();
    private final SensitiveConfigSanitizer sensitiveConfigSanitizer = new SensitiveConfigSanitizer();

    public boolean isSqlitePersistenceEnabled() {
        return localConfigurationPersistence != null;
    }

    private void requireConfigurationReadable(String deviceId) {
        if (localRecoveryRequired.contains(deviceId)) {
            throw new WangbinConfigurationPersistenceException(Outcome.CONFIGURATION_UNAVAILABLE, List.of(deviceId));
        }
    }

    /** 调用者持有写锁；缓存清除不改变权威来源，也不提前安装候选或修订。 */
    private DeviceContext localCurrentContext(String deviceId) {
        requireConfigurationReadable(deviceId);
        try {
            return localConfigurationPersistence.findDevice(deviceId)
                    .map(item -> localConfigurationPersistence.toRuntime(item.configuration())).orElse(null);
        } catch (RuntimeException readFailure) {
            throw new WangbinConfigurationPersistenceException(Outcome.CONFIGURATION_UNAVAILABLE, List.of(deviceId));
        }
    }

    private DeviceContext prepareLocalCandidate(DeviceContext input) {
        if (input == null || input.getDeviceInfo() == null || !StringUtils.hasText(input.getDeviceId())) {
            throw new IllegalArgumentException("设备及设备ID不能为空");
        }
        String id = input.getDeviceId();
        requireConfigurationReadable(id);
        DeviceContext previous = localCurrentContext(id);
        if (!isLocalTemporaryDeviceInfo(input.getDeviceInfo())
                || previous != null && !isLocalTemporaryDeviceInfo(previous.getDeviceInfo())) {
            throw new IllegalArgumentException("SQLite用户写入仅支持本地临时设备: " + id);
        }
        input = DeviceContext.of(input.getDeviceInfo(), input.getConnectionConfig(), input.getDataPoints());
        restoreMaskedConfiguration(input, previous);
        validateConfigSource(previous == null ? null : previous.getDeviceInfo(), input.getDeviceInfo());
        Map<String, List<DataPoint>> points = new HashMap<>();
        validateImportContext(input, new HashSet<>(), points);
        validateProtocolPoints(input.getDeviceInfo(), points.get(id));
        DeviceConnection connection = input.copyConnectionConfig();
        if (connection != null) connection.setDeviceId(id);
        DeviceContext candidate = DeviceContext.of(input.getDeviceInfo(), connection, points.get(id));
        validateLocalRecoverability(candidate);
        return candidate;
    }

    /**
     * 在 ConfigManager 锁外读取单设备 SQLite 配置时使用的本地状态快照。
     * 该快照只用于区分本地缺失与远端设备，不保存配置内容。
     */
    private LocalRuntimeState snapshotLocalRuntimeState(String deviceId) {
        lock.readLock().lock();
        try {
            DeviceInfo cached = deviceCache.get(deviceId);
            return new LocalRuntimeState(
                    isLocalTemporaryDeviceInfo(cached)
                            || localConfigurationRevisions.containsKey(deviceId)
                            || localRecoveryRequired.contains(deviceId),
                    cached != null && !isLocalTemporaryDeviceInfo(cached),
                    localRecoveryRequired.contains(deviceId));
        } finally {
            lock.readLock().unlock();
        }
    }

    private WangbinConfigurationPersistenceException localConfigurationUnavailable(String deviceId) {
        return new WangbinConfigurationPersistenceException(Outcome.CONFIGURATION_UNAVAILABLE, List.of(deviceId));
    }

    private record LocalRuntimeState(boolean local, boolean cachedRemote, boolean recoveryRequired) {
    }

    private record LocalRuntimeGeneration(long generation, boolean active) {
    }

    /** 所有写入口在锁内恢复掩码，禁止旧页面锁外读取秘密后覆盖并发赢家。 */
    private void restoreMaskedConfiguration(DeviceContext incoming, DeviceContext existing) {
        sensitiveConfigSanitizer.restoreMaskedValues(incoming.getDeviceInfo(), existing == null ? null : existing.getDeviceInfo());
        sensitiveConfigSanitizer.restoreMaskedValues(incoming.getConnectionConfig(), existing == null ? null : existing.getConnectionConfig());
    }

    /** 编码与恢复必须对称；无效配置在任何数据库写入之前拒绝。 */
    private void validateLocalRecoverability(DeviceContext candidate) {
        if (!isSqlitePersistenceEnabled()) return;
        try {
            localConfigurationPersistence.toRuntime(localConfigurationPersistence.fromRuntime(candidate));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("配置无法无损恢复，请检查 device/connection/points 字段结构");
        }
    }

    private void requireBaseVersion(String id, Long expectedVersion) {
        if (expectedVersion == null) return;
        long current = deviceConfigVersions.getOrDefault(id, 0L);
        if (current != expectedVersion) throw new ConfigVersionConflictException(id, expectedVersion, current);
    }

    private Map<String, Long> revisionFor(String deviceId, Long baseRevision) {
        if (baseRevision == null) return Map.of();
        if (baseRevision < 0) throw new IllegalArgumentException("持久化修订不能为负数");
        return Map.of(deviceId, baseRevision);
    }

    /** 候选构造、来源校验和事务提交都在原写锁内；事件始终在解锁后发布。 */
    private Map<String, DeviceConfigCommitResult> commitLocalAggregates(
            Supplier<Map<String, DeviceContext>> candidateFactory, ConfigUpdateType type, String source) {
        return commitLocalAggregates(candidateFactory, type, source, Map.of());
    }

    private Map<String, DeviceConfigCommitResult> commitLocalAggregates(
            Supplier<Map<String, DeviceContext>> candidateFactory, ConfigUpdateType type, String source,
            Map<String, Long> submittedRevisions) {
        List<ConfigUpdateEvent> events = new ArrayList<>();
        RuntimeException operationFailure = null;
        lock.writeLock().lock();
        try {
            Map<String, DeviceContext> requested = candidateFactory.get();
            Map<String, DeviceContext> previous = new LinkedHashMap<>();
            Map<String, DeviceContext> changes = new LinkedHashMap<>();
            Map<String, Long> versions = new HashMap<>();
            Map<String, DeviceConfiguration> encoded = new LinkedHashMap<>();
            Map<String, Long> expectedRevisions = new HashMap<>();
            Map<String, DeviceConfigCommitResult> results = new LinkedHashMap<>();
            for (var entry : requested.entrySet()) {
                String id = entry.getKey();
                requireConfigurationReadable(id);
                DeviceContext before = localCurrentContext(id);
                if (before != null && !isLocalTemporaryDeviceInfo(before.getDeviceInfo())) {
                    throw new IllegalArgumentException("SQLite用户写入不支持远端设备: " + id);
                }
                DeviceContext candidate = entry.getValue() == null ? null : prepareLocalCandidate(entry.getValue());
                long version = deviceConfigVersions.getOrDefault(id, 0L);
                previous.put(id, before);
                versions.put(id, version);
                results.put(id, new DeviceConfigCommitResult(id, version, version,
                        candidate == null ? 0 : candidate.getDataPoints().size(),
                        localConfigurationRevisions.getOrDefault(id, 0L)));
                expectedRevisions.put(id, submittedRevisions.getOrDefault(id,
                        localConfigurationRevisions.getOrDefault(id, 0L)));
                if (sameDeviceConfiguration(before, candidate)) continue;
                changes.put(id, candidate);
                if (candidate != null) encoded.put(id, localConfigurationPersistence.fromRuntime(candidate));
            }
            if (requested.isEmpty()) return results;
            List<String> ids = List.copyOf(requested.keySet());
            Map<String, Long> committedRevisions;
            try {
                committedRevisions = localConfigurationPersistence.transaction(writer -> {
                    // 无变化设备也必须核对数据库真实修订，禁止陈旧请求绕过 CAS。
                    for (String id : ids) {
                        long actual = writer.findDeviceSummary(id).map(item -> item.revision()).orElse(0L);
                        long expected = expectedRevisions.get(id);
                        if (actual != expected) throw new ConfigVersionConflictException(id, expected, actual, true);
                    }
                    if (changes.isEmpty()) return Map.of();
                    // 在同一写事务内只查询候选点位的持久归属；删除后也不能暗中转移身份。
                    Set<String> candidatePointIds = new LinkedHashSet<>();
                    for (DeviceConfiguration config : encoded.values()) {
                        for (var point : config.points()) candidatePointIds.add(point.pointId());
                    }
                    Map<String, String> owners = new HashMap<>(writer.findPointOwners(candidatePointIds));
                    for (DeviceContext context : deviceContextCache.values()) {
                        if (!isLocalTemporaryDeviceInfo(context.getDeviceInfo())) {
                            for (DataPoint point : context.getDataPoints()) {
                                if (candidatePointIds.contains(point.getPointId())) {
                                    owners.putIfAbsent(point.getPointId(), context.getDeviceId());
                                }
                            }
                        }
                    }
                    for (DeviceConfiguration config : encoded.values()) {
                        for (var point : config.points()) {
                            String owner = owners.putIfAbsent(point.pointId(), config.deviceId());
                            if (owner != null && !owner.equals(config.deviceId())) {
                                throw new IllegalArgumentException("点位身份已属于其他设备，禁止转移pointId");
                            }
                        }
                    }
                    Map<String, Long> revisions = new HashMap<>();
                    for (var entry : changes.entrySet()) {
                        String id = entry.getKey();
                        if (entry.getValue() == null) writer.deleteDevice(id, expectedRevisions.get(id));
                        else revisions.put(id, writer.saveDevice(encoded.get(id), expectedRevisions.get(id)).revision());
                    }
                    return revisions;
                });
            } catch (ConfigurationConflictException | ConfigVersionConflictException conflict) {
                try {
                    reconcileLocalAggregates(ids, previous, versions, events, source + "-conflict");
                } catch (RuntimeException recoveryFailure) {
                    localRecoveryRequired.addAll(ids);
                    markConfigMutation();
                }
                if (conflict instanceof ConfigVersionConflictException versionConflict) throw versionConflict;
                throw new WangbinConfigurationPersistenceException(Outcome.CONFLICT, ids);
            } catch (IllegalArgumentException validationFailure) {
                throw validationFailure;
            } catch (ConfigurationCommitUncertainException uncertain) {
                localRecoveryRequired.addAll(ids);
                markConfigMutation();
                throw new WangbinConfigurationPersistenceException(Outcome.COMMIT_UNCONFIRMED, ids);
            } catch (RuntimeException failure) {
                throw new WangbinConfigurationPersistenceException(Outcome.WRITE_FAILED, ids);
            }
            if (changes.isEmpty()) {
                for (String id : ids) {
                    DeviceConfigCommitResult unchanged = results.get(id);
                    results.put(id, new DeviceConfigCommitResult(id, unchanged.previousVersion(),
                            unchanged.configVersion(), unchanged.pointCount(), expectedRevisions.get(id)));
                }
                return results;
            }
            try {
                installLocalAggregates(changes, committedRevisions, previous, versions, events, type, source);
            } catch (RuntimeException publicationFailure) {
                events.clear();
                try {
                    reconcileLocalAggregates(ids, previous, versions, events, source + "-reconciled");
                } catch (RuntimeException recoveryFailure) {
                    localRecoveryRequired.addAll(ids);
                    markConfigMutation();
                    throw new WangbinConfigurationPersistenceException(Outcome.COMMITTED_RECOVERY_REQUIRED, ids);
                }
                throw new WangbinConfigurationPersistenceException(Outcome.COMMITTED_RECONCILED, ids);
            }
            for (String id : ids) {
                if (!changes.containsKey(id)) {
                    DeviceConfigCommitResult unchanged = results.get(id);
                    results.put(id, new DeviceConfigCommitResult(id, unchanged.previousVersion(),
                            unchanged.configVersion(), unchanged.pointCount(), expectedRevisions.get(id)));
                    continue;
                }
                DeviceContext candidate = changes.get(id);
                results.put(id, new DeviceConfigCommitResult(id, versions.get(id),
                        deviceConfigVersions.getOrDefault(id, versions.get(id)),
                        candidate == null ? 0 : candidate.getDataPoints().size(),
                        candidate == null ? 0L : committedRevisions.get(id)));
            }
            return results;
        } catch (RuntimeException failure) {
            operationFailure = failure;
            throw failure;
        } finally {
            lock.writeLock().unlock();
            try {
                publishConfigurationEvents(events, operationFailure == null
                        || operationFailure instanceof WangbinConfigurationPersistenceException persistenceFailure && persistenceFailure.isCommitted());
            } catch (RuntimeException applicationFailure) {
                // 不让对账事件故障覆盖原始 CAS/写入结果。
                if (operationFailure != null) operationFailure.addSuppressed(applicationFailure);
                else throw applicationFailure;
            }
        }
    }

    /** 先准备全部事件及新版本，随后安装缓存；发生故障由调用方从权威源重建。 */
    private void installLocalAggregates(Map<String, DeviceContext> candidates, Map<String, Long> revisions,
                                        Map<String, DeviceContext> previous, Map<String, Long> previousVersions,
                                        List<ConfigUpdateEvent> events, ConfigUpdateType type, String source) {
        Map<String, Long> newVersions = new LinkedHashMap<>();
        List<ConfigUpdateEvent> prepared = new ArrayList<>();
        for (var entry : candidates.entrySet()) {
            String id = entry.getKey();
            DeviceContext candidate = entry.getValue();
            long version = nextConfigVersion();
            newVersions.put(id, version);
            ConfigUpdateEvent event = configChangeEvent(id, candidate == null ? ConfigUpdateType.LOCAL_DELETE : type,
                    source, previousVersions.getOrDefault(id, 0L), version,
                    previous.get(id) == null || candidate == null
                            || isConnectionChanged(previous.get(id).getDeviceInfo(), candidate.getDeviceInfo())
                            || !sameConnectionConfiguration(previous.get(id).getConnectionConfig(), candidate.getConnectionConfig()));
            event.setRetiredPointIds(retiredPointIds(previous.get(id), candidate));
            prepared.add(event);
        }
        for (var entry : candidates.entrySet()) {
            String id = entry.getKey();
            restoreDeviceContext(id, entry.getValue());
            if (entry.getValue() == null) localConfigurationRevisions.remove(id);
            else localConfigurationRevisions.put(id, revisions.get(id));
        }
        for (var entry : candidates.entrySet()) {
            if (entry.getValue() == null) registerDeletedConfigurationVersion(entry.getKey(), newVersions.get(entry.getKey()));
            else deviceConfigVersions.put(entry.getKey(), newVersions.get(entry.getKey()));
        }
        localRecoveryRequired.removeAll(candidates.keySet());
        markConfigMutation();
        events.addAll(prepared);
    }

    /** 对账使用单一事务快照，不重试用户写入、不覆盖外部赢家。 */
    private void reconcileLocalAggregates(List<String> ids, Map<String, DeviceContext> previous,
                                          Map<String, Long> versions, List<ConfigUpdateEvent> events, String source) {
        Map<String, StoredDeviceConfiguration> stored = localConfigurationPersistence.transaction(writer -> {
            Map<String, StoredDeviceConfiguration> snapshot = new LinkedHashMap<>();
            for (String id : ids) snapshot.put(id, writer.findDevice(id).orElse(null));
            return snapshot;
        });
        Map<String, DeviceContext> candidates = new LinkedHashMap<>();
        Map<String, DeviceContext> runtimePrevious = new LinkedHashMap<>();
        Map<String, Long> revisions = new HashMap<>();
        for (String id : ids) {
            StoredDeviceConfiguration item = stored.get(id);
            DeviceContext candidate = item == null ? null : localConfigurationPersistence.toRuntime(item.configuration());
            DeviceContext cached = deviceContextCache.get(id);
            runtimePrevious.put(id, cached);
            if (candidate != null) {
                validateDeviceContext(id, candidate.getDeviceInfo(), candidate.getConnectionConfig(), candidate.getDataPoints());
                revisions.put(id, item.revision());
            }
            if (!sameDeviceConfiguration(previous.get(id), candidate)
                    || !sameDeviceConfiguration(cached, candidate) || localRecoveryRequired.contains(id)) {
                candidates.put(id, candidate);
            } else {
                // 相同业务配置仍需修复已被部分发布污染的缓存，并吸收权威修订。
                Long previousRevision = localConfigurationRevisions.get(id);
                restoreDeviceContext(id, candidate);
                if (item == null) localConfigurationRevisions.remove(id);
                else localConfigurationRevisions.put(id, item.revision());
                if (!Objects.equals(previousRevision, item == null ? null : item.revision())) {
                    deviceConfigVersions.put(id, nextConfigVersion());
                } else if (versions.getOrDefault(id, 0L) > 0) {
                    deviceConfigVersions.put(id, versions.get(id));
                }
            }
        }
        if (!candidates.isEmpty()) installLocalAggregates(candidates, revisions, runtimePrevious, versions, events, ConfigUpdateType.ALL, source);
        localRecoveryRequired.removeAll(ids);
    }

    private Map<String, DeviceContext> oneLocalAggregate(String id, DeviceContext context) {
        Map<String, DeviceContext> result = new LinkedHashMap<>();
        result.put(id, context);
        return result;
    }

    private DeviceConfigCommitResult saveSqliteLocalDevice(DeviceInfo input, DeviceConnection connection,
                                                           List<DataPoint> points, boolean overwrite, Long baseVersion,
                                                           Long baseRevision) {
        String id = input.getDeviceId();
        return commitLocalAggregates(() -> {
            if (baseRevision == null) requireBaseVersion(id, baseVersion);
            validateLocalDevice(input, id);
            validateDeviceIdentity(id, connection.getDeviceId());
            validatePointIdentities(id, points, true);
            validateLocalPoints(id, points == null ? List.of() : points);
            DeviceContext existing = localCurrentContext(id);
            if (existing != null && !overwrite) throw new IllegalArgumentException("本地设备已存在: " + id);
            DeviceContext snapshot = DeviceContext.of(input, connection, points);
            DeviceInfo device = snapshot.getDeviceInfo();
            DeviceConnection candidateConnection = snapshot.copyConnectionConfig();
            List<DataPoint> candidatePoints = new ArrayList<>(snapshot.copyDataPoints());
            normalizeLocalDevice(device, existing == null ? null : existing.getDeviceInfo());
            normalizeLocalConnection(device, candidateConnection);
            normalizeLocalPoints(device, candidatePoints);
            return oneLocalAggregate(id, DeviceContext.of(device, candidateConnection, candidatePoints));
        }, ConfigUpdateType.LOCAL, "local-save", revisionFor(id, baseRevision)).get(id);
    }

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

    public ConfigManager(ConfigSyncService configSyncService,
                         ApplicationEventPublisher eventPublisher,
                         FieldUniquenessValidator fieldUniquenessValidator,
                         LocalDeviceConfigStore localDeviceConfigStore,
                         ObjectProvider<ProtocolConnectionValidator> protocolConnectionValidatorProvider,
                         ObjectProvider<ProtocolPointValidator> pointValidatorProvider) {
        this(configSyncService, eventPublisher, fieldUniquenessValidator, localDeviceConfigStore,
                protocolConnectionValidatorProvider, pointValidatorProvider, null);
    }

    /** 权威配置来源在构造时选定，属性变动不能在运行中热切换来源。 */
    @Autowired
    public ConfigManager(ConfigSyncService configSyncService,
                         ApplicationEventPublisher eventPublisher,
                         FieldUniquenessValidator fieldUniquenessValidator,
                         LocalDeviceConfigStore localDeviceConfigStore,
                         ObjectProvider<ProtocolConnectionValidator> protocolConnectionValidatorProvider,
                         ObjectProvider<ProtocolPointValidator> pointValidatorProvider,
                         ObjectProvider<WangbinLocalConfigurationPersistence> persistenceProvider) {
        this.configSyncService = configSyncService;
        this.eventPublisher = eventPublisher;
        this.fieldUniquenessValidator = fieldUniquenessValidator;
        this.localDeviceConfigStore = localDeviceConfigStore;
        this.localConfigurationPersistence = persistenceProvider == null ? null : persistenceProvider.getIfAvailable();
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
        if (!isSqlitePersistenceEnabled()) loadAllConfig();
        startConfigSync();
        log.info("配置管理器初始化完成");
    }

    /**
     * 在 ConfigManager 锁外按设备 ID读取 SQLite 启动配置；远端设备返回 null。
     * 已确认属于本地设备但数据库缺失、读取失败或校验失败时必须封闭启动。
     */
    public LocalStartConfiguration loadLocalStartConfiguration(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (!isSqlitePersistenceEnabled()) return null;

        LocalRuntimeState state = snapshotLocalRuntimeState(deviceId);
        if (state.recoveryRequired()) throw localConfigurationUnavailable(deviceId);

        Optional<StoredDeviceConfiguration> stored;
        try {
            // SQLite 读取和配置转换故意不持有 ConfigManager 锁，停止操作可以同时使启动代次失效。
            stored = localConfigurationPersistence.findDevice(deviceId);
            if (stored == null) throw new IllegalStateException("SQLite设备读取结果为空");
        } catch (RuntimeException readFailure) {
            if (state.cachedRemote() && !state.local()) return null;
            throw localConfigurationUnavailable(deviceId);
        }
        if (stored.isEmpty()) {
            if (state.local()) throw localConfigurationUnavailable(deviceId);
            return null;
        }

        try {
            StoredDeviceConfiguration item = stored.get();
            DeviceConfiguration configuration = item.configuration();
            if (!deviceId.equals(configuration.deviceId())) {
                throw new IllegalArgumentException("SQLite设备身份不一致");
            }
            DeviceContext candidate = localConfigurationPersistence.toRuntime(configuration);
            if (candidate == null || !deviceId.equals(candidate.getDeviceId())
                    || !isLocalTemporaryDeviceInfo(candidate.getDeviceInfo())) {
                throw new IllegalArgumentException("SQLite本地设备来源不合法");
            }
            validateRestoredDeviceContext(deviceId, candidate);
            return new LocalStartConfiguration(candidate, item.revision());
        } catch (RuntimeException invalidConfiguration) {
            // 不传播协议校验器或 codec 的原始消息，避免把敏感配置带出启动失败路径。
            throw localConfigurationUnavailable(deviceId);
        }
    }

    /**
     * 在设备生命周期锁内调用；ConfigManager 写锁下原子安装四个运行缓存、持久修订和 JVM 版本。
     * 仅接受不早于当前持久修订的候选，避免 SQLite 读取期间的旧候选覆盖同进程并发保存。
     */
    public boolean installLocalStartConfiguration(String deviceId, long generation,
                                                  LocalStartConfiguration candidate) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (candidate == null || !isSqlitePersistenceEnabled()) return true;

        lock.writeLock().lock();
        try {
            requireConfigurationReadable(deviceId);
            LocalRuntimeGeneration owner = localRuntimeGenerations.get(deviceId);
            if (owner != null && (generation < owner.generation()
                    || generation == owner.generation() && !owner.active())) {
                return false;
            }
            if (candidate.persistedRevision() < 1) {
                throw localConfigurationUnavailable(deviceId);
            }
            Long currentRevision = localConfigurationRevisions.get(deviceId);
            if (currentRevision != null && candidate.persistedRevision() < currentRevision) {
                return false;
            }
            DeviceContext loaded = candidate.context();
            if (loaded == null) throw localConfigurationUnavailable(deviceId);
            DeviceContext installed = DeviceContext.of(loaded.getDeviceInfo(), loaded.getConnectionConfig(),
                    loaded.getDataPoints());
            if (!deviceId.equals(installed.getDeviceId())
                    || !isLocalTemporaryDeviceInfo(installed.getDeviceInfo())) {
                throw localConfigurationUnavailable(deviceId);
            }
            validateRestoredDeviceContext(deviceId, installed);

            DeviceContext previous = deviceContextCache.get(deviceId);
            boolean sameLocalConfiguration = previous != null
                    && isLocalTemporaryDeviceInfo(previous.getDeviceInfo())
                    && sameDeviceConfiguration(previous, installed);
            Long previousVersion = deviceConfigVersions.get(deviceId);
            boolean revisionChanged = !Objects.equals(currentRevision, candidate.persistedRevision());

            // 写锁覆盖四个缓存及两个版本字段，读线程不会观察到半安装状态。
            restoreDeviceContext(deviceId, installed);
            localConfigurationRevisions.put(deviceId, candidate.persistedRevision());
            if (previousVersion == null || !sameLocalConfiguration || revisionChanged) {
                deviceConfigVersions.put(deviceId, nextConfigVersion());
            }
            localRuntimeGenerations.put(deviceId, new LocalRuntimeGeneration(generation, true));
            if (previousVersion == null || !sameLocalConfiguration || revisionChanged) markConfigMutation();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 只在当前运行代次仍归属本地配置时释放运行缓存；不删除 SQLite、不发布配置事件。 */
    public void releaseLocalRuntimeConfiguration(String deviceId, long generation) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (!isSqlitePersistenceEnabled()) return;

        lock.writeLock().lock();
        try {
            LocalRuntimeGeneration owner = localRuntimeGenerations.get(deviceId);
            if (owner == null || !owner.active() || owner.generation() != generation) return;
            clearLocalRuntimeCaches(deviceId);
            localRuntimeGenerations.put(deviceId, new LocalRuntimeGeneration(generation, false));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 完整 STOP 成功后释放本地运行缓存；兼容 init 预加载但尚无代次归属的情况。 */
    public void releaseStoppedLocalRuntimeConfiguration(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (!isSqlitePersistenceEnabled()) return;

        lock.writeLock().lock();
        try {
            LocalRuntimeGeneration owner = localRuntimeGenerations.get(deviceId);
            if (isLocalRuntimeConfigurationCached(deviceId)) clearLocalRuntimeCaches(deviceId);
            if (owner != null) {
                localRuntimeGenerations.put(deviceId, new LocalRuntimeGeneration(owner.generation(), false));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 调用方持有配置写锁；只清理本地运行态，保留 SQLite CAS 修订和故障封闭标记。 */
    private boolean clearLocalRuntimeCaches(String deviceId) {
        boolean changed = deviceCache.remove(deviceId) != null;
        changed |= pointCache.remove(deviceId) != null;
        changed |= connectionCache.remove(deviceId) != null;
        changed |= deviceContextCache.remove(deviceId) != null;
        changed |= deviceConfigVersions.remove(deviceId) != null;
        if (changed) markConfigMutation();
        return changed;
    }

    private boolean isLocalRuntimeConfigurationCached(String deviceId) {
        DeviceInfo cached = deviceCache.get(deviceId);
        return isLocalTemporaryDeviceInfo(cached) || localConfigurationRevisions.containsKey(deviceId)
                && (deviceContextCache.containsKey(deviceId) || pointCache.containsKey(deviceId)
                || connectionCache.containsKey(deviceId) || deviceConfigVersions.containsKey(deviceId));
    }

    /**
     * 从本地快照恢复非远端托管设备。
     */
    private void restorePersistedLocalTemporaryContexts() {
        if (localConfigurationPersistence != null) {
            restoreSqliteLocalConfigurations();
            return;
        }
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

    /** SQLite恢复全部候选先校验后发布，失败不退回JSON、不创建空库、不发启停事件。 */
    private void restoreSqliteLocalConfigurations() {
        lock.writeLock().lock();
        try {
            List<StoredDeviceConfiguration> stored = localConfigurationPersistence.restore();
            Map<String, DeviceContext> candidates = new LinkedHashMap<>();
            Map<String, Long> revisions = new HashMap<>();
            Set<String> pointIds = new HashSet<>();
            for (StoredDeviceConfiguration item : stored) {
                String deviceId = item.configuration().deviceId();
                DeviceContext context = localConfigurationPersistence.toRuntime(item.configuration());
                if (!Objects.equals(deviceId, context.getDeviceId())
                        || !isLocalTemporaryDeviceInfo(context.getDeviceInfo())
                        || candidates.putIfAbsent(deviceId, context) != null) {
                    throw new IllegalStateException("SQLite恢复失败，字段=deviceId/configSource");
                }
                for (DataPoint point : context.getDataPoints()) {
                    if (!StringUtils.hasText(point.getPointId()) || !pointIds.add(point.getPointId())) {
                        throw new IllegalStateException("SQLite恢复失败，字段=pointId，设备=" + deviceId);
                    }
                }
                try {
                    validateRestoredDeviceContext(deviceId, context);
                } catch (RuntimeException exception) {
                    // 协议校验器的原异常可能包含连接参数，不传播其消息或cause。
                    throw new IllegalStateException("SQLite恢复校验失败，设备=" + deviceId + "，字段=device/connection/points");
                }
                revisions.put(deviceId, item.revision());
            }
            for (var entry : candidates.entrySet()) {
                restoreDeviceContext(entry.getKey(), entry.getValue());
                deviceConfigVersions.put(entry.getKey(), nextConfigVersion());
            }
            localConfigurationRevisions.clear();
            localConfigurationRevisions.putAll(revisions);
            log.info("SQLite本地配置恢复完成，设备数量={}", candidates.size());
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
        LocalAuthoritySnapshot localSnapshot;
        List<DeviceContext> localTemporaryContexts;
        lock.readLock().lock();
        try {
            previousContexts = new HashMap<>(deviceContextCache);
            previousVersions = new HashMap<>(deviceConfigVersions);
            localSnapshot = snapshotLocalAuthority();
            localTemporaryContexts = localSnapshot.contexts();
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
                // 在安装前重读同一权威全集，缓存 clear 不能把本地身份让给远端。
                localSnapshot = snapshotLocalAuthority();
                for (String localId : localIds) {
                    candidateDevices.remove(localId);
                    candidateConnections.remove(localId);
                    candidatePoints.remove(localId);
                    candidateContexts.remove(localId);
                }
                mergeLocalTemporaryCandidates(candidateDevices, candidateConnections, candidatePoints, candidateContexts,
                        localSnapshot.contexts());
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
                if (isSqlitePersistenceEnabled()) {
                    localConfigurationRevisions.clear();
                    localConfigurationRevisions.putAll(localSnapshot.revisions());
                }
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
            publishConfigurationEvents(committedEvents, false);
            log.info("配置加载完成，共加载 {} 个设备配置", candidateContexts.size());
        } catch (RuntimeException exception) {
            log.error("full configuration refresh aborted, live cache preserved, reason={}", exception.getMessage(), exception);
        }
    }

    private record LocalAuthoritySnapshot(List<DeviceContext> contexts, Map<String, Long> revisions) {}

    /** 只读权威全集；不安装缓存、不重放迁移、不调用远端。调用方持有配置锁。 */
    private LocalAuthoritySnapshot snapshotLocalAuthority() {
        if (!isSqlitePersistenceEnabled()) return new LocalAuthoritySnapshot(snapshotLocalTemporaryContexts(), Map.of());
        List<StoredDeviceConfiguration> stored = localConfigurationPersistence.transaction(writer -> writer.listDevices());
        List<DeviceContext> contexts = new ArrayList<>();
        Map<String, Long> revisions = new LinkedHashMap<>();
        Set<String> pointIds = new HashSet<>();
        for (StoredDeviceConfiguration item : stored) {
            String id = item.configuration().deviceId();
            DeviceContext context = localConfigurationPersistence.toRuntime(item.configuration());
            if (!id.equals(context.getDeviceId()) || !isLocalTemporaryDeviceInfo(context.getDeviceInfo())
                    || revisions.putIfAbsent(id, item.revision()) != null) {
                throw new WangbinConfigurationPersistenceException(Outcome.CONFIGURATION_UNAVAILABLE, List.of(id));
            }
            validateDeviceContext(id, context.getDeviceInfo(), context.getConnectionConfig(), context.getDataPoints());
            for (DataPoint point : context.getDataPoints()) {
                if (!pointIds.add(point.getPointId())) throw new WangbinConfigurationPersistenceException(
                        Outcome.CONFIGURATION_UNAVAILABLE, List.of(id));
            }
            contexts.add(context);
        }
        return new LocalAuthoritySnapshot(contexts, revisions);
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
        if (localConfigurationPersistence != null && isLocalTemporaryDeviceInfo(left.getDeviceInfo())
                && isLocalTemporaryDeviceInfo(right.getDeviceInfo())) {
            return localConfigurationPersistence.equivalent(left, right);
        }
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
            requireConfigurationReadable(deviceId);
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
            return deviceCache.entrySet().stream().filter(entry -> !localRecoveryRequired.contains(entry.getKey()))
                    .map(Map.Entry::getValue).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 仅供配置管理页面使用；本地设备以 SQLite 基础记录为准，远端沿用运行缓存。 */
    public List<DeviceInfo> getConfigurationDevices() {
        if (!isSqlitePersistenceEnabled()) return getAllDevices();
        // SQLite 查询不占用运行配置锁；锁内只合并短暂的恢复保护和远端缓存快照。
        List<DeviceInfo> localDevices = localConfigurationPersistence.listDeviceSummaries();
        lock.readLock().lock();
        try {
            Map<String, DeviceInfo> devices = new LinkedHashMap<>();
            for (DeviceInfo local : localDevices) {
                if (localRecoveryRequired.contains(local.getDeviceId())) continue;
                devices.put(local.getDeviceId(), local);
            }
            for (DeviceInfo cached : deviceCache.values()) {
                if (!isLocalTemporaryDeviceInfo(cached)
                        && !localRecoveryRequired.contains(cached.getDeviceId())) {
                    devices.putIfAbsent(cached.getDeviceId(), cached);
                }
            }
            return List.copyOf(devices.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 配置查询优先取持久本地基础信息，不把远端配置误判为本地。 */
    public DeviceInfo getConfigurationDevice(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (!isSqlitePersistenceEnabled()) return getDevice(deviceId);
        boolean readable;
        DeviceInfo cached;
        lock.readLock().lock();
        try {
            requireConfigurationReadable(deviceId);
            readable = true;
            cached = deviceCache.get(deviceId);
        } finally {
            lock.readLock().unlock();
        }
        DeviceInfo local = localConfigurationPersistence.findDeviceSummary(deviceId).orElse(null);
        if (local != null) return local;
        return readable && !isLocalTemporaryDeviceInfo(cached) ? cached : null;
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
            requireConfigurationReadable(deviceId);
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
            requireConfigurationReadable(deviceId);
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
            requireConfigurationReadable(deviceId);
            DeviceContext copy = context == null ? null : DeviceContext.of(
                    context.getDeviceInfo(), context.getConnectionConfig(), context.getDataPoints());
            return new DeviceConfigurationSnapshot(copy, deviceConfigVersions.getOrDefault(deviceId, 0L));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 设备编辑按 ID 读取权威本地聚合，同时返回独立的持久修订和 JVM 运行版本。 */
    public DeviceConfigurationSnapshot getConfigurationDeviceSnapshot(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (!isSqlitePersistenceEnabled()) return getDeviceConfigurationSnapshot(deviceId);
        DeviceContext cachedRemote;
        long configVersion;
        lock.readLock().lock();
        try {
            requireConfigurationReadable(deviceId);
            DeviceContext cached = deviceContextCache.get(deviceId);
            cachedRemote = cached == null || isLocalTemporaryDeviceInfo(cached.getDeviceInfo()) ? null
                    : DeviceContext.of(cached.getDeviceInfo(), cached.getConnectionConfig(), cached.getDataPoints());
            configVersion = deviceConfigVersions.getOrDefault(deviceId, 0L);
        } finally {
            lock.readLock().unlock();
        }
        StoredDeviceConfiguration stored;
        try {
            stored = localConfigurationPersistence.findDevice(deviceId).orElse(null);
        } catch (RuntimeException readFailure) {
            throw new WangbinConfigurationPersistenceException(Outcome.CONFIGURATION_UNAVAILABLE, List.of(deviceId));
        }
        DeviceContext local = stored == null ? null : localConfigurationPersistence.toRuntime(stored.configuration());
        return new DeviceConfigurationSnapshot(local != null ? local : cachedRemote, configVersion,
                stored == null ? 0L : stored.revision());
    }

    /** 只为配置页面定向读取点位；运行采集仍调用 getDataPoints。 */
    public DeviceContext getConfigurationPoints(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (!isSqlitePersistenceEnabled()) return getDeviceConfigurationSnapshot(deviceId).context();
        DeviceContext cachedRemote;
        lock.readLock().lock();
        try {
            requireConfigurationReadable(deviceId);
            DeviceContext cached = deviceContextCache.get(deviceId);
            cachedRemote = cached == null || isLocalTemporaryDeviceInfo(cached.getDeviceInfo()) ? null
                    : DeviceContext.of(cached.getDeviceInfo(), cached.getConnectionConfig(), cached.getDataPoints());
        } finally {
            lock.readLock().unlock();
        }
        return localConfigurationPersistence.findDevicePointsContext(deviceId).orElse(cachedRemote);
    }

    /** 只为配置页面定向读取连接；运行采集仍调用 getConnectionConfig。 */
    public DeviceContext getConfigurationConnection(String deviceId) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (!isSqlitePersistenceEnabled()) return getDeviceConfigurationSnapshot(deviceId).context();
        DeviceContext cachedRemote;
        lock.readLock().lock();
        try {
            requireConfigurationReadable(deviceId);
            DeviceContext cached = deviceContextCache.get(deviceId);
            cachedRemote = cached == null || isLocalTemporaryDeviceInfo(cached.getDeviceInfo()) ? null
                    : DeviceContext.of(cached.getDeviceInfo(), cached.getConnectionConfig(), cached.getDataPoints());
        } finally {
            lock.readLock().unlock();
        }
        return localConfigurationPersistence.findDeviceConnectionContext(deviceId).orElse(cachedRemote);
    }

    public record DeviceConfigurationSnapshot(DeviceContext context, long configVersion, long persistedRevision) {
        public DeviceConfigurationSnapshot(DeviceContext context, long configVersion) {
            this(context, configVersion, 0L);
        }
    }

    /** SQLite 单设备启动快照；只在启动准备阶段传递，不构成第二份配置缓存。 */
    public record LocalStartConfiguration(DeviceContext context, long persistedRevision) {
    }

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
            if (localRecoveryRequired.contains(deviceId)
                    || deviceConfigVersions.getOrDefault(deviceId, 0L) != expectedVersion) return false;
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
            return deviceContextCache.entrySet().stream().filter(entry -> !localRecoveryRequired.contains(entry.getKey()))
                    .map(Map.Entry::getValue).toList();
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
            requireConfigurationReadable(deviceId);
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
            requireConfigurationReadable(deviceId);
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
            requireConfigurationReadable(deviceId);
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
            requireConfigurationReadable(deviceId);
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
        return updateDeviceConfig(device, null);
    }

    public boolean updateDeviceConfig(DeviceInfo device, Long baseRevision) {
        Objects.requireNonNull(device, "设备信息不能为空");
        if (isSqlitePersistenceEnabled()) {
            commitLocalAggregates(() -> {
                DeviceContext current = localCurrentContext(device.getDeviceId());
                if (current == null) throw new IllegalArgumentException("设备不存在");
                return oneLocalAggregate(device.getDeviceId(), DeviceContext.of(device,
                        current.getConnectionConfig(), current.getDataPoints()));
            }, ConfigUpdateType.DEVICE, "device-edit", revisionFor(device.getDeviceId(), baseRevision));
            return true;
        }
        return updateDeviceConfig(device, false);
    }

    private boolean updateDeviceConfig(DeviceInfo device, boolean remoteReload) {
        Objects.requireNonNull(device, "设备信息不能为空");
        if (isSqlitePersistenceEnabled() && !remoteReload) {
            DeviceInfo inputDevice = device;
            commitLocalAggregates(() -> {
                DeviceContext current = localCurrentContext(inputDevice.getDeviceId());
                if (current == null) throw new IllegalArgumentException("设备不存在");
                return oneLocalAggregate(inputDevice.getDeviceId(), DeviceContext.of(inputDevice, current.getConnectionConfig(), current.getDataPoints()));
            }, ConfigUpdateType.DEVICE, "device-edit");
            return true;
        }
        ConfigUpdateEvent event;

        try {
            lock.writeLock().lock();

            String deviceId = device.getDeviceId();
            if (deviceId == null || deviceId.trim().isEmpty()) {
                log.error("设备ID为空，无法更新配置");
                return false;
            }

            if (remoteReload && isLocalTemporaryDevice(deviceId)) return false;
            DeviceInfo oldDevice = deviceCache.get(deviceId);
            device = DeviceContext.of(device, null, List.of()).getDeviceInfo();
            if (!remoteReload) sensitiveConfigSanitizer.restoreMaskedValues(device, oldDevice);
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
        return updateDataPoints(deviceId, points, null);
    }

    public boolean updateDataPoints(String deviceId, List<DataPoint> points, Long baseRevision) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        Objects.requireNonNull(points, "数据点列表不能为空");
        if (isSqlitePersistenceEnabled()) {
            commitLocalAggregates(() -> {
                DeviceContext current = localCurrentContext(deviceId);
                if (current == null) throw new IllegalArgumentException("设备不存在");
                return oneLocalAggregate(deviceId, DeviceContext.of(current.getDeviceInfo(),
                        current.getConnectionConfig(), points));
            }, ConfigUpdateType.POINTS, "points-edit", revisionFor(deviceId, baseRevision));
            return true;
        }
        return updateDataPoints(deviceId, points, true);
    }

    /** 远端历史快照由运行期逐点隔离；新写入仍进行严格协议校验。 */
    private boolean updateDataPoints(String deviceId, List<DataPoint> points, boolean validateNewPoints) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        Objects.requireNonNull(points, "数据点列表不能为空");
        if (isSqlitePersistenceEnabled() && validateNewPoints) {
            commitLocalAggregates(() -> {
                DeviceContext current = localCurrentContext(deviceId);
                if (current == null) throw new IllegalArgumentException("设备不存在");
                return oneLocalAggregate(deviceId, DeviceContext.of(current.getDeviceInfo(), current.getConnectionConfig(), points));
            }, ConfigUpdateType.POINTS, "points-edit");
            return true;
        }
        ConfigUpdateEvent event;

        try {
            lock.writeLock().lock();

            if (!deviceCache.containsKey(deviceId)) {
                log.warn("设备不存在，无法更新数据点: {}", deviceId);
                return false;
            }
            if (!validateNewPoints && isLocalTemporaryDevice(deviceId)) return false;
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
        return updateConnectionConfig(deviceId, connection, null);
    }

    public boolean updateConnectionConfig(String deviceId, DeviceConnection connection, Long baseRevision) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (isSqlitePersistenceEnabled()) {
            commitLocalAggregates(() -> {
                DeviceContext current = localCurrentContext(deviceId);
                if (current == null) throw new IllegalArgumentException("设备不存在");
                return oneLocalAggregate(deviceId, DeviceContext.of(current.getDeviceInfo(),
                        connection, current.getDataPoints()));
            }, ConfigUpdateType.CONNECTION, "connection-edit", revisionFor(deviceId, baseRevision));
            return true;
        }
        return updateConnectionConfig(deviceId, connection, false);
    }

    private boolean updateConnectionConfig(String deviceId, DeviceConnection connection, boolean remoteReload) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (isSqlitePersistenceEnabled() && !remoteReload) {
            DeviceConnection inputConnection = connection;
            commitLocalAggregates(() -> {
                DeviceContext current = localCurrentContext(deviceId);
                if (current == null) throw new IllegalArgumentException("设备不存在");
                return oneLocalAggregate(deviceId, DeviceContext.of(current.getDeviceInfo(), inputConnection, current.getDataPoints()));
            }, ConfigUpdateType.CONNECTION, "connection-edit");
            return true;
        }
        ConfigUpdateEvent event;

        try {
            lock.writeLock().lock();

            if (!deviceCache.containsKey(deviceId)) {
                log.warn("设备不存在，无法更新连接配置: {}", deviceId);
                return false;
            }
            if (remoteReload && isLocalTemporaryDevice(deviceId)) return false;
            long previousVersion = deviceConfigVersions.getOrDefault(deviceId, 0L);
            DeviceContext previousContext = deviceContextCache.get(deviceId);

            if (connection != null) {
                validateDeviceIdentity(deviceId, connection.getDeviceId());
                connection = DeviceContext.of(null, connection, List.of()).copyConnectionConfig();
                connection.setDeviceId(deviceId);
                if (!remoteReload) sensitiveConfigSanitizer.restoreMaskedValues(connection, connectionCache.get(deviceId));
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
        publishConfigurationEvents(List.of(event), true);
    }

    /** 逐设备发布，单个监听故障不阻止其余设备通知；失败不回滚已提交配置。 */
    private void publishConfigurationEvents(List<ConfigUpdateEvent> events, boolean committed) {
        Set<String> failedIds = new LinkedHashSet<>();
        for (ConfigUpdateEvent event : events) {
            try {
                eventPublisher.publishEvent(event);
            } catch (RuntimeException exception) {
                log.error("配置变更应用失败，设备={}", event.getDeviceId(), exception);
                failedIds.add(event.getDeviceId());
                if (isSqlitePersistenceEnabled()) {
                    lock.writeLock().lock();
                    try {
                        // 复用目标封闭/显式 refresh 对账路径，不重试数据库写入。
                        if (Objects.equals(deviceConfigVersions.get(event.getDeviceId()), event.getConfigVersion())) {
                            localRecoveryRequired.add(event.getDeviceId());
                            markConfigMutation();
                        }
                    } finally {
                        lock.writeLock().unlock();
                    }
                }
            } finally {
                if (ConfigUpdateType.LOCAL_DELETE.getValue().equals(event.getConfigType()) && event.getConfigVersion() != null) {
                    releaseDeletedConfigurationVersion(event.getDeviceId(), event.getConfigVersion());
                }
            }
        }
        if (!failedIds.isEmpty()) throw new WangbinConfigurationPersistenceException(
                committed ? Outcome.COMMITTED_APPLY_FAILED : Outcome.CONFIGURATION_UNAVAILABLE, List.copyOf(failedIds));
    }

    /** 单设备原子提交结果。 */
    public record DeviceConfigCommitResult(String deviceId, long previousVersion, long configVersion,
                                           int pointCount, long persistedRevision) {
        public DeviceConfigCommitResult(String deviceId, long previousVersion, long configVersion, int pointCount) {
            this(deviceId, previousVersion, configVersion, pointCount, 0L);
        }
    }

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
        validateLocalRecoverability(candidate);
    }

    /** 已由 SQLite codec 解码的配置只做运行期语义校验，不再次要求测试桥接或读取源执行回编码。 */
    private void validateRestoredDeviceContext(String deviceId, DeviceContext context) {
        if (context == null || context.getDeviceInfo() == null) {
            throw new IllegalArgumentException("恢复设备配置不能为空");
        }
        validateDeviceIdentity(deviceId, context.getDeviceInfo().getDeviceId());
        DeviceConnection connection = context.copyConnectionConfig();
        if (connection != null) {
            validateDeviceIdentity(deviceId, connection.getDeviceId());
            protocolConnectionValidator.validate(context.getDeviceInfo(), connection);
        }
        List<DataPoint> points = context.copyDataPoints();
        validatePointIdentities(deviceId, points, true);
        points.forEach(point -> point.setDeviceId(deviceId));
        normalizeDataPointCollectionPolicy(context.getDeviceInfo(), points);
        if (fieldUniquenessValidator != null) fieldUniquenessValidator.validate(deviceId, points);
        validateProtocolPoints(context.getDeviceInfo(), points);
    }


    /**
     * 使用设备级 CAS 原子替换完整配置。
     */
    public DeviceConfigCommitResult replaceDeviceContextAtomically(String deviceId,
                                                                     DeviceInfo device,
                                                                     DeviceConnection connection,
                                                                     List<DataPoint> points,
                                                                     long expectedVersion) {
        return replaceDeviceContextAtomically(deviceId, device, connection, points, expectedVersion, null);
    }

    public DeviceConfigCommitResult replaceDeviceContextAtomically(String deviceId,
                                                                     DeviceInfo device,
                                                                     DeviceConnection connection,
                                                                     List<DataPoint> points,
                                                                     long expectedVersion, Long baseRevision) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (isSqlitePersistenceEnabled()) {
            return commitLocalAggregates(() -> {
                requireConfigurationReadable(deviceId);
                long current = deviceConfigVersions.getOrDefault(deviceId, 0L);
                if (baseRevision == null && current != expectedVersion) {
                    throw new ConfigVersionConflictException(deviceId, expectedVersion, current);
                }
                if (device == null) throw new IllegalArgumentException("设备信息不能为空");
                validateDeviceIdentity(deviceId, device.getDeviceId());
                DeviceContext copy = DeviceContext.of(device, connection, points);
                copy.getDeviceInfo().setDeviceId(deviceId);
                return oneLocalAggregate(deviceId, copy);
            }, ConfigUpdateType.ALL, "config-bundle", revisionFor(deviceId, baseRevision)).get(deviceId);
        }
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
            restoreMaskedConfiguration(candidate, contextBackup.get(deviceId));
            candidateDevice = candidate.getDeviceInfo();
            candidateConnection = candidate.getConnectionConfig();
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
        return replaceDeviceContextsAtomically(contexts, Map.of());
    }

    public boolean replaceDeviceContextsAtomically(List<DeviceContext> contexts,
                                                   Map<String, Long> baseRevisions) {
        if (CollectionUtils.isEmpty(contexts)) return false;
        if (isSqlitePersistenceEnabled()) {
            List<DeviceContext> inputContexts = contexts;
            commitLocalAggregates(() -> {
                Map<String, DeviceContext> candidates = new LinkedHashMap<>();
                for (DeviceContext context : inputContexts) {
                    if (context == null || context.getDeviceInfo() == null || !StringUtils.hasText(context.getDeviceId())) {
                        throw new IllegalArgumentException("导入设备及设备ID不能为空");
                    }
                    if (candidates.putIfAbsent(context.getDeviceId(), DeviceContext.of(context.getDeviceInfo(),
                            context.getConnectionConfig(), context.getDataPoints())) != null) {
                        throw new IllegalArgumentException("导入设备ID重复");
                    }
                }
                return candidates;
            }, ConfigUpdateType.ALL, "config-import", baseRevisions);
            return true;
        }
        List<ConfigUpdateEvent> events = new ArrayList<>();
        lock.writeLock().lock();
        try {
            contexts = contexts.stream().map(context -> DeviceContext.of(context.getDeviceInfo(),
                    context.getConnectionConfig(), context.getDataPoints())).toList();
            Set<String> deviceIds = new HashSet<>();
            Map<String, List<DataPoint>> normalizedPoints = new HashMap<>();
            for (DeviceContext context : contexts) {
                restoreMaskedConfiguration(context, deviceContextCache.get(context.getDeviceId()));
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
        publishConfigurationEvents(events, true);
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
        return saveLocalDeviceConfigWithResult(device, connection, points, overwrite, null);
    }

    /** 可选编辑 CAS；旧重载保留覆盖语义，比较与掩码恢复均在同一写锁内。 */
    public DeviceConfigCommitResult saveLocalDeviceConfigWithResult(DeviceInfo device,
                                                                   DeviceConnection connection,
                                                                   List<DataPoint> points,
                                                                   boolean overwrite, Long baseVersion) {
        return saveLocalDeviceConfigWithResult(device, connection, points, overwrite, baseVersion, null);
    }

    public DeviceConfigCommitResult saveLocalDeviceConfigWithResult(DeviceInfo device,
                                                                   DeviceConnection connection,
                                                                   List<DataPoint> points,
                                                                   boolean overwrite, Long baseVersion, Long baseRevision) {
        Objects.requireNonNull(device, "device config is required");
        Objects.requireNonNull(connection, "connection config is required");
        if (isSqlitePersistenceEnabled()) return saveSqliteLocalDevice(device, connection, points, overwrite,
                baseVersion, baseRevision);

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
            requireBaseVersion(deviceId, baseVersion);
            DeviceInfo existing = deviceCache.get(deviceId);
            DeviceContext previousContext = deviceContextCache.get(deviceId);
            Long previousVersion = deviceConfigVersions.get(deviceId);
            sensitiveConfigSanitizer.restoreMaskedValues(device, existing);
            sensitiveConfigSanitizer.restoreMaskedValues(connection, previousContext == null ? null : previousContext.getConnectionConfig());
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
        return deleteLocalDeviceConfig(deviceId, null);
    }

    public boolean deleteLocalDeviceConfig(String deviceId, Long baseRevision) {
        Objects.requireNonNull(deviceId, "设备ID不能为空");
        if (isSqlitePersistenceEnabled()) {
            return !commitLocalAggregates(() -> {
                if (localCurrentContext(deviceId) == null && (baseRevision == null || baseRevision == 0L)) {
                    return Map.of();
                }
                return oneLocalAggregate(deviceId, null);
            }, ConfigUpdateType.LOCAL_DELETE, "local-delete", revisionFor(deviceId, baseRevision)).isEmpty();
        }
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
        boolean cachedLocal;
        boolean recovery;
        boolean sqlite;
        lock.readLock().lock();
        try {
            cachedLocal = isLocalTemporaryDeviceInfo(deviceCache.get(deviceId));
            recovery = localRecoveryRequired.contains(deviceId);
            sqlite = isSqlitePersistenceEnabled();
        } finally {
            lock.readLock().unlock();
        }
        if (cachedLocal || !sqlite || recovery) return cachedLocal || recovery;
        // 存在性判断只做轻量单设备查询，且不在运行配置锁内读取 SQLite 聚合。
        return localConfigurationPersistence.existsDevice(deviceId);
    }

    /**
     * 获取所有设备ID列表
     *
     * @return 设备ID列表
     */
    public List<String> getAllDeviceIds() {
        lock.readLock().lock();
        try {
            return deviceCache.keySet().stream().filter(id -> !localRecoveryRequired.contains(id)).toList();
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
            requireConfigurationReadable(deviceId);
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
        if (isSqlitePersistenceEnabled() && (isLocalTemporaryDevice(deviceId) || localConfigurationRevisionsContains(deviceId))) {
            List<ConfigUpdateEvent> events = new ArrayList<>();
            lock.writeLock().lock();
            try {
                Map<String, DeviceContext> previous = oneLocalAggregate(deviceId, deviceContextCache.get(deviceId));
                Map<String, Long> versions = Map.of(deviceId, deviceConfigVersions.getOrDefault(deviceId, 0L));
                try {
                    reconcileLocalAggregates(List.of(deviceId), previous, versions, events, "local-refresh");
                } catch (RuntimeException failure) {
                    localRecoveryRequired.add(deviceId);
                    throw new WangbinConfigurationPersistenceException(Outcome.CONFIGURATION_UNAVAILABLE, List.of(deviceId));
                }
                return isCachedDeviceConfigAvailable(deviceId);
            } finally {
                lock.writeLock().unlock();
                publishConfigurationEvents(events, false);
            }
        }
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
    private boolean localConfigurationRevisionsContains(String deviceId) {
        lock.readLock().lock();
        try {
            return localConfigurationRevisions.containsKey(deviceId) || localRecoveryRequired.contains(deviceId);
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean isCachedDeviceConfigAvailable(String deviceId) {
        lock.readLock().lock();
        try {
            DeviceInfo device = deviceCache.get(deviceId);
            requireConfigurationReadable(deviceId);
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
        if (localConfigurationPersistence != null) {
            throw new IllegalStateException("SQLite权威模式禁止旧JSON写入，请通过本地聚合事务提交配置");
        }
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
        if (CollectionUtils.isEmpty(points) && !isSqlitePersistenceEnabled()) {
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
        // 先注册监听；SQLite 启动只恢复本地，首轮远端读取交给既有同步执行器。
        configSyncService.registerConfigListener(this::handleConfigChange);
        // 不新增恢复调度器；异步首次刷新与后续定时刷新共用同步服务。
        configSyncService.startSyncTask();

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
                updateDeviceConfig(device, true);
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
            if (remoteReload && isLocalTemporaryDevice(deviceId)) return;
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


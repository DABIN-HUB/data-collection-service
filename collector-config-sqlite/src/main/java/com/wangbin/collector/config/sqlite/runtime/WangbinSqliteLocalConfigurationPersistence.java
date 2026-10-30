package com.wangbin.collector.config.sqlite.runtime;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.config.sqlite.migration.LegacyJsonMigration;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.persistence.ConfigurationReader;
import com.wangbin.collector.core.config.persistence.ConfigurationRevision;
import com.wangbin.collector.core.config.persistence.ConfigurationStore;
import com.wangbin.collector.core.config.persistence.SqliteConfigService;
import com.wangbin.collector.core.config.persistence.DeviceConfiguration;
import com.wangbin.collector.core.config.persistence.MigrationRecord;
import com.wangbin.collector.core.config.persistence.StoredDeviceConfiguration;
import com.wangbin.collector.core.config.persistence.WangbinLocalConfigurationPersistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 本地 SQLite 权威源桥接。只复用 Phase 1 仓储，不创建库、不自动迁移，也不管理仓储生命周期。
 * 生产启动只从数据库恢复；显式来源构造仅保留旧迁移工具的严格校验兼容入口。
 */
public final class WangbinSqliteLocalConfigurationPersistence implements WangbinLocalConfigurationPersistence {
    private static final int AUDIT_PAGE_SIZE = 256;
    private static final int MAX_SOURCE_BYTES = 64 * 1024 * 1024;
    private final SqliteConfigService store;
    private final Path migrationSource;
    private final ConfigurationJsonCodec codec;

    /** 新配置从合法空库开始，不读取旧JSON，也不要求迁移记录。 */
    public WangbinSqliteLocalConfigurationPersistence(SqliteConfigService store) {
        this(store, java.time.ZoneId.of("Asia/Shanghai"));
    }

    public WangbinSqliteLocalConfigurationPersistence(SqliteConfigService store, java.time.ZoneId legacyTimeZone) {
        this.store = Objects.requireNonNull(store, "配置仓储不能为空");
        this.codec = new ConfigurationJsonCodec(legacyTimeZone);
        this.migrationSource = null;
    }

    public WangbinSqliteLocalConfigurationPersistence(SqliteConfigService store, Path migrationSource) {
        this(store, migrationSource, java.time.ZoneId.of("Asia/Shanghai"));
    }

    /** 保留两参数兼容入口；切换到其他旧JSON来源时可显式传入其写出时区。 */
    public WangbinSqliteLocalConfigurationPersistence(SqliteConfigService store, Path migrationSource, java.time.ZoneId legacyTimeZone) {
        this.codec = new ConfigurationJsonCodec(legacyTimeZone);
        this.store = Objects.requireNonNull(store, "配置仓储不能为空");
        this.migrationSource = Objects.requireNonNull(migrationSource, "显式迁移来源路径不能为空")
                .toAbsolutePath().normalize();
    }

    @Override
    public List<StoredDeviceConfiguration> restore() {
        return store.transaction(migrationSource == null ? this::restoreDatabase : this::verifiedRestore);
    }

    /** 聚合与持久修订在同一事务视图核对，空库无需伪造JSON迁移。 */
    private List<StoredDeviceConfiguration> restoreDatabase(ConfigurationStore.Writer reader) {
        AuditState audit = auditState(reader, null);
        List<StoredDeviceConfiguration> current = reader.listDevices();
        Set<String> deviceIds = new HashSet<>();
        Set<String> pointIds = new HashSet<>();
        for (StoredDeviceConfiguration stored : current) {
            DeviceConfiguration device = stored.configuration();
            if (!deviceIds.add(device.deviceId())
                    || !Objects.equals(audit.revisions().get(device.deviceId()), stored.revision())) {
                throw rejected("数据库设备身份或持久化修订与审计不一致");
            }
            for (var point : device.points()) {
                if (!pointIds.add(point.pointId())) throw rejected("数据库点位身份重复");
            }
            codec.toRuntime(device);
        }
        if (!deviceIds.equals(audit.revisions().keySet())) {
            throw rejected("数据库设备集合与持久化审计不一致");
        }
        return List.copyOf(current);
    }

    private List<StoredDeviceConfiguration> verifiedRestore(ConfigurationStore.Writer reader) {
        MigrationRecord marker = reader.findMigration(LegacyJsonMigration.MIGRATION_ID)
                .orElseThrow(() -> rejected("缺少显式迁移成功记录，禁止自动迁移或回退 JSON"));
        verifyPath(marker);
        byte[] bytes = readSource();
        verifyDigest(marker, bytes);
        List<DeviceConfiguration> original = decodeSource(bytes);
        verifyCounts(marker, original);
        Map<String, DeviceConfiguration> originals = new LinkedHashMap<>();
        for (DeviceConfiguration device : original) {
            // 类型与告警格式也必须能恢复，不能把仅 JSON 语法合法当成来源完整。
            codec.toRuntime(device);
            originals.put(device.deviceId(), device);
        }
        AuditState audit = auditState(reader, originals.keySet());
        List<StoredDeviceConfiguration> current = reader.listDevices();
        Set<String> identities = new HashSet<>();
        Set<String> pointIds = new HashSet<>();
        for (StoredDeviceConfiguration stored : current) {
            DeviceConfiguration device = stored.configuration();
            if (!identities.add(device.deviceId()) || !Objects.equals(audit.revisions().get(device.deviceId()), stored.revision())) {
                throw rejected("当前设备身份或持久化修订与审计不一致");
            }
            for (var point : device.points()) {
                if (!pointIds.add(point.pointId())) throw rejected("当前设备点位身份重复");
            }
            codec.toRuntime(device);
            if (!audit.changed().contains(device.deviceId())) {
                DeviceConfiguration previous = originals.get(device.deviceId());
                if (previous == null || !codec.encodeLocalDevice(previous).equals(codec.encodeLocalDevice(device))) {
                    throw rejected("未经审计修改的设备与迁移来源白名单配置不一致");
                }
            }
        }
        if (!identities.equals(audit.revisions().keySet())) throw rejected("当前设备集合与迁移后审计不一致");
        // 覆盖预检期间外部修改来源的情况；源文件始终只读，不重写或清理。
        verifyDigest(marker, readSource());
        return List.copyOf(current);
    }

    private void verifyPath(MigrationRecord marker) {
        try {
            if (!migrationSource.equals(Path.of(marker.sourcePath()).toAbsolutePath().normalize())) {
                throw rejected("当前迁移来源规范路径与成功记录不匹配");
            }
        } catch (java.nio.file.InvalidPathException exception) {
            throw rejected("迁移记录中的来源路径不合法");
        }
    }

    private void verifyCounts(MigrationRecord marker, List<DeviceConfiguration> original) {
        long connections = original.stream().filter(device -> device.connection() != null).count();
        long points = original.stream().mapToLong(device -> device.points().size()).sum();
        if (marker.deviceCount() != original.size() || marker.connectionCount() != connections || marker.pointCount() != points) {
            throw rejected("迁移记录与来源的设备、连接或点位数量不一致");
        }
    }

    private List<DeviceConfiguration> decodeSource(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            return codec.decodeLocalDevices(codec.readJson(text));
        } catch (CharacterCodingException | IllegalArgumentException exception) {
            throw rejected("迁移来源必须是完整合法的 UTF-8 本地配置数组");
        }
    }

    /** 审计按页读取并推演身份，既覆盖初始完整校验，也允许合法的后续增改删。 */
    private AuditState auditState(ConfigurationReader reader, Set<String> originalIds) {
        Map<String, Long> current = new HashMap<>();
        Set<String> changed = new HashSet<>();
        long cursor = 0;
        long lastRevision = reader.currentRevision();
        boolean migrated = originalIds == null;
        while (cursor < lastRevision) {
            List<ConfigurationRevision> page = reader.listRevisions(cursor, AUDIT_PAGE_SIZE);
            if (page.isEmpty()) throw rejected("迁移审计记录不完整");
            for (ConfigurationRevision revision : page) {
                if (revision.revision() <= cursor || revision.revision() > lastRevision) throw rejected("迁移审计游标不合法");
                cursor = revision.revision();
                if (originalIds != null && "JSON_MIGRATION".equals(revision.entityType())
                        && LegacyJsonMigration.MIGRATION_ID.equals(revision.entityId())) {
                    if (migrated || !"CREATE".equals(revision.operation()) || !current.keySet().equals(originalIds)) {
                        throw rejected("迁移成功审计与原始设备集合不一致");
                    }
                    migrated = true;
                } else if ("DEVICE".equals(revision.entityType())) {
                    String id = revision.entityId();
                    if (!migrated && (!originalIds.contains(id) || !"CREATE".equals(revision.operation()))) {
                        throw rejected("迁移成功前设备审计不符合显式迁移规则");
                    }
                    switch (revision.operation()) {
                        case "CREATE" -> {
                            if (current.putIfAbsent(id, cursor) != null) throw rejected("设备创建审计身份冲突");
                        }
                        case "UPDATE" -> {
                            if (!current.containsKey(id)) throw rejected("设备更新审计身份缺失");
                            current.put(id, cursor);
                        }
                        case "DELETE" -> {
                            if (current.remove(id) == null) throw rejected("设备删除审计身份缺失");
                        }
                        default -> throw rejected("设备审计操作不合法");
                    }
                    if (migrated) changed.add(id);
                }
            }
        }
        if (!migrated) throw rejected("缺少迁移成功审计");
        return new AuditState(current, changed);
    }

    private byte[] readSource() {
        try {
            if (!Files.isRegularFile(migrationSource, LinkOption.NOFOLLOW_LINKS)) {
                throw rejected("迁移来源缺失、不是普通文件或为符号链接");
            }
            if (Files.size(migrationSource) > MAX_SOURCE_BYTES) throw rejected("迁移来源超过 64 MiB 安全读取上限");
            try (var stream = Files.newInputStream(migrationSource)) {
                byte[] bytes = stream.readNBytes(MAX_SOURCE_BYTES + 1);
                if (bytes.length > MAX_SOURCE_BYTES) throw rejected("迁移来源超过 64 MiB 安全读取上限");
                return bytes;
            }
        } catch (IOException | SecurityException exception) {
            throw rejected("迁移来源无法读取，请检查路径与权限");
        }
    }

    private void verifyDigest(MigrationRecord marker, byte[] bytes) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!digest.equalsIgnoreCase(marker.sourceSha256())) throw rejected("迁移来源 SHA-256 与成功记录不同，拒绝切源");
        } catch (NoSuchAlgorithmException exception) {
            throw rejected("当前 Java 环境缺少 SHA-256");
        }
    }

    private IllegalStateException rejected(String message) { return new IllegalStateException(message); }

    @Override public Optional<StoredDeviceConfiguration> findDevice(String deviceId) { return store.findDevice(deviceId); }
    @Override public List<DeviceInfo> listDeviceSummaries() {
        return store.listDeviceSummaries().stream().map(codec::toDeviceInfo).toList();
    }
    @Override public Optional<DeviceInfo> findDeviceSummary(String deviceId) {
        return store.findDeviceSummary(deviceId).map(codec::toDeviceInfo);
    }
    @Override public boolean existsDevice(String deviceId) {
        return store.findDeviceSummary(deviceId).isPresent();
    }
    @Override public Optional<DeviceContext> findDeviceConnectionContext(String deviceId) {
        return store.readSnapshot(reader -> reader.findDeviceSummary(deviceId).map(summary ->
                codec.toRuntime(new DeviceConfiguration(summary.deviceId(), summary.deviceJson(),
                        reader.findDeviceConnection(deviceId).orElse(null), List.of()))));
    }
    @Override public Optional<DeviceContext> findDevicePointsContext(String deviceId) {
        return store.readSnapshot(reader -> reader.findDeviceSummary(deviceId).map(summary ->
                codec.toRuntime(new DeviceConfiguration(summary.deviceId(), summary.deviceJson(),
                        null, reader.listDevicePoints(deviceId)))));
    }
    @Override public DeviceContext toRuntime(DeviceConfiguration configuration) { return codec.toRuntime(configuration); }
    @Override public DeviceConfiguration fromRuntime(DeviceContext context) { return codec.fromRuntime(context); }
    @Override public boolean equivalent(DeviceContext left, DeviceContext right) { return codec.equivalent(left, right); }
    @Override public <T> T transaction(Function<ConfigurationStore.Writer, T> action) { return store.transaction(action); }

    /** 只保存无负载审计状态，禁止记录源文件或完整配置。 */
    private record AuditState(Map<String, Long> revisions, Set<String> changed) {
        @Override public String toString() { return "AuditState[REDACTED]"; }
    }
}

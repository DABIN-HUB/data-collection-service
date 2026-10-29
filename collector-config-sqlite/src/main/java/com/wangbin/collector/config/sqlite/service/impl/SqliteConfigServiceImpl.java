package com.wangbin.collector.config.sqlite.service.impl;

import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.config.sqlite.database.SqliteDatabase;
import com.wangbin.collector.config.sqlite.mapper.SqliteConfigMapper;
import com.wangbin.collector.core.config.persistence.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** SQLite 配置业务的唯一实现；负责事务、校验、修订冲突及保存编排。 */
public class SqliteConfigServiceImpl implements SqliteConfigService {
    private static final System.Logger LOGGER = System.getLogger(SqliteConfigServiceImpl.class.getName());
    private final SqliteDatabase database;
    private final ConfigurationJsonCodec codec = new ConfigurationJsonCodec();
    private final ThreadLocal<Boolean> transactionActive = ThreadLocal.withInitial(() -> false);
    private volatile boolean closed;

    public SqliteConfigServiceImpl(SqliteDatabase database) {
        this.database = java.util.Objects.requireNonNull(database, "数据库不能为空");
    }

    /** 兼容旧构造入口也必须从统一实现取得数据库连接。 */
    protected SqliteConfigServiceImpl(Path path, int busyTimeoutMs, boolean create) {
        this(create ? SqliteDatabase.createNew(path, busyTimeoutMs)
                : SqliteDatabase.openExisting(path, busyTimeoutMs));
    }

    public static SqliteConfigServiceImpl createNew(Path path, int busyTimeoutMs) {
        return new SqliteConfigServiceImpl(SqliteDatabase.createNew(path, busyTimeoutMs));
    }

    public static SqliteConfigServiceImpl openExisting(Path path, int busyTimeoutMs) {
        return new SqliteConfigServiceImpl(SqliteDatabase.openExisting(path, busyTimeoutMs));
    }

    @Override public void close() { closed = true; }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("配置仓储已经关闭");
    }

    @Override public <T> T transaction(Function<Writer, T> action) {
        checkOpen();
        if (action == null) throw new IllegalArgumentException("事务回调不能为空");
        if (transactionActive.get()) throw new IllegalStateException("不允许嵌套配置事务");
        transactionActive.set(true);
        try { return operate(action::apply, true); }
        finally { transactionActive.remove(); }
    }

    private <T> T read(Function<Session, T> action) { return operate(action, false); }

    private <T> T operate(Function<Session, T> action, boolean write) {
        checkOpen();
        boolean committed = false;
        T result = null;
        try (Connection connection = database.openConnection()) {
            // 在读取 CAS 修订前取得写锁，保持原有 SQLite 事务隔离。
            statement(connection, write ? "BEGIN IMMEDIATE" : "BEGIN");
            Session session = new Session(connection, write);
            try {
                result = action.apply(session);
                session.check();
                if (session.rollbackOnly) throw new IllegalStateException("事务已因前序操作失败而失效");
                database.secureFiles();
                commit(connection);
                committed = true;
                return result;
            } catch (RuntimeException | Error exception) {
                try { statement(connection, "ROLLBACK"); } catch (SQLException ignored) { /* 不传播驱动原始异常。 */ }
                throw exception;
            } catch (SQLException exception) {
                try { statement(connection, "ROLLBACK"); } catch (SQLException ignored) { /* 不覆盖受控提交错误。 */ }
                throw SqliteDatabase.failure(exception);
            } finally { session.active = false; }
        } catch (SQLException exception) {
            if (committed) {
                // 已提交结果不能因资源关闭失败被误报为回滚，避免调用方重复写入。
                LOGGER.log(System.Logger.Level.WARNING, "配置事务已提交，但数据库连接资源关闭失败");
                return result;
            }
            throw SqliteDatabase.failure(exception);
        } catch (RuntimeException exception) {
            if (Arrays.stream(exception.getSuppressed()).anyMatch(SQLException.class::isInstance)) {
                throw new IllegalStateException("数据库资源释放失败");
            }
            throw exception;
        }
    }

    private static void statement(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    /** 提交语句执行成功后，即使 statement 关闭失败也不得报告回滚。 */
    public static void commit(Connection connection) throws SQLException {
        boolean executed = false;
        try (Statement statement = connection.createStatement()) {
            statement.execute("COMMIT");
            executed = true;
        } catch (SQLException exception) {
            if (!executed) throw exception;
            LOGGER.log(System.Logger.Level.WARNING, "配置事务已提交，但提交语句资源关闭失败");
        }
    }

    @Override public Optional<StoredDeviceConfiguration> findDevice(String id) { return read(s -> s.findDevice(id)); }
    @Override public List<StoredDeviceConfiguration> listDevices() { return read(Session::listDevices); }
    @Override public Optional<StoredDeviceSummary> findDeviceSummary(String id) { return read(s -> s.findDeviceSummary(id)); }
    @Override public List<StoredDeviceSummary> listDeviceSummaries() { return read(Session::listDeviceSummaries); }
    @Override public Optional<ConnectionConfiguration> findDeviceConnection(String id) { return read(s -> s.findDeviceConnection(id)); }
    @Override public List<PointConfiguration> listDevicePoints(String id) { return read(s -> s.listDevicePoints(id)); }
    @Override public Optional<ProtocolProfile> findProtocolProfile(String id) { return read(s -> s.findProtocolProfile(id)); }
    @Override public List<ProtocolProfile> listProtocolProfiles() { return read(Session::listProtocolProfiles); }
    @Override public Optional<GatewaySetting> findGatewaySetting(String key) { return read(s -> s.findGatewaySetting(key)); }
    @Override public List<GatewaySetting> listGatewaySettings() { return read(Session::listGatewaySettings); }
    @Override public Optional<RemoteSnapshot> findRemoteSnapshot(String source, String id, String version) {
        return read(s -> s.findRemoteSnapshot(source, id, version));
    }
    @Override public List<RemoteSnapshot> listRemoteSnapshots(String source) { return read(s -> s.listRemoteSnapshots(source)); }
    @Override public long currentRevision() { return read(Session::currentRevision); }
    @Override public List<ConfigurationRevision> listRevisions(long after, int limit) { return read(s -> s.listRevisions(after, limit)); }
    @Override public Optional<MigrationRecord> findMigration(String id) { return read(s -> s.findMigration(id)); }
    @Override public List<MigrationRecord> listMigrations() { return read(Session::listMigrations); }

    private static String identity(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("配置稳定身份不能为空");
        return value;
    }

    private final class Session implements Writer {
        private final SqliteConfigMapper mapper;
        private final Thread owner = Thread.currentThread();
        private final boolean writable;
        private boolean active = true;
        private boolean rollbackOnly;

        private Session(Connection connection, boolean writable) {
            this.mapper = new SqliteConfigMapper(connection, this::check);
            this.writable = writable;
        }

        private void check() {
            checkOpen();
            if (!active || rollbackOnly || owner != Thread.currentThread()) {
                throw new IllegalStateException("事务入口已失效或被跨线程调用");
            }
        }

        private <T> T mutate(Function<Session, T> action) {
            check();
            if (!writable || rollbackOnly) throw new IllegalStateException("当前事务禁止继续写入");
            try { return action.apply(this); }
            catch (RuntimeException | Error exception) { rollbackOnly = true; throw exception; }
        }

        private void cas(long actual, long expected, boolean deleting) {
            if (expected < 0 || (deleting && expected == 0)) throw new IllegalArgumentException("持久化修订参数不合法");
            if (actual != expected || (deleting && actual == 0)) throw new ConfigurationConflictException();
        }

        private long audit(String type, String id, String operation) {
            return mapper.insertRevision(type, id, operation, Instant.now());
        }

        private void validateStoredDevice(StoredDeviceConfiguration item) {
            try {
                codec.validateLocalDevice(item.configuration());
            } catch (IllegalArgumentException | ClassCastException | NullPointerException
                     | java.time.DateTimeException exception) {
                throw new IllegalStateException("数据库配置记录不合法");
            }
        }

        @Override public Optional<StoredDeviceConfiguration> findDevice(String id) {
            check(); identity(id);
            Optional<StoredDeviceConfiguration> result = mapper.findDevice(id);
            result.ifPresent(this::validateStoredDevice);
            return result;
        }

        @Override public List<StoredDeviceConfiguration> listDevices() {
            List<StoredDeviceConfiguration> result = mapper.listDevices();
            result.forEach(this::validateStoredDevice);
            return result;
        }

        @Override public Optional<StoredDeviceSummary> findDeviceSummary(String id) {
            check(); identity(id);
            return mapper.findDeviceSummary(id);
        }

        @Override public List<StoredDeviceSummary> listDeviceSummaries() { return mapper.listDeviceSummaries(); }

        @Override public Optional<ConnectionConfiguration> findDeviceConnection(String id) {
            check(); identity(id);
            return mapper.findDeviceConnection(id);
        }

        @Override public List<PointConfiguration> listDevicePoints(String id) {
            check(); identity(id);
            return mapper.listDevicePoints(id);
        }

        @Override public Map<String, String> findPointOwners(Collection<String> pointIds) {
            check();
            if (pointIds == null) throw new IllegalArgumentException("点位身份列表不能为空");
            pointIds.forEach(SqliteConfigServiceImpl::identity);
            return mapper.findPointOwners(pointIds);
        }

        @Override public Optional<ProtocolProfile> findProtocolProfile(String id) {
            check(); identity(id);
            return mapper.findProtocolProfile(id);
        }
        @Override public List<ProtocolProfile> listProtocolProfiles() { return mapper.listProtocolProfiles(); }
        @Override public Optional<GatewaySetting> findGatewaySetting(String key) {
            check(); identity(key);
            return mapper.findGatewaySetting(key);
        }
        @Override public List<GatewaySetting> listGatewaySettings() { return mapper.listGatewaySettings(); }
        @Override public Optional<RemoteSnapshot> findRemoteSnapshot(String source, String id, String version) {
            check(); identity(source); identity(id); identity(version);
            return mapper.findRemoteSnapshot(source, id, version);
        }
        @Override public List<RemoteSnapshot> listRemoteSnapshots(String source) {
            check(); identity(source);
            return mapper.listRemoteSnapshots(source);
        }
        @Override public long currentRevision() { return mapper.currentRevision(); }
        @Override public List<ConfigurationRevision> listRevisions(long after, int limit) {
            check();
            if (after < 0 || limit < 1) throw new IllegalArgumentException("修订查询游标或数量不合法");
            return mapper.listRevisions(after, limit);
        }
        @Override public Optional<MigrationRecord> findMigration(String id) {
            check(); identity(id);
            return mapper.findMigration(id);
        }
        @Override public List<MigrationRecord> listMigrations() { return mapper.listMigrations(); }

        @Override public StoredDeviceConfiguration saveDevice(DeviceConfiguration config, long expected) {
            return mutate(session -> {
                codec.validateLocalDevice(config);
                long actual = mapper.deviceRevision(config.deviceId());
                cas(actual, expected, false);
                long revision = audit("DEVICE", config.deviceId(), actual == 0 ? "CREATE" : "UPDATE");
                if (actual == 0) mapper.insertDevice(config, revision);
                else mapper.updateDevice(config, revision);
                mapper.deleteConnections(config.deviceId());
                mapper.deletePoints(config.deviceId());
                if (config.connection() != null) mapper.insertConnection(config.connection());
                for (PointConfiguration point : config.points()) {
                    var code = codec.readJson(point.configJson()).get("pointCode");
                    mapper.insertPoint(point, code != null && code.isTextual() ? code.textValue() : null);
                }
                return new StoredDeviceConfiguration(config, revision);
            });
        }

        @Override public void deleteDevice(String id, long expected) {
            mutate(session -> {
                identity(id); cas(mapper.deviceRevision(id), expected, true);
                audit("DEVICE", id, "DELETE"); mapper.deleteDevice(id); return null;
            });
        }

        @Override public ProtocolProfile saveProtocolProfile(String id, String protocol, String json, long expected) {
            return mutate(session -> {
                identity(id); identity(protocol);
                if (!codec.validateConfigurationPayload(json).isObject()) throw new IllegalArgumentException("协议模板连接配置必须为 JSON 对象");
                long actual = mapper.profileRevision(id); cas(actual, expected, false);
                long revision = audit("PROTOCOL_PROFILE", id, actual == 0 ? "CREATE" : "UPDATE");
                if (actual == 0) mapper.insertProtocolProfile(id, protocol, json, revision);
                else mapper.updateProtocolProfile(id, protocol, json, revision);
                return new ProtocolProfile(id, protocol, json, revision);
            });
        }

        @Override public void deleteProtocolProfile(String id, long expected) {
            mutate(session -> {
                identity(id); cas(mapper.profileRevision(id), expected, true);
                audit("PROTOCOL_PROFILE", id, "DELETE"); mapper.deleteProtocolProfile(id); return null;
            });
        }

        @Override public GatewaySetting saveGatewaySetting(String key, String json, long expected) {
            return mutate(session -> {
                identity(key); codec.validateConfigurationPayload(json);
                long actual = mapper.settingRevision(key); cas(actual, expected, false);
                long revision = audit("GATEWAY_SETTING", key, actual == 0 ? "CREATE" : "UPDATE");
                if (actual == 0) mapper.insertGatewaySetting(key, json, revision);
                else mapper.updateGatewaySetting(key, json, revision);
                return new GatewaySetting(key, json, revision);
            });
        }

        @Override public void deleteGatewaySetting(String key, long expected) {
            mutate(session -> {
                identity(key); cas(mapper.settingRevision(key), expected, true);
                audit("GATEWAY_SETTING", key, "DELETE"); mapper.deleteGatewaySetting(key); return null;
            });
        }

        @Override public RemoteSnapshot saveRemoteSnapshot(String source, String id, String version, String json, long expected) {
            return mutate(session -> {
                new RemoteSnapshot(source, id, version, json, 1); codec.validateConfigurationPayload(json);
                long actual = mapper.snapshotRevision(source, id, version);
                cas(actual, expected, false);
                // 联合键采用长度前缀编码，避免审计身份发生分隔符碰撞。
                String auditId = source.length() + ":" + source + id.length() + ":" + id + version.length() + ":" + version;
                long revision = audit("REMOTE_SNAPSHOT", auditId, actual == 0 ? "CREATE" : "UPDATE");
                if (actual == 0) mapper.insertRemoteSnapshot(source, id, version, json, revision);
                else mapper.updateRemoteSnapshot(source, id, version, json, revision);
                return new RemoteSnapshot(source, id, version, json, revision);
            });
        }

        @Override public void deleteRemoteSnapshot(String source, String id, String version, long expected) {
            mutate(session -> {
                identity(source); identity(id); identity(version);
                cas(mapper.snapshotRevision(source, id, version), expected, true);
                String auditId = source.length() + ":" + source + id.length() + ":" + id + version.length() + ":" + version;
                audit("REMOTE_SNAPSHOT", auditId, "DELETE");
                mapper.deleteRemoteSnapshot(source, id, version); return null;
            });
        }

        @Override public void recordMigration(MigrationRecord record) {
            mutate(session -> {
                if (record == null) throw new IllegalArgumentException("迁移记录不能为空");
                if (findMigration(record.migrationId()).isPresent()) throw new ConfigurationConflictException();
                audit("JSON_MIGRATION", record.migrationId(), "CREATE");
                mapper.insertMigration(record); return null;
            });
        }
    }
}

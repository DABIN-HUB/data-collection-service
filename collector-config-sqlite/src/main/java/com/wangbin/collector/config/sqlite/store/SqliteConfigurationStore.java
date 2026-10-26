package com.wangbin.collector.config.sqlite.store;

import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.config.sqlite.database.SqliteDatabase;
import com.wangbin.collector.core.config.persistence.*;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** wangbin 私有 SQLite 配置仓储；每个操作拥有独立连接，事务 Writer 不允许逃逸或跨线程。 */
public final class SqliteConfigurationStore implements ConfigurationStore {
    private static final System.Logger LOGGER = System.getLogger(SqliteConfigurationStore.class.getName());
    private final SqliteDatabase database;
    private final ConfigurationJsonCodec codec = new ConfigurationJsonCodec();
    private final ThreadLocal<Boolean> transactionActive = ThreadLocal.withInitial(() -> false);
    private volatile boolean closed;

    private SqliteConfigurationStore(SqliteDatabase database) { this.database = database; }
    public static SqliteConfigurationStore createNew(Path path, int busyTimeoutMs) {
        return new SqliteConfigurationStore(SqliteDatabase.createNew(path, busyTimeoutMs));
    }
    public static SqliteConfigurationStore openExisting(Path path, int busyTimeoutMs) {
        return new SqliteConfigurationStore(SqliteDatabase.openExisting(path, busyTimeoutMs));
    }
    @Override public void close() { closed = true; }
    private void checkOpen() { if (closed) throw new IllegalStateException("配置仓储已经关闭"); }

    @Override public <T> T transaction(Function<Writer, T> action) {
        checkOpen();
        if (action == null) throw new IllegalArgumentException("事务回调不能为空");
        if (transactionActive.get()) throw new IllegalStateException("不允许嵌套配置事务");
        transactionActive.set(true);
        try { return operate(session -> action.apply(session), true); }
        finally { transactionActive.remove(); }
    }

    private <T> T read(Function<Session, T> action) { return operate(action, false); }
    private <T> T operate(Function<Session, T> action, boolean write) {
        checkOpen();
        boolean committed = false;
        T result = null;
        try (Connection connection = database.openConnection()) {
            // IMMEDIATE 在读取 CAS 修订前取得写锁，避免延迟事务升级时出现快照冲突。
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
                // 资源关闭失败不能抹掉已确认COMMIT的结果，也不能诱导调用方重复写入。
                LOGGER.log(System.Logger.Level.WARNING, "配置事务已提交，但数据库连接资源关闭失败");
                return result;
            }
            throw SqliteDatabase.failure(exception);
        }
        catch (RuntimeException exception) {
            if (Arrays.stream(exception.getSuppressed()).anyMatch(SQLException.class::isInstance)) {
                throw new IllegalStateException("数据库资源释放失败");
            }
            throw exception;
        }
    }
    private static void statement(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static void commit(Connection connection) throws SQLException {
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
        private final Connection connection;
        private final Thread owner = Thread.currentThread();
        private final boolean writable;
        private boolean active = true;
        private boolean rollbackOnly;
        private Session(Connection connection, boolean writable) { this.connection = connection; this.writable = writable; }
        private void check() {
            checkOpen();
            if (!active || rollbackOnly || owner != Thread.currentThread()) throw new IllegalStateException("事务入口已失效或被跨线程调用");
        }
        private <T> T mutate(Function<Session, T> action) {
            check();
            if (!writable || rollbackOnly) throw new IllegalStateException("当前事务禁止继续写入");
            try { return action.apply(this); }
            catch (RuntimeException | Error exception) { rollbackOnly = true; throw exception; }
        }
        private <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters) {
            check();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                try (ResultSet rows = statement.executeQuery()) {
                    List<T> results = new ArrayList<>();
                    while (rows.next()) results.add(mapper.map(rows));
                    return List.copyOf(results);
                }
            } catch (SQLException exception) { throw SqliteDatabase.failure(exception); }
            catch (IllegalArgumentException | ClassCastException | NullPointerException | java.time.DateTimeException exception) {
                throw new IllegalStateException("数据库配置记录不合法");
            }
        }
        private int update(String sql, Object... parameters) {
            check();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                return statement.executeUpdate();
            } catch (SQLException exception) { rollbackOnly = true; throw SqliteDatabase.failure(exception); }
        }
        private long revision(String table, String predicate, Object... keys) {
            List<Long> revisions = query("SELECT revision FROM " + table + " WHERE " + predicate, r -> r.getLong(1), keys);
            return revisions.isEmpty() ? 0 : revisions.get(0);
        }
        private void cas(long actual, long expected, boolean deleting) {
            if (expected < 0 || (deleting && expected == 0)) throw new IllegalArgumentException("持久化修订参数不合法");
            if (actual != expected || (deleting && actual == 0)) throw new ConfigurationConflictException();
        }
        private long audit(String type, String id, String operation) {
            update("INSERT INTO dc_config_revision(entity_type, entity_id, operation, created_at) VALUES (?, ?, ?, ?)",
                    type, id, operation, Instant.now().toString());
            return query("SELECT last_insert_rowid()", rows -> rows.getLong(1)).get(0);
        }

        @Override public Optional<StoredDeviceConfiguration> findDevice(String id) {
            check(); identity(id);
            return query("SELECT device_id, device_json, revision FROM dc_device WHERE device_id=?", this::device, id).stream().findFirst();
        }
        @Override public List<StoredDeviceConfiguration> listDevices() {
            return query("SELECT device_id, device_json, revision FROM dc_device ORDER BY device_id", this::device);
        }
        private StoredDeviceConfiguration device(ResultSet rows) throws SQLException {
            String id = rows.getString(1);
            List<ConnectionConfiguration> connections = query("SELECT connection_json FROM dc_device_connection WHERE device_id=?",
                    r -> new ConnectionConfiguration(id, r.getString(1)), id);
            List<PointConfiguration> points = query("SELECT point_id, sort_order, status, point_json FROM dc_device_point WHERE device_id=? ORDER BY sort_order",
                    r -> new PointConfiguration(id, r.getString(1), r.getInt(2), (Integer) r.getObject(3), r.getString(4)), id);
            DeviceConfiguration configuration = new DeviceConfiguration(id, rows.getString(2),
                    connections.isEmpty() ? null : connections.get(0), points);
            codec.validateLocalDevice(configuration);
            return new StoredDeviceConfiguration(configuration, rows.getLong(3));
        }
        @Override public Optional<ProtocolProfile> findProtocolProfile(String id) {
            check(); identity(id);
            return query("SELECT profile_id, protocol_type, connection_json, revision FROM dc_protocol_profile WHERE profile_id=?",
                    this::profile, id).stream().findFirst();
        }
        private ProtocolProfile profile(ResultSet rows) throws SQLException {
            return new ProtocolProfile(rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4));
        }
        @Override public List<ProtocolProfile> listProtocolProfiles() {
            return query("SELECT profile_id, protocol_type, connection_json, revision FROM dc_protocol_profile ORDER BY profile_id", this::profile);
        }
        @Override public Optional<GatewaySetting> findGatewaySetting(String key) {
            check(); identity(key);
            return query("SELECT setting_key, config_json, revision FROM dc_gateway_setting WHERE setting_key=?",
                    this::setting, key).stream().findFirst();
        }
        private GatewaySetting setting(ResultSet rows) throws SQLException {
            return new GatewaySetting(rows.getString(1), rows.getString(2), rows.getLong(3));
        }
        @Override public List<GatewaySetting> listGatewaySettings() {
            return query("SELECT setting_key, config_json, revision FROM dc_gateway_setting ORDER BY setting_key", this::setting);
        }
        @Override public Optional<RemoteSnapshot> findRemoteSnapshot(String source, String id, String version) {
            check(); identity(source); identity(id); identity(version);
            return query("SELECT source, snapshot_id, source_version, config_json, revision FROM dc_remote_snapshot WHERE source=? AND snapshot_id=? AND source_version=?",
                    this::snapshot, source, id, version).stream().findFirst();
        }
        private RemoteSnapshot snapshot(ResultSet rows) throws SQLException {
            return new RemoteSnapshot(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5));
        }
        @Override public List<RemoteSnapshot> listRemoteSnapshots(String source) {
            check(); identity(source);
            return query("SELECT source, snapshot_id, source_version, config_json, revision FROM dc_remote_snapshot WHERE source=? ORDER BY snapshot_id, source_version",
                    this::snapshot, source);
        }
        @Override public long currentRevision() {
            return query("SELECT coalesce(max(revision), 0) FROM dc_config_revision", r -> r.getLong(1)).get(0);
        }
        @Override public List<ConfigurationRevision> listRevisions(long after, int limit) {
            check();
            if (after < 0 || limit < 1) throw new IllegalArgumentException("修订查询游标或数量不合法");
            return query("SELECT revision, entity_type, entity_id, operation, created_at FROM dc_config_revision WHERE revision>? ORDER BY revision LIMIT ?",
                    r -> new ConfigurationRevision(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), Instant.parse(r.getString(5))), after, limit);
        }
        @Override public Optional<MigrationRecord> findMigration(String id) {
            check(); identity(id);
            return query("SELECT migration_id, source_path, source_sha256, device_count, connection_count, point_count, completed_at FROM dc_json_migration WHERE migration_id=?",
                    this::migration, id).stream().findFirst();
        }
        private MigrationRecord migration(ResultSet r) throws SQLException {
            return new MigrationRecord(r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getInt(5), r.getInt(6), Instant.parse(r.getString(7)));
        }
        @Override public List<MigrationRecord> listMigrations() {
            return query("SELECT migration_id, source_path, source_sha256, device_count, connection_count, point_count, completed_at FROM dc_json_migration ORDER BY migration_id", this::migration);
        }

        @Override public StoredDeviceConfiguration saveDevice(DeviceConfiguration config, long expected) {
            return mutate(s -> {
                codec.validateLocalDevice(config);
                long actual = revision("dc_device", "device_id=?", config.deviceId());
                cas(actual, expected, false);
                long revision = audit("DEVICE", config.deviceId(), actual == 0 ? "CREATE" : "UPDATE");
                if (actual == 0) update("INSERT INTO dc_device(device_id, device_json, revision) VALUES (?, ?, ?)", config.deviceId(), config.deviceJson(), revision);
                else update("UPDATE dc_device SET device_json=?, revision=? WHERE device_id=?", config.deviceJson(), revision, config.deviceId());
                update("DELETE FROM dc_device_connection WHERE device_id=?", config.deviceId());
                update("DELETE FROM dc_device_point WHERE device_id=?", config.deviceId());
                if (config.connection() != null) update("INSERT INTO dc_device_connection(device_id, connection_json) VALUES (?, ?)", config.deviceId(), config.connection().configJson());
                for (PointConfiguration point : config.points()) {
                    var code = codec.readJson(point.configJson()).get("pointCode");
                    update("INSERT INTO dc_device_point(point_id, device_id, sort_order, status, point_code, point_json) VALUES (?, ?, ?, ?, ?, ?)",
                            point.pointId(), point.deviceId(), point.sortOrder(), point.status(), code != null && code.isTextual() ? code.textValue() : null, point.configJson());
                }
                return new StoredDeviceConfiguration(config, revision);
            });
        }
        @Override public void deleteDevice(String id, long expected) {
            mutate(s -> {
                identity(id); cas(revision("dc_device", "device_id=?", id), expected, true);
                audit("DEVICE", id, "DELETE"); update("DELETE FROM dc_device WHERE device_id=?", id); return null;
            });
        }
        @Override public ProtocolProfile saveProtocolProfile(String id, String protocol, String json, long expected) {
            return mutate(s -> {
                identity(id); identity(protocol);
                if (!codec.validateConfigurationPayload(json).isObject()) throw new IllegalArgumentException("协议模板连接配置必须为 JSON 对象");
                long actual = revision("dc_protocol_profile", "profile_id=?", id); cas(actual, expected, false);
                long revision = audit("PROTOCOL_PROFILE", id, actual == 0 ? "CREATE" : "UPDATE");
                if (actual == 0) update("INSERT INTO dc_protocol_profile VALUES (?, ?, ?, ?)", id, protocol, json, revision);
                else update("UPDATE dc_protocol_profile SET protocol_type=?, connection_json=?, revision=? WHERE profile_id=?", protocol, json, revision, id);
                return new ProtocolProfile(id, protocol, json, revision);
            });
        }
        @Override public void deleteProtocolProfile(String id, long expected) {
            mutate(s -> {
                identity(id); cas(revision("dc_protocol_profile", "profile_id=?", id), expected, true);
                audit("PROTOCOL_PROFILE", id, "DELETE"); update("DELETE FROM dc_protocol_profile WHERE profile_id=?", id); return null;
            });
        }
        @Override public GatewaySetting saveGatewaySetting(String key, String json, long expected) {
            return mutate(s -> {
                identity(key); codec.validateConfigurationPayload(json);
                long actual = revision("dc_gateway_setting", "setting_key=?", key); cas(actual, expected, false);
                long revision = audit("GATEWAY_SETTING", key, actual == 0 ? "CREATE" : "UPDATE");
                if (actual == 0) update("INSERT INTO dc_gateway_setting VALUES (?, ?, ?)", key, json, revision);
                else update("UPDATE dc_gateway_setting SET config_json=?, revision=? WHERE setting_key=?", json, revision, key);
                return new GatewaySetting(key, json, revision);
            });
        }
        @Override public void deleteGatewaySetting(String key, long expected) {
            mutate(s -> {
                identity(key); cas(revision("dc_gateway_setting", "setting_key=?", key), expected, true);
                audit("GATEWAY_SETTING", key, "DELETE"); update("DELETE FROM dc_gateway_setting WHERE setting_key=?", key); return null;
            });
        }
        @Override public RemoteSnapshot saveRemoteSnapshot(String source, String id, String version, String json, long expected) {
            return mutate(s -> {
                new RemoteSnapshot(source, id, version, json, 1); codec.validateConfigurationPayload(json);
                long actual = revision("dc_remote_snapshot", "source=? AND snapshot_id=? AND source_version=?", source, id, version);
                cas(actual, expected, false);
                // 联合键通过长度前缀编码，避免分隔符碰撞；审计从不保存配置负载。
                String auditId = source.length() + ":" + source + id.length() + ":" + id + version.length() + ":" + version;
                long revision = audit("REMOTE_SNAPSHOT", auditId, actual == 0 ? "CREATE" : "UPDATE");
                if (actual == 0) update("INSERT INTO dc_remote_snapshot VALUES (?, ?, ?, ?, ?)", source, id, version, json, revision);
                else update("UPDATE dc_remote_snapshot SET config_json=?, revision=? WHERE source=? AND snapshot_id=? AND source_version=?", json, revision, source, id, version);
                return new RemoteSnapshot(source, id, version, json, revision);
            });
        }
        @Override public void deleteRemoteSnapshot(String source, String id, String version, long expected) {
            mutate(s -> {
                identity(source); identity(id); identity(version);
                cas(revision("dc_remote_snapshot", "source=? AND snapshot_id=? AND source_version=?", source, id, version), expected, true);
                String auditId = source.length() + ":" + source + id.length() + ":" + id + version.length() + ":" + version;
                audit("REMOTE_SNAPSHOT", auditId, "DELETE");
                update("DELETE FROM dc_remote_snapshot WHERE source=? AND snapshot_id=? AND source_version=?", source, id, version); return null;
            });
        }
        @Override public void recordMigration(MigrationRecord record) {
            mutate(s -> {
                if (record == null) throw new IllegalArgumentException("迁移记录不能为空");
                if (findMigration(record.migrationId()).isPresent()) throw new ConfigurationConflictException();
                audit("JSON_MIGRATION", record.migrationId(), "CREATE");
                update("INSERT INTO dc_json_migration VALUES (?, ?, ?, ?, ?, ?, ?)", record.migrationId(), record.sourcePath(), record.sourceSha256(),
                        record.deviceCount(), record.connectionCount(), record.pointCount(), record.completedAt().toString()); return null;
            });
        }
    }
    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
    }
    @FunctionalInterface private interface RowMapper<T> { T map(ResultSet rows) throws SQLException; }
}

package com.wangbin.collector.config.sqlite.mapper;

import com.wangbin.collector.config.sqlite.database.SqliteDatabase;
import com.wangbin.collector.core.config.persistence.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 仅在调用方持有的 SQLite 事务连接内执行配置 SQL、绑定参数并映射结果。 */
public final class SqliteConfigMapper {
    private final Connection connection;
    private final Runnable check;

    public SqliteConfigMapper(Connection connection, Runnable check) {
        this.connection = connection;
        this.check = check;
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters) {
        check.run();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                List<T> results = new ArrayList<>();
                while (rows.next()) results.add(mapper.map(rows));
                return List.copyOf(results);
            }
        } catch (SQLException exception) {
            throw SqliteDatabase.failure(exception);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException | java.time.DateTimeException exception) {
            throw new IllegalStateException("数据库配置记录不合法");
        }
    }

    private int update(String sql, Object... parameters) {
        check.run();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw SqliteDatabase.failure(exception);
        }
    }

    public long deviceRevision(String id) {
        return firstRevision("SELECT revision FROM dc_device WHERE device_id=?", id);
    }

    public long profileRevision(String id) {
        return firstRevision("SELECT revision FROM dc_protocol_profile WHERE profile_id=?", id);
    }

    public long settingRevision(String key) {
        return firstRevision("SELECT revision FROM dc_gateway_setting WHERE setting_key=?", key);
    }

    public long snapshotRevision(String source, String id, String version) {
        return firstRevision("SELECT revision FROM dc_remote_snapshot WHERE source=? AND snapshot_id=? AND source_version=?", source, id, version);
    }

    private long firstRevision(String sql, Object... keys) {
        List<Long> revisions = query(sql, rows -> rows.getLong(1), keys);
        return revisions.isEmpty() ? 0 : revisions.get(0);
    }

    public long insertRevision(String type, String id, String operation, Instant createdAt) {
        update("INSERT INTO dc_config_revision(entity_type, entity_id, operation, created_at) VALUES (?, ?, ?, ?)",
                type, id, operation, createdAt.toString());
        return query("SELECT last_insert_rowid()", rows -> rows.getLong(1)).get(0);
    }

    public Optional<StoredDeviceConfiguration> findDevice(String id) {
        return query("SELECT device_id, device_json, revision FROM dc_device WHERE device_id=?", this::device, id).stream().findFirst();
    }

    public List<StoredDeviceConfiguration> listDevices() {
        return query("SELECT device_id, device_json, revision FROM dc_device ORDER BY device_id", this::device);
    }

    private StoredDeviceConfiguration device(ResultSet rows) throws SQLException {
        String id = rows.getString(1);
        List<ConnectionConfiguration> connections = query("SELECT connection_json FROM dc_device_connection WHERE device_id=?",
                result -> new ConnectionConfiguration(id, result.getString(1)), id);
        List<PointConfiguration> points = query("SELECT point_id, sort_order, status, point_json FROM dc_device_point WHERE device_id=? ORDER BY sort_order",
                result -> new PointConfiguration(id, result.getString(1), result.getInt(2),
                        (Integer) result.getObject(3), result.getString(4)), id);
        return new StoredDeviceConfiguration(new DeviceConfiguration(id, rows.getString(2),
                connections.isEmpty() ? null : connections.get(0), points), rows.getLong(3));
    }

    public Optional<ProtocolProfile> findProtocolProfile(String id) {
        return query("SELECT profile_id, protocol_type, connection_json, revision FROM dc_protocol_profile WHERE profile_id=?",
                this::profile, id).stream().findFirst();
    }

    public List<ProtocolProfile> listProtocolProfiles() {
        return query("SELECT profile_id, protocol_type, connection_json, revision FROM dc_protocol_profile ORDER BY profile_id", this::profile);
    }

    private ProtocolProfile profile(ResultSet rows) throws SQLException {
        return new ProtocolProfile(rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4));
    }

    public Optional<GatewaySetting> findGatewaySetting(String key) {
        return query("SELECT setting_key, config_json, revision FROM dc_gateway_setting WHERE setting_key=?",
                this::setting, key).stream().findFirst();
    }

    public List<GatewaySetting> listGatewaySettings() {
        return query("SELECT setting_key, config_json, revision FROM dc_gateway_setting ORDER BY setting_key", this::setting);
    }

    private GatewaySetting setting(ResultSet rows) throws SQLException {
        return new GatewaySetting(rows.getString(1), rows.getString(2), rows.getLong(3));
    }

    public Optional<RemoteSnapshot> findRemoteSnapshot(String source, String id, String version) {
        return query("SELECT source, snapshot_id, source_version, config_json, revision FROM dc_remote_snapshot WHERE source=? AND snapshot_id=? AND source_version=?",
                this::snapshot, source, id, version).stream().findFirst();
    }

    public List<RemoteSnapshot> listRemoteSnapshots(String source) {
        return query("SELECT source, snapshot_id, source_version, config_json, revision FROM dc_remote_snapshot WHERE source=? ORDER BY snapshot_id, source_version",
                this::snapshot, source);
    }

    private RemoteSnapshot snapshot(ResultSet rows) throws SQLException {
        return new RemoteSnapshot(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5));
    }

    public long currentRevision() {
        return query("SELECT coalesce(max(revision), 0) FROM dc_config_revision", rows -> rows.getLong(1)).get(0);
    }

    public List<ConfigurationRevision> listRevisions(long after, int limit) {
        return query("SELECT revision, entity_type, entity_id, operation, created_at FROM dc_config_revision WHERE revision>? ORDER BY revision LIMIT ?",
                rows -> new ConfigurationRevision(rows.getLong(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), Instant.parse(rows.getString(5))), after, limit);
    }

    public Optional<MigrationRecord> findMigration(String id) {
        return query("SELECT migration_id, source_path, source_sha256, device_count, connection_count, point_count, completed_at FROM dc_json_migration WHERE migration_id=?",
                this::migration, id).stream().findFirst();
    }

    public List<MigrationRecord> listMigrations() {
        return query("SELECT migration_id, source_path, source_sha256, device_count, connection_count, point_count, completed_at FROM dc_json_migration ORDER BY migration_id", this::migration);
    }

    private MigrationRecord migration(ResultSet rows) throws SQLException {
        return new MigrationRecord(rows.getString(1), rows.getString(2), rows.getString(3),
                rows.getInt(4), rows.getInt(5), rows.getInt(6), Instant.parse(rows.getString(7)));
    }

    public void insertDevice(DeviceConfiguration config, long revision) {
        update("INSERT INTO dc_device(device_id, device_json, revision) VALUES (?, ?, ?)",
                config.deviceId(), config.deviceJson(), revision);
    }

    public void updateDevice(DeviceConfiguration config, long revision) {
        update("UPDATE dc_device SET device_json=?, revision=? WHERE device_id=?",
                config.deviceJson(), revision, config.deviceId());
    }

    public void deleteConnections(String id) {
        update("DELETE FROM dc_device_connection WHERE device_id=?", id);
    }

    public void deletePoints(String id) {
        update("DELETE FROM dc_device_point WHERE device_id=?", id);
    }

    public void insertConnection(ConnectionConfiguration config) {
        update("INSERT INTO dc_device_connection(device_id, connection_json) VALUES (?, ?)",
                config.deviceId(), config.configJson());
    }

    public void insertPoint(PointConfiguration point, String code) {
        update("INSERT INTO dc_device_point(point_id, device_id, sort_order, status, point_code, point_json) VALUES (?, ?, ?, ?, ?, ?)",
                point.pointId(), point.deviceId(), point.sortOrder(), point.status(), code, point.configJson());
    }

    public void deleteDevice(String id) {
        update("DELETE FROM dc_device WHERE device_id=?", id);
    }

    public void insertProtocolProfile(String id, String protocol, String json, long revision) {
        update("INSERT INTO dc_protocol_profile VALUES (?, ?, ?, ?)", id, protocol, json, revision);
    }

    public void updateProtocolProfile(String id, String protocol, String json, long revision) {
        update("UPDATE dc_protocol_profile SET protocol_type=?, connection_json=?, revision=? WHERE profile_id=?",
                protocol, json, revision, id);
    }

    public void deleteProtocolProfile(String id) {
        update("DELETE FROM dc_protocol_profile WHERE profile_id=?", id);
    }

    public void insertGatewaySetting(String key, String json, long revision) {
        update("INSERT INTO dc_gateway_setting VALUES (?, ?, ?)", key, json, revision);
    }

    public void updateGatewaySetting(String key, String json, long revision) {
        update("UPDATE dc_gateway_setting SET config_json=?, revision=? WHERE setting_key=?", json, revision, key);
    }

    public void deleteGatewaySetting(String key) {
        update("DELETE FROM dc_gateway_setting WHERE setting_key=?", key);
    }

    public void insertRemoteSnapshot(String source, String id, String version, String json, long revision) {
        update("INSERT INTO dc_remote_snapshot VALUES (?, ?, ?, ?, ?)", source, id, version, json, revision);
    }

    public void updateRemoteSnapshot(String source, String id, String version, String json, long revision) {
        update("UPDATE dc_remote_snapshot SET config_json=?, revision=? WHERE source=? AND snapshot_id=? AND source_version=?",
                json, revision, source, id, version);
    }

    public void deleteRemoteSnapshot(String source, String id, String version) {
        update("DELETE FROM dc_remote_snapshot WHERE source=? AND snapshot_id=? AND source_version=?", source, id, version);
    }

    public void insertMigration(MigrationRecord record) {
        update("INSERT INTO dc_json_migration VALUES (?, ?, ?, ?, ?, ?, ?)", record.migrationId(),
                record.sourcePath(), record.sourceSha256(), record.deviceCount(), record.connectionCount(),
                record.pointCount(), record.completedAt().toString());
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }
}

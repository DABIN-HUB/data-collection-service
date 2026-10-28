package com.wangbin.collector.config.sqlite.diagnostic;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 仅供受控进程内诊断使用；不经会收紧文件权限的配置仓储入口。 */
public final class SqliteRevisionSnapshot {
    private static final int APP_ID = 0x57424346;

    private SqliteRevisionSnapshot() { }

    public record Revision(long revision, String operation, String createdAt) { }
    public record Snapshot(long currentRevision, long deviceRevision, List<String> pointIds,
                           List<Revision> recentDeviceRevisions) { }

    /**
     * 在单一短读事务中取得相互关联的持久修订；附属文件采样只在连接活跃、事务开始前执行。
     * 调用方持有该连接的引用仅用于超时关闭，不能复用连接写入配置。
     */
    public static Snapshot capture(Path database, String deviceId, AtomicReference<Connection> active,
                                   Runnable sidecarCapture) {
        if (deviceId == null || deviceId.isBlank() || active == null || sidecarCapture == null) {
            throw new IllegalArgumentException("诊断目标不完整");
        }
        try {
            if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS) || Files.size(database) == 0) {
                throw new IllegalStateException("诊断数据库不存在");
            }
            String uri = database.toUri().toASCIIString();
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + uri + "?mode=ro");
            try (connection) {
                active.set(connection);
                    execute(connection, "PRAGMA busy_timeout=250");
                    execute(connection, "PRAGMA query_only=ON");
                    if (number(connection, "PRAGMA query_only") != 1
                            || number(connection, "PRAGMA application_id") != APP_ID
                            || number(connection, "PRAGMA user_version") != 1
                            || !"wal".equalsIgnoreCase(text(connection, "PRAGMA journal_mode"))) {
                        throw new IllegalStateException("数据库身份或只读状态不符合要求");
                    }
                    sidecarCapture.run();
                    execute(connection, "BEGIN");
                    try {
                        long current = number(connection, "SELECT coalesce(max(revision), 0) FROM dc_config_revision");
                        long deviceRevision;
                        try (PreparedStatement statement = prepare(connection,
                                "SELECT revision FROM dc_device WHERE device_id=?", deviceId);
                             ResultSet rows = statement.executeQuery()) {
                            if (!rows.next()) throw new IllegalStateException("目标设备不存在");
                            deviceRevision = rows.getLong(1);
                            if (rows.next()) throw new IllegalStateException("设备身份重复");
                        }
                        List<String> points = new ArrayList<>(2);
                        try (PreparedStatement statement = prepare(connection,
                                "SELECT point_id FROM dc_device_point WHERE device_id=? ORDER BY sort_order LIMIT 3", deviceId);
                             ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) points.add(rows.getString(1));
                        }
                        if (points.size() != 2) throw new IllegalStateException("设备点位身份不符合要求");
                        List<Revision> revisions = new ArrayList<>(5);
                        try (PreparedStatement statement = prepare(connection,
                                "SELECT revision, operation, created_at FROM dc_config_revision "
                                        + "WHERE entity_type='DEVICE' AND entity_id=? ORDER BY revision DESC LIMIT 5", deviceId);
                             ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) revisions.add(new Revision(rows.getLong(1), rows.getString(2), rows.getString(3)));
                        }
                        return new Snapshot(current, deviceRevision, List.copyOf(points), List.copyOf(revisions));
                    } finally {
                        execute(connection, "ROLLBACK");
                    }
            } finally {
                active.compareAndSet(connection, null);
            }
        } catch (SQLException | java.io.IOException exception) {
            // 原始驱动错误和路径可能带有用户配置，不允许越过诊断边界。
            throw new IllegalStateException("SQLite 受控诊断失败");
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, String deviceId) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(1);
            statement.setString(1, deviceId);
            return statement;
        } catch (SQLException exception) {
            statement.close();
            throw exception;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            statement.execute(sql);
        }
    }

    private static long number(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            try (ResultSet rows = statement.executeQuery(sql)) {
                if (!rows.next()) throw new IllegalStateException("数据库诊断值缺失");
                return rows.getLong(1);
            }
        }
    }

    private static String text(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            try (ResultSet rows = statement.executeQuery(sql)) {
                if (!rows.next()) throw new IllegalStateException("数据库诊断值缺失");
                return rows.getString(1);
            }
        }
    }
}

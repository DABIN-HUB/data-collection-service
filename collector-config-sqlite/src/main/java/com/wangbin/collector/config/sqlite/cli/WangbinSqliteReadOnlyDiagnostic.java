package com.wangbin.collector.config.sqlite.cli;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** R2-A2 受限离线诊断；不经 Spring、HTTP 或具有写入副作用的 openExisting 入口。 */
public final class WangbinSqliteReadOnlyDiagnostic {
    private static final String RUN_ID = "20261027-083137";
    private static final String DEVICE_ID = "wangbin-r2a2-r1-device-20261027";
    private static final Path LIVE_DATABASE = Path.of("C:/ProgramData/WangbinCollector-R2A2/"
            + RUN_ID + "/data/collector/data/sqlite/collector.db").toAbsolutePath().normalize();
    private static final Path ISOLATED_ROOT = Path.of("C:/Users/wangbin/AppData/Local/hermes/cache/scratch/"
            + "r2a2-r5-isolated").toAbsolutePath().normalize();
    private static final Path DIAGNOSTIC_ROOT = Path.of("C:/Program Files/WangbinCollector-R2A2/"
            + RUN_ID + "/code/diagnostics").toAbsolutePath().normalize();
    private static final Path CURRENT_JAR = Path.of("C:/Program Files/WangbinCollector-R2A2/"
            + RUN_ID + "/code/collector/collector.jar").toAbsolutePath().normalize();
    private static final String SERVICE_ACCOUNT = "NT SERVICE\\WangbinCollector-R2A2-Collector";
    private static final int APP_ID = 0x57424346;
    private static final ObjectMapper JSON = new ObjectMapper();

    private WangbinSqliteReadOnlyDiagnostic() { }

    public static void main(String[] args) {
        if (run(args) != 0) System.exit(1);
    }

    /** 仅接受本轮设备；主库路径必须是本轮现役库或受控 scratch 隔离库。 */
    public static int run(String[] args) {
        try {
            if (args.length != 4 || !"--database".equals(args[0]) || !"--artifact".equals(args[2])) {
                throw new IllegalArgumentException();
            }
            Path database = Path.of(args[1]).toAbsolutePath().normalize();
            Path artifact = Path.of(args[3]).toAbsolutePath().normalize();
            boolean live = database.equals(LIVE_DATABASE);
            if (live && !SERVICE_ACCOUNT.equalsIgnoreCase(authenticatedAccount())) {
                throw new IllegalArgumentException();
            }
            if (!live && !database.startsWith(ISOLATED_ROOT)) {
                throw new IllegalArgumentException();
            }
            if (!(live ? artifact.equals(DIAGNOSTIC_ROOT.resolve("collector-config-sqlite-r5-diagnostic.jar"))
                    : artifact.equals(ISOLATED_ROOT.resolve("collector-config-sqlite-r5-diagnostic.jar")))) {
                throw new IllegalArgumentException();
            }
            if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS) || Files.size(database) == 0
                    || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(CURRENT_JAR, LinkOption.NOFOLLOW_LINKS)
                    || hasReparseComponent(database) || hasReparseComponent(artifact)
                    || hasReparseComponent(CURRENT_JAR)) {
                throw new IllegalArgumentException();
            }
            // mode=ro 拒绝创建缺失的主库及配置写入；query_only 再限制当前连接。
            String uri = database.toUri().toASCIIString();
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + uri + "?mode=ro")) {
                execute(connection, "PRAGMA busy_timeout=2000");
                execute(connection, "PRAGMA query_only=ON");
                if (number(connection, "PRAGMA query_only") != 1 || number(connection, "PRAGMA application_id") != APP_ID
                        || number(connection, "PRAGMA user_version") != 1
                        || !"wal".equalsIgnoreCase(text(connection, "PRAGMA journal_mode"))) {
                    throw new IllegalStateException();
                }
                execute(connection, "BEGIN");
                try {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("runId", RUN_ID);
                    result.put("deviceId", DEVICE_ID);
                    result.put("currentRevision", number(connection,
                            "SELECT coalesce(max(revision), 0) FROM dc_config_revision"));
                    try (PreparedStatement device = connection.prepareStatement(
                            "SELECT revision FROM dc_device WHERE device_id=?")) {
                        device.setString(1, DEVICE_ID);
                        try (ResultSet rows = device.executeQuery()) {
                            if (!rows.next()) throw new IllegalStateException();
                            result.put("deviceRevision", rows.getLong(1));
                            if (rows.next()) throw new IllegalStateException();
                        }
                    }
                    List<String> pointIds = new ArrayList<>();
                    try (PreparedStatement points = connection.prepareStatement(
                            "SELECT point_id FROM dc_device_point WHERE device_id=? ORDER BY sort_order")) {
                        points.setString(1, DEVICE_ID);
                        try (ResultSet rows = points.executeQuery()) {
                            while (rows.next()) pointIds.add(rows.getString(1));
                        }
                    }
                    if (pointIds.size() != 2) throw new IllegalStateException();
                    result.put("pointIds", pointIds);
                    List<Map<String, Object>> revisions = new ArrayList<>();
                    try (PreparedStatement history = connection.prepareStatement(
                            "SELECT revision, operation, created_at FROM dc_config_revision "
                                    + "WHERE entity_type='DEVICE' AND entity_id=? ORDER BY revision DESC LIMIT 5")) {
                        history.setString(1, DEVICE_ID);
                        try (ResultSet rows = history.executeQuery()) {
                            while (rows.next()) {
                                revisions.add(Map.of("revision", rows.getLong(1), "operation", rows.getString(2),
                                        "createdAt", rows.getString(3)));
                            }
                        }
                    }
                    result.put("recentDeviceRevisions", revisions);
                    // 进程外不能读取 ConfigManager 内存；由现有认证 API 独立取证，不伪造数值。
                    result.put("configVersion", null);
                    result.put("currentJarSha256", sha256(CURRENT_JAR));
                    result.put("diagnosticAt", Instant.now().toString());
                    System.out.println(JSON.writeValueAsString(result));
                } finally {
                    execute(connection, "ROLLBACK");
                }
            }
            return 0;
        } catch (Exception exception) {
            // 不回显路径、SQL、凭据、数据库配置或驱动原始异常。
            System.err.println("READ_ONLY_DIAGNOSTIC_REJECTED");
            return 1;
        }
    }

    private static String authenticatedAccount() {
        var identity = new com.sun.security.auth.module.NTSystem();
        String domain = identity.getDomain();
        return (domain == null || domain.isBlank() ? "" : domain + "\\") + identity.getName();
    }

    private static boolean hasReparseComponent(Path path) throws Exception {
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(cursor, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) return true;
            }
        }
        return false;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] block = new byte[8192];
            for (int count; (count = input.read(block)) != -1;) digest.update(block, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static long number(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new IllegalStateException();
            return rows.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new IllegalStateException();
            return rows.getString(1);
        }
    }
}

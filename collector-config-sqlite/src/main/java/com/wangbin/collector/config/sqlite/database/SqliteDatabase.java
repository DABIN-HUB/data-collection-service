package com.wangbin.collector.config.sqlite.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 私有 JDBC 资源所有者。已有文件始终以 URI mode=rw 连接，禁止隐式创建丢失的库。 */
public final class SqliteDatabase {
    private static final int APP_ID = 0x57424346;
    private static final String IDENTITY = "wangbin-collector-config";
    private static final String SCRIPT = "V001__wangbin_configuration.sql";
    private static final int VERSION = 1;
    private final Path path;
    private final int busyTimeoutMs;

    private SqliteDatabase(Path path, int busyTimeoutMs) {
        this.path = path;
        this.busyTimeoutMs = busyTimeoutMs;
    }

    /** 仅为全新目录取得初始化资格；失败保留安装证据，不接管已有目录。 */
    public static void claimNewInstallationDirectory(Path path, int busyTimeoutMs) {
        validateTimeout(busyTimeoutMs);
        try {
            SqliteFileSecurity.claimNewInstallationDirectory(path);
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException("首次安装目录不可创建或不可确认，请检查运行账号权限；禁止自动重建");
        }
    }

    /** 仅在成功新建或校验已有数据库后，由安装入口补齐保护目录。 */
    public static void ensureInstallationGuard(Path path) {
        try {
            SqliteFileSecurity.ensureInstallationGuard(path);
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException("安装保护目录不可用，请检查运行账号权限；禁止忽略安装证据");
        }
    }

    public static SqliteDatabase createNew(Path path, int busyTimeoutMs) {
        validateTimeout(busyTimeoutMs);
        try {
            Path target = SqliteFileSecurity.prepare(path, true);
            if (Files.getFileStore(target.getParent()).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(target);
            }
            SqliteFileSecurity.restrict(target, false);
            SqliteDatabase database = new SqliteDatabase(target, busyTimeoutMs);
            try (Connection connection = database.connect(false)) {
                String script = loadScript();
                connection.setAutoCommit(false);
                try {
                    for (String statement : statements(script)) execute(connection, statement);
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO dc_schema_version VALUES (?, ?, ?, ?, ?)")) {
                        insert.setInt(1, VERSION);
                        insert.setString(2, SCRIPT);
                        insert.setString(3, checksum(script));
                        insert.setString(4, IDENTITY);
                        insert.setString(5, Instant.now().toString());
                        insert.executeUpdate();
                    }
                    execute(connection, "PRAGMA application_id=" + APP_ID);
                    execute(connection, "PRAGMA user_version=" + VERSION);
                    connection.commit();
                } catch (SQLException | RuntimeException exception) {
                    rollback(connection);
                    throw exception;
                }
                connection.setAutoCommit(true);
                database.validateSchema(connection);
                initializeWal(connection);
            }
            database.secureFiles();
            return database;
        } catch (SQLException exception) {
            throw failure(exception);
        } catch (SecurityException exception) {
            throw new IllegalStateException("数据库文件权限不可用");
        } catch (RuntimeException exception) {
            if (Arrays.stream(exception.getSuppressed()).anyMatch(SQLException.class::isInstance)) {
                throw new IllegalStateException("数据库资源释放失败");
            }
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("数据库新建失败：文件或权限不可用");
        }
    }

    public static SqliteDatabase openExisting(Path path, int busyTimeoutMs) {
        validateTimeout(busyTimeoutMs);
        try {
            Path target = SqliteFileSecurity.prepare(path, false);
            if (Files.size(target) == 0) throw new IllegalStateException("已有数据库为空文件，禁止重建");
            SqliteDatabase database = new SqliteDatabase(target, busyTimeoutMs);
            try (Connection connection = database.connect(true)) {
                database.validateSchema(connection);
                initializeWal(connection);
            }
            database.secureFiles();
            return database;
        } catch (SQLException exception) {
            throw failure(exception);
        } catch (SecurityException exception) {
            throw new IllegalStateException("数据库文件权限不可用");
        } catch (RuntimeException exception) {
            if (Arrays.stream(exception.getSuppressed()).anyMatch(SQLException.class::isInstance)) {
                throw new IllegalStateException("数据库资源释放失败");
            }
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("已有数据库不可访问或权限不可用");
        }
    }

    /** 每次调用新建并校验一个物理连接；由 HikariCP 的物理连接来源调用。 */
    public Connection openConnection() {
        try {
            Connection connection = connect(true);
            try {
                if (!"wal".equalsIgnoreCase(text(connection, "PRAGMA journal_mode"))) {
                    throw new IllegalStateException("数据库 WAL 模式不符合要求");
                }
                secureFiles();
                return connection;
            } catch (RuntimeException | SQLException exception) {
                try { connection.close(); } catch (SQLException ignored) { /* 不传播可能包含敏感内容的驱动异常。 */ }
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public Path path() {
        return path;
    }

    public int busyTimeoutMs() {
        return busyTimeoutMs;
    }

    private Connection connect(boolean requireContent) throws SQLException {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || (requireContent && Files.size(path) == 0)) {
                throw new IllegalStateException("数据库文件已丢失或为空，禁止隐式创建");
            }
            SqliteFileSecurity.requireTrustedFiles(path);
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException("数据库文件不可访问");
        }
        Connection connection = DriverManager.getConnection(SqlitePaths.readWriteJdbcUrl(path.toUri()));
        try {
            execute(connection, "PRAGMA busy_timeout=" + busyTimeoutMs);
            execute(connection, "PRAGMA foreign_keys=ON");
            execute(connection, "PRAGMA synchronous=FULL");
            if (number(connection, "PRAGMA busy_timeout") != busyTimeoutMs
                    || number(connection, "PRAGMA foreign_keys") != 1 || number(connection, "PRAGMA synchronous") != 2) {
                throw new IllegalStateException("数据库连接 PRAGMA 回读校验失败");
            }
            return connection;
        } catch (SQLException | RuntimeException exception) {
            try { connection.close(); } catch (SQLException ignored) { /* 保留受控错误，不传播原始驱动异常。 */ }
            throw exception;
        }
    }

    public void secureFiles() {
        try { SqliteFileSecurity.secureSidecars(path); }
        catch (IOException | SecurityException exception) { throw new IllegalStateException("数据库文件权限校验失败"); }
    }

    private void validateSchema(Connection connection) throws SQLException {
        if (number(connection, "PRAGMA application_id") != APP_ID || number(connection, "PRAGMA user_version") != VERSION) {
            throw new IllegalStateException("数据库应用身份或 Schema 版本未知、不兼容");
        }
        String script = loadScript();
        try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery(
                "SELECT version, script_name, checksum, app_identity FROM dc_schema_version")) {
            if (!rows.next() || rows.getInt(1) != VERSION || !SCRIPT.equals(rows.getString(2))
                    || !checksum(script).equals(rows.getString(3)) || !IDENTITY.equals(rows.getString(4)) || rows.next()) {
                throw new IllegalStateException("数据库 Schema 脚本身份或校验和不匹配");
            }
        }
        Map<String, String> expected = new HashMap<>();
        for (String statement : statements(script)) {
            String[] tokens = statement.split("\\s+", 5);
            expected.put(tokens[2], normalizeSql(statement));
        }
        try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery(
                "SELECT name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
            while (rows.next()) {
                String sql = expected.remove(rows.getString(1));
                if (sql == null || !sql.equals(normalizeSql(rows.getString(2)))) {
                    throw new IllegalStateException("数据库 Schema 结构未知或被修改");
                }
            }
        }
        if (!expected.isEmpty() || !"ok".equals(text(connection, "PRAGMA quick_check"))) {
            throw new IllegalStateException("数据库结构不完整或完整性检查失败");
        }
        try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("PRAGMA foreign_key_check")) {
            if (rows.next()) throw new IllegalStateException("数据库外键完整性检查失败");
        }
    }

    private static String normalizeSql(String sql) { return sql.replaceAll("\\s+", " ").trim(); }
    private static List<String> statements(String script) {
        return Arrays.stream(script.split(";")).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }
    private static String loadScript() {
        try (InputStream stream = SqliteDatabase.class.getResourceAsStream("/db/migration/" + SCRIPT)) {
            if (stream == null) throw new IllegalStateException("缺少数据库 Schema 类路径资源");
            return canonicalScript(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) { throw new IllegalStateException("数据库 Schema 资源读取失败"); }
    }
    /** 换行不属于迁移语义，避免 Git 在 Windows/Linux 检出时造成虚假的脚本篡改。 */
    static String canonicalScript(String script) {
        return script.replace("\r\n", "\n").replace('\r', '\n');
    }
    private static String checksum(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用"); }
    }
    private static void initializeWal(Connection connection) throws SQLException {
        if (!"wal".equalsIgnoreCase(text(connection, "PRAGMA journal_mode=WAL"))
                || !"wal".equalsIgnoreCase(text(connection, "PRAGMA journal_mode"))) {
            throw new IllegalStateException("数据库无法启用 WAL 模式");
        }
    }
    private static void validateTimeout(int value) {
        if (value <= 0) throw new IllegalArgumentException("数据库 busyTimeoutMs 必须为正数");
    }
    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static long number(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new IllegalStateException("数据库 PRAGMA 无回读值");
            return rows.getLong(1);
        }
    }
    private static String text(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new IllegalStateException("数据库检查无回读值");
            return rows.getString(1);
        }
    }
    public static void rollback(Connection connection) {
        try { connection.rollback(); } catch (SQLException ignored) { /* 原始驱动错误不离开私有 JDBC 边界。 */ }
    }
    /** 只使用数字错误类别，不传播 SQL、参数、文件名、驱动 cause 或驱动错误文本。 */
    public static IllegalStateException failure(SQLException exception) {
        return switch (exception.getErrorCode() & 0xff) {
            case 5, 6 -> new IllegalStateException("数据库忙或被占用，请稍后重试");
            case 19 -> new IllegalStateException("数据库配置约束冲突");
            case 8 -> new IllegalStateException("数据库为只读");
            case 11, 26 -> new IllegalStateException("数据库损坏或不是受支持的数据库");
            default -> new IllegalStateException("数据库操作失败");
        };
    }
}

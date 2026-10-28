package com.wangbin.collector.boot.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.boot.config.WangbinConfigSqliteProperties;
import com.wangbin.collector.config.sqlite.database.SqlitePaths;
import com.wangbin.collector.config.sqlite.diagnostic.SqliteRevisionSnapshot;
import com.wangbin.collector.core.config.manager.ConfigManager;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.sql.Connection;

/** 默认不装配的 R2-A2 一次性受保护指令入口；不提供 HTTP 或 TCP 监听。 */
@Component
@ConditionalOnProperty(prefix = "wangbin.r2a2.diagnostic", name = "enabled", havingValue = "true")
public final class R2SqliteInProcessDiagnostic {
    private static final long SOFT_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(4);
    private static final String RUN_ID = "20261027-083137";
    private static final String DEVICE_ID = "wangbin-r2a2-r1-device-20261027";
    private static final Path CODE = Path.of("C:/Program Files/WangbinCollector-R2A2/" + RUN_ID + "/code");
    private static final Path DATA = Path.of("C:/ProgramData/WangbinCollector-R2A2/" + RUN_ID + "/data/collector/data");
    private static final Path DATABASE = DATA.resolve("sqlite/collector.db");
    private static final Path JAR = CODE.resolve("collector/collector.jar");
    private static final Path AUTH = CODE.resolve("diagnostics");
    private static final Path REQUEST = AUTH.resolve("request.txt");
    private static final Path OUTPUT = DATA.resolve("diagnostics");
    private static final String POINT_A = "wangbin-r2a2-r1-point-20261027";
    private static final String POINT_B = "wangbin-r2a2-r3-point-b-20261027";
    private final Environment environment;
    private final WangbinConfigSqliteProperties properties;
    private final ConfigManager manager;
    private final ObjectMapper json;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "r2a2-sqlite-diagnostic");
        thread.setDaemon(true);
        return thread;
    });
    private boolean disabled;
    private int attempts;

    public R2SqliteInProcessDiagnostic(Environment environment, WangbinConfigSqliteProperties properties,
                                       ConfigManager manager, ObjectMapper json) {
        this.environment = environment;
        this.properties = properties;
        this.manager = manager;
        this.json = json;
    }

    /** 缺失授权目录时静默不执行；启用开关仅在受控服务进程启动配置中生效。 */
    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    public synchronized void poll() {
        if (disabled || attempts >= 4) return;
        long started = System.nanoTime();
        try {
            // 先证实当前进程令牌和物理数据库属于固定隔离实例，拒绝任意属性注入更换数据源。
            WindowsDiagnosticAcl.TokenEvidence token = WindowsDiagnosticAcl.requireServiceToken();
            if (Files.notExists(REQUEST, LinkOption.NOFOLLOW_LINKS)) return;
            if (!"sqlite".equals(environment.getProperty("wangbin.config.persistence.mode", "sqlite"))
                    || !properties.isEnabled() || !SqlitePaths.resolve(properties.getDatabasePath()).equals(DATABASE)
                    || !Files.isRegularFile(JAR, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("实例身份不符合要求");
            }
            requireNoReparse(CODE);
            requireNoReparse(AUTH);
            requireNoReparse(REQUEST);
            requireNoReparse(DATA);
            requireNoReparse(OUTPUT);
            requireNoReparse(DATABASE);
            requireNoReparse(JAR);
            WindowsDiagnosticAcl.requireProtectedInstruction(AUTH, true);
            WindowsDiagnosticAcl.requireProtectedInstruction(REQUEST, false);
            WindowsDiagnosticAcl.requirePrivateOutput(OUTPUT);
            Request request = parseRequest();
            Path receipt = OUTPUT.resolve(request.id() + ".claimed");
            try {
                // 先持久领取，后打开数据库；服务重启、指令重复出现均不得再次执行。
                Files.writeString(receipt, "CLAIMED " + request.approvalId() + " " + Instant.now() + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException replay) {
                return;
            }
            attempts++;
            requireBudget(started);
            AtomicReference<Connection> active = new AtomicReference<>();
            AtomicReference<List<WindowsDiagnosticAcl.FileEvidence>> acl = new AtomicReference<>();
            Future<Capture> task = executor.submit(() -> {
                String jarSha = sha256(JAR);
                SqliteRevisionSnapshot.Snapshot snapshot = SqliteRevisionSnapshot.capture(
                        DATABASE, DEVICE_ID, active, () -> acl.set(List.of(
                                WindowsDiagnosticAcl.inspectDatabaseFile(DATABASE, true),
                                WindowsDiagnosticAcl.inspectDatabaseFile(Path.of(DATABASE + "-wal"), false),
                                WindowsDiagnosticAcl.inspectDatabaseFile(Path.of(DATABASE + "-shm"), false))));
                return new Capture(snapshot, jarSha);
            });
            Capture capture;
            try {
                capture = task.get(requireBudget(started), TimeUnit.NANOSECONDS);
            } catch (Exception failure) {
                task.cancel(true);
                Connection connection = active.get();
                if (connection != null) {
                    // 不在 Spring 调度线程等待不受控的原生 JDBC close；仍不能证明驱动已经退出。
                    Thread closer = new Thread(() -> {
                        try { connection.close(); } catch (Exception ignored) { /* 不输出驱动详情。 */ }
                    }, "r2a2-sqlite-close");
                    closer.setDaemon(true);
                    closer.start();
                }
                disabled = true;
                throw new IllegalStateException("诊断超时或取消；本次 JVM 已禁用后续诊断");
            }
            SqliteRevisionSnapshot.Snapshot snapshot = capture.snapshot();
            if (!Set.of(POINT_A, POINT_B).equals(Set.copyOf(snapshot.pointIds()))) {
                throw new IllegalStateException("点位身份不符合要求");
            }
            requireBudget(started);
            Map<String, Object> result = Map.ofEntries(
                    Map.entry("runId", RUN_ID), Map.entry("requestId", request.id()),
                    Map.entry("approvalId", request.approvalId()),
                    Map.entry("deviceId", DEVICE_ID), Map.entry("pointIds", snapshot.pointIds()),
                    Map.entry("currentRevision", snapshot.currentRevision()),
                    Map.entry("deviceRevision", snapshot.deviceRevision()),
                    Map.entry("recentDeviceRevisions", snapshot.recentDeviceRevisions()),
                    Map.entry("jvmConfigVersionAfterSnapshot", manager.getDeviceConfigVersion(DEVICE_ID)),
                    Map.entry("jarSha256", capture.jarSha()), Map.entry("capturedAt", Instant.now().toString()),
                    Map.entry("token", token), Map.entry("files", acl.get()));
            Path resultFile = OUTPUT.resolve(request.id() + ".json");
            Path pending = OUTPUT.resolve(request.id() + ".partial");
            try {
                Files.writeString(pending, json.writeValueAsString(result), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                requireBudget(started);
                Files.move(pending, resultFile, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(pending);
            }
            disabled = true;
            executor.shutdown();
        } catch (Exception failure) {
            disabled = true;
            executor.shutdownNow();
            // 错误原因可能由文件/数据库提供，不记录异常正文、路径或敏感负载。
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "R2-A2 受控诊断拒绝或失败；本进程已禁用诊断");
        }
    }

    /** 单调时钟只用于软预算，原生阻塞与磁盘写入仍没有可证明的硬时限。 */
    private static long requireBudget(long started) {
        long remaining = SOFT_BUDGET_NANOS - (System.nanoTime() - started);
        if (remaining <= 0) throw new IllegalStateException("诊断已超过软预算");
        return remaining;
    }

    private Request parseRequest() throws Exception {
        if (Files.size(REQUEST) > 1024) throw new IllegalStateException("指令大小超限");
        List<String> lines = Files.readAllLines(REQUEST, StandardCharsets.UTF_8);
        if (lines.size() != 6 || !lines.get(0).equals("runId=" + RUN_ID)
                || !lines.get(1).equals("deviceId=" + DEVICE_ID)
                || !lines.get(2).equals("pointIds=" + POINT_A + "," + POINT_B)
                || !lines.get(3).matches("requestId=[0-9a-f-]{36}")
                || !lines.get(4).startsWith("expiresAt=")
                || !lines.get(5).matches("approvalId=[A-Za-z0-9-]{8,64}")) {
            throw new IllegalStateException("指令格式拒绝");
        }
        String id = lines.get(3).substring("requestId=".length());
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalStateException("指令编号拒绝");
        Instant expiry = Instant.parse(lines.get(4).substring("expiresAt=".length()));
        Instant now = Instant.now();
        if (!expiry.isAfter(now) || expiry.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw new IllegalStateException("指令已过期或期限过长");
        }
        return new Request(id, lines.get(5).substring("approvalId=".length()));
    }

    private static void requireNoReparse(Path path) throws Exception {
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(cursor, java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IllegalStateException("路径重解析拒绝");
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int length; (length = stream.read(buffer)) != -1;) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @PreDestroy
    public void close() {
        disabled = true;
        executor.shutdownNow();
    }

    private record Request(String id, String approvalId) { }
    private record Capture(SqliteRevisionSnapshot.Snapshot snapshot, String jarSha) { }
}

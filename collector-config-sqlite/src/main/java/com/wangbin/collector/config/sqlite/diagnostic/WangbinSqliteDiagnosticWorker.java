package com.wangbin.collector.config.sqlite.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.config.sqlite.database.SqlitePaths;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 由 Collector 受控启动的单次 SQLite 诊断工作进程入口。
 *
 * <p>生产模式只接受固定实例、固定构件和固定数据库路径；isolated 模式仅用于本轮 scratch
 * 合成库验证，不授予现役访问权限。数据库连接关闭后才计算构件哈希并写入结果。</p>
 */
public final class WangbinSqliteDiagnosticWorker {
    private static final String RUN_ID = "20261027-083137";
    private static final String DEVICE_ID = "wangbin-r2a2-r1-device-20261027";
    private static final String POINT_A = "wangbin-r2a2-r1-point-20261027";
    private static final String POINT_B = "wangbin-r2a2-r3-point-b-20261027";
    private static final String SERVICE_SID = "S-1-5-80-3842808028-2956155112-34687348-1206607495-337597549";
    private static final Path LIVE_DATABASE = Path.of("C:/ProgramData/WangbinCollector-R2A2/"
            + RUN_ID + "/data/collector/data/sqlite/collector.db").toAbsolutePath().normalize();
    private static final Path LIVE_ARTIFACT = Path.of("C:/Program Files/WangbinCollector-R2A2/"
            + RUN_ID + "/code/diagnostics/r2a2-sqlite-worker.jar").toAbsolutePath().normalize();
    private static final Path LIVE_OUTPUT_ROOT = Path.of("C:/ProgramData/WangbinCollector-R2A2/"
            + RUN_ID + "/data/collector/data/diagnostics").toAbsolutePath().normalize();
    private static final Path ISOLATED_ROOT = Path.of("C:/Users/wangbin/AppData/Local/hermes/cache/scratch/"
            + "r2a2-r9/isolated").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();

    private WangbinSqliteDiagnosticWorker() {
    }

    public static void main(String[] args) {
        int exit = run(args);
        if (exit != 0) System.exit(exit);
    }

    public static int run(String[] args) {
        try {
            Request request = Request.parse(args);
            validate(request);
            if ("service".equals(request.mode())) requireServiceToken();
            AtomicReference<List<SqliteDiagnosticFileAudit.Evidence>> files = new AtomicReference<>();
            SqliteRevisionSnapshot.Snapshot snapshot = SqliteRevisionSnapshot.capture(
                    request.database(), DEVICE_ID, new AtomicReference<>(), () -> files.set(List.of(
                            SqliteDiagnosticFileAudit.inspect(request.database()),
                            SqliteDiagnosticFileAudit.inspect(Path.of(request.database() + "-wal")),
                            SqliteDiagnosticFileAudit.inspect(Path.of(request.database() + "-shm"))
                    )));
            if (!List.of(POINT_A, POINT_B).equals(snapshot.pointIds())) {
                throw new IllegalStateException("诊断点位身份不符合要求");
            }
            String artifactSha256 = sha256(request.artifact());
            if (!artifactSha256.equalsIgnoreCase(request.expectedSha256())) {
                throw new IllegalStateException("诊断构件校验失败");
            }
            List<Map<String, Object>> fileResult = files.get().stream().map(file -> Map.<String, Object>ofEntries(
                    Map.entry("status", file.status()),
                    Map.entry("sampledAt", file.sampledAt()),
                    Map.entry("ownerSid", file.ownerSid() == null ? "ABSENT_OR_UNAVAILABLE" : file.ownerSid()),
                    Map.entry("daclProtected", file.daclProtected()),
                    Map.entry("aceCount", file.aceCount()))).toList();
            List<Map<String, Object>> revisionResult = snapshot.recentDeviceRevisions().stream()
                    .map(revision -> Map.<String, Object>ofEntries(
                            Map.entry("revision", revision.revision()),
                            Map.entry("operation", revision.operation()),
                            Map.entry("createdAt", revision.createdAt()))).toList();
            Map<String, Object> result = Map.ofEntries(
                    Map.entry("runId", RUN_ID),
                    Map.entry("deviceId", DEVICE_ID),
                    Map.entry("pointIds", snapshot.pointIds()),
                    Map.entry("currentRevision", snapshot.currentRevision()),
                    Map.entry("deviceRevision", snapshot.deviceRevision()),
                    Map.entry("recentDeviceRevisions", revisionResult),
                    Map.entry("tokenMode", request.mode()),
                    Map.entry("tokenUserSid", "service".equals(request.mode()) ? SERVICE_SID : "ISOLATED_NOT_SERVICE"),
                    Map.entry("files", fileResult),
                    Map.entry("workerJarSha256", artifactSha256),
                    Map.entry("capturedAt", Instant.now().toString()));
            writeResult(request.output(), result);
            return 0;
        } catch (Exception exception) {
            System.err.println("SQLITE_DIAGNOSTIC_WORKER_REJECTED");
            return 1;
        }
    }

    private static void validate(Request request) throws Exception {
        if (!request.database().isAbsolute() || !request.artifact().isAbsolute() || !request.output().isAbsolute()
                || !request.expectedSha256().matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException();
        boolean isolated = "isolated".equals(request.mode()) && request.database().startsWith(ISOLATED_ROOT)
                && request.artifact().equals(ISOLATED_ROOT.resolve("r2a2-sqlite-worker.jar"))
                && request.output().startsWith(ISOLATED_ROOT);
        boolean service = "service".equals(request.mode()) && request.database().equals(LIVE_DATABASE)
                && request.artifact().equals(LIVE_ARTIFACT) && request.output().startsWith(LIVE_OUTPUT_ROOT);
        if (!isolated && !service) throw new IllegalArgumentException();
        for (Path path : List.of(request.database(), request.artifact(), request.output())) {
            rejectReparse(path);
        }
        if (!Files.isDirectory(request.output().getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException();
        }
        if (!Files.isRegularFile(request.database(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(request.artifact(), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(request.output(), LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
    }

    private static void requireServiceToken() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) throw new IllegalStateException();
        String userSid = new com.sun.security.auth.module.NTSystem().getUserSID();
        if (!SERVICE_SID.equals(userSid)) throw new IllegalStateException();
    }

    private static void rejectReparse(Path path) throws Exception {
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && (Files.readAttributes(cursor, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isOther()
                    || Files.readAttributes(cursor, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isSymbolicLink())) throw new IllegalArgumentException();
        }
    }

    private static void writeResult(Path output, Map<String, Object> result) throws Exception {
        Path partial = output.resolveSibling(output.getFileName() + ".partial");
        try {
            Files.writeString(partial, JSON.writeValueAsString(result), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (Files.size(partial) > 65_536) throw new IllegalStateException();
            Files.move(partial, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException exception) {
            throw new IllegalStateException("诊断结果不可重放");
        } finally {
            Files.deleteIfExists(partial);
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

    private record Request(String mode, Path database, Path artifact, Path output, String expectedSha256) {
        static Request parse(String[] args) {
            if (args.length != 10 || !"--mode".equals(args[0]) || !"--database".equals(args[2])
                    || !"--artifact".equals(args[4]) || !"--output".equals(args[6])
                    || !"--sha256".equals(args[8])) throw new IllegalArgumentException();
            return new Request(args[1], SqlitePaths.resolve(args[3]).toAbsolutePath().normalize(),
                    Path.of(args[5]).toAbsolutePath().normalize(), Path.of(args[7]).toAbsolutePath().normalize(), args[9]);
        }
    }
}

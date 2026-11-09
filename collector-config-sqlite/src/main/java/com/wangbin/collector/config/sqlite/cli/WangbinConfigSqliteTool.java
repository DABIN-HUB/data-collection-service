package com.wangbin.collector.config.sqlite.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.config.sqlite.database.SqlitePaths;
import com.wangbin.collector.config.sqlite.migration.LegacyJsonMigration;
import com.wangbin.collector.config.sqlite.service.impl.SqliteConfigServiceImpl;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** wangbin 配置库显式管理入口；不启动 Spring、设备、Redis 或 TDengine。 */
public final class WangbinConfigSqliteTool {
    private static final int BUSY_TIMEOUT_MS = 5000;

    private WangbinConfigSqliteTool() {
    }

    public static void main(String[] args) {
        int result = run(args, System.out, System.err);
        if (result != 0) System.exit(result);
    }

    /** 输出仅包含计数、摘要及受控状态，禁止打印配置或原始异常。 */
    public static int run(String[] args, PrintStream output, PrintStream errors) {
        try {
            if (args.length == 0) throw new IllegalArgumentException();
            String operation = args[0];
            Map<String, String> options = parseOptions(args);
            Path source = options.containsKey("--source") ? Path.of(options.get("--source")) : LegacyJsonMigration.defaultSource();
            Map<String, Object> result = new LinkedHashMap<>();
            switch (operation) {
                case "inspect" -> {
                    requireOptions(options, "--source");
                    var inspection = LegacyJsonMigration.inspect(source);
                    result.put("status", inspection.canMigrate() ? "VALID" : "REJECTED");
                    result.put("inspection", inspection);
                    output.println(new ObjectMapper().writeValueAsString(result));
                    return inspection.canMigrate() ? 0 : 2;
                }
                case "init" -> {
                    requireOptions(options, "--database", "--fresh-install");
                    if (!options.containsKey("--fresh-install")) throw new IllegalArgumentException();
                    try (var ignored = WangbinInstallationBootstrap.initializeNewInstallation(
                            SqlitePaths.resolve(options.get("--database")), BUSY_TIMEOUT_MS, true)) {
                        result.put("status", "INITIALIZED");
                    }
                }
                case "migrate" -> {
                    requireOptions(options, "--database", "--source");
                    try (var store = SqliteConfigServiceImpl.openExisting(SqlitePaths.resolve(options.get("--database")), BUSY_TIMEOUT_MS)) {
                        var migration = new LegacyJsonMigration(store, new ConfigurationJsonCodec()).migrate(source);
                        result.put("status", migration.status().name());
                        result.put("devices", migration.record().deviceCount());
                        result.put("connections", migration.record().connectionCount());
                        result.put("points", migration.record().pointCount());
                        result.put("sourceSha256", migration.record().sourceSha256());
                        result.put("identityAndConfigurationVerified", migration.identityAndConfigurationVerified());
                    }
                }
                default -> throw new IllegalArgumentException();
            }
            output.println(new ObjectMapper().writeValueAsString(result));
            return 0;
        } catch (IllegalArgumentException exception) {
            errors.println("wangbin 配置操作被拒绝：检查命令、选项、配置身份及来源；可先执行 inspect 获取无负载诊断。");
            return 2;
        } catch (Exception exception) {
            errors.println("wangbin 配置操作失败 [" + failureCode(exception) + "]；未自动重建数据库，未输出配置负载。");
            return 1;
        }
    }

    /** 仅输出固定诊断类别，不回显驱动异常、路径、SQL、参数或用户配置。 */
    private static String failureCode(Exception exception) {
        String message = java.util.Objects.toString(exception.getMessage(), "");
        if (message.contains("Schema") || message.contains("应用身份")) return "SCHEMA_INCOMPATIBLE";
        if (message.contains("不存在") || message.contains("丢失")) return "DB_MISSING";
        if (message.contains("空文件")) return "DB_EMPTY";
        if (message.contains("损坏")) return "DB_CORRUPT";
        if (message.contains("只读")) return "DB_READ_ONLY";
        if (message.contains("权限") || message.contains("不可访问") || message.contains("读写")) return "DB_PERMISSION";
        if (message.contains("忙") || message.contains("占用")) return "DB_BUSY";
        if (message.contains("目标已经存在")) return "DB_ALREADY_EXISTS";
        if (message.contains("要求空")) return "TARGET_NOT_EMPTY";
        if (message.contains("迁移期间发生变化")) return "SOURCE_CHANGED";
        return "RESOURCE_OR_TRANSACTION_FAILURE";
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String option = args[i];
            if (result.containsKey(option)) throw new IllegalArgumentException();
            if ("--fresh-install".equals(option)) {
                result.put(option, "true");
            } else {
                if (!("--database".equals(option) || "--source".equals(option)) || i + 1 >= args.length
                        || args[i + 1].isBlank() || args[i + 1].startsWith("--")) throw new IllegalArgumentException();
                result.put(option, args[++i]);
            }
        }
        return result;
    }

    private static void requireOptions(Map<String, String> options, String... allowed) {
        for (String key : options.keySet()) {
            if (java.util.Arrays.stream(allowed).noneMatch(key::equals)) throw new IllegalArgumentException();
        }
    }
}

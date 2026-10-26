package com.wangbin.collector.config.sqlite.database;

import java.nio.file.Path;
import java.util.Locale;

/** wangbin 配置库路径策略；不创建目录，不以用户目录替代缺失的 ProgramData。 */
public final class SqlitePaths {
    private SqlitePaths() { }

    public static Path resolve(String configuredPath) {
        return Path.of(resolveValue(configuredPath, System.getProperty("os.name"), System.getenv("ProgramData")))
                .toAbsolutePath().normalize();
    }

    /** 统一使用标准 URI 编码，问号、井号和非 ASCII 字符只能作为文件名，不能注入连接选项。 */
    static String readWriteJdbcUrl(java.net.URI fileUri) {
        if (!"file".equals(fileUri.getScheme()) || fileUri.getRawQuery() != null || fileUri.getRawFragment() != null) {
            throw new IllegalArgumentException("数据库 URI 必须是纯文件路径");
        }
        return "jdbc:sqlite:" + fileUri.toASCIIString() + "?mode=rw";
    }

    /** 与本机文件系统无关的纯路径策略，便于验证 Windows 和 Linux 默认值。 */
    public static String resolveValue(String configuredPath, String osName, String programData) {
        if (configuredPath != null && !configuredPath.isBlank()) return configuredPath;
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            if (programData == null || programData.isBlank()) {
                throw new IllegalStateException("Windows ProgramData 未配置，不能选择数据库默认目录");
            }
            return programData.replace('\\', '/').replaceAll("/+$", "") + "/WangbinCollector/data/collector.db";
        }
        if (os.contains("linux")) return "/var/lib/wangbin-collector/collector.db";
        throw new IllegalStateException("当前操作系统必须显式配置数据库路径");
    }
}

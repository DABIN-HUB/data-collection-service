package com.wangbin.collector.config.sqlite.database;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 路径策略的跨平台纯函数验证。 */
class SqlitePathsTest {
    @Test void reservedCharactersNeverBecomeSqliteUriOptions() throws Exception {
        var uri = new java.net.URI("file", "", "/private/数据 ' ? # % /db.sqlite", null);
        String encoded = SqlitePaths.readWriteJdbcUrl(uri);
        assertTrue(encoded.contains("%3F")); assertTrue(encoded.contains("%23")); assertTrue(encoded.contains("%25"));
        assertTrue(encoded.endsWith("?mode=rw")); assertEquals(encoded.indexOf('?'), encoded.lastIndexOf('?'));
        assertFalse(encoded.contains("数据")); assertFalse(encoded.contains(" "));
        assertThrows(IllegalArgumentException.class, () -> SqlitePaths.readWriteJdbcUrl(java.net.URI.create("file:/db?mode=rwc")));
    }
    @Test void linuxDefaultUsesExactApprovedLocation() {
        assertEquals("/var/lib/wangbin-collector/collector.db", SqlitePaths.resolveValue(null, "Linux", null));
    }
    @Test void windowsDefaultUsesProgramDataAndNeverUserHome() {
        assertEquals("C:/ProgramData/WangbinCollector/data/collector.db", SqlitePaths.resolveValue("", "Windows 10", "C:\\ProgramData"));
        assertEquals("D:/共享 数据/WangbinCollector/data/collector.db", SqlitePaths.resolveValue(null, "Windows Server", "D:/共享 数据/"));
        assertThrows(IllegalStateException.class, () -> SqlitePaths.resolveValue(null, "Windows", null));
        assertThrows(IllegalStateException.class, () -> SqlitePaths.resolveValue(null, "Windows", " "));
    }
    @Test void explicitPathsPreserveWhitespaceUnicodeAndReservedCharacters() {
        String input = " 数据 ' ? # % /collector.db";
        assertEquals(input, SqlitePaths.resolveValue(input, "unsupported", null));
        assertEquals(java.nio.file.Path.of("private db/数据 #.db").toAbsolutePath().normalize(), SqlitePaths.resolve("private db/数据 #.db"));
        assertThrows(IllegalStateException.class, () -> SqlitePaths.resolveValue(null, "unsupported", null));
    }
}

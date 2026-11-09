package com.wangbin.collector.boot.diagnostic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 隔离交互令牌和临时文件的权限观察，不能替代真实 SCM 服务令牌验收。 */
@EnabledOnOs(OS.WINDOWS)
class WindowsDiagnosticAclTest {
    @TempDir
    Path directory;

    @Test
    void interactiveTokenCannotActivateServiceDiagnostic() {
        assertThrows(IllegalStateException.class, WindowsDiagnosticAcl::requireServiceToken);
    }

    @Test
    void reportsPresentAndAbsentSeparatelyAndRejectsMissingMainDatabase() throws Exception {
        Path file = directory.resolve("synthetic.db");
        Files.writeString(file, "synthetic data");
        var present = WindowsDiagnosticAcl.inspect(file);
        assertEquals("PRESENT", present.status());
        assertNotNull(present.ownerSid());
        assertFalse(present.aces().isEmpty());
        assertEquals("ACE_ONLY_NOT_EFFECTIVE_ACCESS_PROOF", present.expectedServiceAccess());
        Path absent = directory.resolve("synthetic.db-wal");
        assertEquals("ABSENT", WindowsDiagnosticAcl.inspect(absent).status());
        assertThrows(IllegalStateException.class, () -> WindowsDiagnosticAcl.inspectDatabaseFile(absent, true));
    }
}

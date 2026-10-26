package com.wangbin.collector.config.sqlite.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** wangbin 显式工具不能隐式建库、忽略错误参数或输出配置负载。 */
class WangbinConfigSqliteToolTest {
    @TempDir
    Path directory;

    @Test
    void initializationNeedsExplicitFreshInstallAndCannotOverwrite() throws Exception {
        Path database = directory.resolve("private/collector.db");
        assertEquals(2, run("init", "--database", database.toString()).exitCode());
        assertFalse(Files.exists(database));
        var initialized = run("init", "--database", database.toString(), "--fresh-install");
        assertEquals(0, initialized.exitCode());
        assertEquals("INITIALIZED", new ObjectMapper().readTree(initialized.output()).path("status").asText());
        byte[] before = Files.readAllBytes(database);
        assertNotEquals(0, run("init", "--database", database.toString(), "--fresh-install").exitCode());
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void migrationDoesNotCreateMissingDatabase() {
        Path database = directory.resolve("not-created/collector.db");
        var result = run("migrate", "--database", database.toString());
        assertEquals(1, result.exitCode());
        assertTrue(result.errors().contains("DB_MISSING"));
        assertFalse(Files.exists(database));
    }

    @Test
    void explicitInspectionOutputsOnlyCountsAndControlledProblems() throws Exception {
        Path source = directory.resolve("source.json");
        Files.writeString(source, """
                [{"device":{"deviceId":"one","configSource":"local","temporaryConfig":true},
                "connection":{"deviceId":"one","password":"fixture-sensitive-value"},"points":[]}]
                """);
        var inspected = run("inspect", "--source", source.toString());
        assertEquals(0, inspected.exitCode());
        assertFalse(inspected.output().contains("fixture-sensitive-value"));
        assertFalse(inspected.output().contains("password"));
        assertEquals(1, new ObjectMapper().readTree(inspected.output()).path("inspection").path("deviceCount").asInt());
    }

    @Test
    void wrongDuplicateAndCrossCommandOptionsAreRejected() {
        assertEquals(2, run("init", "--unknown").exitCode());
        assertEquals(2, run("inspect", "--fresh-install").exitCode());
        assertEquals(2, run("migrate", "--fresh-install").exitCode());
        assertEquals(2, run("inspect", "--source", "one", "--source", "two").exitCode());
        assertEquals(2, run().exitCode());
    }

    @Test
    void reservedInstallerEntryRemainsInertWithoutDeclaration() {
        Path database = directory.resolve("not-created/collector.db");
        assertThrows(IllegalArgumentException.class,
                () -> WangbinInstallationBootstrap.initializeNewInstallation(database, 500, false));
        assertFalse(Files.exists(database));
    }

    private Result run(String... args) {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int code = WangbinConfigSqliteTool.run(args,
                new PrintStream(output, true, StandardCharsets.UTF_8), new PrintStream(errors, true, StandardCharsets.UTF_8));
        return new Result(code, output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String output, String errors) {
    }
}

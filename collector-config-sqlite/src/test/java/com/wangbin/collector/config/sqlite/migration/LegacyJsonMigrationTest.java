package com.wangbin.collector.config.sqlite.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 wangbin 旧 JSON 迁移入口的只读预检。 */
class LegacyJsonMigrationTest {
    @TempDir
    Path directory;

    @Test
    void inspectionReportsStableIdentityGapsWithoutChangingSource() throws Exception {
        Path source = directory.resolve("local-device-configs.json");
        String json = """
                [{"device":{"deviceId":"local-1","configSource":"local","temporaryConfig":true},
                  "connection":{"deviceId":"local-1","extJson":{"unknown":{"array":[1,null,true]}}},
                  "points":[{"deviceId":"local-1","pointId":"p-1"},{"deviceId":"local-1"}]}]
                """;
        Files.writeString(source, json);
        MigrationInspection inspection = LegacyJsonMigration.inspect(source);
        assertEquals(1, inspection.deviceCount());
        assertEquals(1, inspection.connectionCount());
        assertEquals(2, inspection.pointCount());
        assertEquals(1, inspection.missingPointIds());
        assertEquals(json, Files.readString(source));
    }
}

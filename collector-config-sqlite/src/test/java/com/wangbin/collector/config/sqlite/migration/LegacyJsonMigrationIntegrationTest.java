package com.wangbin.collector.config.sqlite.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用真实 wangbin SQLite 验证迁移、回滚及原始扩展，不依赖外部服务。 */
class LegacyJsonMigrationIntegrationTest {
    @TempDir
    Path directory;
    private final ConfigurationJsonCodec codec = new ConfigurationJsonCodec();

    @Test
    void importsOnceReopensAndPreservesUnknownJsonAndSourceBytes() throws Exception {
        Path source = writeSource("[" + device("local-1", "p-1") + "]");
        byte[] before = Files.readAllBytes(source);
        Path database = database();
        try (var store = SqliteConfigurationStore.createNew(database, 500)) {
            var migration = new LegacyJsonMigration(store, codec);
            var result = migration.migrate(source);
            assertEquals(MigrationResult.Status.IMPORTED, result.status());
            assertTrue(result.identityAndConfigurationVerified());
            assertEquals(1, result.record().deviceCount());
            assertEquals(1, result.record().connectionCount());
            assertEquals(1, result.record().pointCount());
            var stored = store.findDevice("local-1").orElseThrow();
            JsonNode expected = codec.readJson(Files.readString(source)).get(0);
            JsonNode actual = codec.encodeLocalDevice(stored.configuration());
            assertRawConfiguration(expected, actual);
            long revision = store.currentRevision();
            assertEquals(MigrationResult.Status.ALREADY_MIGRATED, migration.migrate(source).status());
            assertEquals(revision, store.currentRevision());
            assertEquals(1, store.listMigrations().size());
        }
        try (var store = SqliteConfigurationStore.openExisting(database, 500)) {
            assertEquals(1, store.listDevices().size());
            assertEquals(MigrationResult.Status.ALREADY_MIGRATED, new LegacyJsonMigration(store, codec).migrate(source).status());
        }
        assertArrayEquals(before, Files.readAllBytes(source));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "source", "owner", "alias", "duplicate-device"})
    void rejectsInvalidBatchWithoutAnyWrites(String kind) throws Exception {
        String first = device("local-1", "p-1");
        String second = device("local-2", "p-2");
        second = switch (kind) {
            case "missing" -> second.replace("\"pointId\":\"p-2\",", "");
            case "duplicate" -> second.replace("\"pointId\":\"p-2\"", "\"pointId\":\"p-1\"");
            case "source" -> second.replace("\"configSource\":\"local\"", "\"configSource\":\"remote\"");
            case "owner" -> second.replace("\"connection\":{\"deviceId\":\"local-2\"", "\"connection\":{\"deviceId\":\"other\"");
            case "alias" -> second.replace("\"deviceId\":\"local-2\",\"deviceName\"", "\"deviceId\":\"local-2\",\"id\":\"other\",\"deviceName\"");
            case "duplicate-device" -> second.replace("local-2", "local-1");
            default -> throw new IllegalStateException();
        };
        Path source = writeSource("[" + first + "," + second + "]");
        byte[] before = Files.readAllBytes(source);
        try (var store = SqliteConfigurationStore.createNew(database(), 500)) {
            assertFalse(LegacyJsonMigration.inspect(source).canMigrate());
            assertThrows(IllegalArgumentException.class, () -> new LegacyJsonMigration(store, codec).migrate(source));
            assertTrue(store.listDevices().isEmpty());
            assertTrue(store.listMigrations().isEmpty());
            assertEquals(0, store.currentRevision());
        }
        assertArrayEquals(before, Files.readAllBytes(source));
    }

    @Test
    void databaseFailureAfterFirstDeviceRollsBackDataRevisionAndMarker() throws Exception {
        Path source = writeSource("[" + device("local-1", "p-1") + "," + device("local-2", "p-2") + "]");
        byte[] original = Files.readAllBytes(source);
        Path database = database();
        try (var store = SqliteConfigurationStore.createNew(database, 500)) {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=rw");
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER wangbin_reject_second BEFORE INSERT ON dc_device "
                        + "WHEN NEW.device_id='local-2' BEGIN SELECT RAISE(ABORT,'wangbin controlled failure'); END");
            }
            assertTrue(LegacyJsonMigration.inspect(source).canMigrate());
            assertThrows(IllegalStateException.class, () -> new LegacyJsonMigration(store, codec).migrate(source));
            assertTrue(store.listDevices().isEmpty());
            assertEquals(0, store.currentRevision());
            assertTrue(store.listMigrations().isEmpty());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=rw");
                 var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER wangbin_reject_second");
            }
            assertEquals(MigrationResult.Status.IMPORTED, new LegacyJsonMigration(store, codec).migrate(source).status());
            assertEquals(2, store.listDevices().size());
        }
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test
    void refusesNonemptyTargetWithoutOverwritingLocalConfiguration() throws Exception {
        Path source = writeSource("[" + device("local-1", "p-1") + "]");
        try (var store = SqliteConfigurationStore.createNew(database(), 500)) {
            store.transaction(writer -> writer.saveDevice(codec.decodeLocalDevice(device("other", "existing")), 0));
            long revision = store.currentRevision();
            assertThrows(IllegalStateException.class, () -> new LegacyJsonMigration(store, codec).migrate(source));
            assertTrue(store.findDevice("other").isPresent());
            assertTrue(store.findDevice("local-1").isEmpty());
            assertEquals(revision, store.currentRevision());
            assertTrue(store.listMigrations().isEmpty());
        }
    }

    @Test
    void completedMigrationDoesNotReimportChangedOrDeletedSource() throws Exception {
        Path source = writeSource("[" + device("local-1", "p-1") + "]");
        try (var store = SqliteConfigurationStore.createNew(database(), 500)) {
            var migration = new LegacyJsonMigration(store, codec);
            migration.migrate(source);
            Files.delete(source);
            assertEquals(MigrationResult.Status.ALREADY_MIGRATED, migration.migrate(source).status());
            assertEquals(1, store.listDevices().size());
        }
    }

    @Test
    void malformedMissingAndInvalidUtf8SourcesAreNotEmptyMigrations() throws Exception {
        assertFalse(LegacyJsonMigration.inspect(directory.resolve("missing.json")).canMigrate());
        Path source = writeSource("[{malformed]");
        assertFalse(LegacyJsonMigration.inspect(source).canMigrate());
        Files.write(source, new byte[]{'[', '"', (byte) 0xC3, (byte) 0x28, '"', ']'});
        assertFalse(LegacyJsonMigration.inspect(source).canMigrate());
        assertTrue(LegacyJsonMigration.inspect(source).problems().get(0).contains("UTF-8"));
    }

    @Test
    void realLegacySampleMatchesAllStableIdentitiesAndWhitelistedFields() throws Exception {
        String configured = System.getProperty("wangbin.migration.sample");
        assumeTrue(configured != null && !configured.isBlank(), "仅在显式提供只读迁移样本时执行");
        Path source = Path.of(configured);
        byte[] original = Files.readAllBytes(source);
        String digest = sha256(original);
        var inspection = LegacyJsonMigration.inspect(source);
        assertTrue(inspection.canMigrate(), "真实样本必须通过预检，诊断中不包含配置负载");
        assertEquals(45, inspection.deviceCount());
        assertEquals(45, inspection.connectionCount());
        assertEquals(749, inspection.pointCount());
        assertEquals(0, inspection.missingPointIds());
        assertEquals(0, inspection.duplicatePointIds());
        JsonNode raw = codec.readJson(Files.readString(source));
        Set<String> deviceIds = new HashSet<>();
        Set<String> pointIds = new HashSet<>();
        int verifiedFields = 0;
        Path database = database();
        try (var store = SqliteConfigurationStore.createNew(database, 1000)) {
            var migration = new LegacyJsonMigration(store, codec);
            var result = migration.migrate(source);
            assertEquals(MigrationResult.Status.IMPORTED, result.status());
            assertTrue(result.identityAndConfigurationVerified());
            assertEquals(45, store.listDevices().size());
            for (JsonNode entry : raw) {
                String id = entry.path("device").path("deviceId").textValue();
                assertTrue(deviceIds.add(id), "真实样本的设备身份必须唯一");
                JsonNode actual = codec.encodeLocalDevice(store.findDevice(id).orElseThrow().configuration());
                verifiedFields += assertRawConfiguration(entry, actual);
                for (JsonNode point : actual.path("points")) {
                    assertTrue(pointIds.add(point.path("pointId").textValue()), "真实样本的点位身份必须全局唯一");
                }
            }
            assertEquals(45, deviceIds.size());
            assertEquals(749, pointIds.size());
            long revision = store.currentRevision();
            assertEquals(MigrationResult.Status.ALREADY_MIGRATED, migration.migrate(source).status());
            assertEquals(revision, store.currentRevision());
            assertEquals(1, store.listMigrations().size());
        }
        try (var store = SqliteConfigurationStore.openExisting(database, 1000)) {
            assertEquals(45, store.listDevices().size());
            assertEquals(749, store.listDevices().stream().mapToInt(item -> item.configuration().points().size()).sum());
            assertEquals(digest, store.findMigration(LegacyJsonMigration.MIGRATION_ID).orElseThrow().sourceSha256());
        }
        assertTrue(MessageDigest.isEqual(original, Files.readAllBytes(source)), "只读真实样本必须逐字节保持不变");
        String report = System.getProperty("wangbin.migration.report");
        if (report != null && !report.isBlank()) {
            Map<String, Object> summary = new HashMap<>();
            summary.put("status", "VERIFIED");
            summary.put("sourceSha256", digest);
            summary.put("deviceCount", 45);
            summary.put("connectionCount", 45);
            summary.put("pointCount", 749);
            summary.put("stableDeviceIdentities", deviceIds.size());
            summary.put("stablePointIdentities", pointIds.size());
            summary.put("whitelistedFieldsCompared", verifiedFields);
            summary.put("unknownExtensionJsonPreserved", true);
            summary.put("sourceBytesUnchanged", true);
            summary.put("repeatMigrationSkipped", true);
            summary.put("reopenVerified", true);
            summary.put("isolatedDatabase", true);
            Path destination = Path.of(report);
            Files.createDirectories(destination.toAbsolutePath().getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), summary);
        }
    }

    private int assertRawConfiguration(JsonNode raw, JsonNode actual) {
        int count = assertFields(raw.path("device"), actual.path("device"), Set.of(
                "deviceId", "id", "deviceName", "deviceAlias", "productKey", "productName", "groupId", "groupName",
                "protocolType", "connectionType", "ipAddress", "port", "authConfig", "collectionInterval", "reportInterval",
                "cloudTarget", "maxRetryCount", "createTime", "updateTime", "remark", "configSource", "temporaryConfig"));
        if (raw.hasNonNull("connection")) {
            count += assertFields(raw.get("connection"), actual.path("connection"), Set.of(
                    "id", "connectionType", "connectionKey", "host", "port", "url", "connectTimeoutMs", "readTimeoutMs",
                    "writeTimeoutMs", "retries", "extJson", "remark", "connectionId", "deviceId", "deviceName", "timeout",
                    "heartbeatInterval", "heartbeatTimeout", "subscriptionInterval", "maxFrameLength", "reconnectDelay",
                    "maxReconnectTimes", "maxGroupConnections", "username", "password", "clientId", "productKey", "deviceSecret",
                    "authToken", "securityPolicy", "authParams", "sslEnabled", "sslCertPath", "sslKeyPath", "charset", "keepAlive",
                    "bufferSize", "autoReconnect", "initialReconnectDelay", "maxReconnectDelay", "maxReconnectAttempts",
                    "reconnectBackoffMultiplier", "maxPendingMessages", "dispatchBatchSize", "dispatchFlushInterval",
                    "overflowStrategy", "createTime", "updateTime"));
            assertFalse(actual.path("connection").has("status"));
            assertFalse(actual.path("connection").has("connectionStats"));
        }
        assertFalse(actual.path("device").has("status"));
        assertEquals(raw.path("points").size(), actual.path("points").size());
        for (int i = 0; i < raw.path("points").size(); i++) {
            JsonNode point = actual.path("points").get(i);
            count += assertFields(raw.path("points").get(i), point, Set.of(
                    "id", "unitId", "commonAddress", "pointId", "pointCode", "pointName", "pointAlias", "deviceId", "deviceName",
                    "groupId", "address", "dataType", "readWrite", "scalingFactor", "offset", "deadband", "unit", "minValue", "maxValue",
                    "collectionMode", "priority", "cacheEnabled", "cacheDuration", "alarmEnabled", "alarmRule", "status", "createTime",
                    "updateTime", "precision", "remark", "additionalConfig", "baseCollectionInterval", "minCollectionInterval",
                    "maxCollectionInterval", "pointChangeThreshold"));
            for (String runtime : List.of("currentCollectionInterval", "stableCount", "lastValue", "changeRate", "lastAdjustTime",
                    "reportFieldConflict", "reportConfigParsed", "parsedAdditionalConfigRef")) assertFalse(point.has(runtime));
        }
        return count;
    }

    private int assertFields(JsonNode raw, JsonNode actual, Set<String> fields) {
        int compared = 0;
        for (String field : fields) {
            assertEquals(raw.has(field), actual.has(field), "配置白名单字段存在性必须一致：" + field);
            if (raw.has(field)) {
                assertTrue(raw.get(field).equals(actual.get(field)), "配置白名单内容必须一致，不在断言中输出配置：" + field);
                compared++;
            }
        }
        return compared;
    }

    private Path database() {
        return directory.resolve("wangbin-database/collector.db");
    }

    private Path writeSource(String json) throws Exception {
        Path source = directory.resolve("local-device-configs.json");
        Files.writeString(source, json);
        return source;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String device(String deviceId, String pointId) {
        return """
                {"device":{"deviceId":"%s","deviceName":"示例设备","protocolType":"MODBUS_TCP","configSource":"local",
                "temporaryConfig":true,"status":"ONLINE","retryCount":9},
                "connection":{"deviceId":"%s","connectionType":"MODBUS_TCP","status":"CONNECTED",
                "extJson":{"vendor-unknown":{"numbers":[123456789012345678901234567890,1.2300,null,true]}},
                "authParams":{"unknown-auth":"{\\\"unchanged\\\":true}","leading-zero":"001"}},
                "points":[{"deviceId":"%s","pointId":"%s","pointCode":"temperature","status":1,"address":"40001","dataType":"FLOAT",
                "alarmRule":[{"operator":">","threshold":1.2300,"unknown-rule":{"array":[null,2]}}],
                "additionalConfig":{"historyEnabled":false,"vendor":{"unknown":[false,1.2300]}},
                "lastValue":99,"stableCount":4,"currentCollectionInterval":123}]}
                """.formatted(deviceId, deviceId, deviceId, pointId);
    }
}

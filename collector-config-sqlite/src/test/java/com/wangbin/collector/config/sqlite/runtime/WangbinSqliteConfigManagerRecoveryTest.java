package com.wangbin.collector.config.sqlite.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.config.sqlite.migration.LegacyJsonMigration;
import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.loader.FileConfigLoader;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.config.persistence.WangbinLocalConfigurationPersistence;
import com.wangbin.collector.core.config.store.LocalDeviceConfigStore;
import com.wangbin.collector.core.report.validator.FieldUniquenessValidator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 真实SQLite→真实ConfigManager恢复，采用空文件provider，不创建采集器/Redis/历史连接。 */
class WangbinSqliteConfigManagerRecoveryTest {
    @TempDir
    Path directory;
    private final ConfigurationJsonCodec codec = new ConfigurationJsonCodec();

    @Test
    void migratedConfigurationRecoversThroughManagerAfterCloseAndReopen() throws Exception {
        String json = """
                [{"device":{"deviceId":"wangbin-recovery","deviceName":"恢复测试","protocolType":"MODBUS_TCP", "connectionType":"MODBUS_TCP","configSource":"local","temporaryConfig":true},
                "connection":{"deviceId":"wangbin-recovery","connectionType":"MODBUS_TCP","host":"127.0.0.1","port":1502,"extJson":{"nested":{"values":[1,null,2.3400]}}},
                "points":[{"deviceId":"wangbin-recovery","pointId":"wangbin-recovery-b","pointCode":"b","address":"40002","dataType":"FLOAT","status":0,"alarmRule":[{"type":"threshold","unknown":{"precise":12345678901234567890.123456789,"flags":[true,null]}}],"additionalConfig":{"historyEnabled":false}},
                {"deviceId":"wangbin-recovery","pointId":"wangbin-recovery-a","pointCode":"a","address":"40001","dataType":"FLOAT","status":1,"additionalConfig":{"historyEnabled":true}}]}]
                """;
        verifySourceThroughManager(json.getBytes(StandardCharsets.UTF_8), false);
    }

    @Test
    void currentAuthorizedSampleRestoresExactIdentitiesAndConfigurationFields() throws Exception {
        String sample = System.getProperty("wangbin.phase2.sample");
        Assumptions.assumeTrue(sample != null && !sample.isBlank(), "真实样本仅在显式指定时只读使用");
        Path original = Path.of(sample);
        byte[] bytes = Files.readAllBytes(original);
        String before = sha256(bytes);
        Map<String, Object> result = verifySourceThroughManager(bytes, true);
        assertEquals(before, sha256(Files.readAllBytes(original)), "原始JSON不得被修改");
        result.put("sourceSha256", before);
        result.put("sourceUnchanged", true);
        String report = System.getProperty("wangbin.phase2.recovery-report");
        if (report != null && !report.isBlank()) {
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(report).toFile(), result);
        }
    }

    private Map<String, Object> verifySourceThroughManager(byte[] bytes, boolean actualSample) throws Exception {
        Path database = directory.resolve("private/collector.db");
        Path source = directory.resolve("private/legacy.json");
        // 先由数据库实现限制专用目录ACL，再复制含敏感内容的授权样本，避免先写后收权限。
        try (var store = SqliteConfigurationStore.createNew(database, 500)) {
            Files.write(source, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
            new LegacyJsonMigration(store, codec).migrate(source);
        }
        Path remote = directory.resolve("remote.json");
        Files.writeString(remote, "[]\n");
        Path poisonJson = directory.resolve("legacy-must-not-be-read.json");
        Files.writeString(poisonJson, "wangbin-invalid-json-if-legacy-fallback");
        List<String> pointIdentities = new ArrayList<>();
        int deviceCount;
        int connectionCount = 0;
        int fields = 0;
        List<String> deviceIdentities = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            try (var store = SqliteConfigurationStore.openExisting(database, 500)) {
                var bridge = new WangbinSqliteLocalConfigurationPersistence(store, source);
                List<Object> events = new ArrayList<>();
                ConfigManager manager = manager(bridge, remote, poisonJson, events);
                manager.init();
                var records = store.listDevices();
                assertEquals(records.size(), manager.getAllDevices().size());
                assertTrue(events.stream().noneMatch(ConfigUpdateEvent.class::isInstance), "恢复不能发布自动启停事件");
                for (var record : records) {
                    String id = record.configuration().deviceId();
                    var snapshot = manager.getDeviceConfigurationSnapshot(id);
                    assertNotNull(snapshot.context());
                    assertEquals(id, snapshot.context().getDeviceId());
                    assertTrue(manager.getDeviceConfigVersion(id) > 0);
                    assertNotEquals(record.revision(), manager.getDeviceConfigVersion(id));
                    assertNotEquals("ONLINE", snapshot.context().getDeviceInfo().getStatus());
                    assertTrue(snapshot.context().getDataPoints().stream().allMatch(point -> point.getLastValue() == null));
                    JsonNode expected = codec.encodeLocalDevice(record.configuration());
                    JsonNode actual = codec.encodeLocalDevice(codec.fromRuntime(snapshot.context().getDeviceInfo(),
                            snapshot.context().getConnectionConfig(), snapshot.context().getDataPoints()));
                    int checked = verifyExpectedFields(expected, actual, "configuration");
                    if (round == 0) {
                        fields += checked;
                        deviceIdentities.add(id);
                        connectionCount += record.configuration().connection() == null ? 0 : 1;
                        record.configuration().points().forEach(point -> pointIdentities.add(point.pointId()));
                    }
                    for (int index = 0; index < record.configuration().points().size(); index++) {
                        assertEquals(record.configuration().points().get(index).pointId(),
                                snapshot.context().getDataPoints().get(index).getPointId(), "点位顺序和身份不得改变");
                    }
                }
                assertEquals(1, store.listMigrations().size());
            }
        }
        try (var store = SqliteConfigurationStore.openExisting(database, 500)) {
            deviceCount = store.listDevices().size();
        }
        assertArrayEquals(bytes, Files.readAllBytes(source));
        assertEquals("wangbin-invalid-json-if-legacy-fallback", Files.readString(poisonJson));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PASS");
        result.put("actualAuthorizedSample", actualSample);
        result.put("deviceCount", deviceCount);
        result.put("connectionCount", connectionCount);
        result.put("pointCount", pointIdentities.size());
        result.put("configurationLeafFieldsChecked", fields);
        result.put("stableDeviceIdentitySha256", sha256(new ObjectMapper().writeValueAsBytes(deviceIdentities.stream().sorted().toList())));
        result.put("stablePointIdentitySha256", sha256(new ObjectMapper().writeValueAsBytes(pointIdentities.stream().sorted().toList())));
        result.put("managerRecoveryRounds", 2);
        result.put("legacyFallbackUsed", false);
        result.put("startupEvents", 0);
        return result;
    }

    private ConfigManager manager(WangbinLocalConfigurationPersistence bridge, Path remote, Path poisonJson, List<Object> events) {
        var properties = new CollectorProperties();
        properties.getConfig().getFile().setDevices(remote.toString());
        properties.getConfig().setLocalDeviceSnapshotFile(poisonJson.toString());
        var sync = new ConfigSyncService(new FileConfigLoader(properties), Runnable::run, properties);
        var beans = new StaticListableBeanFactory(Map.of("wangbinLocalConfigurationPersistence", bridge));
        return new ConfigManager(sync, events::add, new FieldUniquenessValidator(),
                new LocalDeviceConfigStore(new ObjectMapper(), properties), null, null,
                beans.getBeanProvider(WangbinLocalConfigurationPersistence.class));
    }

    private int verifyExpectedFields(JsonNode expected, JsonNode actual, String field) {
        if (field.endsWith(".createTime") || field.endsWith(".updateTime") || field.equals("configuration.device.id")) return 0;
        if (expected.isTextual() && (field.endsWith(".alarmRule") || field.endsWith(".extJson")
                || field.endsWith(".authParams") || field.endsWith(".authConfig") || field.endsWith(".additionalConfig"))) {
            String text = expected.textValue().strip();
            if (text.startsWith("{") || text.startsWith("[")) expected = codec.readJson(text);
        }
        if (actual != null && actual.isTextual() && (expected.isObject() || expected.isArray())) {
            actual = codec.readJson(actual.textValue());
        }
        assertNotNull(actual, () -> "恢复缺少字段：" + field);
        if (expected.isObject()) {
            assertTrue(actual.isObject(), () -> "恢复对象类型不一致：" + field);
            int checked = 0;
            for (var entry : expected.properties()) checked += verifyExpectedFields(entry.getValue(), actual.get(entry.getKey()), field + "." + entry.getKey());
            return checked;
        }
        if (expected.isArray()) {
            assertTrue(actual.isArray(), () -> "恢复数组类型不一致：" + field);
            assertEquals(expected.size(), actual.size(), () -> "恢复数组长度不一致：" + field);
            int checked = 0;
            for (int index = 0; index < expected.size(); index++) checked += verifyExpectedFields(expected.get(index), actual.get(index), field + "[" + index + "]");
            return checked;
        }
        if (expected.isNumber()) {
            assertTrue(actual.isNumber(), () -> "恢复数值类型不一致：" + field);
            assertTrue(expected.decimalValue().compareTo(actual.decimalValue()) == 0, () -> "恢复数值不一致：" + field);
        } else assertTrue(expected.equals(actual), () -> "恢复配置不一致：" + field + "（值已隐藏）");
        return 1;
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}

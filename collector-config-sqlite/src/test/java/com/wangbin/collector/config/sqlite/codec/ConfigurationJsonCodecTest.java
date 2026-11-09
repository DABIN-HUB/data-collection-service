package com.wangbin.collector.config.sqlite.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.wangbin.collector.common.domain.entity.AlarmRule;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.persistence.ConfigurationRevision;
import com.wangbin.collector.core.config.persistence.DeviceConfiguration;
import com.wangbin.collector.core.config.persistence.GatewaySetting;
import com.wangbin.collector.core.config.persistence.MigrationRecord;
import com.wangbin.collector.core.config.persistence.PointConfiguration;
import com.wangbin.collector.core.config.persistence.ProtocolProfile;
import com.wangbin.collector.core.config.persistence.RemoteSnapshot;
import com.wangbin.collector.core.config.persistence.StoredDeviceConfiguration;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationJsonCodecTest {
    @Test
    void validatesRedundantIdentityStatusAndWhitelistAtDatabaseBoundary() {
        DeviceConfiguration good = codec.decodeLocalDevice(document());
        assertDoesNotThrow(() -> codec.validateLocalDevice(good));
        PointConfiguration point = good.points().get(0);
        DeviceConfiguration wrongStatus = new DeviceConfiguration(good.deviceId(), good.deviceJson(), good.connection(),
                List.of(new PointConfiguration(point.deviceId(), point.pointId(), 0, 0, point.configJson())));
        assertThrows(IllegalArgumentException.class, () -> codec.validateLocalDevice(wrongStatus));
        DeviceConfiguration wrongId = new DeviceConfiguration("d2", good.deviceJson(), null, List.of());
        assertThrows(IllegalArgumentException.class, () -> codec.encodeLocalDevice(wrongId));
        JsonNode raw = codec.readJson(good.deviceJson());
        ((ObjectNode) raw).put("status", "ONLINE");
        DeviceConfiguration runtimePollution = new DeviceConfiguration(good.deviceId(), raw.toString(), good.connection(), good.points());
        assertThrows(IllegalArgumentException.class, () -> codec.validateLocalDevice(runtimePollution));
        assertThrows(IllegalArgumentException.class, () -> new DeviceConfiguration("d2", good.deviceJson(), good.connection(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DeviceConfiguration(good.deviceId(), good.deviceJson(), good.connection(),
                List.of(new PointConfiguration(point.deviceId(), point.pointId(), 1, point.status(), point.configJson()))));
    }

    @Test
    void preservesNullContainersMissingStatusAndLegacyDeviceAlias() {
        ObjectNode input = document();
        device(input).remove("deviceId");
        connection(input).putNull("extJson").putNull("authParams");
        point(input).putNull("additionalConfig").putNull("alarmRule").remove("status");
        DeviceConfiguration config = codec.decodeLocalDevice(input);
        assertEquals("d1", config.deviceId());
        assertNull(config.points().get(0).status());
        JsonNode output = codec.encodeLocalDevice(config);
        assertTrue(output.get("connection").get("extJson").isNull());
        assertTrue(output.get("connection").get("authParams").isNull());
        assertTrue(output.get("points").get(0).get("additionalConfig").isNull());
        assertTrue(output.get("points").get(0).get("alarmRule").isNull());
        assertFalse(output.get("points").get(0).has("status"));
    }

    @Test
    void rejectsNullMalformedAggregateAndNonIntegerPointStatus() {
        for (String json : List.of("null", "[]", "{}", "{\"device\":null,\"points\":[]}")) {
            assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevice(json));
        }
        for (JsonNode status : List.of(codec.readJson("\"1\""), codec.readJson("1.2"), codec.readJson("99999999999999999999"))) {
            ObjectNode input = document(); point(input).set("status", status);
            assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevice(input));
        }
        ObjectNode input = document(); input.putNull("connection");
        DeviceConfiguration result = codec.decodeLocalDevice(input);
        assertNull(result.connection());
        assertTrue(codec.encodeLocalDevice(result).get("connection").isNull());
    }

    @Test
    void databaseBoundaryRejectsRulesAndTypedFieldsThatCannotBeRestored() {
        for (String raw : List.of("1", "true", "not-json")) {
            ObjectNode input = document();
            point(input).put("alarmRule", raw);
            DeviceConfiguration candidate = codec.decodeLocalDevice(input);
            assertThrows(IllegalArgumentException.class, () -> codec.validateLocalDevice(candidate));
        }
        ObjectNode input = document();
        connection(input).put("connectTimeoutMs", "invalid-number");
        DeviceConfiguration candidate = codec.decodeLocalDevice(input);
        assertThrows(IllegalArgumentException.class, () -> codec.validateLocalDevice(candidate));
    }

    @Test
    void metadataModelsRedactPayloadAndValidateRevisionsAndMigrationCounts() {
        String secret = "placeholder-secret";
        java.time.Instant time = java.time.Instant.parse("2026-01-01T00:00:00Z");
        for (Object model : List.of(new ProtocolProfile("p", "MODBUS_TCP", secret, 1),
                new GatewaySetting("g", secret, 2), new RemoteSnapshot("remote", "s", "v", secret, 3),
                new ConfigurationRevision(4, "DEVICE", "d1", "CREATE", time),
                new MigrationRecord("m", secret, "a".repeat(64), 45, 45, 749, time),
                new StoredDeviceConfiguration(codec.decodeLocalDevice(document()), 5))) {
            assertFalse(model.toString().contains(secret));
        }
        assertThrows(IllegalArgumentException.class, () -> new StoredDeviceConfiguration(codec.decodeLocalDevice(document()), 0));
        assertThrows(IllegalArgumentException.class, () -> new MigrationRecord("m", "source", "bad", 45, 45, 749, time));
        assertThrows(IllegalArgumentException.class, () -> new MigrationRecord("m", "source", "a".repeat(64), 1, 2, 3, time));
        assertThrows(IllegalArgumentException.class, () -> new RemoteSnapshot("local", "s", "v", "{}", 1));
    }

    @Test
    void conflictExceptionContractNeverAcceptsSensitivePayload() {
        assertDoesNotThrow(() -> {
            Class<?> type = Class.forName("com.wangbin.collector.core.config.persistence.ConfigurationConflictException");
            Object error = type.getConstructor().newInstance();
            assertTrue(error instanceof IllegalStateException);
            assertNull(((Throwable) error).getCause());
            assertEquals(1, type.getConstructors().length);
        });
    }

    private final ConfigurationJsonCodec codec = new ConfigurationJsonCodec();
    private ObjectNode document() {
        return (ObjectNode) codec.readJson("""
                {"device":{"deviceId":"d1","id":"d1","configSource":"local","temporaryConfig":true},
                 "connection":{"deviceId":"d1","connectionId":"c1"},
                 "points":[{"deviceId":"d1","pointId":"p1","status":2}]}
                """);
    }
    private ObjectNode device(ObjectNode input) { return (ObjectNode) input.get("device"); }
    private ObjectNode connection(ObjectNode input) { return (ObjectNode) input.get("connection"); }
    private ObjectNode point(ObjectNode input) { return (ObjectNode) input.get("points").get(0); }

    @Test
    void extractsConfigurationWithoutRuntimeOrDerivedFields() {
        ObjectNode input = document();
        device(input).put("status", "ONLINE").put("retryCount", 7).put("online", true).put("unknown", 9);
        connection(input).put("status", "CONNECTED").put("duration", 99).putObject("stats").put("errors", 4);
        point(input).put("lastValue", "placeholder-secret").put("currentCollectionInterval", 100)
                .put("stableCount", 10).put("reportFieldConflict", true);
        DeviceConfiguration result = codec.decodeLocalDevice(input);
        JsonNode output = codec.encodeLocalDevice(result);
        for (String key : List.of("status", "retryCount", "online", "unknown")) assertFalse(output.get("device").has(key));
        for (String key : List.of("status", "duration", "stats")) assertFalse(output.get("connection").has(key));
        for (String key : List.of("lastValue", "currentCollectionInterval", "stableCount", "reportFieldConflict"))
            assertFalse(output.get("points").get(0).has(key));
        assertEquals(2, result.points().get(0).status());
        assertEquals(0, result.points().get(0).sortOrder());
        assertEquals(result, codec.decodeLocalDevice(output));
    }

    @Test
    void preservesExactNumbersNullArraysAndUnknownExtensionKeys() {
        ObjectNode input = document();
        JsonNode extension = codec.readJson("""
                {"future":{"large":1234567890123456789012345678901234567890,
                "decimal":0.1234567890123456789012345678901234567890,
                "values":[null,true,"x",{"unknown":9}]},"historyEnabled":false}
                """);
        connection(input).set("extJson", extension);
        point(input).set("additionalConfig", extension);
        JsonNode output = codec.encodeLocalDevice(codec.decodeLocalDevice(input));
        assertEquals(extension, output.get("connection").get("extJson"));
        assertEquals(extension, output.get("points").get(0).get("additionalConfig"));
        assertEquals(new BigDecimal("0.1234567890123456789012345678901234567890"),
                output.get("connection").get("extJson").get("future").get("decimal").decimalValue());
    }

    @Test
    void preservesObjectStringsAndAuthStringValuesWithoutCoercion() {
        ObjectNode input = document();
        String extension = "{\"future\":[null,123456789012345678901234567890,0.12345678901234567890]}";
        String auth = "{\"token\":\"placeholder-secret\",\"code\":\"001\",\"jsonText\":\"[1,null]\"}";
        connection(input).put("extJson", extension).put("authParams", auth);
        point(input).put("additionalConfig", extension);
        JsonNode output = codec.encodeLocalDevice(codec.decodeLocalDevice(input));
        assertEquals(extension, output.get("connection").get("extJson").textValue());
        assertEquals(auth, output.get("connection").get("authParams").textValue());
        assertEquals(extension, output.get("points").get(0).get("additionalConfig").textValue());
        connection(input).set("authParams", codec.readJson(auth));
        output = codec.encodeLocalDevice(codec.decodeLocalDevice(input));
        assertEquals("001", output.get("connection").get("authParams").get("code").textValue());
        assertEquals("[1,null]", output.get("connection").get("authParams").get("jsonText").textValue());
    }

    @Test
    void preservesAlarmRuleStringArrayObjectAndUnknownNestedProperties() {
        JsonNode rule = codec.readJson("{\"futureRule\":7,\"additionalConfig\":{\"newKey\":[null,1]}}");
        for (JsonNode variant : List.of(rule, codec.readJson("[" + rule + "]"),
                com.fasterxml.jackson.databind.node.TextNode.valueOf(rule.toString()))) {
            ObjectNode input = document();
            point(input).set("alarmRule", variant);
            assertEquals(variant, codec.encodeLocalDevice(codec.decodeLocalDevice(input)).get("points").get(0).get("alarmRule"));
        }
    }

    @Test
    void readsOnlyExplicitEntityFieldsAndNeverTypedAlarmGetter() {
        DeviceInfo device = new DeviceInfo() {
            @Override public String getDisplayName() { throw new AssertionError("不得读取派生字段"); }
        };
        device.setDeviceId("d1"); device.setConfigSource("local"); device.setTemporaryConfig(true); device.setStatus("ONLINE");
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId("d1"); connection.setConnectTimeout(123); connection.setReadTimeout(456);
        connection.setWriteTimeout(789); connection.setPassword("placeholder-secret");
        connection.setExtJson(Map.of("decimal", new BigDecimal("0.12345678901234567890")));
        DataPoint point = new DataPoint() {
            @Override public List<AlarmRule> getAlarmRule() { throw new AssertionError("不得转换规则"); }
        };
        point.setDeviceId("d1"); point.setPointId("p1"); point.setStatus(0);
        String alarm = "{\"futureRule\":{\"additionalConfig\":{\"x\":[null,1]}}}";
        point.setAlarmRule(alarm);
        JsonNode output = codec.encodeLocalDevice(codec.fromRuntime(device, connection, List.of(point)));
        assertEquals(alarm, output.get("points").get(0).get("alarmRule").textValue());
        assertEquals(0, output.get("points").get(0).get("status").intValue());
        assertEquals(123, output.get("connection").get("connectTimeoutMs").intValue());
        assertEquals(456, output.get("connection").get("readTimeoutMs").intValue());
        assertEquals(789, output.get("connection").get("writeTimeoutMs").intValue());
        assertFalse(output.get("connection").has("connectTimeout"));
        assertFalse(output.get("device").has("displayName")); assertFalse(output.get("device").has("status"));
        assertEquals(new BigDecimal("0.12345678901234567890"), output.get("connection").get("extJson").get("decimal").decimalValue());
    }

    @Test
    void rejectsDeviceIdentityAliasConflictInEitherOrder() {
        for (String device : List.of("{\"deviceId\":\"d1\",\"id\":\"d2\"}", "{\"id\":\"d2\",\"deviceId\":\"d1\"}")) {
            ObjectNode input = document(); input.set("device", codec.readJson(device));
            assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevice(input));
        }
    }

    @Test
    void rejectsMissingBlankOrNonStringPointIdentity() {
        for (JsonNode invalid : List.of(codec.readJson("null"), codec.readJson("\" \""), codec.readJson("42"))) {
            ObjectNode input = document(); point(input).set("pointId", invalid);
            assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevice(input));
        }
        ObjectNode input = document(); point(input).remove("pointId");
        assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevice(input));
    }

    @Test
    void rejectsDuplicatePointsAndDevicesAcrossImportBatch() {
        ObjectNode input = document(); ((ArrayNode) input.get("points")).add(point(input).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevice(input));
        ObjectNode other = document(); device(other).put("deviceId", "d2").put("id", "d2");
        connection(other).put("deviceId", "d2"); point(other).put("deviceId", "d2");
        assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevices(codec.readJson("[" + document() + "," + other + "]")));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevices(codec.readJson("[" + document() + "," + document() + "]")));
    }

    @Test
    void rejectsMissingOrMismatchedChildOwnership() {
        for (boolean isConnection : List.of(true, false)) {
            for (JsonNode owner : List.of(codec.readJson("null"), codec.readJson("\"d2\""))) {
                ObjectNode input = document(); (isConnection ? connection(input) : point(input)).set("deviceId", owner);
                assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevice(input));
            }
        }
    }

    @Test
    void rejectsRemoteAndNonTemporaryLocalDevices() {
        for (String source : List.of("remote", "LOCAL", " local ", "")) {
            ObjectNode input = document(); device(input).put("configSource", source);
            assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevice(input));
        }
        ObjectNode input = document(); device(input).put("temporaryConfig", false);
        assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevice(input));
        device(input).put("temporaryConfig", "true");
        assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevice(input));
    }

    @Test
    void retainsAuditLegacyIdsOrderingAndUnnormalizedIdentity() {
        ObjectNode input = document();
        device(input).put("deviceId", " d1 ").put("id", " d1 ").put("createTime", 123).put("updateTime", "2026-01-01");
        connection(input).put("deviceId", " d1 ").put("id", 987654321);
        point(input).put("deviceId", " d1 ").put("pointId", " p1 ").put("id", 123456789);
        ((ArrayNode) input.get("points")).add(codec.readJson("{\"deviceId\":\" d1 \",\"pointId\":\"p2\",\"status\":1}"));
        DeviceConfiguration result = codec.decodeLocalDevice(input);
        assertEquals(" d1 ", result.deviceId()); assertEquals(" p1 ", result.points().get(0).pointId());
        assertEquals(1, result.points().get(1).sortOrder());
        JsonNode output = codec.encodeLocalDevice(result);
        assertEquals(987654321, output.get("connection").get("id").intValue());
        assertEquals(123456789, output.get("points").get(0).get("id").intValue());
        assertEquals(123, output.get("device").get("createTime").intValue());
        assertEquals("2026-01-01", output.get("device").get("updateTime").textValue());
    }

    @Test
    void filtersCloudTargetWithNestedWhitelist() {
        ObjectNode input = document();
        device(input).set("cloudTarget", codec.readJson("{\"enabled\":true,\"deviceType\":\"GATEWAY\",\"productKey\":\"p\",\"deviceName\":\"n\",\"topologyEnabled\":false,\"status\":\"ONLINE\"}"));
        JsonNode target = codec.encodeLocalDevice(codec.decodeLocalDevice(input)).get("device").get("cloudTarget");
        assertEquals(5, target.size()); assertFalse(target.has("status")); assertFalse(target.get("topologyEnabled").booleanValue());
    }

    @Test
    void rejectsMalformedDuplicateAndTrailingJsonWithoutLeakingPayload() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> codec.decodeLocalDevice("{\"password\":\"placeholder-secret\", broken"));
        assertFalse(error.toString().contains("placeholder-secret")); assertNull(error.getCause());
        assertThrows(IllegalArgumentException.class, () -> codec.readJson("{} {}"));
        assertThrows(IllegalArgumentException.class, () -> codec.readJson("{\"a\":1,\"a\":2}"));
    }

    @Test
    void rejectsInvalidContainersAndAuthValueCoercion() {
        for (JsonNode invalid : List.of(codec.readJson("[1]"), codec.readJson("\"not-json\""), codec.readJson("7"))) {
            ObjectNode input = document(); connection(input).set("extJson", invalid);
            assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevice(input));
        }
        ObjectNode input = document(); connection(input).set("authParams", codec.readJson("{\"code\":1}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeLocalDevice(input));
    }

    @Test
    void modelsAreImmutableAndToStringRedactsPayload() {
        ObjectNode input = document(); connection(input).put("password", "placeholder-secret");
        DeviceConfiguration config = codec.decodeLocalDevice(input);
        assertThrows(UnsupportedOperationException.class, () -> config.points().clear());
        assertFalse(config.toString().contains("placeholder-secret"));
        assertFalse(config.connection().toString().contains("placeholder-secret"));
        assertFalse(config.points().get(0).toString().contains("configJson"));
        input.remove("points"); assertEquals(1, config.points().size());
    }
}

package com.wangbin.collector.config.sqlite.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.persistence.DeviceConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 白名单双向恢复：真实实体、自定义反序列化器精度、告警和运行态边界。 */
class WangbinConfigurationRuntimeMappingTest {
    private final ConfigurationJsonCodec codec = new ConfigurationJsonCodec();

    @Test
    void restoresFourProtocolsIdentityStatusHistoryOrderingAndRawAlarm() throws Exception {
        for (String protocol : List.of("MODBUS_TCP", "S7", "OPC_UA", "IEC104")) {
            ObjectNode document = document();
            ((ObjectNode) document.get("device")).put("protocolType", protocol);
            DeviceContext runtime = codec.toRuntime(codec.decodeLocalDevice(document));
            assertEquals("mapping-device", runtime.getDeviceId());
            assertEquals(protocol, runtime.getDeviceInfo().getProtocolType());
            assertEquals(List.of("point-b", "point-a"), runtime.getDataPoints().stream().map(DataPoint::getPointId).toList());
            assertEquals(0, runtime.getDataPoints().get(0).getStatus());
            assertEquals(2, runtime.getDataPoints().get(1).getStatus());
            assertEquals(false, runtime.getDataPoints().get(0).getAdditionalConfig().get("historyEnabled"));
            assertEquals("[ {\"futureRule\":{\"items\":[null,123456789012345678901234567890]}} ]", raw(runtime.getDataPoints().get(0), "alarmRule"));
            assertFalse(codec.readJson(codec.fromRuntime(runtime).deviceJson()).has("id"));
        }
    }

    @Test
    void mapsExactObjectAndJsonStringContainersWithoutCommonDeserializer() {
        for (boolean objectString : List.of(false, true)) {
            ObjectNode document = document();
            JsonNode extension = codec.readJson("{\"decimal\":0.123456789012345678901234567890,\"huge\":123456789012345678901234567890,\"nested\":[null,{\"future\":true}]}");
            ObjectNode connection = (ObjectNode) document.get("connection");
            if (objectString) connection.put("extJson", extension.toString());
            else connection.set("extJson", extension);
            DeviceContext runtime = codec.toRuntime(codec.decodeLocalDevice(document));
            assertEquals(new BigDecimal("0.123456789012345678901234567890"), runtime.getConnectionConfig().getExtJson().get("decimal"));
            assertEquals(new BigInteger("123456789012345678901234567890"), runtime.getConnectionConfig().getExtJson().get("huge"));
            assertNull(((List<?>) runtime.getConnectionConfig().getExtJson().get("nested")).get(0));
            assertEquals(StandardCharsets.UTF_8, runtime.getConnectionConfig().getCharset());
            assertEquals(3456, runtime.getConnectionConfig().getConnectTimeout());
            assertEquals(new Date(123), runtime.getConnectionConfig().getCreateTime());
            assertEquals("001", runtime.getConnectionConfig().getAuthParams().get("code"));
            assertTrue(codec.equivalent(runtime, codec.toRuntime(codec.fromRuntime(runtime))));
        }
    }

    @Test
    void explicitNullContainersAndStatusSurviveSnapshotAndRoundTrip() throws Exception {
        ObjectNode document = document();
        ((ObjectNode) document.get("device")).putNull("authConfig");
        ((ObjectNode) document.get("connection")).putNull("extJson").putNull("authParams");
        ((ObjectNode) document.get("points").get(0)).putNull("additionalConfig").putNull("alarmRule").putNull("status");
        DeviceContext runtime = codec.toRuntime(codec.decodeLocalDevice(document));
        assertNull(raw(runtime.getDataPoints().get(0), "additionalConfig"));
        assertNull(raw(runtime.copyDataPoints().get(0), "additionalConfig"));
        JsonNode output = codec.encodeLocalDevice(codec.fromRuntime(runtime));
        assertTrue(output.path("points").get(0).get("additionalConfig").isNull());
        assertTrue(output.path("points").get(0).get("alarmRule").isNull());
        assertTrue(output.path("points").get(0).get("status").isNull());
        assertTrue(output.path("connection").get("extJson").isNull());
    }

    @Test
    void excludesRuntimeAndInitializesAdaptiveIntervalFromBasePolicy() {
        ObjectNode document = document();
        ((ObjectNode) document.get("device")).put("status", "ONLINE").put("retryCount", 8);
        ((ObjectNode) document.get("connection")).put("status", "CONNECTED").putObject("stats").put("errors", 99);
        ((ObjectNode) document.get("points").get(0)).put("lastValue", "[REDACTED]")
                .put("currentCollectionInterval", 777).put("stableCount", 9).put("lastAdjustTime", 123);
        DeviceContext runtime = codec.toRuntime(codec.decodeLocalDevice(document));
        assertNull(runtime.getDeviceInfo().getStatus());
        assertNull(runtime.getDeviceInfo().getRetryCount());
        assertNull(runtime.getConnectionConfig().getStatus());
        DataPoint point = runtime.getDataPoints().get(0);
        assertNull(point.getLastValue());
        assertEquals(2500, point.getCurrentCollectionInterval());
        assertEquals(0, point.getStableCount());
        assertEquals(0, point.getLastAdjustTime());
        runtime.getDeviceInfo().setStatus("ONLINE");
        point.setLastValue("[REDACTED]"); point.setStableCount(8);
        DeviceContext restored = codec.toRuntime(codec.fromRuntime(runtime));
        assertNull(restored.getDeviceInfo().getStatus());
        assertNull(restored.getDataPoints().get(0).getLastValue());
        assertTrue(codec.equivalent(runtime, restored));
    }

    @Test
    void equivalentIgnoresAuditTimesAndJsonTextFormattingButKeepsPointOrder() {
        DeviceContext first = codec.toRuntime(codec.decodeLocalDevice(document()));
        DeviceContext second = codec.toRuntime(codec.fromRuntime(first));
        first.getDeviceInfo().setCreateTime(new Date(100));
        second.getDeviceInfo().setCreateTime(new Date(200));
        first.getConnectionConfig().setUpdateTime(new Date(100));
        second.getDataPoints().get(0).setUpdateTime(new Date(200));
        second.getDataPoints().get(0).setAlarmRule("[{\"futureRule\":{\"items\":[null,123456789012345678901234567890]}}]");
        assertTrue(codec.equivalent(first, second));
        second.getDataPoints().get(0).setAdditionalConfig(Map.of("historyEnabled", false, "future", Map.of("b", 2, "a", 1)));
        first.getDataPoints().get(0).setAdditionalConfig(Map.of("future", Map.of("a", 1, "b", 2), "historyEnabled", false));
        assertTrue(codec.equivalent(first, second));
        DeviceContext reordered = DeviceContext.of(second.getDeviceInfo(), second.getConnectionConfig(),
                List.of(second.getDataPoints().get(1), second.getDataPoints().get(0)));
        assertFalse(codec.equivalent(first, reordered));
        second.getDataPoints().get(0).setStatus(1);
        assertFalse(codec.equivalent(first, second));
    }

    @Test
    void invalidTypedFieldReportsOnlyDeviceAndFieldWithoutValueOrCause() {
        for (String field : List.of("charset", "port", "createTime", "autoReconnect")) {
            ObjectNode document = document();
            ((ObjectNode) document.get("connection")).put(field, "invalid-[REDACTED]");
            DeviceConfiguration config = codec.decodeLocalDevice(document);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> codec.toRuntime(config));
            assertTrue(error.getMessage().contains("mapping-device"));
            assertTrue(error.getMessage().contains(field));
            assertFalse(error.toString().contains("invalid-[REDACTED]"));
            assertNull(error.getCause());
        }
    }

    @Test
    void rejectsMalformedRawAlarmWithoutLeakingPayload() {
        ObjectNode document = document();
        ((ObjectNode) document.get("points").get(0)).put("alarmRule", "{invalid-[REDACTED]");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> codec.toRuntime(codec.decodeLocalDevice(document)));
        assertTrue(error.getMessage().contains("alarmRule"));
        assertTrue(error.getMessage().contains("mapping-device"));
        assertFalse(error.toString().contains("invalid-[REDACTED]"));
        assertNull(error.getCause());
    }

    @Test
    void rejectsNullPrimitiveOverflowAndNonFiniteNumbersAtTheirFields() {
        for (String field : List.of("autoReconnect", "initialReconnectDelay", "reconnectBackoffMultiplier")) {
            ObjectNode document = document();
            ObjectNode connection = (ObjectNode) document.get("connection");
            if ("autoReconnect".equals(field)) connection.putNull(field);
            else connection.set(field, codec.readJson("reconnectBackoffMultiplier".equals(field)
                    ? "1e999" : "9223372036854775808"));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> codec.toRuntime(codec.decodeLocalDevice(document)));
            assertTrue(error.getMessage().contains(field));
            assertTrue(error.getMessage().contains("mapping-device"));
            assertNull(error.getCause());
        }
    }

    @Test
    void restoresAllRawAlarmShapesWithoutTypedRuleConversion() throws Exception {
        JsonNode rule = codec.readJson("{\"future\":{\"decimal\":0.123456789012345678901234567890,\"array\":[null,true]}}");
        for (JsonNode variant : List.of(rule, codec.readJson("[" + rule + "]"),
                com.fasterxml.jackson.databind.node.TextNode.valueOf("[ " + rule + " ]"))) {
            ObjectNode document = document();
            ((ObjectNode) document.get("points").get(0)).set("alarmRule", variant);
            DeviceContext runtime = codec.toRuntime(codec.decodeLocalDevice(document));
            JsonNode restored = codec.readJson((String) raw(runtime.getDataPoints().get(0), "alarmRule"));
            assertEquals(variant.isTextual() ? codec.readJson(variant.textValue()) : variant, restored);
            assertTrue(codec.equivalent(runtime, codec.toRuntime(codec.fromRuntime(runtime))));
        }
    }

    @Test
    void nestedExtensionMetadataIsRealConfigurationNotIgnoredAuditTime() {
        DeviceContext first = codec.toRuntime(codec.decodeLocalDevice(document()));
        DeviceContext second = codec.toRuntime(codec.fromRuntime(first));
        first.getDataPoints().get(0).setAdditionalConfig(Map.of("future", Map.of("createTime", 100)));
        second.getDataPoints().get(0).setAdditionalConfig(Map.of("future", Map.of("createTime", 200)));
        assertFalse(codec.equivalent(first, second));
    }

    @Test
    void restoresLegacyLocalDateTextUsingTheKnownSourceTimezoneAndRejectsInvalidDates() {
        ObjectNode document = document();
        String legacy = "2026-10-22 01:02:03";
        ((ObjectNode) document.get("device")).put("createTime", legacy);
        ((ObjectNode) document.get("connection")).put("updateTime", legacy);
        ((ObjectNode) document.get("points").get(0)).put("createTime", legacy);
        DeviceContext runtime = codec.toRuntime(codec.decodeLocalDevice(document));
        Date expected = Date.from(java.time.LocalDateTime.parse("2026-10-22T01:02:03")
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant());
        assertEquals(expected, runtime.getDeviceInfo().getCreateTime());
        assertEquals(expected, runtime.getConnectionConfig().getUpdateTime());
        assertEquals(expected, runtime.getDataPoints().get(0).getCreateTime());
        ((ObjectNode) document.get("device")).put("createTime", "2026-02-30 01:02:03");
        var error = assertThrows(IllegalArgumentException.class, () -> codec.toRuntime(codec.decodeLocalDevice(document)));
        assertTrue(error.getMessage().contains("device.createTime"));
        assertNull(error.getCause());
    }

    @Test
    void explicitLegacyTimezoneDoesNotUseTheHostDefaultAndIsoOffsetsRemainAuthoritative() {
        ObjectNode document = document();
        ((ObjectNode) document.get("device")).put("createTime", "2026-10-22 01:02:03");
        ConfigurationJsonCodec utc = new ConfigurationJsonCodec(java.time.ZoneId.of("UTC"));
        Date expectedUtc = Date.from(java.time.Instant.parse("2026-10-22T01:02:03Z"));
        assertEquals(expectedUtc, utc.toRuntime(utc.decodeLocalDevice(document)).getDeviceInfo().getCreateTime());
        ((ObjectNode) document.get("device")).put("createTime", "2026-10-22T01:02:03Z");
        assertEquals(expectedUtc, codec.toRuntime(codec.decodeLocalDevice(document)).getDeviceInfo().getCreateTime());
        ((ObjectNode) document.get("device")).put("createTime", 123L);
        assertEquals(new Date(123), utc.toRuntime(utc.decodeLocalDevice(document)).getDeviceInfo().getCreateTime());
    }

    private ObjectNode document() {
        ObjectNode document = (ObjectNode) codec.readJson("""
                {"device":{"deviceId":"mapping-device","id":"mapping-device","protocolType":"MODBUS_TCP",
                "configSource":"local","temporaryConfig":true,"authConfig":{"future":[null,0.123456789012345678901]}},
                "connection":{"deviceId":"mapping-device","connectTimeoutMs":3456,"charset":"UTF-8","createTime":123},
                "points":[{"deviceId":"mapping-device","pointId":"point-b","status":0,"baseCollectionInterval":2500,
                "additionalConfig":{"historyEnabled":false}},
                {"deviceId":"mapping-device","pointId":"point-a","status":2,"additionalConfig":{}}]}
                """);
        ((ObjectNode) document.get("connection")).put("authParams", "{\"code\":\"001\",\"nullable\":null}");
        ((ObjectNode) document.get("points").get(0)).put("alarmRule",
                "[ {\"futureRule\":{\"items\":[null,123456789012345678901234567890]}} ]");
        return document;
    }

    private Object raw(DataPoint point, String name) throws Exception {
        Field field = DataPoint.class.getDeclaredField(name); field.setAccessible(true); return field.get(point);
    }
}

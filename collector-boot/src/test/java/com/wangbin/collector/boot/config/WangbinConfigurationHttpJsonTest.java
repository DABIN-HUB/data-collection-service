package com.wangbin.collector.boot.config;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.wangbin.collector.common.domain.entity.*;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WangbinConfigurationHttpJsonTest {
    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(new WangbinConfigurationJsonConfiguration().wangbinConfigurationJsonModule()).build();
    private static final String EXT = "{\"nested\":{\"decimal\":1.234567890123456789012345678900,\"integer\":123456789012345678901234567890},\"items\":[null,{\"extra\":true}]}";

    @Test void objectAlarmRuleDoesNotInvokeTypedGetterAndKeepsUnknownKeys() throws Exception {
        DataPoint p = new DataPoint(); p.setPointId("p"); p.setStatus(1);
        p.setAlarmRule(EXT);
        String json = mapper.writeValueAsString(p);
        JsonNode n = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build().readTree(json);
        assertTrue(n.path("alarmRule").path("nested").has("integer"));
        assertEquals("p",n.path("pointId").asText()); assertEquals(1,n.path("status").asInt());
        assertTrue(json.contains("1.234567890123456789012345678900"));
    }

    @Test void arrayAlarmRulePreservesUnknownFieldsThroughHttpReadWrite() throws Exception {
        DataPoint p = mapper.readValue("{\"pointId\":\"p\",\"alarmRule\":[{\"type\":\"threshold\",\"unknown\":"+EXT+"}]}",DataPoint.class);
        String json = mapper.writeValueAsString(p);
        assertTrue(mapper.readTree(json).path("alarmRule").get(0).has("unknown"));
        assertTrue(json.contains("1.234567890123456789012345678900"));
    }

    @Test void connectionMapsPreserveModbusOpcIecMqttStructuresAndExactNumbers() throws Exception {
        for (String protocol : List.of("MODBUS_TCP","OPC_UA","IEC104","MQTT")) {
            String input = "{\"connectionType\":\""+protocol+"\",\"extJson\":"+EXT+",\"authParams\":{\"username\":\"test\",\"optional\":null}}";
            DeviceConnection c = mapper.readValue(input,DeviceConnection.class);
            Map<?,?> nested = (Map<?,?>)c.getExtJson().get("nested");
            assertEquals(new BigDecimal("1.234567890123456789012345678900"),nested.get("decimal"));
            assertEquals(new BigInteger("123456789012345678901234567890"),nested.get("integer"));
            String json = mapper.writeValueAsString(c);
            DeviceConnection again = mapper.readValue(json,DeviceConnection.class);
            assertEquals(c.getExtJson(),again.getExtJson());
            assertEquals(protocol,again.getConnectionType());
            assertNull(again.getAuthParams().get("optional"));
        }
    }

    @Test void objectJsonStringMapsAndPointAdditionalConfigRemainExact() throws Exception {
        DeviceConnection c = mapper.readValue("{\"extJson\":"+mapper.writeValueAsString(EXT)+"}",DeviceConnection.class);
        DataPoint p = mapper.readValue("{\"additionalConfig\":"+mapper.writeValueAsString(EXT)+"}",DataPoint.class);
        assertEquals(c.getExtJson(),p.getAdditionalConfig());
        assertEquals(new BigDecimal("1.234567890123456789012345678900"),((Map<?,?>)p.getAdditionalConfig().get("nested")).get("decimal"));
        assertEquals(p.getAdditionalConfig(),mapper.readValue(mapper.writeValueAsString(p),DataPoint.class).getAdditionalConfig());
    }

    @Test void duplicateKeysTrailingTextAndNonStringAuthValuesAreRejectedWithoutEcho() throws Exception {
        for(String input : List.of("{\"extJson\":{\"password\":\"test-sensitive\",\"password\":\"other\"}}",
                "{\"extJson\":\"{\\\"a\\\":1} {\\\"b\\\":2}\"}","{\"authParams\":{\"password\":42}}")) {
            Exception failure = assertThrows(Exception.class,()->mapper.readValue(input,DeviceConnection.class));
            assertFalse(failure.getMessage().contains("test-sensitive"));
        }
    }

    @Test void scalarRulesAreRejectedBeforePersistence() {
        for (String rule : List.of("1", "true", "\"1\"", "\"true\"")) {
            assertThrows(Exception.class, () -> mapper.readValue("{\"alarmRule\":" + rule + "}", DataPoint.class));
        }
    }

    @Test void deviceAuthenticationConfigPreservesExactNestedNumbers() throws Exception {
        DeviceInfo device = mapper.readValue("{\"authConfig\":" + EXT + "}", DeviceInfo.class);
        Map<?, ?> nested = (Map<?, ?>) device.getAuthConfig().get("nested");
        assertEquals(new BigDecimal("1.234567890123456789012345678900"), nested.get("decimal"));
        assertEquals(new BigInteger("123456789012345678901234567890"), nested.get("integer"));
    }

    @Test void blankAndNullRulesRemainNull() throws Exception {
        for(String raw : Arrays.asList(null,"","  ","null")) {
            DataPoint p = new DataPoint(); p.setAlarmRule(raw);
            assertTrue(mapper.readTree(mapper.writeValueAsString(p)).path("alarmRule").isNull());
        }
    }
}

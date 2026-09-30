package com.wangbin.collector.common.domain.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataPointTest {

    @Test
    void getReportFieldPrefersConfiguredField() {
        DataPoint point = new DataPoint();
        point.setPointAlias("current");
        Map<String, Object> config = new HashMap<>();
        config.put("reportEnabled", true);
        config.put("reportField", " currentA ");
        point.setAdditionalConfig(config);

        assertEquals("currentA", point.getReportField());
        assertTrue(point.isReportEnabled());
    }

    @Test
    void pointAliasDoesNotFallbackToReportField() {
        DataPoint point = new DataPoint();
        point.setPointAlias(" voltage ");
        Map<String, Object> config = new HashMap<>();
        config.put("reportEnabled", true);
        point.setAdditionalConfig(config);

        assertNull(point.getReportField());
        assertFalse(point.isReportEnabled());
    }

    @Test
    void missingAliasMeansRawOnly() {
        DataPoint point = new DataPoint();
        point.setPointAlias("   ");
        Map<String, Object> config = new HashMap<>();
        config.put("reportEnabled", true);
        point.setAdditionalConfig(config);

        assertNull(point.getReportField());
        assertFalse(point.isReportEnabled());
    }

    @Test
    void disablingReportFlagTakesEffect() {
        DataPoint point = new DataPoint();
        point.setPointAlias("temp");
        Map<String, Object> config = new HashMap<>();
        config.put("reportEnabled", false);
        point.setAdditionalConfig(config);

        assertNull(point.getReportField());
        assertFalse(point.isReportEnabled());
    }

    @Test
    void shouldDeserializeAlarmRuleArrayReturnedByApi() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        DataPoint point = objectMapper.readValue("""
                {
                  "pointCode": "temperature",
                  "alarmRule": [{"ruleId":"r1","operator":">=","threshold":10,"level":"WARNING","enabled":true}]
                }
                """, DataPoint.class);

        assertEquals(1, point.getAlarmRule().size());
        assertEquals("r1", point.getAlarmRule().get(0).getRuleId());
    }
}

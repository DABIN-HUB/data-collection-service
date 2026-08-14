package com.wangbin.collector.common.domain.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertNotificationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldKeepBuilderAndJsonContractAfterMovingToCommon() throws Exception {
        AlertNotification notification = AlertNotification.builder()
                .deviceId("dev-1")
                .deviceName("device-1")
                .pointId("p1")
                .pointCode("temperature")
                .ruleId("rule-1")
                .ruleName("high-temperature")
                .level("WARNING")
                .message("温度过高")
                .eventType("ALARM")
                .eventId("event-1")
                .relatedEventId("event-0")
                .startedAt(100L)
                .durationMillis(20L)
                .value(12.5d)
                .unit("℃")
                .timestamp(123456789L)
                .build();

        String json = objectMapper.writeValueAsString(notification);
        AlertNotification restored = objectMapper.readValue(json, AlertNotification.class);

        assertTrue(json.contains("\"deviceId\""));
        assertTrue(json.contains("\"pointCode\""));
        assertTrue(json.contains("\"eventType\""));
        assertEquals(notification, restored);
    }
}

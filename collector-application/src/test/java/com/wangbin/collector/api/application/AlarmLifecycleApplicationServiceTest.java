package com.wangbin.collector.api.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgementService;
import com.wangbin.collector.storage.service.AlarmHistoryService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlarmLifecycleApplicationServiceTest {
    @Test
    void acknowledgedActivationIncludesValueAndCanBeFilteredByState() {
        AlarmHistoryService history = mock(AlarmHistoryService.class);
        AlarmAcknowledgementService acknowledgements = mock(AlarmAcknowledgementService.class);
        when(history.isEnabled()).thenReturn(true);
        Map<String, Object> activation = new HashMap<>(Map.of("alarm_id", "a-2", "event_ts", 100L,
                "alarm_last_occurred_at", 80L, "value_text", "42.5", "unit", "C"));
        when(history.queryRecentAlarmActivations(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(activation));
        when(history.queryRecoveriesByAlarmIds(List.of("a-2"))).thenReturn(List.of());
        when(acknowledgements.findAll(List.of("a-2"))).thenReturn(Map.of("a-2",
                new com.wangbin.collector.monitor.alert.AlarmAcknowledgement("a-2", "operator", 200L, "ok", "key")));
        org.springframework.beans.factory.ObjectProvider<AlarmHistoryService> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(history);
        var service = new AlarmLifecycleApplicationService(provider, acknowledgements, new ObjectMapper());
        var result = service.query(null, null, null, null, null, "ACKED", null, null, 200);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).lifecycleState()).isEqualTo("ACKED");
        assertThat(result.items().get(0).lastOccurredAt()).isEqualTo(80L);
        assertThat(result.items().get(0).value()).isEqualTo("42.5");
        assertThat(result.items().get(0).unit()).isEqualTo("C");
        assertThat(service.query(null, null, null, null, null, "ACTIVE", null, null, 200).items()).isEmpty();
    }

    @Test
    void overlaysInfoRecoveryOnActivationAndExcludesRecoveryFromRows() {
        AlarmHistoryService history = mock(AlarmHistoryService.class);
        AlarmAcknowledgementService acknowledgements = mock(AlarmAcknowledgementService.class);
        when(history.isEnabled()).thenReturn(true);
        Map<String, Object> activation = new HashMap<>(Map.of("alarm_event_type", "ALARM", "alarm_id", "a-1", "alarm_level", "WARNING", "event_ts", 100L));
        Map<String, Object> recovery = new HashMap<>(Map.of("related_alarm_id", "a-1", "alarm_event_type", "ALARM_RECOVERED", "alarm_level", "INFO", "event_ts", 200L));
        when(history.queryRecentAlarmActivations(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(activation));
        when(history.queryRecoveriesByAlarmIds(List.of("a-1"))).thenReturn(List.of(recovery));
        when(acknowledgements.findAll(anyList())).thenReturn(Map.of());
        org.springframework.beans.factory.ObjectProvider<AlarmHistoryService> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(history);
        var service = new AlarmLifecycleApplicationService(provider, acknowledgements, new ObjectMapper());
        var result = service.query(null, null, null, null, null, null, null, null, 200);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).lifecycleState()).isEqualTo("RECOVERED");
        assertThat(result.items().get(0).alarmId()).isEqualTo("a-1");
        assertThat(result.items().get(0).recoveredAt()).isEqualTo(200L);
    }
}

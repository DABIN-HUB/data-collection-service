package com.wangbin.collector.api.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgement;
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
    void stateFilterPagesPastNonMatchingActivations() {
        AlarmHistoryService history = mock(AlarmHistoryService.class);
        AlarmAcknowledgementService acknowledgements = mock(AlarmAcknowledgementService.class);
        when(history.isEnabled()).thenReturn(true);
        List<Map<String, Object>> first = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> (Map<String, Object>) new HashMap<String, Object>(Map.of("alarm_id", "old-" + i, "event_ts", (long) i)))
                .toList();
        List<Map<String, Object>> second = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> (Map<String, Object>) new HashMap<String, Object>(Map.of("alarm_id", "active-" + i, "event_ts", (long) (100 + i))))
                .toList();
        when(history.queryRecentAlarmActivations(any(), any(), any(), any(), any(), any(), any(), eq(100)))
                .thenReturn(first);
        when(history.queryRecentAlarmActivations(any(), any(), any(), any(), any(), any(), any(), eq(100), eq(100)))
                .thenReturn(second);
        when(history.queryRecoveriesByAlarmIds(anyList())).thenReturn(List.of());
        when(acknowledgements.findAll(anyList())).thenAnswer(invocation -> {
            Map<String, AlarmAcknowledgement> result = new HashMap<>();
            for (String id : (List<String>) invocation.getArgument(0)) {
                if (id.startsWith("old-")) {
                    result.put(id, new AlarmAcknowledgement(id, "operator", 200L, "ack", "key"));
                }
            }
            return result;
        });
        org.springframework.beans.factory.ObjectProvider<AlarmHistoryService> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(history);
        var service = new AlarmLifecycleApplicationService(provider, acknowledgements, new ObjectMapper());

        var result = service.query(null, null, null, null, null, "ACTIVE", null, null, 100);

        assertThat(result.items()).hasSize(100);
        assertThat(result.items().get(0).alarmId()).isEqualTo("active-0");
        verify(history).queryRecentAlarmActivations(any(), any(), any(), any(), any(), any(), any(), eq(100), eq(100));
    }

    @Test
    void recoveryUsesStructuredLastOccurredAtBeforeLegacyPayload() {
        AlarmHistoryService history = mock(AlarmHistoryService.class);
        AlarmAcknowledgementService acknowledgements = mock(AlarmAcknowledgementService.class);
        when(history.isEnabled()).thenReturn(true);
        Map<String, Object> activation = new HashMap<>(Map.of("alarm_id", "a-structured", "event_ts", 100L));
        Map<String, Object> recovery = new HashMap<>(Map.of("related_alarm_id", "a-structured", "event_ts", 200L,
                "alarm_last_occurred_at", 1500L, "payload_json", "{}"));
        when(history.queryRecentAlarmActivations(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(activation));
        when(history.queryRecoveriesByAlarmIds(anyList())).thenReturn(List.of(recovery));
        when(acknowledgements.findAll(anyList())).thenReturn(Map.of());
        org.springframework.beans.factory.ObjectProvider<AlarmHistoryService> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(history);
        var service = new AlarmLifecycleApplicationService(provider, acknowledgements, new ObjectMapper());

        var result = service.query(null, null, null, null, null, "RECOVERED", null, null, 100);

        assertThat(result.items().get(0).lastOccurredAt()).isEqualTo(1500L);
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

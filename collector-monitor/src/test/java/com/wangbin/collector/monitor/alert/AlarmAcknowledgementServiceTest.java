package com.wangbin.collector.monitor.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.core.alarm.AlarmStateProperties;
import com.wangbin.collector.core.alarm.AlarmStateTracker;
import com.wangbin.collector.core.alarm.AlarmLifecycleState;
import com.wangbin.collector.core.alarm.InMemoryAlarmStateRepository;
import com.wangbin.collector.common.domain.entity.AlarmRule;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlarmAcknowledgementServiceTest {

    private final AlarmAcknowledgementService service = createMemoryService();

    @Test
    void shouldAcknowledgeAlarmIdempotently() {
        AlarmAcknowledgementRequest firstRequest = new AlarmAcknowledgementRequest("首次处理", "request-1");
        AlarmAcknowledgementRequest repeatedRequest = new AlarmAcknowledgementRequest("重复处理", "request-2");

        AlarmAcknowledgement first = service.acknowledge("alarm-001", "测试人员", firstRequest);
        AlarmAcknowledgement repeated = service.acknowledge("alarm-001", "其他人员", repeatedRequest);

        assertThat(repeated).isEqualTo(first);
        assertThat(repeated.note()).isEqualTo("首次处理");
    }

    @Test
    void shouldQueryExistingAcknowledgementsOnly() {
        service.acknowledge("alarm-001", "测试人员", new AlarmAcknowledgementRequest("已处理", "request-1"));

        Map<String, AlarmAcknowledgement> result = service.findAll(List.of("alarm-001", "alarm-002"));

        assertThat(result).containsOnlyKeys("alarm-001");
    }

    @Test
    void shouldRestoreAcknowledgementFromRedis() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AlarmStateProperties properties = new AlarmStateProperties();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        AlarmAcknowledgementService writer = new AlarmAcknowledgementService(
                redisTemplate, objectMapper, properties,
                new AlarmStateTracker(new InMemoryAlarmStateRepository()));
        AlarmAcknowledgement acknowledgement = writer.acknowledge(
                "alarm-redis", "测试人员", new AlarmAcknowledgementRequest("已处理", "request-redis"));
        when(valueOperations.multiGet(List.of("collector:default:alarm:ack:v1:alarm-redis")))
                .thenReturn(List.of(objectMapper.writeValueAsString(acknowledgement)));

        AlarmAcknowledgementService reader = new AlarmAcknowledgementService(
                redisTemplate, objectMapper, properties,
                new AlarmStateTracker(new InMemoryAlarmStateRepository()));
        Map<String, AlarmAcknowledgement> restored = reader.findAll(List.of("alarm-redis"));

        assertThat(restored.get("alarm-redis")).isEqualTo(acknowledgement);
        verify(valueOperations).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldBridgeAuditAcknowledgementToRuntimeStateWithoutRequiringRuntimeSnapshot() {
        InMemoryAlarmStateRepository repository = new InMemoryAlarmStateRepository();
        AlarmStateTracker tracker = new AlarmStateTracker(repository);
        AlarmRule rule = new AlarmRule();
        rule.setRuleId("high");
        rule.setOperator(">");
        rule.setThreshold(10D);
        rule.setDuration(0);
        String alarmId = tracker.evaluate("device", "point", rule, 12D, 1_000L).alarmId();
        AlarmStateProperties properties = new AlarmStateProperties();
        properties.setEnabled(false);
        AlarmAcknowledgementService bridge = new AlarmAcknowledgementService(
                mock(StringRedisTemplate.class), new ObjectMapper(), properties, tracker);
        AlarmAcknowledgement acknowledgement = bridge.acknowledge(alarmId, "operator",
                new AlarmAcknowledgementRequest("handled", "request-1"));
        assertThat(repository.findByAlarmId(alarmId).orElseThrow().getLifecycleState())
                .isEqualTo(AlarmLifecycleState.ACKED);
        assertThat(repository.findByAlarmId(alarmId).orElseThrow().getAckedAt())
                .isEqualTo(acknowledgement.acknowledgedAt());
        assertThat(bridge.acknowledge("missing", "operator",
                new AlarmAcknowledgementRequest("note", "request-2"))).isNotNull();
    }

    private AlarmAcknowledgementService createMemoryService() {
        AlarmStateProperties properties = new AlarmStateProperties();
        properties.setEnabled(false);
        return new AlarmAcknowledgementService(
                mock(StringRedisTemplate.class), new ObjectMapper(), properties,
                new AlarmStateTracker(new InMemoryAlarmStateRepository()));
    }
}
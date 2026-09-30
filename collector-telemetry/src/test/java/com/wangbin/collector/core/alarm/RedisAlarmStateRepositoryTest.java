package com.wangbin.collector.core.alarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RedisAlarmStateRepositoryTest {

    @Test
    void shouldLookupPendingAndPersistedIncidentIndexAndReadLegacyJson() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        AlarmStateProperties properties = new AlarmStateProperties();
        ObjectMapper mapper = new ObjectMapper();
        RedisAlarmStateRepository writer = new RedisAlarmStateRepository(redisTemplate, mapper, properties);
        AlarmStateSnapshot snapshot = new AlarmStateSnapshot(
                "device|point|rule", AlarmLifecycleState.ACTIVE, 0L, 1_000L, "alarm-1", 2_000L);
        snapshot.setLastOccurredAt(2_000L);
        writer.save(snapshot);
        assertThat(writer.findByAlarmId("alarm-1")).contains(snapshot);
        writer.flushPendingSnapshots();
        org.mockito.Mockito.verify(values).set(
                org.mockito.ArgumentMatchers.eq("collector:default:alarm:state:v1:id:alarm-1"),
                org.mockito.ArgumentMatchers.eq("device|point|rule"), any(Duration.class));
        when(values.get("collector:default:alarm:state:v1:id:alarm-1"))
                .thenReturn("device|point|rule");
        when(values.get("collector:default:alarm:state:v1:device|point|rule"))
                .thenReturn(mapper.writeValueAsString(snapshot));
        RedisAlarmStateRepository reader = new RedisAlarmStateRepository(redisTemplate, mapper, properties);
        assertThat(reader.findByAlarmId("alarm-1")).isPresent();
        assertThat(reader.findByAlarmId("old-alarm")).isEmpty();
        when(values.get("collector:default:alarm:state:v1:device|point|rule"))
                .thenReturn("{\"stateKey\":\"device|point|rule\",\"lifecycleState\":\"ACTIVE\",\"activeSince\":1000,\"alarmId\":\"alarm-1\"}");
        assertThat(reader.find("device|point|rule").orElseThrow().getLastOccurredAt()).isZero();
    }

    @Test
    void shouldRetainPendingSnapshotWhenRedisWriteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new IllegalStateException("Redis不可用"))
                .when(valueOperations).set(any(String.class), any(String.class), any(Duration.class));
        AlarmStateProperties properties = new AlarmStateProperties();
        RedisAlarmStateRepository repository = new RedisAlarmStateRepository(
                redisTemplate, new ObjectMapper(), properties);
        repository.save(new AlarmStateSnapshot(
                "device-1|point-1|rule-1", AlarmLifecycleState.ACTIVE,
                0L, 1_000L, "alarm-1", 1_000L));

        repository.flushPendingSnapshots();

        assertThat(repository.getPendingWriteCount()).isEqualTo(1);
        assertThat(repository.find("device-1|point-1|rule-1")).isPresent();
    }
}

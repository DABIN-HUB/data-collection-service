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

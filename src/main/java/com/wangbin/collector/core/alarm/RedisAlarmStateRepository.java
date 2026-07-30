package com.wangbin.collector.core.alarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Redis告警状态仓库。写入先进入本地合并队列，避免阻塞数据处理线程。
 */
@Slf4j
@Primary
@Repository
public class RedisAlarmStateRepository implements AlarmStateRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AlarmStateProperties properties;
    private final ConcurrentMap<String, AlarmStateSnapshot> pendingSnapshots = new ConcurrentHashMap<>();

    public RedisAlarmStateRepository(StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     AlarmStateProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<AlarmStateSnapshot> find(String stateKey) {
        AlarmStateSnapshot pending = pendingSnapshots.get(stateKey);
        if (pending != null) {
            return Optional.of(pending);
        }
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(redisKey(stateKey));
            return json == null ? Optional.empty() : Optional.of(deserialize(json));
        } catch (RuntimeException exception) {
            log.warn("读取告警状态失败，当前规则使用本地状态: stateKey={}", stateKey, exception);
            return Optional.empty();
        }
    }

    @Override
    public void save(AlarmStateSnapshot snapshot) {
        if (snapshot == null || snapshot.getStateKey() == null) {
            return;
        }
        pendingSnapshots.put(snapshot.getStateKey(), snapshot);
    }

    /**
     * 合并同一规则的高频状态更新，仅持久化每批次中的最新快照。
     */
    @Scheduled(fixedDelayString = "${collector.alarm.state.retry-interval-ms:5000}")
    public void flushPendingSnapshots() {
        if (!properties.isEnabled() || pendingSnapshots.isEmpty()) {
            return;
        }
        int batchSize = Math.max(1, properties.getRetryBatchSize());
        for (Map.Entry<String, AlarmStateSnapshot> entry
                : new ArrayList<>(pendingSnapshots.entrySet()).stream().limit(batchSize).toList()) {
            try {
                AlarmStateSnapshot snapshot = entry.getValue();
                redisTemplate.opsForValue().set(
                        redisKey(entry.getKey()),
                        serialize(snapshot),
                        Duration.ofSeconds(Math.max(1L, properties.getTtlSeconds())));
                pendingSnapshots.remove(entry.getKey(), snapshot);
            } catch (RuntimeException exception) {
                log.warn("持久化告警状态失败，保留本地快照等待重试: stateKey={}", entry.getKey(), exception);
                return;
            }
        }
    }

    public int getPendingWriteCount() {
        return pendingSnapshots.size();
    }

    private String redisKey(String stateKey) {
        String prefix = properties.getKeyPrefix();
        return prefix.endsWith(":") ? prefix + stateKey : prefix + ":" + stateKey;
    }

    private String serialize(AlarmStateSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化告警状态失败", exception);
        }
    }

    private AlarmStateSnapshot deserialize(String json) {
        try {
            return objectMapper.readValue(json, AlarmStateSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("反序列化告警状态失败", exception);
        }
    }
}

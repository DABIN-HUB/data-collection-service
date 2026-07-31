package com.wangbin.collector.monitor.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.core.alarm.AlarmStateProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警确认服务。
 *
 * <p>Redis 可用时确认记录跨应用重启保留；Redis 不可用时降级到当前进程内存。</p>
 */
@Slf4j
@Service
public class AlarmAcknowledgementService {

    private static final int MAX_RECORD_COUNT = 10_000;
    private static final int MAX_ALARM_ID_LENGTH = 256;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AlarmStateProperties properties;
    private final Map<String, AlarmAcknowledgement> records = new LinkedHashMap<>();

    public AlarmAcknowledgementService(StringRedisTemplate redisTemplate,
                                       ObjectMapper objectMapper,
                                       AlarmStateProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 幂等确认告警。
     */
    public synchronized AlarmAcknowledgement acknowledge(String alarmId,
                                                          String operator,
                                                          AlarmAcknowledgementRequest request) {
        String normalizedAlarmId = validateAlarmId(alarmId);
        AlarmAcknowledgement existing = findExisting(normalizedAlarmId);
        if (existing != null) {
            return existing;
        }

        AlarmAcknowledgement acknowledgement = new AlarmAcknowledgement(
                normalizedAlarmId,
                StringUtils.hasText(operator) ? operator.trim() : "未知操作人",
                System.currentTimeMillis(),
                request.note() == null ? "" : request.note().trim(),
                request.idempotencyKey().trim());
        AlarmAcknowledgement persisted = persistIfAbsent(acknowledgement);
        AlarmAcknowledgement result = persisted == null ? acknowledgement : persisted;
        records.put(normalizedAlarmId, result);
        removeOldestIfNecessary();
        return result;
    }

    /**
     * 批量读取确认状态。
     */
    public synchronized Map<String, AlarmAcknowledgement> findAll(List<String> alarmIds) {
        Map<String, AlarmAcknowledgement> result = new LinkedHashMap<>();
        for (String alarmId : alarmIds) {
            if (!StringUtils.hasText(alarmId)) {
                continue;
            }
            String normalizedAlarmId = alarmId.trim();
            AlarmAcknowledgement acknowledgement = findExisting(normalizedAlarmId);
            if (acknowledgement != null) {
                result.put(normalizedAlarmId, acknowledgement);
            }
        }
        return Map.copyOf(result);
    }

    private AlarmAcknowledgement findExisting(String alarmId) {
        AlarmAcknowledgement cached = records.get(alarmId);
        if (cached != null || !properties.isEnabled()) {
            return cached;
        }
        try {
            String payload = redisTemplate.opsForValue().get(redisKey(alarmId));
            if (!StringUtils.hasText(payload)) {
                return null;
            }
            AlarmAcknowledgement acknowledgement = objectMapper.readValue(payload, AlarmAcknowledgement.class);
            records.put(alarmId, acknowledgement);
            removeOldestIfNecessary();
            return acknowledgement;
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("读取告警确认记录失败，降级使用本地状态: alarmId={}", alarmId, exception);
            return null;
        }
    }

    private AlarmAcknowledgement persistIfAbsent(AlarmAcknowledgement acknowledgement) {
        if (!properties.isEnabled()) {
            return null;
        }
        String key = redisKey(acknowledgement.alarmId());
        try {
            String payload = objectMapper.writeValueAsString(acknowledgement);
            Duration ttl = Duration.ofSeconds(Math.max(1L, properties.getTtlSeconds()));
            Boolean inserted = redisTemplate.opsForValue().setIfAbsent(key, payload, ttl);
            if (!Boolean.FALSE.equals(inserted)) {
                return null;
            }
            String existingPayload = redisTemplate.opsForValue().get(key);
            return StringUtils.hasText(existingPayload)
                    ? objectMapper.readValue(existingPayload, AlarmAcknowledgement.class)
                    : null;
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("持久化告警确认记录失败，降级保存在本地内存: alarmId={}", acknowledgement.alarmId(), exception);
            return null;
        }
    }

    private String redisKey(String alarmId) {
        String prefix = properties.getAcknowledgementKeyPrefix();
        if (!StringUtils.hasText(prefix)) {
            throw new IllegalStateException("告警确认 Redis 键前缀不能为空");
        }
        return prefix.endsWith(":") ? prefix + alarmId : prefix + ":" + alarmId;
    }

    private String validateAlarmId(String alarmId) {
        if (!StringUtils.hasText(alarmId)) {
            throw new IllegalArgumentException("告警标识不能为空");
        }
        String normalized = alarmId.trim();
        if (normalized.length() > MAX_ALARM_ID_LENGTH) {
            throw new IllegalArgumentException("告警标识不能超过 256 个字符");
        }
        return normalized;
    }

    private void removeOldestIfNecessary() {
        while (records.size() > MAX_RECORD_COUNT) {
            String oldestKey = records.keySet().iterator().next();
            records.remove(oldestKey);
        }
    }
}
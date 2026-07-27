package com.wangbin.collector.core.report.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.core.report.config.ReportProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 基于Redis哈希和有序集合实现的版本化云端上报发件箱。
 */
@Repository
public class RedisCloudOutboxRepository implements CloudOutboxRepository {

    private static final String DATA_SUFFIX = "data";
    private static final String DUE_SUFFIX = "due";
    private static final String CREATED_SUFFIX = "created";
    private static final String ISOLATED_SUFFIX = "isolated";
    private static final String DEVICE_SUFFIX = "device:";
    private static final DefaultRedisScript<List> CLAIM_SCRIPT = new DefaultRedisScript<>(
            "local ids=redis.call('ZRANGEBYSCORE',KEYS[1],'-inf',ARGV[1],'LIMIT',0,ARGV[2]);"
                    + "for _,id in ipairs(ids) do redis.call('ZADD',KEYS[1],ARGV[3],id); end;"
                    + "return ids;",
            List.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ReportProperties reportProperties;

    public RedisCloudOutboxRepository(StringRedisTemplate redisTemplate,
                                      ObjectMapper objectMapper,
                                      ReportProperties reportProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.reportProperties = reportProperties;
    }

    @Override
    public CloudOutboxMessage saveIfAbsent(CloudOutboxMessage message, long leaseUntil) {
        validateMessage(message);
        String existingJson = (String) redisTemplate.opsForHash().get(dataKey(), message.getMessageId());
        if (existingJson != null) {
            return deserialize(existingJson);
        }
        message.setNextAttemptAt(leaseUntil);
        Boolean stored = redisTemplate.opsForHash().putIfAbsent(
                dataKey(), message.getMessageId(), serialize(message));
        if (!Boolean.TRUE.equals(stored)) {
            return find(message.getMessageId()).orElseThrow(() ->
                    new IllegalStateException("发件箱并发写入后无法读取消息"));
        }
        redisTemplate.opsForZSet().add(dueKey(), message.getMessageId(), leaseUntil);
        redisTemplate.opsForZSet().add(createdKey(), message.getMessageId(), message.getCreatedAt());
        for (CloudOutboxMessage.CloudOutboxCommit commit : message.resolveCommits()) {
            redisTemplate.opsForSet().add(deviceKey(commit.getLocalDeviceId()), message.getMessageId());
        }
        return message;
    }

    @Override
    public Optional<CloudOutboxMessage> find(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        Object json = redisTemplate.opsForHash().get(dataKey(), messageId);
        return json == null ? Optional.empty() : Optional.of(deserialize(String.valueOf(json)));
    }

    @Override
    public List<CloudOutboxMessage> claimDue(long now, int limit, long leaseUntil) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        List<?> claimedIds = redisTemplate.execute(
                CLAIM_SCRIPT,
                List.of(dueKey()),
                Long.toString(now),
                Integer.toString(limit),
                Long.toString(leaseUntil));
        if (claimedIds == null || claimedIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<CloudOutboxMessage> messages = new ArrayList<>(claimedIds.size());
        for (Object claimedId : claimedIds) {
            find(String.valueOf(claimedId)).ifPresent(messages::add);
        }
        return messages;
    }

    @Override
    public void reschedule(CloudOutboxMessage message) {
        validateMessage(message);
        redisTemplate.opsForHash().put(dataKey(), message.getMessageId(), serialize(message));
        if (message.getStatus() == CloudOutboxStatus.ISOLATED) {
            redisTemplate.opsForZSet().remove(dueKey(), message.getMessageId());
            redisTemplate.opsForSet().add(isolatedKey(), message.getMessageId());
            return;
        }
        redisTemplate.opsForSet().remove(isolatedKey(), message.getMessageId());
        redisTemplate.opsForZSet().add(dueKey(), message.getMessageId(), message.getNextAttemptAt());
    }

    @Override
    public void complete(String messageId) {
        Optional<CloudOutboxMessage> existing = find(messageId);
        if (existing.isEmpty()) {
            return;
        }
        redisTemplate.opsForHash().delete(dataKey(), messageId);
        redisTemplate.opsForZSet().remove(dueKey(), messageId);
        redisTemplate.opsForZSet().remove(createdKey(), messageId);
        redisTemplate.opsForSet().remove(isolatedKey(), messageId);
        for (CloudOutboxMessage.CloudOutboxCommit commit : existing.get().resolveCommits()) {
            redisTemplate.opsForSet().remove(deviceKey(commit.getLocalDeviceId()), messageId);
        }
    }

    @Override
    public long countPending() {
        Long count = redisTemplate.opsForZSet().zCard(dueKey());
        return count == null ? 0L : count;
    }

    @Override
    public long countIsolated() {
        Long count = redisTemplate.opsForSet().size(isolatedKey());
        return count == null ? 0L : count;
    }

    @Override
    public long oldestCreatedAt() {
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> values =
                redisTemplate.opsForZSet().rangeWithScores(createdKey(), 0, 0);
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        Double score = values.iterator().next().getScore();
        return score == null ? 0L : score.longValue();
    }

    @Override
    public boolean hasPendingForDevice(String localDeviceId) {
        Long size = redisTemplate.opsForSet().size(deviceKey(localDeviceId));
        return size != null && size > 0;
    }

    private String dataKey() {
        return keyPrefix() + DATA_SUFFIX;
    }

    private String dueKey() {
        return keyPrefix() + DUE_SUFFIX;
    }

    private String createdKey() {
        return keyPrefix() + CREATED_SUFFIX;
    }

    private String isolatedKey() {
        return keyPrefix() + ISOLATED_SUFFIX;
    }

    private String deviceKey(String localDeviceId) {
        return keyPrefix() + DEVICE_SUFFIX + localDeviceId;
    }

    private String keyPrefix() {
        String configured = reportProperties.getOutbox().getKeyPrefix();
        return configured.endsWith(":") ? configured : configured + ":";
    }

    private String serialize(CloudOutboxMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化云端上报发件箱消息失败", exception);
        }
    }

    private CloudOutboxMessage deserialize(String json) {
        try {
            return objectMapper.readValue(json, CloudOutboxMessage.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("反序列化云端上报发件箱消息失败", exception);
        }
    }

    private void validateMessage(CloudOutboxMessage message) {
        if (message == null || message.getMessageId() == null || message.getMessageId().isBlank()
                || message.getLocalDeviceId() == null || message.getLocalDeviceId().isBlank()) {
            throw new IllegalArgumentException("发件箱消息标识和本地设备ID不能为空");
        }
    }
}

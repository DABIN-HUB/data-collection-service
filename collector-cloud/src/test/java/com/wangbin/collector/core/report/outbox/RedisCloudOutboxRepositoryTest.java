package com.wangbin.collector.core.report.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.core.report.config.ReportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCloudOutboxRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private HashOperations<String, Object, Object> hash;
    private RedisCloudOutboxRepository repository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        hash = mock(HashOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForHash()).thenReturn(hash);
        repository = new RedisCloudOutboxRepository(redis, mapper, new ReportProperties());
    }

    @Test
    void listPaginatesPastNonmatchesAndReturnsNewestMatchingStatusAndDevice() throws Exception {
        // 最新的一千条均不匹配，旧窗口内仍需找到目标消息。
        List<String> newest = IntStream.range(0, 1000).mapToObj(i -> "other-" + i).toList();
        when(zset.reverseRange(any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            long start = invocation.getArgument(1);
            long end = invocation.getArgument(2);
            if (start >= 1002) return Set.of();
            List<String> all = new java.util.ArrayList<>(newest);
            all.add("match-new");
            all.add("match-old");
            return new LinkedHashSet<>(all.subList((int) start, (int) Math.min(all.size(), end + 1)));
        });
        when(hash.get(any(), any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(1);
            CloudOutboxMessage message = message(id, id.startsWith("match") ? "device-a" : "device-b",
                    id.startsWith("match") ? CloudOutboxStatus.WAITING_CONFIG : CloudOutboxStatus.PENDING);
            return mapper.writeValueAsString(message);
        });

        assertEquals(List.of("match-new", "match-old"), repository.list(
                CloudOutboxStatus.WAITING_CONFIG, "device-a", 2).stream()
                .map(CloudOutboxMessage::getMessageId).toList());
        verify(zset).reverseRange(any(), eq(1000L), eq(1199L));
    }

    @Test
    void listReturnsNewestFirstAndHonorsHardCapForUnfilteredResults() throws Exception {
        when(zset.reverseRange(any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            long start = invocation.getArgument(1);
            return IntStream.range((int) start, (int) start + 200)
                    .mapToObj(i -> "id-" + i).collect(Collectors.toCollection(LinkedHashSet::new));
        });
        when(hash.get(any(), any())).thenAnswer(invocation ->
                mapper.writeValueAsString(message(invocation.getArgument(1), "device-a", CloudOutboxStatus.ISOLATED)));

        List<CloudOutboxMessage> result = repository.list(null, null, 999);
        assertEquals(200, result.size());
        assertEquals("id-0", result.get(0).getMessageId());
        assertEquals("id-199", result.get(199).getMessageId());
        verify(zset).reverseRange(any(), eq(0L), eq(199L));
    }

    @Test
    void replayScriptChecksPresenceAndIsolationBeforeAnyWrite() {
        CloudOutboxMessage pending = message("replay-id", "device-a", CloudOutboxStatus.PENDING);
        pending.setNextAttemptAt(123L);
        when(redis.execute(any(RedisScript.class), any(List.class), any(String.class),
                any(String.class), any(String.class))).thenReturn(1L, 0L);

        assertTrue(repository.replayIsolated(pending));
        assertFalse(repository.replayIsolated(pending));
        org.mockito.ArgumentCaptor<RedisScript> script = org.mockito.ArgumentCaptor.forClass(RedisScript.class);
        verify(redis, org.mockito.Mockito.times(2)).execute(script.capture(),
                eq(List.of("collector:default:cloud:outbox:v1:data", "collector:default:cloud:outbox:v1:isolated", "collector:default:cloud:outbox:v1:due")),
                eq("replay-id"), any(String.class), eq("123"));
        String lua = script.getValue().getScriptAsString();
        assertTrue(lua.contains("HEXISTS"));
        assertTrue(lua.contains("SISMEMBER"));
        assertTrue(lua.indexOf("HEXISTS") < lua.indexOf("HSET"));
        assertTrue(lua.indexOf("SISMEMBER") < lua.indexOf("HSET"));
        assertTrue(lua.contains("SREM"));
        assertTrue(lua.contains("ZADD"));
    }

    @Test
    void listMatchesDevicesInsideAggregatedCommits() throws Exception {
        CloudOutboxMessage batch = message("batch", "device-a", CloudOutboxStatus.PENDING);
        batch.setCommits(List.of(
                new CloudOutboxMessage.CloudOutboxCommit("device-a", 1, 1, 2, java.util.Map.of()),
                new CloudOutboxMessage.CloudOutboxCommit("device-b", 1, 1, 2, java.util.Map.of()),
                new CloudOutboxMessage.CloudOutboxCommit("device-c", 1, 1, 2, java.util.Map.of())));
        when(zset.reverseRange(any(), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>(List.of("batch")));
        when(hash.get(any(), eq("batch"))).thenReturn(mapper.writeValueAsString(batch));

        assertEquals(List.of("batch"), repository.list(CloudOutboxStatus.PENDING, "device-b", 50)
                .stream().map(CloudOutboxMessage::getMessageId).toList());
        assertEquals(List.of("batch"), repository.list(CloudOutboxStatus.PENDING, "device-c", 50)
                .stream().map(CloudOutboxMessage::getMessageId).toList());
        assertTrue(repository.list(CloudOutboxStatus.PENDING, "device-x", 50).isEmpty());
    }

    private CloudOutboxMessage message(String id, String device, CloudOutboxStatus status) {
        return new CloudOutboxMessage(id, device, "pk", "cloud-device", "gateway", 1L,
                10L, 20L, 100L, 123L, 0, status, null, null, null);
    }
}

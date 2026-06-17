package com.wangbin.collector.core.cache.manager;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCacheManagerTest {

    @Test
    void redisCacheManagerShouldScanAndDeleteInBatches() {
        RedisCacheManager manager = new RedisCacheManager();
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        RedisConnection connection = mock(RedisConnection.class);
        @SuppressWarnings("unchecked")
        Cursor<byte[]> cursor = mock(Cursor.class);

        ReflectionTestUtils.setField(manager, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(manager, "keyPrefix", "collector:");
        ReflectionTestUtils.setField(manager, "initialized", true);

        doAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        }).when(redisTemplate).execute(org.mockito.ArgumentMatchers.<RedisCallback<?>>any());

        when(connection.scan(any(ScanOptions.class))).thenReturn(cursor);

        List<byte[]> scanResults = IntStream.range(0, 1001)
                .mapToObj(index -> ("collector:key:" + index).getBytes(StandardCharsets.UTF_8))
                .toList();
        AtomicInteger nextIndex = new AtomicInteger();
        when(cursor.hasNext()).thenAnswer(invocation -> nextIndex.get() < scanResults.size());
        when(cursor.next()).thenAnswer(invocation -> scanResults.get(nextIndex.getAndIncrement()));
        when(redisTemplate.delete(any(Collection.class)))
                .thenAnswer(invocation -> (long) ((Collection<?>) invocation.getArgument(0)).size());

        assertTrue(manager.deleteByPattern("*"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> deleteCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(redisTemplate, times(2)).delete(deleteCaptor.capture());
        List<Collection<String>> deleteBatches = deleteCaptor.getAllValues();
        assertEquals(1000, deleteBatches.get(0).size());
        assertEquals(1, deleteBatches.get(1).size());
    }
}

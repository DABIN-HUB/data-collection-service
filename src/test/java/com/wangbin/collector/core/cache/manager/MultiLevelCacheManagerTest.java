package com.wangbin.collector.core.cache.manager;

import com.wangbin.collector.core.cache.model.CacheKey;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiLevelCacheManagerTest {

    @Test
    void multiLevelCacheManagerShouldUsePipelineForBulkReads() {
        MultiLevelCacheManager manager = new MultiLevelCacheManager();
        LocalCacheManager localCacheManager = mock(LocalCacheManager.class);
        RedisCacheManager redisCacheManager = mock(RedisCacheManager.class);
        ExecutorService directExecutor = new DirectExecutorService();

        ReflectionTestUtils.setField(manager, "localCacheManager", localCacheManager);
        ReflectionTestUtils.setField(manager, "redisCacheManager", redisCacheManager);
        ReflectionTestUtils.setField(manager, "asyncExecutor", directExecutor);
        ReflectionTestUtils.setField(manager, "enabled", true);
        ReflectionTestUtils.setField(manager, "shuttingDown", false);
        ReflectionTestUtils.setField(manager, "maxLevel", 2);

        when(localCacheManager.getCacheLevel()).thenReturn(1);
        when(redisCacheManager.getCacheLevel()).thenReturn(2);

        CacheKey key1 = CacheKey.dataKey("dev-1", "p1");
        CacheKey key2 = CacheKey.dataKey("dev-1", "p2");
        when(localCacheManager.get(key1)).thenReturn("local-v1");
        when(localCacheManager.get(key2)).thenReturn(null);
        when(redisCacheManager.pipelineGetAll(List.of(key2), null)).thenReturn(Map.of(key2, "redis-v2"));

        Map<CacheKey, String> values = manager.getAll(List.of(key1, key2));

        assertEquals(2, values.size());
        assertEquals("local-v1", values.get(key1));
        assertEquals("redis-v2", values.get(key2));
        verify(redisCacheManager).pipelineGetAll(List.of(key2), null);
        verify(redisCacheManager, never()).get(any(CacheKey.class));
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}

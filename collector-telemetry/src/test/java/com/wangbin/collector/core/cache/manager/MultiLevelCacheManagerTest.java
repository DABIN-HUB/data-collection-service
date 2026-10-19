package com.wangbin.collector.core.cache.manager;

import com.wangbin.collector.core.cache.config.CacheMode;
import com.wangbin.collector.core.cache.config.CacheProperties;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.port.ExceptionReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiLevelCacheManagerTest {

    private LocalCacheManager localCache;
    private RedisCacheManager redisCache;
    private MultiLevelCacheManager cacheManager;
    private DirectExecutorService deferredExecutor;
    private final Map<String, Object> redisValues = new HashMap<>();

    @AfterEach
    void tearDown() {
        if (cacheManager != null) {
            cacheManager.destroy();
            deferredExecutor.shutdownNow();
        }
    }

    @Test
    void multiLevelCacheManagerShouldUsePipelineForBulkReads() {
        LocalCacheManager localCacheManager = mock(LocalCacheManager.class);
        RedisCacheManager redisCacheManager = mock(RedisCacheManager.class);
        ExecutorService directExecutor = new DirectExecutorService();
        MultiLevelCacheManager manager = new MultiLevelCacheManager(
                localCacheManager,
                redisCacheManager,
                null,
                new CacheProperties(),
                directExecutor);

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

    @Test
    void multiLevelCacheManagerShouldReportCacheReadExceptionThroughPort() {
        LocalCacheManager localCacheManager = mock(LocalCacheManager.class);
        RedisCacheManager redisCacheManager = mock(RedisCacheManager.class);
        ExceptionReporter exceptionReporter = mock(ExceptionReporter.class);
        ExecutorService directExecutor = new DirectExecutorService();
        MultiLevelCacheManager manager = new MultiLevelCacheManager(
                localCacheManager,
                redisCacheManager,
                exceptionReporter,
                new CacheProperties(),
                directExecutor);
        ReflectionTestUtils.setField(manager, "enabled", true);
        ReflectionTestUtils.setField(manager, "shuttingDown", false);
        ReflectionTestUtils.setField(manager, "maxLevel", 2);
        when(localCacheManager.getCacheLevel()).thenReturn(1);
        when(redisCacheManager.getCacheLevel()).thenReturn(2);
        CacheKey key = CacheKey.dataKey("dev-1", "p1");
        RuntimeException failure = new RuntimeException("local cache unavailable");
        when(localCacheManager.get(key)).thenThrow(failure);
        when(redisCacheManager.pipelineGetAll(List.of(key), null)).thenReturn(Map.of());

        manager.getAll(List.of(key));

        verify(exceptionReporter).record(failure, key.getFullKey(), null);
    }

    @Test
    void pendingReadThroughShouldPopulateLocalCacheWhenThereIsNoMutation() throws Exception {
        MultiLevelCacheManager manager = initializedManager(null);
        CacheKey key = CacheKey.dataKey("dev-1", "p1");
        redisValues.put(key.getFullKey(), "old");

        assertEquals("old", manager.get(key, String.class));
        assertNull(localCache.get(key));
        assertEquals(1, deferredExecutor.pending.size());

        deferredExecutor.runPending();

        assertEquals("old", localCache.get(key));
    }

    @Test
    void pendingReadThroughMustNotResurrectDeletedValue() throws Exception {
        MultiLevelCacheManager manager = initializedManager(null);
        CacheKey key = CacheKey.dataKey("dev-1", "p1");
        redisValues.put(key.getFullKey(), "old");

        assertEquals("old", manager.get(key, String.class));
        assertEquals(1, deferredExecutor.pending.size());
        assertTrue(manager.delete(key));
        deferredExecutor.runPending();

        assertNull(localCache.get(key));
        assertFalse(redisValues.containsKey(key.getFullKey()));
    }

    @Test
    void pendingReadThroughMustNotOverwriteNewPut() throws Exception {
        MultiLevelCacheManager manager = initializedManager(null);
        CacheKey key = CacheKey.dataKey("dev-1", "p1");
        redisValues.put(key.getFullKey(), "old");

        assertEquals("old", manager.get(key, String.class));
        assertTrue(manager.put(key, "new"));
        deferredExecutor.runPending();

        assertEquals("new", localCache.get(key));
        assertEquals("new", redisValues.get(key.getFullKey()));
    }

    @Test
    void pendingCacheAsideRemovalMustNotEraseNewWriteThroughPut() throws Exception {
        MultiLevelCacheManager manager = initializedManager(null);
        CacheKey key = CacheKey.dataKey("dev-1", "p1");
        manager.setCacheStrategy(false, true, true);
        assertTrue(manager.put(key, "old"));
        assertEquals(1, deferredExecutor.pending.size());

        manager.setCacheStrategy(true, true, true);
        assertTrue(manager.put(key, "new"));
        deferredExecutor.runPending();

        assertEquals("new", localCache.get(key));
        assertEquals("new", redisValues.get(key.getFullKey()));
    }

    @Test
    void bulkReadMustRejectResponsesCrossingDeleteOrPutWithoutExtraRedisGets() throws Exception {
        MultiLevelCacheManager manager = initializedManager(null);
        CacheKey deletedKey = CacheKey.dataKey("dev-1", "p1");
        CacheKey replacedKey = CacheKey.dataKey("dev-1", "p2");
        CacheKey unchangedKey = CacheKey.dataKey("dev-1", "p3");
        List<CacheKey> keys = List.of(deletedKey, replacedKey, unchangedKey);
        when(redisCache.pipelineGetAll(keys, null)).thenAnswer(invocation -> {
            Map<CacheKey, String> oldResponse = Map.of(
                    deletedKey, "deleted-old", replacedKey, "replaced-old", unchangedKey, "valid");
            assertTrue(manager.delete(deletedKey));
            assertTrue(manager.put(replacedKey, "new"));
            return oldResponse;
        });

        Map<CacheKey, String> result = manager.getAll(keys);
        deferredExecutor.runPending();

        assertEquals(Map.of(unchangedKey, "valid"), result);
        assertNull(localCache.get(deletedKey));
        assertEquals("new", localCache.get(replacedKey));
        assertEquals("valid", localCache.get(unchangedKey));
        verify(redisCache).pipelineGetAll(keys, null);
        verify(redisCache, never()).get(any(CacheKey.class));
        verify(redisCache, never()).get(any(CacheKey.class), any());
    }

    @Test
    void pendingReadThroughMustNotOverwriteWarmUp() throws Exception {
        MultiLevelCacheManager manager = initializedManager(null);
        CacheKey key = CacheKey.dataKey("dev-1", "p1");
        redisValues.put(key.getFullKey(), "old");

        assertEquals("old", manager.get(key, String.class));
        manager.warmUp(key, "new");
        deferredExecutor.runPending();

        assertEquals("new", localCache.get(key));
        assertEquals("new", redisValues.get(key.getFullKey()));
    }

    @Test
    void deleteIfMustPreserveNewLocalValueAndDeleteOldRedisValue() throws Exception {
        MultiLevelCacheManager manager = initializedManager(null);
        CacheKey key = CacheKey.dataKey("dev-1", "p1");
        redisValues.put(key.getFullKey(), "old");
        assertEquals("old", manager.get(key, String.class));
        localCache.put(key, "new");

        assertTrue(manager.deleteIf(key, "old"::equals));
        deferredExecutor.runPending();

        assertEquals("new", localCache.get(key));
        assertFalse(redisValues.containsKey(key.getFullKey()));
    }

    @Test
    void deleteIfOnEmptyLayersMustInvalidatePendingReadThrough() throws Exception {
        MultiLevelCacheManager manager = initializedManager(null);
        CacheKey key = CacheKey.dataKey("dev-1", "p1");
        redisValues.put(key.getFullKey(), "old");
        assertEquals("old", manager.get(key, String.class));
        redisValues.clear();

        assertTrue(manager.deleteIf(key, value -> true));
        deferredExecutor.runPending();

        assertNull(localCache.get(key));
    }

    @Test
    void deleteIfMustReportLayerFailureAndContinueWithOtherLayers() throws Exception {
        ExceptionReporter reporter = mock(ExceptionReporter.class);
        MultiLevelCacheManager manager = initializedManager(reporter);
        CacheKey key = CacheKey.dataKey("dev-1", "p1");
        localCache.put(key, "new");
        redisValues.put(key.getFullKey(), "old");
        RuntimeException failure = new RuntimeException("predicate failed");

        assertFalse(manager.deleteIf(key, value -> {
            if ("new".equals(value)) {
                throw failure;
            }
            return true;
        }));

        assertEquals("new", localCache.get(key));
        assertFalse(redisValues.containsKey(key.getFullKey()));
        verify(reporter).record(failure, key.getFullKey(), null);
    }

    @Test
    void deleteIfMustReportActualRedisReadFailureInsteadOfTreatingItAsAnEmptyLayer() throws Exception {
        ExceptionReporter reporter = mock(ExceptionReporter.class);
        MultiLevelCacheManager manager = initializedManager(reporter);
        CacheKey key = CacheKey.dataKey("dev-1", "p1");
        localCache.put(key, "old");
        RuntimeException failure = new RuntimeException("redis read failed");
        when(redisCache.doGet(key)).thenThrow(failure);

        assertFalse(manager.deleteIf(key, value -> true));

        assertNull(localCache.get(key));
        verify(reporter).record(failure, key.getFullKey(), null);
    }

    @Test
    void deleteIfMustReportDeleteFailureAndKeepOtherLayersDeleted() throws Exception {
        ExceptionReporter reporter = mock(ExceptionReporter.class);
        MultiLevelCacheManager manager = initializedManager(reporter);
        CacheKey key = CacheKey.dataKey("dev-1", "p1");
        localCache.put(key, "old");
        redisValues.put(key.getFullKey(), "old");
        doReturn(false).when(redisCache).doDelete(key);

        assertFalse(manager.deleteIf(key, value -> true));

        assertNull(localCache.get(key));
        assertEquals("old", redisValues.get(key.getFullKey()));
        verify(reporter).record(any(IllegalStateException.class), eq(key.getFullKey()), isNull());
    }

    @Test
    void pendingReadThroughMustNotResurrectValueAfterClear() throws Exception {
        MultiLevelCacheManager manager = initializedManager(null);
        CacheKey key = CacheKey.dataKey("dev-1", "p1");
        redisValues.put(key.getFullKey(), "old");
        assertEquals("old", manager.get(key, String.class));

        manager.clear();
        deferredExecutor.runPending();

        assertNull(localCache.get(key));
        assertTrue(redisValues.isEmpty());
    }

    @Test
    void pendingReadThroughMustNotResurrectValueAfterPatternDelete() throws Exception {
        MultiLevelCacheManager manager = initializedManager(null);
        CacheKey key = CacheKey.dataKey("dev-1", "p1");
        redisValues.put(key.getFullKey(), "old");
        when(redisCache.deleteByPattern("data:dev-1:")).thenAnswer(invocation -> {
            redisValues.clear();
            return true;
        });
        assertEquals("old", manager.get(key, String.class));
        // 保留已排队的读穿回写，并让两层实际包含待删除值；空层模式删除原本返回 false。
        assertTrue(localCache.put(key, "old"));

        assertTrue(manager.deleteByPattern("data:dev-1:"));
        deferredExecutor.runPending();

        assertNull(localCache.get(key));
        assertTrue(redisValues.isEmpty());
    }

    private MultiLevelCacheManager initializedManager(ExceptionReporter reporter) throws Exception {
        CacheProperties properties = new CacheProperties();
        properties.setType(CacheMode.MULTI_LEVEL);
        properties.getLocal().setInitialCapacity(16);
        properties.getLocal().setMaxSize(128);
        localCache = new LocalCacheManager(properties);
        redisCache = mock(RedisCacheManager.class);
        when(redisCache.getCacheLevel()).thenReturn(2);
        when(redisCache.get(any(CacheKey.class))).thenAnswer(invocation ->
                redisValues.get(invocation.<CacheKey>getArgument(0).getFullKey()));
        when(redisCache.get(any(CacheKey.class), any())).thenAnswer(invocation ->
                redisValues.get(invocation.<CacheKey>getArgument(0).getFullKey()));
        when(redisCache.doGet(any(CacheKey.class))).thenAnswer(invocation ->
                redisValues.get(invocation.<CacheKey>getArgument(0).getFullKey()));
        when(redisCache.put(any(CacheKey.class), any(), anyLong())).thenAnswer(invocation -> {
            redisValues.put(invocation.<CacheKey>getArgument(0).getFullKey(), invocation.getArgument(1));
            return true;
        });
        when(redisCache.put(any(CacheKey.class), any())).thenAnswer(invocation -> {
            redisValues.put(invocation.<CacheKey>getArgument(0).getFullKey(), invocation.getArgument(1));
            return true;
        });
        when(redisCache.delete(any(CacheKey.class))).thenAnswer(invocation -> {
            redisValues.remove(invocation.<CacheKey>getArgument(0).getFullKey());
            return true;
        });
        when(redisCache.doDelete(any(CacheKey.class))).thenAnswer(invocation -> {
            redisValues.remove(invocation.<CacheKey>getArgument(0).getFullKey());
            return true;
        });
        doAnswer(invocation -> {
            redisValues.clear();
            return null;
        }).when(redisCache).clear();
        deferredExecutor = new DirectExecutorService(true);
        cacheManager = new MultiLevelCacheManager(localCache, redisCache, reporter, properties, deferredExecutor);
        cacheManager.init();
        return cacheManager;
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;
        private final boolean deferred;
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

        private DirectExecutorService() {
            this(false);
        }

        private DirectExecutorService(boolean deferred) {
            this.deferred = deferred;
        }

        private void runPending() {
            while (!pending.isEmpty()) {
                pending.removeFirst().run();
            }
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            pending.clear();
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
            if (deferred) {
                pending.addLast(command);
            } else {
                command.run();
            }
        }
    }
}

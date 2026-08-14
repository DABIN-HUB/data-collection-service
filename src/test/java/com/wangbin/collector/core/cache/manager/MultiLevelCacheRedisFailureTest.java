package com.wangbin.collector.core.cache.manager;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.wangbin.collector.core.cache.config.CacheMode;
import com.wangbin.collector.core.cache.config.CacheProperties;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.port.ExceptionReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiLevelCacheRedisFailureTest {

    private LocalCacheManager localCacheManager;
    private RedisCacheManager redisCacheManager;
    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOperations;
    private DirectExecutorService directExecutor;
    private Map<Logger, Level> originalLogLevels;

    @BeforeEach
    void setUp() {
        muteExpectedFailureLogs();
        CacheProperties properties = cacheProperties();
        localCacheManager = new LocalCacheManager(properties);
        localCacheManager.init();
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        redisCacheManager = new RedisCacheManager(redisTemplate, properties);
        redisCacheManager.initialized = true;
        directExecutor = new DirectExecutorService();
    }

    @AfterEach
    void tearDown() {
        try {
            localCacheManager.destroy();
            directExecutor.shutdownNow();
        } finally {
            restoreLogLevels();
        }
    }

    @Test
    void redisPutFailureShouldNotBreakLevelOneCache() {
        MultiLevelCacheManager manager = multiLevelManager(null);
        CacheKey key = CacheKey.dataKey("redis-fail-dev", "p1");
        doThrow(new RedisConnectionFailureException("redis write failed"))
                .when(valueOperations).set(anyString(), any(), anyLong(), eq(TimeUnit.SECONDS));

        boolean success = manager.put(key, "v1", 1000L);

        assertFalse(success);
        assertEquals("v1", manager.get(key, String.class));
        assertEquals(1L, localCacheManager.getStatistics().get("totalPuts"));
        assertEquals(1L, redisCacheManager.getStatistics().get("totalErrors"));
    }

    @Test
    void redisRecoveryShouldAllowLaterWritesWithoutDisablingLocalCache() {
        MultiLevelCacheManager manager = multiLevelManager(null);
        CacheKey firstKey = CacheKey.dataKey("redis-recovery-dev", "p1");
        CacheKey secondKey = CacheKey.dataKey("redis-recovery-dev", "p2");
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> {
            if (writes.incrementAndGet() == 1) {
                throw new RedisConnectionFailureException("redis first write failed");
            }
            return null;
        }).when(valueOperations).set(anyString(), any(), anyLong(), eq(TimeUnit.SECONDS));

        assertFalse(manager.put(firstKey, "v1", 1000L));
        assertTrue(manager.put(secondKey, "v2", 1000L));

        assertEquals("v1", manager.get(firstKey, String.class));
        assertEquals("v2", manager.get(secondKey, String.class));
        assertEquals(2, writes.get());
    }

    @Test
    void levelOneHitShouldNotTouchRedisWhenRedisIsDown() {
        MultiLevelCacheManager manager = multiLevelManager(null);
        CacheKey key = CacheKey.dataKey("redis-l1-dev", "p1");
        localCacheManager.put(key, "local-value", 1000L);
        doThrow(new RedisConnectionFailureException("redis read failed"))
                .when(valueOperations).get(anyString());

        String value = manager.get(key, String.class);

        assertEquals("local-value", value);
        assertEquals(0L, redisCacheManager.getStatistics().get("totalGets"));
    }

    @Test
    void redisReadFailureOnLevelOneMissShouldReturnMissAndKeepManagerOperational() {
        MultiLevelCacheManager manager = multiLevelManager(mock(ExceptionReporter.class));
        CacheKey key = CacheKey.dataKey("redis-miss-dev", "p1");
        doThrow(new RedisConnectionFailureException("redis read failed"))
                .when(valueOperations).get(anyString());

        assertNull(manager.get(key, String.class));
        assertTrue(manager.put(CacheKey.dataKey("redis-miss-dev", "p2"), "after-failure", 1000L));
    }

    private MultiLevelCacheManager multiLevelManager(ExceptionReporter exceptionReporter) {
        MultiLevelCacheManager manager = new MultiLevelCacheManager(
                localCacheManager,
                redisCacheManager,
                exceptionReporter,
                cacheProperties(),
                directExecutor);
        manager.init();
        return manager;
    }

    private CacheProperties cacheProperties() {
        CacheProperties properties = new CacheProperties();
        properties.setType(CacheMode.MULTI_LEVEL);
        properties.getLocal().setInitialCapacity(16);
        properties.getLocal().setMaxSize(128);
        properties.getLocal().setExpireAfterAccess(60);
        properties.getLocal().setExpireAfterWrite(300);
        properties.getRedis().setKeyPrefix("collector:test:");
        properties.getRedis().setDefaultExpire(60);
        return properties;
    }

    private void muteExpectedFailureLogs() {
        originalLogLevels = new LinkedHashMap<>();
        for (Class<?> loggerClass : java.util.List.of(
                RedisCacheManager.class,
                AbstractCacheManager.class,
                MultiLevelCacheManager.class,
                LocalCacheManager.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
            originalLogLevels.put(logger, logger.getLevel());
            logger.setLevel(Level.OFF);
        }
    }

    private void restoreLogLevels() {
        if (originalLogLevels == null) {
            return;
        }
        originalLogLevels.forEach(Logger::setLevel);
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown = true;
            return java.util.List.of();
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

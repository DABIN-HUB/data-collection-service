package com.wangbin.collector.core.cache.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.core.cache.config.CacheMode;
import com.wangbin.collector.core.cache.config.CacheProperties;
import com.wangbin.collector.core.cache.model.CacheKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 06 缓存模式和 Redis 故障矩阵的真实定点验证。
 *
 * <p>可用 Redis 端口由 phase6.redis.port 提供；不可用端口使用本机未监听端口，
 * 不连接现役 Redis，也不执行全库清理。</p>
 */
@EnabledIfSystemProperty(named = "phase6.redis.port", matches = "\\d+")
class Phase06CacheModeMatrixTest {

    private static final int DEFAULT_REDIS_PORT = 56379;
    private static final int UNAVAILABLE_REDIS_PORT = 56389;

    @Test
    void shouldPreserveLocalRedisAndMultiLevelContractsAcrossRealAvailabilityMatrix() throws Exception {
        int availablePort = Integer.getInteger("phase6.redis.port", DEFAULT_REDIS_PORT);
        verifyLocalMode("phase06:matrix:local:available:");
        verifyLocalMode("phase06:matrix:local:unavailable:");
        verifyRedisMode(availablePort, true);
        verifyRedisMode(UNAVAILABLE_REDIS_PORT, false);
        verifyMultiLevelMode(availablePort, true);
        verifyMultiLevelMode(UNAVAILABLE_REDIS_PORT, false);
    }

    private void verifyLocalMode(String prefix) throws Exception {
        CacheProperties properties = cacheProperties(CacheMode.LOCAL, prefix);
        LocalCacheManager local = new LocalCacheManager(properties);
        local.init();
        try {
            CacheKey key = CacheKey.dataKey("phase6-local", UUID.randomUUID().toString());
            assertTrue(local.put(key, "local-value", CacheKey.EXPIRE_1_MINUTE));
            assertEquals("local-value", local.get(key, String.class));
        } finally {
            local.destroy();
        }
    }

    private void verifyRedisMode(int port, boolean expectedAvailable) throws Exception {
        CacheProperties properties = cacheProperties(CacheMode.REDIS,
                "phase06:matrix:redis:" + port + ":" + UUID.randomUUID() + ":");
        Fixture fixture = fixture(properties, port, false);
        try {
            if (expectedAvailable) {
                assertEquals("PONG", fixture.factory.getConnection().ping());
                CacheKey key = CacheKey.dataKey("phase6-redis", UUID.randomUUID().toString());
                assertTrue(fixture.redis.put(key, "redis-value", CacheKey.EXPIRE_1_MINUTE));
                assertEquals("redis-value", fixture.redis.get(key, String.class));
                assertNotNull(fixture.template.opsForValue().get(properties.getRedis().getKeyPrefix()
                        + key.getFullKey()));
            } else {
                long start = System.nanoTime();
                CacheKey key = CacheKey.dataKey("phase6-redis-unavailable", UUID.randomUUID().toString());
                assertFalse(fixture.redis.put(key, "must-not-fallback", CacheKey.EXPIRE_1_MINUTE));
                assertNull(fixture.redis.get(key, String.class));
                assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5,
                        "不可用 Redis 必须在有界超时内明确失败");
            }
        } finally {
            fixture.close();
        }
    }

    private void verifyMultiLevelMode(int port, boolean expectedAvailable) throws Exception {
        String prefix = "phase06:matrix:multi:" + port + ":" + UUID.randomUUID() + ":";
        CacheProperties properties = cacheProperties(CacheMode.MULTI_LEVEL, prefix);
        Fixture fixture = fixture(properties, port, true);
        try {
            CacheKey key = CacheKey.dataKey("phase6-multi", UUID.randomUUID().toString());
            boolean success = fixture.multi.put(key, "multi-value", CacheKey.EXPIRE_1_MINUTE);
            if (expectedAvailable) {
                assertTrue(success, "两层可用时 put 必须返回全部层级成功");
                assertEquals("multi-value", fixture.multi.get(key, String.class));
                assertNotNull(fixture.template.opsForValue().get(prefix + key.getFullKey()));
                assertFalse(fixture.multi.consumeLastWriteFallbackSuccess());
            } else {
                assertFalse(success, "Redis 失败时 MULTI_LEVEL.put 必须保持全部层级成功语义");
                assertEquals("multi-value", fixture.local.get(key, String.class));
                assertEquals("multi-value", fixture.multi.get(key, String.class));
                assertTrue(fixture.multi.consumeLastWriteFallbackSuccess(),
                        "本地层成功、Redis 层失败时必须报告明确降级结果");
                assertFalse(fixture.multi.consumeLastWriteFallbackSuccess(),
                        "消费后 ThreadLocal 降级标记不得污染下一次处理");
                CacheKey nextKey = CacheKey.dataKey("phase6-multi-next", UUID.randomUUID().toString());
                assertFalse(fixture.multi.put(nextKey, "next-value", CacheKey.EXPIRE_1_MINUTE));
                assertTrue(fixture.multi.consumeLastWriteFallbackSuccess());
            }
        } finally {
            fixture.close();
        }
    }

    private Fixture fixture(CacheProperties properties, int port, boolean withLocal) {
        LettuceConnectionFactory factory = connectionFactory(port);
        RedisTemplate<String, Object> template = redisTemplate(factory);
        RedisCacheManager redis = new RedisCacheManager(template, properties);
        redis.init();
        LocalCacheManager local = null;
        if (withLocal) {
            local = new LocalCacheManager(properties);
            local.init();
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        MultiLevelCacheManager multi = new MultiLevelCacheManager(
                local, redis, null, properties, executor);
        multi.init();
        return new Fixture(factory, template, local, redis, multi, executor);
    }

    private LettuceConnectionFactory connectionFactory(int port) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration("127.0.0.1", port);
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(1))
                .shutdownTimeout(Duration.ofMillis(100))
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client);
        factory.afterPropertiesSet();
        return factory;
    }

    private RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        StringRedisSerializer strings = new StringRedisSerializer();
        template.setConnectionFactory(factory);
        template.setKeySerializer(strings);
        template.setHashKeySerializer(strings);
        GenericJackson2JsonRedisSerializer values = new GenericJackson2JsonRedisSerializer(new ObjectMapper());
        template.setValueSerializer(values);
        template.setHashValueSerializer(values);
        template.afterPropertiesSet();
        return template;
    }

    private CacheProperties cacheProperties(CacheMode mode, String prefix) {
        CacheProperties properties = new CacheProperties();
        properties.setType(mode);
        properties.getLocal().setInitialCapacity(16);
        properties.getLocal().setMaxSize(128);
        properties.getLocal().setExpireAfterAccess(60);
        properties.getLocal().setExpireAfterWrite(300);
        properties.getRedis().setKeyPrefix(prefix);
        properties.getRedis().setDefaultExpire(60);
        return properties;
    }

    private record Fixture(LettuceConnectionFactory factory,
                           RedisTemplate<String, Object> template,
                           LocalCacheManager local,
                           RedisCacheManager redis,
                           MultiLevelCacheManager multi,
                           ExecutorService executor) {
        private void close() {
            multi.destroy();
            if (local != null) local.destroy();
            redis.destroy();
            executor.shutdownNow();
            factory.destroy();
        }
    }
}

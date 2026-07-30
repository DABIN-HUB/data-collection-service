package com.wangbin.collector.core.cache.config;

import com.wangbin.collector.core.cache.manager.LocalCacheManager;
import com.wangbin.collector.core.cache.manager.RedisCacheManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CacheModeContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CacheManagerTestConfiguration.class)
            .withBean("cacheRedisTemplate", RedisTemplate.class, this::redisTemplate);

    @Test
    void localModeShouldNotCreateRedisCacheManager() {
        contextRunner.withPropertyValues("collector.cache.type=local")
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(LocalCacheManager.class).size());
                    assertEquals(0, context.getBeansOfType(RedisCacheManager.class).size());
                });
    }

    @Test
    void redisModeShouldNotCreateLocalCacheManager() {
        contextRunner.withPropertyValues("collector.cache.type=redis")
                .run(context -> {
                    assertEquals(0, context.getBeansOfType(LocalCacheManager.class).size());
                    assertEquals(1, context.getBeansOfType(RedisCacheManager.class).size());
                });
    }

    @Test
    void multiLevelModeShouldCreateBothCacheManagers() {
        contextRunner.withPropertyValues("collector.cache.type=multi-level")
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(LocalCacheManager.class).size());
                    assertEquals(1, context.getBeansOfType(RedisCacheManager.class).size());
                });
    }

    @SuppressWarnings("unchecked")
    private RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        return redisTemplate;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CacheProperties.class)
    @Import({LocalCacheManager.class, RedisCacheManager.class})
    static class CacheManagerTestConfiguration {
    }
}

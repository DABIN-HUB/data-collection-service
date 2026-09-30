package com.wangbin.collector.core.cache.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * 装配当前模块的配置。
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfiguration {

    /**
     * Redis模板配置（复用common.config.RedisConfig的）
     * 这里需要确保RedisTemplate已经配置
     */

    /**
     * 本地缓存管理器（可选，如果你还需要Spring的CacheManager）
     */
    @Bean("springLocalCacheManager")
    @ConditionalOnProperty(name = "collector.cache.type", havingValue = CacheMode.LOCAL_VALUE)
    /**
     * 执行当前业务逻辑。
     */
    public CacheManager springLocalCacheManager() {
        return createCaffeineCacheManager();
    }

    /**
     * 执行当前业务逻辑。
     */
    private CacheManager createCaffeineCacheManager() {
        com.github.benmanes.caffeine.cache.Cache<Object, Object> cache = Caffeine.newBuilder()
                .initialCapacity(100)
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofSeconds(30))
                .recordStats()
                .build();

        return new CaffeineCacheManager() {
            /**
             * 创建并返回业务对象。
             */
            @Override
            protected Cache<Object, Object> createNativeCaffeineCache(String name) {
                return cache;
            }
        };
    }

    /**
     * Redis缓存管理器（可选，如果你还需要Spring的CacheManager）
     */
    @Bean("springRedisCacheManager")
    @ConditionalOnProperty(name = "collector.cache.type", havingValue = CacheMode.REDIS_VALUE)
    public CacheManager springRedisCacheManager(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper) {

        GenericJackson2JsonRedisSerializer jacksonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jacksonSerializer))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .transactionAware()
                .build();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Bean("springMultiLevelCacheManager")
    @ConditionalOnProperty(name = "collector.cache.type", havingValue = CacheMode.MULTI_LEVEL_VALUE, matchIfMissing = true)
    public CacheManager springMultiLevelCacheManager() {
        return createCaffeineCacheManager();
    }
}


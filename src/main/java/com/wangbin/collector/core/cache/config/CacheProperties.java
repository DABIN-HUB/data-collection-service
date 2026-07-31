package com.wangbin.collector.core.cache.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 缓存配置属性
 */
@ConfigurationProperties(prefix = "collector.cache")
@Data
public class CacheProperties {

    private CacheMode type = CacheMode.LOCAL;
    private LocalCache local = new LocalCache();
    private RedisCache redis = new RedisCache();


    /**
     * 定义当前模块的业务组件。
     */
    @Data
    public static class LocalCache {
        private long maxSize = 10000;
        private long expireAfterWrite = 300;
        private long expireAfterAccess = 60;
        private int initialCapacity = 1000;
    }

    /**
     * 定义当前模块的业务组件。
     */
    @Data
    public static class RedisCache {
        private String keyPrefix = "collector:";
        private long defaultExpire = 3600;
        private long connectionTimeout = 3000;
    }
}

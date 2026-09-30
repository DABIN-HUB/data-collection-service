package com.wangbin.collector.core.cache.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 缓存配置属性
 */
@ConfigurationProperties(prefix = "collector.cache")
@Validated
@Data
public class CacheProperties {

    private CacheMode type = CacheMode.MULTI_LEVEL;
    @Valid
    private LocalCache local = new LocalCache();

    @Valid
    private RedisCache redis = new RedisCache();


    /**
     * 定义当前模块的业务组件。
     */
    @Data
    public static class LocalCache {
        @Min(1)
        private long maxSize = 10000;

        @Min(1)
        private long expireAfterWrite = 300;

        @Min(1)
        private long expireAfterAccess = 60;

        @Min(1)
        private int initialCapacity = 1000;
    }

    /**
     * 定义当前模块的业务组件。
     */
    @Data
    public static class RedisCache {
        private String keyPrefix = "collector:";

        @Min(1)
        private long defaultExpire = 3600;

        @Min(1)
        private long connectionTimeout = 3000;
    }
}

package com.wangbin.collector.core.cache.config;

/**
 * 缓存运行模式
 */
public enum CacheMode {

    LOCAL,
    REDIS,
    MULTI_LEVEL;

    public static final String LOCAL_VALUE = "local";
    public static final String REDIS_VALUE = "redis";
    public static final String MULTI_LEVEL_VALUE = "multi-level";
}

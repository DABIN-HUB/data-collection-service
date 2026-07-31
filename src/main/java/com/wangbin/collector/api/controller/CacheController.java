package com.wangbin.collector.api.controller;

import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供当前模块的控制器接口。
 */
@RestController
@RequestMapping("/api/cache")
public class CacheController {

    private final MultiLevelCacheManager cacheManager;

    /**
     * 创建缓存控制器。
     */
    public CacheController(@Qualifier("multiLevelCacheManager") MultiLevelCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return cacheManager.getStatistics();
    }

    @GetMapping("/health")
    public Map<String, Object> getHealth() {
        return cacheManager.getHealthStatus();
    }
}

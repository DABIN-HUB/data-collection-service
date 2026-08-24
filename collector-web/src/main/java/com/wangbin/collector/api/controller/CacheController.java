package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.controller.dto.CacheHealthResponse;
import com.wangbin.collector.api.controller.dto.CacheStatsResponse;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public CacheStatsResponse getStats() {
        return CacheStatsResponse.from(cacheManager.getStatistics());
    }

    @GetMapping("/health")
    public CacheHealthResponse getHealth() {
        return CacheHealthResponse.from(cacheManager.getHealthStatus());
    }
}

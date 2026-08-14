package com.wangbin.collector.api.controller;

import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CacheControllerTest {

    private final MultiLevelCacheManager cacheManager = mock(MultiLevelCacheManager.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CacheController(cacheManager)).build();

    @Test
    void shouldReturnCacheStatsWithStableDtoAndDynamicLevelKeys() throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", true);
        stats.put("writeThrough", true);
        stats.put("readThrough", true);
        stats.put("cacheAside", false);
        stats.put("maxLevel", 2);
        stats.put("totalReads", 10L);
        stats.put("totalWrites", 5L);
        stats.put("totalDeletes", 1L);
        stats.put("level1Hits", 7L);
        stats.put("level2Hits", 2L);
        stats.put("totalMisses", 1L);
        stats.put("totalHitRate", "90.00%");
        stats.put("level1HitRate", "77.78%");
        stats.put("level2HitRate", "22.22%");
        stats.put("missRate", "10.00%");
        stats.put("totalAccess", 10L);
        stats.put("levelStatistics", Map.of(
                "LOCAL", Map.of("cacheType", "LOCAL", "cacheSize", 3L),
                "REDIS", Map.of("cacheType", "REDIS", "redisHitRate", "80.00%")));
        when(cacheManager.getStatistics()).thenReturn(stats);

        mockMvc.perform(get("/api/cache/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.writeThrough", is(true)))
                .andExpect(jsonPath("$.totalReads", is(10)))
                .andExpect(jsonPath("$.totalHitRate", is("90.00%")))
                .andExpect(jsonPath("$.levelStatistics.LOCAL.cacheType", is("LOCAL")))
                .andExpect(jsonPath("$.levelStatistics.REDIS.redisHitRate", is("80.00%")));
    }

    @Test
    void shouldReturnCacheHealthWithStableLevelItems() throws Exception {
        Map<String, Object> unhealthy = new LinkedHashMap<>();
        unhealthy.put("type", "REDIS");
        unhealthy.put("level", 2);
        unhealthy.put("size", 0L);
        unhealthy.put("status", "UNHEALTHY");
        unhealthy.put("error", "Redis不可用");
        when(cacheManager.getHealthStatus()).thenReturn(Map.of(
                "enabled", true,
                "totalLevels", 2,
                "maxLevel", 2,
                "levels", List.of(
                        Map.of("type", "LOCAL", "level", 1, "size", 3L, "status", "HEALTHY"),
                        unhealthy),
                "overallStatus", "DEGRADED"));

        mockMvc.perform(get("/api/cache/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.totalLevels", is(2)))
                .andExpect(jsonPath("$.levels[0].type", is("LOCAL")))
                .andExpect(jsonPath("$.levels[0].error").doesNotExist())
                .andExpect(jsonPath("$.levels[1].status", is("UNHEALTHY")))
                .andExpect(jsonPath("$.levels[1].error", is("Redis不可用")))
                .andExpect(jsonPath("$.overallStatus", is("DEGRADED")));
    }
}

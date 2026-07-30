package com.wangbin.collector.core.cache.manager;

import com.google.common.util.concurrent.Striped;
import com.wangbin.collector.core.cache.model.CacheData;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.cache.config.CacheMode;
import com.wangbin.collector.core.cache.config.CacheProperties;
import com.wangbin.collector.monitor.metrics.ExceptionMonitorService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;

/**
 * 多级缓存管理器。
 */
@Slf4j
@Component("multiLevelCacheManager")
public class MultiLevelCacheManager implements CacheManager {

    @Autowired(required = false)
    @Qualifier("localCacheManager")
    private LocalCacheManager localCacheManager;

    @Autowired(required = false)
    @Qualifier("redisCacheManager")
    private RedisCacheManager redisCacheManager;

    @Autowired(required = false)
    private ExceptionMonitorService exceptionMonitorService;

    @Autowired
    private CacheProperties cacheProperties;

    private final List<CacheManager> cacheManagers = new CopyOnWriteArrayList<>();
    private volatile boolean enabled = true;
    private volatile boolean shuttingDown = false;
    private boolean writeThrough = true;
    private boolean readThrough = true;
    private boolean cacheAside = true;
    private int maxLevel = 2;

    private final AtomicLong totalReads = new AtomicLong(0);
    private final AtomicLong totalWrites = new AtomicLong(0);
    private final AtomicLong totalDeletes = new AtomicLong(0);
    private final AtomicLong level1Hits = new AtomicLong(0);
    private final AtomicLong level2Hits = new AtomicLong(0);
    private final AtomicLong totalMisses = new AtomicLong(0);

    @Autowired
    @Qualifier("ioIntensiveExecutor")
    private ExecutorService asyncExecutor;

    private final Striped<Lock> cacheLocks = Striped.lazyWeakLock(1024);

    @PostConstruct
    public void init() {
        shuttingDown = false;
        if (!isOperational()) {
            log.warn("多级缓存管理器已禁用");
            recordCacheWarning("多级缓存管理器已禁用", null);
            return;
        }

        cacheManagers.clear();
        CacheMode cacheMode = cacheProperties.getType();
        if (cacheMode == CacheMode.REDIS) {
            addCacheManager(redisCacheManager, CacheMode.REDIS);
        } else if (cacheMode == CacheMode.MULTI_LEVEL) {
            addCacheManager(localCacheManager, CacheMode.MULTI_LEVEL);
            addCacheManager(redisCacheManager, CacheMode.MULTI_LEVEL);
        } else {
            addCacheManager(localCacheManager, CacheMode.LOCAL);
        }
        if (cacheManagers.isEmpty()) {
            enabled = false;
            throw new IllegalStateException("缓存模式未找到可用缓存管理器: " + cacheMode);
        }
        cacheManagers.sort(Comparator.comparingInt(CacheManager::getCacheLevel));

        for (CacheManager manager : cacheManagers) {
            try {
                manager.init();
                log.info("缓存管理器初始化完成: {} [层级: {}]",
                        manager.getCacheType(), manager.getCacheLevel());
            } catch (Exception e) {
                log.error("缓存管理器初始化失败: {}", manager.getCacheType(), e);
            }
        }

        log.info("多级缓存管理器初始化完成，层级数: {}", cacheManagers.size());
    }

    @PreDestroy
    public void destroy() {
        shuttingDown = true;
        boolean wasEnabled = enabled;
        enabled = false;
        if (!wasEnabled) {
            return;
        }

        for (CacheManager manager : cacheManagers) {
            try {
                manager.destroy();
            } catch (Exception e) {
                log.error("cache manager destroy failed: {}", manager.getCacheType(), e);
            }
        }

        cacheManagers.clear();
        log.info("multi-level cache manager destroyed");
    }

    @Override
    public <T> boolean put(CacheKey key, T value) {
        return put(key, value, key.getExpireTime());
    }

    @Override
    public <T> boolean put(CacheKey key, T value, long expireTime) {
        if (!isOperational() || key == null || value == null) {
            return false;
        }

        totalWrites.incrementAndGet();
        Lock lock = cacheLocks.get(key.getFullKey());
        lock.lock();
        try {
            boolean allSuccess = true;
            if (writeThrough) {
                for (int i = cacheManagers.size() - 1; i >= 0; i--) {
                    CacheManager manager = cacheManagers.get(i);
                    boolean success = manager.put(key, value, expireTime);
                    if (!success) {
                        allSuccess = false;
                        log.warn("缓存写入失败: {} [层级: {}]",
                                manager.getCacheType(), manager.getCacheLevel());
                        recordCacheWarning(
                                String.format("缓存写入失败: %s [层级: %d]",
                                        manager.getCacheType(), manager.getCacheLevel()),
                                key);
                    }
                }
            } else if (cacheAside) {
                CacheManager primaryManager = getPrimaryCacheManager(key);
                boolean success = primaryManager.put(key, value, expireTime);
                if (!success) {
                    allSuccess = false;
                    log.warn("主缓存写入失败: {}", primaryManager.getCacheType());
                    recordCacheWarning(
                            String.format("主缓存写入失败: %s", primaryManager.getCacheType()),
                            key);
                }
                asyncRemoveLowerLevels(key, primaryManager.getCacheLevel());
            } else {
                CacheManager primaryManager = getPrimaryCacheManager(key);
                boolean success = primaryManager != null && primaryManager.put(key, value, expireTime);
                if (!success) {
                    allSuccess = false;
                    log.warn("本地缓存写入失败");
                    recordCacheWarning("本地缓存写入失败", key);
                }
            }

            log.debug("多级缓存写入完成: key={}, levels={}, success={}",
                    key, cacheManagers.size(), allSuccess);
            return allSuccess;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T> boolean putAll(Map<CacheKey, T> dataMap) {
        if (!isOperational() || dataMap == null || dataMap.isEmpty()) {
            return true;
        }

        boolean allSuccess = true;
        for (Map.Entry<CacheKey, T> entry : dataMap.entrySet()) {
            boolean success = put(entry.getKey(), entry.getValue());
            if (!success) {
                allSuccess = false;
            }
        }

        log.debug("批量缓存写入完成: 总数={}, 全部成功={}", dataMap.size(), allSuccess);
        return allSuccess;
    }

    @Override
    public <T> T get(CacheKey key) {
        return get(key, null);
    }

    @Override
    public <T> T get(CacheKey key, Class<T> type) {
        if (!isOperational() || key == null) {
            return null;
        }

        totalReads.incrementAndGet();
        Lock lock = cacheLocks.get(key.getFullKey());
        lock.lock();
        try {
            T value = null;
            int hitLevel = -1;

            for (CacheManager manager : cacheManagers) {
                if (manager.getCacheLevel() > maxLevel) {
                    continue;
                }

                try {
                    T foundValue = manager.get(key, type);
                    if (foundValue != null) {
                        value = foundValue;
                        hitLevel = manager.getCacheLevel();
                        updateHitStatistics(hitLevel);
                        if (readThrough && hitLevel > 1) {
                            asyncUpdateLowerLevels(key, value, hitLevel);
                        }
                        break;
                    }
                } catch (Exception e) {
                    log.warn("cache read failed, fallback to next cache level: {} [Level: {}]",
                            manager.getCacheType(), manager.getCacheLevel(), e);
                    recordCacheException(e, key);
                }
            }

            if (value == null) {
                totalMisses.incrementAndGet();
                log.debug("多级缓存未命中: key={}", key);
            } else {
                log.debug("多级缓存命中: key={}, level={}", key, hitLevel);
            }

            return value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T> CacheData<T> getWithMetadata(CacheKey key) {
        T value = get(key);
        if (value == null) {
            return null;
        }

        CacheManager primaryManager = cacheManagers.isEmpty() ? null : cacheManagers.get(0);
        CacheData<T> cacheData = primaryManager == null ? null : primaryManager.getWithMetadata(key);
        if (cacheData == null) {
            cacheData = new CacheData<>();
            cacheData.setKey(key);
            cacheData.setValue(value);
            cacheData.setCacheTime(System.currentTimeMillis());
        }

        return cacheData;
    }

    @Override
    public <T> Map<CacheKey, T> getAll(List<CacheKey> keys) {
        if (!isOperational() || keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }

        if (!canUseRedisBulkRead()) {
            return getAllIndividually(keys);
        }

        totalReads.addAndGet(keys.size());
        Map<CacheKey, T> result = new HashMap<>();
        List<CacheKey> redisCandidates = new ArrayList<>();

        for (CacheKey key : keys) {
            try {
                T localValue = localCacheManager.get(key);
                if (localValue != null) {
                    result.put(key, localValue);
                    updateHitStatistics(localCacheManager.getCacheLevel());
                } else {
                    redisCandidates.add(key);
                }
            } catch (Exception e) {
                log.warn("本地缓存批量读取失败，将回退到 Redis: key={}", key, e);
                recordCacheException(e, key);
                redisCandidates.add(key);
            }
        }

        if (!redisCandidates.isEmpty()) {
            try {
                Map<CacheKey, T> redisValues = redisCacheManager.pipelineGetAll(redisCandidates, null);
                for (CacheKey key : redisCandidates) {
                    T value = redisValues.get(key);
                    if (value != null) {
                        result.put(key, value);
                        updateHitStatistics(redisCacheManager.getCacheLevel());
                        if (readThrough) {
                            asyncUpdateLowerLevels(key, value, redisCacheManager.getCacheLevel());
                        }
                    } else {
                        totalMisses.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                log.warn("Redis 批量读取失败，将回退到逐个读取: count={}", redisCandidates.size(), e);
                recordCacheException(e, null);
                populateFromRedisIndividually(redisCandidates, result);
            }
        }

        return result;
    }

    @Override
    public <T> List<CacheData<T>> getListWithMetadata(List<CacheKey> keys) {
        if (!isOperational() || keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }

        List<CacheData<T>> result = new ArrayList<>();
        for (CacheKey key : keys) {
            CacheData<T> data = getWithMetadata(key);
            if (data != null) {
                result.add(data);
            }
        }

        return result;
    }

    @Override
    public boolean delete(CacheKey key) {
        if (!isOperational() || key == null) {
            return false;
        }

        totalDeletes.incrementAndGet();
        Lock lock = cacheLocks.get(key.getFullKey());
        lock.lock();
        try {
            boolean allSuccess = true;
            for (CacheManager manager : cacheManagers) {
                boolean success = manager.delete(key);
                if (!success) {
                    allSuccess = false;
                    log.warn("缓存删除失败: {} [层级: {}]",
                            manager.getCacheType(), manager.getCacheLevel());
                    recordCacheWarning(
                            String.format("缓存删除失败: %s [层级: %d]",
                                    manager.getCacheType(), manager.getCacheLevel()),
                            key);
                }
            }
            return allSuccess;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean deleteAll(List<CacheKey> keys) {
        if (!isOperational() || keys == null || keys.isEmpty()) {
            return false;
        }

        boolean allSuccess = true;
        for (CacheKey key : keys) {
            boolean success = delete(key);
            if (!success) {
                allSuccess = false;
                log.warn("批量缓存删除失败: key={}", key);
                recordCacheWarning(String.format("批量缓存删除失败: %s", key), key);
            }
        }

        return allSuccess;
    }

    @Override
    public boolean deleteByPattern(String pattern) {
        if (!isOperational() || pattern == null || pattern.isEmpty()) {
            return false;
        }

        boolean allSuccess = true;
        for (CacheManager manager : cacheManagers) {
            boolean success = manager.deleteByPattern(pattern);
            if (!success) {
                allSuccess = false;
                log.warn("按模式删除缓存失败: {} [层级: {}]",
                        manager.getCacheType(), manager.getCacheLevel());
                recordCacheWarning(
                        String.format("按模式删除缓存失败: %s [层级: %d]",
                                manager.getCacheType(), manager.getCacheLevel()),
                        null);
            }
        }

        return allSuccess;
    }

    @Override
    public boolean exists(CacheKey key) {
        if (!isOperational() || key == null) {
            return false;
        }

        for (CacheManager manager : cacheManagers) {
            if (manager.exists(key)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean expire(CacheKey key, long expireTime) {
        if (!isOperational() || key == null || expireTime <= 0) {
            return false;
        }

        boolean allSuccess = true;
        for (CacheManager manager : cacheManagers) {
            boolean success = manager.expire(key, expireTime);
            if (!success) {
                allSuccess = false;
                log.warn("设置缓存过期时间失败: {} [层级: {}]",
                        manager.getCacheType(), manager.getCacheLevel());
                recordCacheWarning(
                        String.format("设置缓存过期时间失败: %s [层级: %d]",
                                manager.getCacheType(), manager.getCacheLevel()),
                        key);
            }
        }

        return allSuccess;
    }

    @Override
    public long getExpire(CacheKey key) {
        if (!isOperational() || key == null) {
            return -1;
        }

        for (CacheManager manager : cacheManagers) {
            long expire = manager.getExpire(key);
            if (expire != -1) {
                return expire;
            }
        }

        return -1;
    }

    @Override
    public void clear() {
        if (!isOperational()) {
            return;
        }

        for (CacheManager manager : cacheManagers) {
            try {
                manager.clear();
                log.info("缓存清空完成: {}", manager.getCacheType());
            } catch (Exception e) {
                log.error("缓存清空失败: {}", manager.getCacheType(), e);
            }
        }
    }

    @Override
    public long size() {
        if (!isOperational()) {
            return 0;
        }

        CacheManager primaryManager = getPrimaryCacheManager(null);
        return primaryManager != null ? primaryManager.size() : 0;
    }

    @Override
    public Set<CacheKey> keys() {
        if (!isOperational()) {
            return Collections.emptySet();
        }

        CacheManager primaryManager = getPrimaryCacheManager(null);
        return primaryManager != null ? primaryManager.keys() : Collections.emptySet();
    }

    @Override
    public Set<CacheKey> keys(String pattern) {
        if (!isOperational()) {
            return Collections.emptySet();
        }

        CacheManager primaryManager = getPrimaryCacheManager(null);
        return primaryManager != null ? primaryManager.keys(pattern) : Collections.emptySet();
    }

    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("enabled", enabled);
        stats.put("writeThrough", writeThrough);
        stats.put("readThrough", readThrough);
        stats.put("cacheAside", cacheAside);
        stats.put("maxLevel", maxLevel);
        stats.put("totalReads", totalReads.get());
        stats.put("totalWrites", totalWrites.get());
        stats.put("totalDeletes", totalDeletes.get());
        stats.put("level1Hits", level1Hits.get());
        stats.put("level2Hits", level2Hits.get());
        stats.put("totalMisses", totalMisses.get());

        long totalHits = level1Hits.get() + level2Hits.get();
        long totalAccess = totalReads.get();
        double totalHitRate = totalAccess > 0 ? (double) totalHits / totalAccess * 100 : 0.0;
        double level1HitRate = totalHits > 0 ? (double) level1Hits.get() / totalHits * 100 : 0.0;
        double level2HitRate = totalHits > 0 ? (double) level2Hits.get() / totalHits * 100 : 0.0;
        double missRate = totalAccess > 0 ? (double) totalMisses.get() / totalAccess * 100 : 0.0;

        stats.put("totalHitRate", String.format("%.2f%%", totalHitRate));
        stats.put("level1HitRate", String.format("%.2f%%", level1HitRate));
        stats.put("level2HitRate", String.format("%.2f%%", level2HitRate));
        stats.put("missRate", String.format("%.2f%%", missRate));
        stats.put("totalAccess", totalAccess);

        Map<String, Map<String, Object>> levelStats = new HashMap<>();
        for (CacheManager manager : cacheManagers) {
            levelStats.put(manager.getCacheType(), manager.getStatistics());
        }
        stats.put("levelStatistics", levelStats);
        return stats;
    }

    @Override
    public void resetStatistics() {
        totalReads.set(0);
        totalWrites.set(0);
        totalDeletes.set(0);
        level1Hits.set(0);
        level2Hits.set(0);
        totalMisses.set(0);

        for (CacheManager manager : cacheManagers) {
            manager.resetStatistics();
        }

        log.info("多级缓存统计重置完成");
    }

    @Override
    public int getCacheLevel() {
        return 0;
    }

    @Override
    public String getCacheType() {
        return "MULTI_LEVEL_CACHE";
    }

    private CacheManager getPrimaryCacheManager(CacheKey key) {
        for (int i = cacheManagers.size() - 1; i >= 0; i--) {
            CacheManager manager = cacheManagers.get(i);
            if (manager.getCacheLevel() <= maxLevel) {
                return manager;
            }
        }
        return cacheManagers.isEmpty() ? null : cacheManagers.get(0);
    }

    private void updateHitStatistics(int hitLevel) {
        switch (hitLevel) {
            case 1 -> level1Hits.incrementAndGet();
            case 2 -> level2Hits.incrementAndGet();
            default -> log.debug("未知的缓存层级命中: level={}", hitLevel);
        }
    }

    private boolean canUseRedisBulkRead() {
        return localCacheManager != null
                && redisCacheManager != null
                && maxLevel >= redisCacheManager.getCacheLevel();
    }

    private void addCacheManager(CacheManager cacheManager, CacheMode cacheMode) {
        if (cacheManager == null) {
            log.warn("缓存模式缺少对应缓存层，mode={}", cacheMode);
            return;
        }
        cacheManagers.add(cacheManager);
    }

    private <T> Map<CacheKey, T> getAllIndividually(List<CacheKey> keys) {
        Map<CacheKey, T> result = new HashMap<>();
        for (CacheKey key : keys) {
            T value = get(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    private <T> void populateFromRedisIndividually(List<CacheKey> keys, Map<CacheKey, T> result) {
        for (CacheKey key : keys) {
            try {
                T value = redisCacheManager.get(key);
                if (value != null) {
                    result.put(key, value);
                    updateHitStatistics(redisCacheManager.getCacheLevel());
                    if (readThrough) {
                        asyncUpdateLowerLevels(key, value, redisCacheManager.getCacheLevel());
                    }
                } else {
                    totalMisses.incrementAndGet();
                }
            } catch (Exception ex) {
                totalMisses.incrementAndGet();
                log.warn("Redis 单键补偿读取失败: key={}", key, ex);
                recordCacheException(ex, key);
            }
        }
    }

    private <T> void asyncUpdateLowerLevels(CacheKey key, T value, int currentLevel) {
        if (!readThrough || currentLevel <= 1) {
            return;
        }

        asyncExecutor.submit(() -> {
            try {
                for (CacheManager manager : cacheManagers) {
                    if (manager.getCacheLevel() < currentLevel) {
                        manager.put(key, value);
                        log.debug("缓存回写完成: key={}, level={} -> {}",
                                key, currentLevel, manager.getCacheLevel());
                    }
                }
            } catch (Exception e) {
                log.error("缓存回写失败: key={}", key, e);
            }
        });
    }

    private void asyncRemoveLowerLevels(CacheKey key, int currentLevel) {
        if (currentLevel <= 1) {
            return;
        }

        asyncExecutor.submit(() -> {
            try {
                for (CacheManager manager : cacheManagers) {
                    if (manager.getCacheLevel() < currentLevel) {
                        manager.delete(key);
                        log.debug("缓存清除完成: key={}, level={}",
                                key, manager.getCacheLevel());
                    }
                }
            } catch (Exception e) {
                log.error("缓存清除失败: key={}", key, e);
            }
        });
    }


    public <T> void warmUp(CacheKey key, T value) {
        if (!isOperational() || key == null || value == null) {
            return;
        }

        log.info("开始预热缓存: key={}", key);
        for (CacheManager manager : cacheManagers) {
            try {
                manager.put(key, value);
                log.debug("缓存预热完成: {} [层级: {}]",
                        manager.getCacheType(), manager.getCacheLevel());
            } catch (Exception e) {
                log.error("缓存预热失败: {}", manager.getCacheType(), e);
            }
        }
        log.info("缓存预热完成: key={}", key);
    }

    public <T> void warmUpAll(Map<CacheKey, T> dataMap) {
        if (!isOperational() || dataMap == null || dataMap.isEmpty()) {
            return;
        }

        log.info("开始批量预热缓存，数量: {}", dataMap.size());
        int successCount = 0;
        for (Map.Entry<CacheKey, T> entry : dataMap.entrySet()) {
            try {
                warmUp(entry.getKey(), entry.getValue());
                successCount++;
            } catch (Exception e) {
                log.error("批量缓存预热失败: key={}", entry.getKey(), e);
            }
        }
        log.info("批量缓存预热完成: 总数={}, 成功={}", dataMap.size(), successCount);
    }

    public <T> boolean refresh(CacheKey key, T newValue) {
        if (!isOperational() || key == null) {
            return false;
        }

        log.info("开始刷新缓存: key={}", key);
        delete(key);
        boolean success = put(key, newValue);
        log.info("缓存刷新完成: key={}, success={}", key, success);
        return success;
    }

    public Map<String, Object> getHealthStatus() {
        Map<String, Object> health = new HashMap<>();
        health.put("enabled", enabled);
        health.put("totalLevels", cacheManagers.size());
        health.put("maxLevel", maxLevel);

        List<Map<String, Object>> levelHealth = new ArrayList<>();
        for (CacheManager manager : cacheManagers) {
            Map<String, Object> levelStatus = new HashMap<>();
            levelStatus.put("type", manager.getCacheType());
            levelStatus.put("level", manager.getCacheLevel());
            levelStatus.put("size", manager.size());

            try {
                if (manager instanceof LocalCacheManager) {
                    levelStatus.put("status", "HEALTHY");
                } else if (manager instanceof RedisCacheManager redisManager) {
                    try {
                        redisManager.getRedisTemplate().opsForValue().get("health:test");
                        levelStatus.put("status", "HEALTHY");
                    } catch (Exception e) {
                        levelStatus.put("status", "UNHEALTHY");
                        levelStatus.put("error", e.getMessage());
                    }
                } else {
                    levelStatus.put("status", "UNKNOWN");
                }
            } catch (Exception e) {
                levelStatus.put("status", "ERROR");
                levelStatus.put("error", e.getMessage());
            }

            levelHealth.add(levelStatus);
        }

        health.put("levels", levelHealth);
        boolean allHealthy = levelHealth.stream().allMatch(level -> "HEALTHY".equals(level.get("status")));
        health.put("overallStatus", allHealthy ? "HEALTHY" : "DEGRADED");
        return health;
    }

    public String getPerformanceReport() {
        Map<String, Object> stats = getStatistics();
        StringBuilder report = new StringBuilder();
        report.append("=== 多级缓存性能报告 ===\n");
        report.append("总访问次数: ").append(stats.get("totalAccess")).append("\n");
        report.append("总命中率: ").append(stats.get("totalHitRate")).append("\n");
        report.append("一级缓存命中率: ").append(stats.get("level1HitRate")).append("\n");
        report.append("二级缓存命中率: ").append(stats.get("level2HitRate")).append("\n");
        report.append("未命中率: ").append(stats.get("missRate")).append("\n");
        report.append("总写入次数: ").append(stats.get("totalWrites")).append("\n");
        report.append("总删除次数: ").append(stats.get("totalDeletes")).append("\n");

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> levelStats =
                (Map<String, Map<String, Object>>) stats.get("levelStatistics");
        if (levelStats != null) {
            report.append("\n=== 各级缓存详情 ===\n");
            for (Map.Entry<String, Map<String, Object>> entry : levelStats.entrySet()) {
                report.append("缓存类型: ").append(entry.getKey()).append("\n");
                for (Map.Entry<String, Object> statEntry : entry.getValue().entrySet()) {
                    report.append("  ").append(statEntry.getKey()).append(": ")
                            .append(statEntry.getValue()).append("\n");
                }
                report.append("\n");
            }
        }
        return report.toString();
    }

    public void setCacheStrategy(boolean writeThrough, boolean readThrough, boolean cacheAside) {
        this.writeThrough = writeThrough;
        this.readThrough = readThrough;
        this.cacheAside = cacheAside;
        log.info("缓存策略设置完成: writeThrough={}, readThrough={}, cacheAside={}",
                writeThrough, readThrough, cacheAside);
    }

    public void setMaxLevel(int maxLevel) {
        this.maxLevel = maxLevel;
        log.info("最大缓存层级设置完成: {}", maxLevel);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("缓存管理器已{}", enabled ? "启用" : "禁用");
    }

    private void recordCacheWarning(String message, CacheKey key) {
        if (exceptionMonitorService == null) {
            return;
        }
        recordCacheException(new IllegalStateException(message), key);
    }

    private void recordCacheException(Throwable throwable, CacheKey key) {
        if (exceptionMonitorService == null || throwable == null) {
            return;
        }
        String resourceId = key != null ? key.getFullKey() : "MULTI_LEVEL_CACHE";
        exceptionMonitorService.record(throwable, resourceId, null);
    }

    private boolean isOperational() {
        return enabled && !shuttingDown;
    }
}


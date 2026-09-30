package com.wangbin.collector.core.cache.manager;

import com.wangbin.collector.core.cache.constant.CacheMetricKeys;

import com.wangbin.collector.core.cache.config.CacheProperties;
import com.wangbin.collector.core.cache.model.CacheKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

/**
 * Redis缓存管理器
 */
@Slf4j
@Component("redisCacheManager")
@ConditionalOnExpression("'${collector.cache.type:multi-level}' != 'local'")
public class RedisCacheManager extends AbstractCacheManager {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheProperties.RedisCache redisProperties;

    /**
     * 创建当前组件实例。
     */
    public RedisCacheManager(@Qualifier("cacheRedisTemplate") RedisTemplate<String, Object> redisTemplate,
                             CacheProperties cacheProperties) {
        super("REDIS", 2);
        this.redisTemplate = redisTemplate;
        this.redisProperties = cacheProperties.getRedis();
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doInit() throws Exception {
        // 测试Redis连接
        testConnection();

        log.info("Redis缓存管理器初始化完成: 键前缀={}, 默认过期秒数={}",
                redisProperties.getKeyPrefix(), redisProperties.getDefaultExpire());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDestroy() throws Exception {
        log.info("Redis缓存管理器已销毁");
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected <T> boolean doPut(CacheKey key, T value, long expireTime) throws Exception {
        String redisKey = buildRedisKey(key);

        try {
            // 序列化值
            Object serializedValue = serializeValue(value);

            if (expireTime > 0) {
                // 带过期时间
                long expireSeconds = TimeUnit.MILLISECONDS.toSeconds(expireTime);
                redisTemplate.opsForValue().set(redisKey, serializedValue,
                        expireSeconds, TimeUnit.SECONDS);
            } else if (expireTime == CacheKey.EXPIRE_NEVER) {
                // 永不过期
                redisTemplate.opsForValue().set(redisKey, serializedValue);
            } else {
                // 使用默认过期时间
                redisTemplate.opsForValue().set(redisKey, serializedValue,
                        redisProperties.getDefaultExpire(), TimeUnit.SECONDS);
            }

            return true;
        } catch (Exception e) {
            log.error("Redis缓存写入失败: 键={}", redisKey, e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    @SuppressWarnings("unchecked")
    protected <T> T doGet(CacheKey key) throws Exception {
        String redisKey = buildRedisKey(key);

        try {
            Object value = redisTemplate.opsForValue().get(redisKey);

            if (value == null) {
                return null;
            }

            // 反序列化值
            return (T) deserializeValue(value, key);
        } catch (Exception e) {
            log.error("Redis缓存读取失败: 键={}", redisKey, e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doDelete(CacheKey key) throws Exception {
        String redisKey = buildRedisKey(key);

        try {
            Boolean deleted = redisTemplate.delete(redisKey);
            return deleted != null && deleted;
        } catch (Exception e) {
            log.error("Redis缓存删除失败: 键={}", redisKey, e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected int doDeleteByPattern(String pattern) throws Exception {
        try {
            String redisPattern = buildRedisPattern(pattern);
            final int[] deletedCount = {0};
            scanKeysBatch(redisPattern, batchKeys -> {
                Long deleted = redisTemplate.delete(batchKeys);
                if (deleted != null) {
                    deletedCount[0] += deleted.intValue();
                }
            });
            return deletedCount[0];
        } catch (Exception e) {
            log.error("Redis模式删除失败: pattern={}", pattern, e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doExists(CacheKey key) throws Exception {
        String redisKey = buildRedisKey(key);

        try {
            Boolean exists = redisTemplate.hasKey(redisKey);
            return exists != null && exists;
        } catch (Exception e) {
            log.error("Redis检查缓存存在失败: 键={}", redisKey, e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected boolean doExpire(CacheKey key, long expireTime) throws Exception {
        String redisKey = buildRedisKey(key);

        try {
            if (expireTime > 0) {
                long expireSeconds = TimeUnit.MILLISECONDS.toSeconds(expireTime);
                Boolean success = redisTemplate.expire(redisKey, expireSeconds, TimeUnit.SECONDS);
                return success != null && success;
            } else if (expireTime == CacheKey.EXPIRE_NEVER) {
                // 移除过期时间
                Boolean success = redisTemplate.persist(redisKey);
                return success != null && success;
            } else {
                return false;
            }
        } catch (Exception e) {
            log.error("Redis设置缓存过期时间失败: 键={}", redisKey, e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected long doGetExpire(CacheKey key) throws Exception {
        String redisKey = buildRedisKey(key);

        try {
            Long expireSeconds = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);

            if (expireSeconds == null) {
                return -1;
            }

            if (expireSeconds == -1) {
                return CacheKey.EXPIRE_NEVER;
            }

            if (expireSeconds == -2) {
                return -1; // 键不存在
            }

            return TimeUnit.SECONDS.toMillis(expireSeconds);
        } catch (Exception e) {
            log.error("Redis获取缓存过期时间失败: 键={}", redisKey, e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doClear() throws Exception {
        try {
            String pattern = redisProperties.getKeyPrefix() + "*";
            scanKeysBatch(pattern, redisTemplate::delete);
        } catch (Exception e) {
            log.error("Redis清空缓存失败", e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected long doSize() throws Exception {
        try {
            String pattern = redisProperties.getKeyPrefix() + "*";
            final long[] count = {0L};
            scanKeysBatch(pattern, keys -> count[0] += keys.size());
            return count[0];
        } catch (Exception e) {
            log.error("Redis获取缓存大小失败", e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Set<CacheKey> doKeys() throws Exception {
        return doKeys("*");
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected Set<CacheKey> doKeys(String pattern) throws Exception {
        try {
            Set<CacheKey> cacheKeys = new HashSet<>();
            scanKeysBatch(buildRedisPattern(pattern), redisKeys -> {
                for (String redisKey : redisKeys) {
                    String originalKey = redisKey.substring(redisProperties.getKeyPrefix().length());
                    cacheKeys.add(new CacheKey(originalKey, 0));
                }
            });
            return cacheKeys;
        } catch (Exception e) {
            log.error("Redis获取缓存键失败: pattern={}", pattern, e);
            throw e;
        }
    }

    @Override
    protected Map<String, Object> getImplementationStatistics() {
        try {
            Map<String, Object> stats = new HashMap<>();

            // 获取Redis信息

            try (RedisConnection connection = Objects.requireNonNull(
                    redisTemplate.getConnectionFactory()).getConnection()) {
                Properties info = connection.info();
                assert info != null;
                stats.put(CacheMetricKeys.REDIS_VERSION, info.getProperty("redis_version"));
                stats.put(CacheMetricKeys.USED_MEMORY, info.getProperty("used_memory_human"));
                stats.put(CacheMetricKeys.CONNECTED_CLIENTS, info.getProperty("connected_clients"));
                stats.put(CacheMetricKeys.TOTAL_COMMANDS_PROCESSED, info.getProperty("total_commands_processed"));
                stats.put(CacheMetricKeys.KEYSPACE_HITS, info.getProperty("keyspace_hits"));
                stats.put(CacheMetricKeys.KEYSPACE_MISSES, info.getProperty("keyspace_misses"));

                // 计算命中率
                long hits = Long.parseLong(info.getProperty("keyspace_hits", "0"));
                long misses = Long.parseLong(info.getProperty("keyspace_misses", "0"));
                long total = hits + misses;
                double hitRate = total > 0 ? (double) hits / total * 100 : 0.0;
                double missRate = total > 0 ? (double) misses / total * 100 : 0.0;

                stats.put(CacheMetricKeys.REDIS_HIT_RATE, String.format("%.2f%%", hitRate));
                stats.put(CacheMetricKeys.REDIS_MISS_RATE, String.format("%.2f%%", missRate));
            }

            return stats;
        } catch (Exception e) {
            log.error("获取Redis统计信息失败", e);
            return Collections.emptyMap();
        }
    }

    // =============== Redis特定方法 ===============

    /**
     * 批量获取缓存值
     */
    public <T> Map<String, T> batchGet(List<String> keys, Class<T> type) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }

        List<T> values = pipelineGet(keys, type);
        Map<String, T> result = new HashMap<>();
        for (int i = 0; i < keys.size() && i < values.size(); i++) {
            T value = values.get(i);
            if (value != null) {
                result.put(keys.get(i), value);
            }
        }

        return result;
    }

    /**
     * 批量设置缓存值
     */
    public <T> boolean batchSet(Map<String, T> dataMap, long expireTime) {
        if (dataMap == null || dataMap.isEmpty()) {
            return true;
        }

        boolean allSuccess = true;
        for (Map.Entry<String, T> entry : dataMap.entrySet()) {
            try {
                boolean success = put(new CacheKey(entry.getKey()), entry.getValue(), expireTime);
                if (!success) {
                    allSuccess = false;
                    log.warn("Redis批量设置失败: 键={}", entry.getKey());
                }
            } catch (Exception e) {
                allSuccess = false;
                log.error("Redis批量设置异常: 键={}", entry.getKey(), e);
            }
        }

        return allSuccess;
    }

    /**
     * 使用Pipeline批量操作
     */
    public <T> List<T> pipelineGet(List<String> keys, Class<T> type) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }

        List<Object> results = redisTemplate.executePipelined(new SessionCallback<Object>() {
            /**
             * 处理当前业务流程。
             */
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                for (String key : keys) {
                    operations.opsForValue().get((K) buildRedisKey(new CacheKey(key)));
                }
                return null;
            }
        });

        List<T> typedResults = new ArrayList<>();
        for (Object result : results) {
            try {
                @SuppressWarnings("unchecked")
                T typedResult = (T) deserializeValue(result, null);
                typedResults.add(typedResult);
            } catch (Exception e) {
                typedResults.add(null);
                log.warn("Redis Pipeline结果类型转换失败", e);
            }
        }

        return typedResults;
    }

    /**
     * 执行当前业务逻辑。
     */
    public <T> Map<CacheKey, T> pipelineGetAll(List<CacheKey> keys, Class<T> type) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> rawKeys = new ArrayList<>(keys.size());
        for (CacheKey key : keys) {
            rawKeys.add(key.getFullKey());
        }

        List<T> values = pipelineGet(rawKeys, type);
        Map<CacheKey, T> result = new LinkedHashMap<>();
        for (int i = 0; i < keys.size() && i < values.size(); i++) {
            T value = values.get(i);
            if (value != null) {
                result.put(keys.get(i), value);
            }
        }
        return result;
    }

    /**
     * 使用Hash存储
     */
    public <T> boolean hashPut(String hashKey, String field, T value) {
        try {
            redisTemplate.opsForHash().put(hashKey, field, serializeValue(value));
            return true;
        } catch (Exception e) {
            log.error("Redis Hash写入失败: 哈希键={}, 字段={}", hashKey, field, e);
            return false;
        }
    }

    /**
     * 从Hash获取
     */
    @SuppressWarnings("unchecked")
    public <T> T hashGet(String hashKey, String field, Class<T> type) {
        try {
            Object value = redisTemplate.opsForHash().get(hashKey, field);
            return value != null ? (T) deserializeValue(value, null) : null;
        } catch (Exception e) {
            log.error("Redis Hash读取失败: 哈希键={}, 字段={}", hashKey, field, e);
            return null;
        }
    }

    /**
     * 获取整个Hash
     */
    public <T> Map<String, T> hashGetAll(String hashKey, Class<T> type) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(hashKey);
            Map<String, T> result = new HashMap<>();

            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                try {
                    String field = entry.getKey().toString();
                    @SuppressWarnings("unchecked")
                    T value = (T) deserializeValue(entry.getValue(), null);
                    result.put(field, value);
                } catch (Exception e) {
                    log.warn("Redis Hash条目转换失败: 哈希键={}", hashKey, e);
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Redis Hash获取全部失败: 哈希键={}", hashKey, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 使用List存储
     */
    public <T> boolean listPush(String listKey, T value, boolean left) {
        try {
            if (left) {
                redisTemplate.opsForList().leftPush(listKey, serializeValue(value));
            } else {
                redisTemplate.opsForList().rightPush(listKey, serializeValue(value));
            }
            return true;
        } catch (Exception e) {
            log.error("Redis List写入失败: 列表键={}", listKey, e);
            return false;
        }
    }

    /**
     * 从List获取
     */
    @SuppressWarnings("unchecked")
    public <T> T listPop(String listKey, boolean left, Class<T> type) {
        try {
            Object value;
            if (left) {
                value = redisTemplate.opsForList().leftPop(listKey);
            } else {
                value = redisTemplate.opsForList().rightPop(listKey);
            }
            return value != null ? (T) deserializeValue(value, null) : null;
        } catch (Exception e) {
            log.error("Redis List读取失败: 列表键={}", listKey, e);
            return null;
        }
    }

    /**
     * 使用Set存储
     */
    public <T> boolean setAdd(String setKey, T value) {
        try {
            redisTemplate.opsForSet().add(setKey, serializeValue(value));
            return true;
        } catch (Exception e) {
            log.error("Redis Set写入失败: 集合键={}", setKey, e);
            return false;
        }
    }

    /**
     * 从Set获取成员
     */
    public boolean setIsMember(String setKey, Object value) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(setKey, serializeValue(value))
            );
        } catch (Exception e) {
            log.error("Redis Set检查成员失败: 集合键={}", setKey, e);
            return false;
        }
    }

    /**
     * 获取Set所有成员
     */
    @SuppressWarnings("unchecked")
    public <T> Set<T> setMembers(String setKey, Class<T> type) {
        try {
            Set<Object> members = redisTemplate.opsForSet().members(setKey);
            Set<T> result = new HashSet<>();

            for (Object member : members) {
                try {
                    result.add((T) deserializeValue(member, null));
                } catch (Exception e) {
                    log.warn("Redis Set成员转换失败: 集合键={}", setKey, e);
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Redis Set获取成员失败: 集合键={}", setKey, e);
            return Collections.emptySet();
        }
    }

    // =============== 辅助方法 ===============

    /**
     * 构建Redis键
     */
    private String buildRedisKey(CacheKey key) {
        return redisProperties.getKeyPrefix() + key.getFullKey();
    }

    /**
     * 构建Redis模式
     */
    private String buildRedisPattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return redisProperties.getKeyPrefix() + "*";
        }
        return redisProperties.getKeyPrefix() + pattern;
    }

    /**
     * 执行当前业务逻辑。
     */
    void scanKeysBatch(String pattern, Consumer<List<String>> batchConsumer) {
        if (pattern == null || pattern.isEmpty() || batchConsumer == null) {
            return;
        }
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            List<String> batch = new ArrayList<>(1000);
            ScanOptions options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(1000)
                    .build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    byte[] next = cursor.next();
                    if (next != null) {
                        batch.add(new String(next));
                    }
                    if (batch.size() >= 1000) {
                        batchConsumer.accept(List.copyOf(batch));
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    batchConsumer.accept(List.copyOf(batch));
                }
            }
            return null;
        });
    }

    /**
     * 序列化值
     */
    private Object serializeValue(Object value) {
        // RedisTemplate已经配置了序列化器，这里直接返回
        return value;
    }

    /**
     * 反序列化值
     */
    private Object deserializeValue(Object value, CacheKey key) {
        // RedisTemplate已经配置了反序列化器，这里直接返回
        return value;
    }

    /**
     * 测试Redis连接
     */
    private void testConnection() throws Exception {
        try {
            String testKey = redisProperties.getKeyPrefix() + "test:connection";
            redisTemplate.opsForValue().set(testKey, "test", 10, TimeUnit.SECONDS);
            Object result = redisTemplate.opsForValue().get(testKey);

            if (!"test".equals(result)) {
                throw new Exception("Redis连接测试失败: 返回值不匹配");
            }

            redisTemplate.delete(testKey);
            log.debug("Redis连接测试成功");
        } catch (Exception e) {
            log.error("Redis连接测试失败", e);
            throw new Exception("Redis连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取RedisTemplate
     */
    public RedisTemplate<String, Object> getRedisTemplate() {
        return redisTemplate;
    }
}

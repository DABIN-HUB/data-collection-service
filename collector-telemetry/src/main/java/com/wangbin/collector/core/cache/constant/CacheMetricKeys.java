package com.wangbin.collector.core.cache.constant;

/**
 * 缓存统计、健康检查和缓存条目详情使用的 Map 字段常量。
 */
public final class CacheMetricKeys {

    /**
     * 缓存类型字段，表示本地缓存、Redis 缓存或多级缓存。
     */
    public static final String CACHE_TYPE = "cacheType";

    /**
     * 缓存层级字段，表示当前缓存位于多级缓存中的层级。
     */
    public static final String CACHE_LEVEL = "cacheLevel";

    /**
     * 缓存初始化状态字段，表示缓存管理器是否已经完成初始化。
     */
    public static final String INITIALIZED = "initialized";

    /**
     * 缓存条目当前数量字段。
     */
    public static final String CACHE_SIZE = "cacheSize";

    /**
     * 缓存写入次数统计字段。
     */
    public static final String TOTAL_PUTS = "totalPuts";

    /**
     * 缓存读取次数统计字段。
     */
    public static final String TOTAL_GETS = "totalGets";

    /**
     * 缓存命中次数统计字段。
     */
    public static final String TOTAL_HITS = "totalHits";

    /**
     * 缓存未命中次数统计字段。
     */
    public static final String TOTAL_MISSES = "totalMisses";

    /**
     * 缓存删除次数统计字段。
     */
    public static final String TOTAL_DELETES = "totalDeletes";

    /**
     * 缓存过期次数统计字段。
     */
    public static final String TOTAL_EXPIRES = "totalExpires";

    /**
     * 缓存异常次数统计字段。
     */
    public static final String TOTAL_ERRORS = "totalErrors";

    /**
     * 缓存命中率字段。
     */
    public static final String HIT_RATE = "hitRate";

    /**
     * 缓存未命中率字段。
     */
    public static final String MISS_RATE = "missRate";

    /**
     * 多级缓存是否同步写穿字段。
     */
    public static final String WRITE_THROUGH = "writeThrough";

    /**
     * 多级缓存是否同步读穿字段。
     */
    public static final String READ_THROUGH = "readThrough";

    /**
     * 多级缓存是否启用旁路模式字段。
     */
    public static final String CACHE_ASIDE = "cacheAside";

    /**
     * 多级缓存最大启用层级字段。
     */
    public static final String MAX_LEVEL = "maxLevel";

    /**
     * 多级缓存读取次数统计字段。
     */
    public static final String TOTAL_READS = "totalReads";

    /**
     * 多级缓存写入次数统计字段。
     */
    public static final String TOTAL_WRITES = "totalWrites";

    /**
     * 一级缓存命中次数字段。
     */
    public static final String LEVEL1_HITS = "level1Hits";

    /**
     * 二级缓存命中次数字段。
     */
    public static final String LEVEL2_HITS = "level2Hits";

    /**
     * 总访问次数字段。
     */
    public static final String TOTAL_ACCESS = "totalAccess";

    /**
     * 总命中率字段。
     */
    public static final String TOTAL_HIT_RATE = "totalHitRate";

    /**
     * 一级缓存命中率字段。
     */
    public static final String LEVEL1_HIT_RATE = "level1HitRate";

    /**
     * 二级缓存命中率字段。
     */
    public static final String LEVEL2_HIT_RATE = "level2HitRate";

    /**
     * 各缓存层统计明细字段。
     */
    public static final String LEVEL_STATISTICS = "levelStatistics";

    /**
     * 健康检查层级列表字段。
     */
    public static final String LEVELS = "levels";

    /**
     * 总层级数量字段。
     */
    public static final String TOTAL_LEVELS = "totalLevels";

    /**
     * 单个健康层级编号字段。
     */
    public static final String LEVEL = "level";

    /**
     * 单个健康层级大小字段。
     */
    public static final String SIZE = "size";

    /**
     * 整体健康状态字段。
     */
    public static final String OVERALL_STATUS = "overallStatus";

    /**
     * 缓存条目原始键字段。
     */
    public static final String KEY = "key";

    /**
     * 缓存条目封装键字段。
     */
    public static final String CACHE_KEY = "cacheKey";

    /**
     * 缓存条目写入时间字段。
     */
    public static final String CACHE_TIME = "cacheTime";

    /**
     * 缓存条目过期时间字段。
     */
    public static final String EXPIRE_TIME = "expireTime";

    /**
     * 缓存条目剩余有效时间字段。
     */
    public static final String REMAINING_TIME = "remainingTime";

    /**
     * 缓存条目是否已过期字段。
     */
    public static final String EXPIRED = "expired";

    /**
     * 缓存值 Java 类型字段。
     */
    public static final String VALUE_TYPE = "valueType";

    /**
     * 缓存值大小估算字段。
     */
    public static final String VALUE_SIZE = "valueSize";

    /**
     * Redis 服务版本字段。
     */
    public static final String REDIS_VERSION = "redisVersion";

    /**
     * Redis 已使用内存字段。
     */
    public static final String USED_MEMORY = "usedMemory";

    /**
     * Redis 当前连接客户端数字段。
     */
    public static final String CONNECTED_CLIENTS = "connectedClients";

    /**
     * Redis 已处理命令总数字段。
     */
    public static final String TOTAL_COMMANDS_PROCESSED = "totalCommandsProcessed";

    /**
     * Redis 键空间命中次数字段。
     */
    public static final String KEYSPACE_HITS = "keyspaceHits";

    /**
     * Redis 键空间未命中次数字段。
     */
    public static final String KEYSPACE_MISSES = "keyspaceMisses";

    /**
     * Redis 命中率字段。
     */
    public static final String REDIS_HIT_RATE = "redisHitRate";

    /**
     * Redis 未命中率字段。
     */
    public static final String REDIS_MISS_RATE = "redisMissRate";

    /**
     * 工具类不允许实例化。
     */
    private CacheMetricKeys() {
    }
}

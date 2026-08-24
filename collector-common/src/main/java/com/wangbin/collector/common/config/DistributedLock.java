package com.wangbin.collector.common.config;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 跨实例协同使用的分布式锁抽象。
 */
public interface DistributedLock {

    /**
     * 执行当前业务逻辑。
     */
    Optional<LockHandle> tryLock(String lockKey, long expireTime, TimeUnit timeUnit);

    boolean isLocked(String lockKey);

    /**
     * 定义当前模块的业务契约。
     */
    interface LockHandle extends AutoCloseable {

        /**
         * 执行当前业务逻辑。
         */
        String lockKey();

        /**
         * 执行当前业务逻辑。
         */
        boolean unlock();

        /**
         * 执行当前业务逻辑。
         */
        @Override
        default void close() {
            unlock();
        }
    }
}

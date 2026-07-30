package com.wangbin.collector.common.config;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Distributed lock abstraction for cross-instance coordination.
 */
public interface DistributedLock {

    Optional<LockHandle> tryLock(String lockKey, long expireTime, TimeUnit timeUnit);

    boolean isLocked(String lockKey);

    interface LockHandle extends AutoCloseable {

        String lockKey();

        boolean unlock();

        @Override
        default void close() {
            unlock();
        }
    }
}

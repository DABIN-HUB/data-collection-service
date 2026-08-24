package com.wangbin.collector.common.config;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定义当前模块的业务组件。
 */
@Component
public class RedisDistributedLock implements DistributedLock {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<Long> unlockScript;

    /**
     * 创建当前组件实例。
     */
    public RedisDistributedLock(
            RedisTemplate<String, Object> redisTemplate,
            DefaultRedisScript<Long> unlockScript) {
        this.redisTemplate = redisTemplate;
        this.unlockScript = unlockScript;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public Optional<LockHandle> tryLock(String lockKey, long expireTime, TimeUnit timeUnit) {
        String lockValue = UUID.randomUUID().toString();

        Boolean success = redisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockValue,
                expireTime,
                timeUnit
        );

        if (!Boolean.TRUE.equals(success)) {
            return Optional.empty();
        }
        return Optional.of(new RedisLockHandle(lockKey, lockValue));
    }

    @Override
    public boolean isLocked(String lockKey) {
        Boolean hasKey = redisTemplate.hasKey(lockKey);
        return Boolean.TRUE.equals(hasKey);
    }

    /**
     * 定义当前模块的业务组件。
     */
    private final class RedisLockHandle implements LockHandle {

        private final String lockKey;
        private final String lockValue;
        private final AtomicBoolean released = new AtomicBoolean(false);

        /**
         * 创建当前组件实例。
         */
        private RedisLockHandle(String lockKey, String lockValue) {
            this.lockKey = lockKey;
            this.lockValue = lockValue;
        }

        /**
         * 执行当前业务逻辑。
         */
        @Override
        public String lockKey() {
            return lockKey;
        }

        /**
         * 执行当前业务逻辑。
         */
        @Override
        public boolean unlock() {
            if (!released.compareAndSet(false, true)) {
                return false;
            }
            Long result = redisTemplate.execute(
                    unlockScript,
                    Collections.singletonList(lockKey),
                    lockValue
            );
            return result != null && result > 0;
        }
    }
}

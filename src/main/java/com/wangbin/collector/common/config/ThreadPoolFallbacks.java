package com.wangbin.collector.common.config;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Shared fallback policy for non-Spring/manual-new scenarios.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ThreadPoolFallbacks {

    public static Executor preferExecutor(Executor injected,
                                          Executor fallback,
                                          String owner,
                                          String fallbackName) {
        if (injected != null) {
            return injected;
        }
        log.warn("{} missing injected executor, fallback to shared {}", owner, fallbackName);
        return fallback;
    }

    public static ExecutorService preferExecutorService(ExecutorService injected,
                                                        ExecutorService fallback,
                                                        String owner,
                                                        String fallbackName) {
        if (injected != null) {
            return injected;
        }
        log.warn("{} missing injected executor, fallback to shared {}", owner, fallbackName);
        return fallback;
    }

    public static ScheduledExecutorService preferScheduler(ScheduledExecutorService injected,
                                                           ScheduledExecutorService fallback,
                                                           String owner,
                                                           String fallbackName) {
        if (injected != null) {
            return injected;
        }
        log.warn("{} missing injected scheduler, fallback to shared {}", owner, fallbackName);
        return fallback;
    }
}

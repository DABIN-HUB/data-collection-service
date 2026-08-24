package com.wangbin.collector.common.config;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 非 Spring 注入或手动创建场景下共享兜底执行器策略。
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ThreadPoolFallbacks {

    /**
     * 执行当前业务逻辑。
     */
    public static Executor preferExecutor(Executor injected,
                                          Executor fallback,
                                          String owner,
                                          String fallbackName) {
        if (injected != null) {
            return injected;
        }
        log.warn("{} 缺少注入的执行器，降级使用共享执行器 {}", owner, fallbackName);
        return fallback;
    }

    /**
     * 执行当前业务逻辑。
     */
    public static ExecutorService preferExecutorService(ExecutorService injected,
                                                        ExecutorService fallback,
                                                        String owner,
                                                        String fallbackName) {
        if (injected != null) {
            return injected;
        }
        log.warn("{} 缺少注入的执行器，降级使用共享执行器 {}", owner, fallbackName);
        return fallback;
    }

    /**
     * 执行当前业务逻辑。
     */
    public static ScheduledExecutorService preferScheduler(ScheduledExecutorService injected,
                                                           ScheduledExecutorService fallback,
                                                           String owner,
                                                           String fallbackName) {
        if (injected != null) {
            return injected;
        }
        log.warn("{} 缺少注入的调度器，降级使用共享调度器 {}", owner, fallbackName);
        return fallback;
    }
}

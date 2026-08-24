package com.wangbin.collector.common.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertSame;

public class ThreadPoolFallbacksTest {

    @Test
    void threadPoolFallbackShouldPreferInjectedExecutorOverCommonPool() {
        Executor injected = Runnable::run;
        Executor fallback = command -> {
            throw new AssertionError("fallback should not be used");
        };

        Executor resolved = ThreadPoolFallbacks.preferExecutor(
                injected,
                fallback,
                "test-owner",
                "test-fallback");

        assertSame(injected, resolved);
    }
}

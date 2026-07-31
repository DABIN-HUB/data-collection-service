package com.wangbin.collector.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Executor;

/**
 * 装配当前模块的配置。
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private final Executor asyncExecutor;

    /**
     * 创建当前组件实例。
     */
    public AsyncConfig(@Qualifier("asyncCollectorExecutor") Executor asyncExecutor) {
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return asyncExecutor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new CustomAsyncExceptionHandler();
    }

    /**
     * 自定义异步异常处理器
     */
    @Slf4j
    static class CustomAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {

        /**
         * 处理当前业务流程。
         */
        @Override
        public void handleUncaughtException(Throwable throwable, Method method, Object... params) {
            log.error("异步任务执行异常：方法={}，参数={}", method.getName(), Arrays.toString(params), throwable);
        }
    }
}

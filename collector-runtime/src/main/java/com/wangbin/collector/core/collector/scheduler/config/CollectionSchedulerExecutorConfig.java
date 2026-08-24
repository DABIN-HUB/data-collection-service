package com.wangbin.collector.core.collector.scheduler.config;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.wangbin.collector.common.config.ObservedRejectedExecutionHandler;
import com.wangbin.collector.core.config.CollectorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 调度器专属执行器配置。
 *
 * 该配置位于 core 包，避免 common 配置层反向依赖 `CollectorProperties`。
 */
@Configuration
@RequiredArgsConstructor
public class CollectionSchedulerExecutorConfig {

    private static final int DEVICE_START_QUEUE_CAPACITY = 256;
    private static final int DEVICE_RECONNECT_QUEUE_CAPACITY = 512;

    private final CollectorProperties collectorProperties;

    @Bean(name = "deviceStartExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor deviceStartExecutor() {
        int poolSize = Math.max(1, collectorProperties.getScheduler().getDeviceStartExecutorSize());
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(DEVICE_START_QUEUE_CAPACITY),
                namedThreadFactory("device-start-%d"),
                new ObservedRejectedExecutionHandler("deviceStartExecutor", new ThreadPoolExecutor.AbortPolicy())
        );
    }

    @Bean(name = "deviceReconnectExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor deviceReconnectExecutor() {
        int poolSize = Math.max(1, collectorProperties.getScheduler().getReconnectExecutorSize());
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(DEVICE_RECONNECT_QUEUE_CAPACITY),
                namedThreadFactory("device-reconnect-%d"),
                new ObservedRejectedExecutionHandler("deviceReconnectExecutor", new ThreadPoolExecutor.AbortPolicy())
        );
    }

    private ThreadFactory namedThreadFactory(String nameFormat) {
        return new ThreadFactoryBuilder()
                .setNameFormat(nameFormat)
                .setDaemon(true)
                .setPriority(Thread.NORM_PRIORITY)
                .build();
    }
}

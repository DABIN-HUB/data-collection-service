package com.wangbin.collector.core.cache.config;

import com.wangbin.collector.common.config.ObservedRejectedExecutionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 遥测数据后处理异步线程池配置。
 */
@Configuration
@EnableAsync
@RequiredArgsConstructor
@EnableConfigurationProperties(TelemetryExecutorProperties.class)
public class CacheAsyncConfig {

    private final TelemetryExecutorProperties telemetryExecutorProperties;

    /**
     * 执行当前业务逻辑。
     */
    @Bean("cacheAsyncExecutor")
    public Executor cacheAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("telemetry-后处理-");
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ObservedRejectedExecutionHandler(
                "cacheAsyncExecutor",
                new ThreadPoolExecutor.AbortPolicy()
        ));
        executor.initialize();
        return executor;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Bean(TelemetryExecutorNames.CACHE_STAGE)
    public Executor telemetryCacheStageExecutor() {
        TelemetryExecutorProperties.Stage stage = telemetryExecutorProperties.getCache();
        return createStageExecutor(TelemetryExecutorNames.CACHE_STAGE,
                "telemetry-cache-", stage.getCoreSize(), stage.getMaxSize(), stage.getQueueCapacity());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Bean(TelemetryExecutorNames.STREAM_STAGE)
    public Executor telemetryStreamStageExecutor() {
        TelemetryExecutorProperties.Stage stage = telemetryExecutorProperties.getStream();
        return createStageExecutor(TelemetryExecutorNames.STREAM_STAGE,
                "telemetry-stream-", stage.getCoreSize(), stage.getMaxSize(), stage.getQueueCapacity());
    }

    /**
     * 执行当前业务逻辑。
     */
    /**
     * 创建 Redis Stream pipeline 写入执行器，只承载缓冲区 drain 和 Redis I/O。
     */
    @Bean(TelemetryExecutorNames.STREAM_WRITE)
    public Executor telemetryStreamWriteExecutor() {
        return createStageExecutor(TelemetryExecutorNames.STREAM_WRITE,
                "telemetry-stream-write-", 1, 1, 1);
    }

    @Bean(TelemetryExecutorNames.HISTORY_STAGE)
    public Executor telemetryHistoryStageExecutor() {
        TelemetryExecutorProperties.Stage stage = telemetryExecutorProperties.getHistory();
        return createStageExecutor(TelemetryExecutorNames.HISTORY_STAGE,
                "telemetry-history-", stage.getCoreSize(), stage.getMaxSize(), stage.getQueueCapacity());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Bean(TelemetryExecutorNames.REPORT_STAGE)
    public Executor telemetryReportStageExecutor() {
        TelemetryExecutorProperties.Stage stage = telemetryExecutorProperties.getReport();
        return createStageExecutor(TelemetryExecutorNames.REPORT_STAGE,
                "telemetry-report-", stage.getCoreSize(), stage.getMaxSize(), stage.getQueueCapacity());
    }

    /**
     * 创建并返回业务对象。
     */
    private Executor createStageExecutor(String executorName,
                                         String threadPrefix,
                                         int coreSize,
                                         int maxSize,
                                         int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, coreSize));
        executor.setMaxPoolSize(Math.max(coreSize, maxSize));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix(threadPrefix);
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ObservedRejectedExecutionHandler(
                executorName, new ThreadPoolExecutor.AbortPolicy()));
        executor.initialize();
        return executor;
    }
}

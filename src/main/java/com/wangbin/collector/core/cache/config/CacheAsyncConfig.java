package com.wangbin.collector.core.cache.config;

import com.wangbin.collector.common.config.ObservedRejectedExecutionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Value;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 遥测数据后处理异步线程池配置。
 */
@Configuration
@EnableAsync
public class CacheAsyncConfig {

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
    public Executor telemetryCacheStageExecutor(
            @Value("${collector.telemetry-executors.cache.core-size:2}") int coreSize,
            @Value("${collector.telemetry-executors.cache.max-size:4}") int maxSize,
            @Value("${collector.telemetry-executors.cache.queue-capacity:2000}") int queueCapacity) {
        return createStageExecutor(TelemetryExecutorNames.CACHE_STAGE,
                "telemetry-cache-", coreSize, maxSize, queueCapacity);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Bean(TelemetryExecutorNames.STREAM_STAGE)
    public Executor telemetryStreamStageExecutor(
            @Value("${collector.telemetry-executors.stream.core-size:2}") int coreSize,
            @Value("${collector.telemetry-executors.stream.max-size:4}") int maxSize,
            @Value("${collector.telemetry-executors.stream.queue-capacity:2000}") int queueCapacity) {
        return createStageExecutor(TelemetryExecutorNames.STREAM_STAGE,
                "telemetry-stream-", coreSize, maxSize, queueCapacity);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Bean(TelemetryExecutorNames.HISTORY_STAGE)
    public Executor telemetryHistoryStageExecutor(
            @Value("${collector.telemetry-executors.history.core-size:2}") int coreSize,
            @Value("${collector.telemetry-executors.history.max-size:4}") int maxSize,
            @Value("${collector.telemetry-executors.history.queue-capacity:5000}") int queueCapacity) {
        return createStageExecutor(TelemetryExecutorNames.HISTORY_STAGE,
                "telemetry-history-", coreSize, maxSize, queueCapacity);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Bean(TelemetryExecutorNames.REPORT_STAGE)
    public Executor telemetryReportStageExecutor(
            @Value("${collector.telemetry-executors.report.core-size:2}") int coreSize,
            @Value("${collector.telemetry-executors.report.max-size:4}") int maxSize,
            @Value("${collector.telemetry-executors.report.queue-capacity:5000}") int queueCapacity) {
        return createStageExecutor(TelemetryExecutorNames.REPORT_STAGE,
                "telemetry-report-", coreSize, maxSize, queueCapacity);
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

package com.wangbin.collector.common.config;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class ThreadPoolConfig {

    private final int cpuCores = Runtime.getRuntime().availableProcessors();

    private ThreadFactory buildNamedThreadFactory(String prefix, boolean daemon) {
        return new ThreadFactoryBuilder()
                .setNameFormat(prefix + "-%d")
                .setDaemon(daemon)
                .setPriority(Thread.NORM_PRIORITY)
                .build();
    }

    @Bean(name = "timeSliceScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService timeSliceScheduler() {
        int poolSize = Math.max(2, cpuCores / 4);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                poolSize,
                buildNamedThreadFactory("time-slice-scheduler", true)
        );
        executor.setRemoveOnCancelPolicy(true);
        executor.setRejectedExecutionHandler(new ObservedRejectedExecutionHandler(
                "timeSliceScheduler",
                new ThreadPoolExecutor.AbortPolicy()
        ));
        return executor;
    }

    @Bean(name = "batchDispatcherExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor batchDispatcherExecutor() {
        return new ThreadPoolExecutor(
                cpuCores,
                cpuCores * 2,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                buildNamedThreadFactory("batch-dispatcher", true),
                new ObservedRejectedExecutionHandler("batchDispatcherExecutor", new ThreadPoolExecutor.AbortPolicy())
        );
    }

    @Bean(name = "asyncCollectorExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor asyncCollectorExecutor() {
        return new ThreadPoolExecutor(
                cpuCores * 4,
                cpuCores * 8,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10000),
                buildNamedThreadFactory("async-collector", true),
                new ObservedRejectedExecutionHandler("asyncCollectorExecutor", new ThreadPoolExecutor.AbortPolicy())
        );
    }

    @Bean(name = "dataProcessorExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor dataProcessorExecutor() {
        return new ThreadPoolExecutor(
                cpuCores,
                cpuCores * 2,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(5000),
                buildNamedThreadFactory("data-processor", true),
                new ObservedRejectedExecutionHandler("dataProcessorExecutor", new ThreadPoolExecutor.AbortPolicy())
        );
    }

    @Bean("reportExecutor")
    public Executor reportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(5000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("report-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ObservedRejectedExecutionHandler(
                "reportExecutor",
                new ThreadPoolExecutor.AbortPolicy()
        ));
        executor.initialize();
        return executor;
    }

    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(10);
        scheduler.setThreadNamePrefix("scheduled-task-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRejectedExecutionHandler(new ObservedRejectedExecutionHandler(
                "taskScheduler",
                new ThreadPoolExecutor.AbortPolicy()
        ));
        return scheduler;
    }

    @Bean("monitorExecutor")
    public ScheduledExecutorService monitorExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                2,
                buildNamedThreadFactory("monitor-thread", true)
        );
        executor.setRemoveOnCancelPolicy(true);
        executor.setRejectedExecutionHandler(new ObservedRejectedExecutionHandler(
                "monitorExecutor",
                new ThreadPoolExecutor.AbortPolicy()
        ));
        return executor;
    }

    @Bean("ioIntensiveExecutor")
    public ExecutorService ioIntensiveExecutor() {
        int corePoolSize = Runtime.getRuntime().availableProcessors() * 2;
        int maxPoolSize = corePoolSize * 4;

        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new ThreadFactoryBuilder()
                        .setNameFormat("io-task-%d")
                        .build(),
                new ObservedRejectedExecutionHandler("ioIntensiveExecutor", new ThreadPoolExecutor.CallerRunsPolicy())
        );
    }

    @Bean("cpuIntensiveExecutor")
    public ExecutorService cpuIntensiveExecutor() {
        int corePoolSize = Runtime.getRuntime().availableProcessors();

        return new ThreadPoolExecutor(
                corePoolSize,
                corePoolSize,
                0L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new ThreadFactoryBuilder()
                        .setNameFormat("cpu-task-%d")
                        .build(),
                new ObservedRejectedExecutionHandler("cpuIntensiveExecutor", new ThreadPoolExecutor.AbortPolicy())
        );
    }
}

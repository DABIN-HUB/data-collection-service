package com.wangbin.collector.storage.buffer;

import com.wangbin.collector.common.config.ObservedRejectedExecutionHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 历史批量 flush I/O 执行器配置，避免 History stage worker 同步承担 TDengine batch write。
 */
@Configuration
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class HistoryBatchFlushExecutorConfig {

    public static final String HISTORY_BATCH_FLUSH_EXECUTOR = "historyBatchFlushExecutor";

    /**
     * 创建历史批量 flush I/O 执行器；队列必须有界，拒绝后由 HistoryBatchWriter 进入可靠 fallback。
     */
    @Bean(HISTORY_BATCH_FLUSH_EXECUTOR)
    public ThreadPoolTaskExecutor historyBatchFlushExecutor(HistoryBatchProperties properties) {
        HistoryBatchProperties.FlushExecutor config = properties.getFlushExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int coreSize = Math.max(1, config.getCoreSize());
        int maxSize = Math.max(coreSize, config.getMaxSize());
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(Math.max(1, config.getQueueCapacity()));
        executor.setThreadNamePrefix("history-batch-flush-");
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(1,
                (int) Math.ceil(properties.getShutdownFlushTimeoutMs() / 1000.0D)));
        executor.setRejectedExecutionHandler(new ObservedRejectedExecutionHandler(
                HISTORY_BATCH_FLUSH_EXECUTOR, new ThreadPoolExecutor.AbortPolicy()));
        executor.initialize();
        return executor;
    }
}

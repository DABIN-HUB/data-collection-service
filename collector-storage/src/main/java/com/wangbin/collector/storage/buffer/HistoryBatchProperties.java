package com.wangbin.collector.storage.buffer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 历史数据正常路径批量写入配置，过载和失败仍交给历史写缓冲处理。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "telemetry.tdengine.batch")
public class HistoryBatchProperties {

    /**
     * 是否启用正常路径批量写入。
     */
    private boolean enabled = true;

    /**
     * 单个设备子表一次刷新的最大行数。
     */
    private int batchSize = 50;

    /**
     * 未满批次的最大等待时间。
     */
    private long flushIntervalMs = 300L;

    /**
     * 定时扫描未满批次是否到期的间隔，业务最大等待时间仍由刷新间隔配置决定。
     */
    private long flushScanIntervalMs = 100L;

    /**
     * 批量写入组件允许暂存的最大行数，达到上限后进入既有可靠补偿。
     */
    private int maxBufferedRows = 10_000;

    /**
     * 应用停止时尽力刷新的最长等待时间。
     */
    private long shutdownFlushTimeoutMs = 30_000L;

    /**
     * 历史批量刷新输入输出执行器配置，专门承接 TDengine 批量写入，不参与点位准入。
     */
    @Valid
    private FlushExecutor flushExecutor = new FlushExecutor();

    /**
     * 历史批量刷新输入输出执行器的有界线程池参数。
     */
    @Data
    public static class FlushExecutor {

        /**
         * 核心线程数，默认与当前历史阶段工作线程并发上限一致，避免隐式扩大 TDengine 并发。
         */
        @Min(1)
        private int coreSize = 4;

        /**
         * 最大线程数，默认固定为 4，保持刷新输入输出并发可预测。
         */
        @Min(1)
        private int maxSize = 4;

        /**
         * 批量刷新任务队列容量，按批次计数，必须保持有界。
         */
        @Min(1)
        private int queueCapacity = 256;
    }
}

package com.wangbin.collector.storage.buffer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 历史数据正常路径批量写入配置，过载和失败仍交给 HistoryWriteBuffer 处理。
 */
@Data
@Component
@ConfigurationProperties(prefix = "telemetry.tdengine.batch")
public class HistoryBatchProperties {

    /**
     * 是否启用正常路径批量写入。
     */
    private boolean enabled = true;

    /**
     * 单个设备子表一次 flush 的最大行数。
     */
    private int batchSize = 50;

    /**
     * 未满批次的最大等待时间。
     */
    private long flushIntervalMs = 100L;

    /**
     * 批量写入组件允许暂存的最大行数，达到上限后进入既有可靠 fallback。
     */
    private int maxBufferedRows = 10_000;

    /**
     * 应用停止时尽力 flush 的最长等待时间。
     */
    private long shutdownFlushTimeoutMs = 30_000L;
}

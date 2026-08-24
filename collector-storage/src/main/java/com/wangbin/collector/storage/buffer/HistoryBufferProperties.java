package com.wangbin.collector.storage.buffer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 历史数据失败缓冲配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "telemetry.tdengine.buffer")
public class HistoryBufferProperties {

    private boolean enabled = true;
    private String pendingKey = "collector:default:history:pending:v1";
    private String processingKey = "collector:default:history:processing:v1";
    private String deadLetterKey = "collector:default:history:dead:v1";
    private long replayIntervalMs = 500L;
    private int replayBatchSize = 500;
    private int replayMaxBatchesPerCycle = 2;
    private int replayLimitedBatchesPerCycle = 1;
    private int replayLiveQueueLimitedThresholdPercent = 30;
    private int replayLiveQueuePauseThresholdPercent = 70;
    private int localQueueCapacity = 10_000;
}

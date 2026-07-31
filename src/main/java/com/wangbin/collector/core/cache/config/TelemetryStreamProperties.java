package com.wangbin.collector.core.cache.config;

import com.wangbin.collector.core.cache.enums.StreamRetentionMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 承载当前模块的配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "spring.data.redis.stream")
public class TelemetryStreamProperties {

    /**
     * 是否启用 Redis Stream 写入。
     */
    private boolean enabled = true;

    /**
     * Redis Stream 键名。
     */
    private String key = "collector:telemetry:stream";

    /**
     * 保留模式，支持按数量或按时间。
     */
    private StreamRetentionMode retentionMode = StreamRetentionMode.COUNT;

    /**
     * 按数量保留时保留最近 N 条消息。
     */
    private long maxLength = 200L;

    /**
     * 按时间保留时保留最近 N 秒数据。
     */
    private long maxSeconds = 60L;

    /**
     * 启用时使用近似裁剪模式。
     */
    private boolean approximateTrim = true;

    /**
     * 按时间保留模式下是否启用定时裁剪。
     */
    private boolean trimTaskEnabled = true;

    /**
     * 定时裁剪周期，单位毫秒。
     */
    private long trimIntervalMs = 5000L;
}


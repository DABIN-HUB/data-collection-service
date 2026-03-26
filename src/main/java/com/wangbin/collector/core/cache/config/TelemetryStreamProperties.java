package com.wangbin.collector.core.cache.config;

import com.wangbin.collector.core.cache.enums.StreamRetentionMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "spring.data.redis.stream")
public class TelemetryStreamProperties {

    /**
     * Whether stream writing is enabled.
     */
    private boolean enabled = true;

    /**
     * Redis stream key.
     */
    private String key = "collector:telemetry:stream";

    /**
     * COUNT or TIME.
     */
    private StreamRetentionMode retentionMode = StreamRetentionMode.COUNT;

    /**
     * Keep latest N messages when retentionMode=COUNT.
     */
    private long maxLength = 200L;

    /**
     * Keep latest N seconds when retentionMode=TIME.
     */
    private long maxSeconds = 60L;

    /**
     * Use approximate trim (~) when true.
     */
    private boolean approximateTrim = true;

    /**
     * Enable scheduled trim for TIME mode.
     */
    private boolean trimTaskEnabled = true;

    /**
     * Scheduled trim period in milliseconds.
     */
    private long trimIntervalMs = 5000L;
}


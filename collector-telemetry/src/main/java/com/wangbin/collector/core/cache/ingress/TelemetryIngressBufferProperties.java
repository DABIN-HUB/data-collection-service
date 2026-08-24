package com.wangbin.collector.core.cache.ingress;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 遥测入口过载缓冲配置。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "collector.telemetry-ingress-buffer")
public class TelemetryIngressBufferProperties {

    private boolean enabled = true;
    private String pendingKey = "collector:default:telemetry:ingress:pending:v1";
    private String processingKey = "collector:default:telemetry:ingress:processing:v1";
    private String deadLetterKey = "collector:default:telemetry:ingress:dead:v1";

    @Min(1)
    private int replayBatchSize = 100;

    @Min(1)
    private int localQueueCapacity = 10_000;
}

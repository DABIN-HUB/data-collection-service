package com.wangbin.collector.storage.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 云上报结果补写容量；配置变更需重启应用，补写记录不设过期时间。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "telemetry.tdengine.cloud-result")
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class CloudResultStorageProperties {

    /** Redis 最大待补写消息数，默认一万；仅在重启应用后生效。 */
    @Min(1)
    @Max(100000)
    private int capacity = 10000;

    /** 每轮后台补写上限，默认五十；仅在重启应用后生效。 */
    @Min(1)
    @Max(500)
    private int replayBatchSize = 50;
}

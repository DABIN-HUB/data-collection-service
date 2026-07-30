package com.wangbin.collector.core.alarm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 告警状态持久化配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "collector.alarm.state")
public class AlarmStateProperties {

    private boolean enabled = true;
    private String keyPrefix = "collector:default:alarm:state:v1:";
    private long ttlSeconds = 2_592_000L;
    private int retryBatchSize = 500;
}

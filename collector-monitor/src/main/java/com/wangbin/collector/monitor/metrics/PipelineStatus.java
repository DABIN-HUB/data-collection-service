package com.wangbin.collector.monitor.metrics;

/**
 * Pipeline backpressure 状态枚举，保持 /monitor/pipeline 内部统一语义。
 */
public enum PipelineStatus {
    HEALTHY,
    WARNING,
    DANGER,
    UNKNOWN,
    DISABLED
}

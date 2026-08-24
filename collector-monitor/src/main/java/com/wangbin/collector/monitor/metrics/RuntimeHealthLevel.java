package com.wangbin.collector.monitor.metrics;

/**
 * 控制台运行健康级别。
 */
public enum RuntimeHealthLevel {

    /**
     * 状态正常。
     */
    OK,

    /**
     * 存在风险但主链路仍可工作。
     */
    WARN,

    /**
     * 存在明确异常。
     */
    ERROR,

    /**
     * 功能未启用。
     */
    DISABLED,

    /**
     * 无法确认状态。
     */
    UNKNOWN
}

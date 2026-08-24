package com.wangbin.collector.core.config.model;

/**
 * 配置加载状态
 */
public enum ConfigLoadStatus {

    /**
     * 加载成功
     */
    SUCCESS,

    /**
     * 配置未发生变化
     */
    NOT_MODIFIED,

    /**
     * 加载失败
     */
    FAILED
}

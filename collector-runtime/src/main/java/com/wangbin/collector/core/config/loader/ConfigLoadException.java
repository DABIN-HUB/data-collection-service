package com.wangbin.collector.core.config.loader;

/**
 * 配置加载异常
 */
public class ConfigLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 创建当前组件实例。
     */
    public ConfigLoadException(String message) {
        super(message);
    }

    /**
     * 创建当前组件实例。
     */
    public ConfigLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}

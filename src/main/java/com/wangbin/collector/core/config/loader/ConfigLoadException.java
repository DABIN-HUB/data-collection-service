package com.wangbin.collector.core.config.loader;

/**
 * 配置加载异常
 */
public class ConfigLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigLoadException(String message) {
        super(message);
    }

    public ConfigLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}

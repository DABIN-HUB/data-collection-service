package com.wangbin.collector.core.config.persistence;

/** 仅创建冲突、修订不匹配或删除目标不存在；不携带配置或数据库原始异常。 */
public final class ConfigurationConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public ConfigurationConflictException() {
        super("配置目标已存在、不存在或持久化修订不匹配");
    }
}

package com.wangbin.collector.core.config.persistence;

/** 配置模型的无负载基础校验；不转换任何稳定身份。 */
final class ConfigurationModelChecks {
    private ConfigurationModelChecks() { }
    static String identity(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("配置稳定身份不能为空");
        return value;
    }
    static String json(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("配置 JSON 不能为空");
        return value;
    }
    static long revision(long value) {
        if (value < 1) throw new IllegalArgumentException("持久化修订必须为正数");
        return value;
    }
}

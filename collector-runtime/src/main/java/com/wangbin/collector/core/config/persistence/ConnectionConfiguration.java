package com.wangbin.collector.core.config.persistence;

/** 一设备一连接的配置文档，历史数值 id 仅保留于 JSON 兼容元数据。 */
public record ConnectionConfiguration(String deviceId, String configJson) {
    public ConnectionConfiguration {
        ConfigurationModelChecks.identity(deviceId);
        ConfigurationModelChecks.json(configJson);
    }
    @Override public String toString() { return "ConnectionConfiguration[已隐藏配置]"; }
}

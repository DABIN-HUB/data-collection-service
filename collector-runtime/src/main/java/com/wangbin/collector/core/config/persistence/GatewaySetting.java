package com.wangbin.collector.core.config.persistence;

/** 网关设置及持久化修订，JSON 可包含机密，禁止直接写入日志。 */
public record GatewaySetting(String settingKey, String configJson, long revision) {
    public GatewaySetting {
        ConfigurationModelChecks.identity(settingKey);
        ConfigurationModelChecks.json(configJson);
        ConfigurationModelChecks.revision(revision);
    }
    @Override public String toString() { return "GatewaySetting[已隐藏配置]"; }
}

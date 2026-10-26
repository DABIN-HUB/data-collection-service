package com.wangbin.collector.core.config.persistence;

/** 协议模板及持久化修订；协议扩展完整保留在配置 JSON 中。 */
public record ProtocolProfile(String profileId, String protocolType, String configJson, long revision) {
    public ProtocolProfile {
        ConfigurationModelChecks.identity(profileId);
        ConfigurationModelChecks.identity(protocolType);
        ConfigurationModelChecks.json(configJson);
        ConfigurationModelChecks.revision(revision);
    }
    @Override public String toString() { return "ProtocolProfile[已隐藏配置]"; }
}

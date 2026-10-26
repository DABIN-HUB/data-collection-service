package com.wangbin.collector.core.config.persistence;

/** 远端快照独立于本地设备表，以来源、快照身份和远端版本联合定位，不覆盖其他版本。 */
public record RemoteSnapshot(String source, String snapshotId, String sourceVersion, String configJson, long revision) {
    public RemoteSnapshot {
        ConfigurationModelChecks.identity(source);
        ConfigurationModelChecks.identity(snapshotId);
        ConfigurationModelChecks.identity(sourceVersion);
        ConfigurationModelChecks.json(configJson);
        ConfigurationModelChecks.revision(revision);
        if ("local".equals(source)) throw new IllegalArgumentException("远端快照不能使用本地来源");
    }
    @Override public String toString() { return "RemoteSnapshot[已隐藏配置]"; }
}

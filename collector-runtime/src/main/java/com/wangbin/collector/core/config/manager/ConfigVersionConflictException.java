package com.wangbin.collector.core.config.manager;

/** 设备配置版本冲突。 */
public class ConfigVersionConflictException extends RuntimeException {
    private final String deviceId;
    private final long expectedVersion;
    private final long currentVersion;
    private final boolean persistedRevision;

    public ConfigVersionConflictException(String deviceId, long expectedVersion, long currentVersion) {
        this(deviceId, expectedVersion, currentVersion, false);
    }

    public ConfigVersionConflictException(String deviceId, long expectedVersion, long currentVersion,
                                          boolean persistedRevision) {
        super("设备配置版本冲突: " + deviceId + ", expected=" + expectedVersion + ", current=" + currentVersion);
        this.deviceId = deviceId;
        this.expectedVersion = expectedVersion;
        this.currentVersion = currentVersion;
        this.persistedRevision = persistedRevision;
    }

    public String getDeviceId() { return deviceId; }
    public long getExpectedVersion() { return expectedVersion; }
    public long getCurrentVersion() { return currentVersion; }
    public boolean isPersistedRevision() { return persistedRevision; }
}

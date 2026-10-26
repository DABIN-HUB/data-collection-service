package com.wangbin.collector.core.config.persistence;

import java.util.Objects;

/** 一次一致读取的设备聚合及其数据库持久化修订，不替代 JVM 配置版本。 */
public record StoredDeviceConfiguration(DeviceConfiguration configuration, long revision) {
    public StoredDeviceConfiguration {
        Objects.requireNonNull(configuration, "设备配置不能为空");
        ConfigurationModelChecks.revision(revision);
    }
    @Override public String toString() { return "StoredDeviceConfiguration[已隐藏配置]"; }
}

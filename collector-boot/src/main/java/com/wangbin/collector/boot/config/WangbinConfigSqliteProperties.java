package com.wangbin.collector.boot.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** wangbin 独立配置库参数；默认启用，全部属性仅在应用重启后生效。 */
@Validated
@ConfigurationProperties(prefix = "wangbin.config.sqlite")
public class WangbinConfigSqliteProperties {
    private boolean enabled = true;
    private String databasePath;
    @Min(1)
    @Max(60000)
    private int busyTimeoutMs = 5000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDatabasePath() {
        return databasePath;
    }

    public void setDatabasePath(String databasePath) {
        this.databasePath = databasePath;
    }

    public int getBusyTimeoutMs() {
        return busyTimeoutMs;
    }

    public void setBusyTimeoutMs(int busyTimeoutMs) {
        this.busyTimeoutMs = busyTimeoutMs;
    }
}

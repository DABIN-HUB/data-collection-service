package com.wangbin.collector.boot.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** wangbin本地配置权威源选择；默认SQLite，全部属性仅在重启应用后生效。 */
@Validated
@ConfigurationProperties(prefix = "wangbin.config.persistence")
public class WangbinConfigPersistenceProperties {
    @NotNull
    @Pattern(regexp = "legacy-json|sqlite", message = "配置持久化模式只能为legacy-json或sqlite")
    private String mode = "sqlite";
    /** 仅兼容旧配置绑定；SQLite生产启动不再读取此路径或要求旧迁移来源。 */
    private String migrationSource;
    @NotNull
    private String legacyTimeZone = "Asia/Shanghai";

    public String getLegacyTimeZone() {
        return legacyTimeZone;
    }

    public void setLegacyTimeZone(String legacyTimeZone) {
        this.legacyTimeZone = legacyTimeZone;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getMigrationSource() {
        return migrationSource;
    }

    public void setMigrationSource(String migrationSource) {
        this.migrationSource = migrationSource;
    }
}

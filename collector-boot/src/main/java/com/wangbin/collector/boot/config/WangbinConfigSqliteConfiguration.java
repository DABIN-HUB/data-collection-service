package com.wangbin.collector.boot.config;

import com.wangbin.collector.config.sqlite.database.SqlitePaths;
import com.wangbin.collector.config.sqlite.cli.WangbinInstallationBootstrap;
import com.wangbin.collector.core.config.persistence.ConfigurationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * wangbin SQLite 独立基础设施装配；首次安装受目录所有权与安装保护约束。
 * 不提供 DataSource、SqlSessionFactory 或旧JSON迁移。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "wangbin.config.persistence", name = "mode", havingValue = "sqlite", matchIfMissing = true)
@ConditionalOnProperty(prefix = "wangbin.config.sqlite", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WangbinConfigSqliteProperties.class)
public class WangbinConfigSqliteConfiguration {
    @Bean(name = "wangbinConfigurationStore", destroyMethod = "close")
    public ConfigurationStore wangbinConfigurationStore(WangbinConfigSqliteProperties properties) {
        return WangbinInstallationBootstrap.openOrInitializeInstallation(
                SqlitePaths.resolve(properties.getDatabasePath()), properties.getBusyTimeoutMs());
    }
}

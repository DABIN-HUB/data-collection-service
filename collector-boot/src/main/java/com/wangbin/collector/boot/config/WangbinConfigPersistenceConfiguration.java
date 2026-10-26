package com.wangbin.collector.boot.config;

import com.wangbin.collector.config.sqlite.runtime.WangbinSqliteLocalConfigurationPersistence;
import com.wangbin.collector.core.config.persistence.ConfigurationStore;
import com.wangbin.collector.core.config.persistence.WangbinLocalConfigurationPersistence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/** 只选择本地配置权威源，不注册JDBC数据源、不迁移旧JSON、不启动采集。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WangbinConfigPersistenceProperties.class)
public class WangbinConfigPersistenceConfiguration {
    @Bean(name = "wangbinLocalConfigurationPersistence")
    @ConditionalOnProperty(prefix = "wangbin.config.persistence", name = "mode", havingValue = "sqlite", matchIfMissing = true)
    public WangbinLocalConfigurationPersistence wangbinLocalConfigurationPersistence(
            WangbinConfigPersistenceProperties properties,
            ObjectProvider<ConfigurationStore> stores) {
        ConfigurationStore store = stores.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("SQLite权威模式不能禁用配置仓储，请检查wangbin.config.sqlite.enabled");
        }
        java.time.ZoneId legacyTimeZone;
        try {
            legacyTimeZone = java.time.ZoneId.of(properties.getLegacyTimeZone());
        } catch (java.time.DateTimeException exception) {
            throw new IllegalStateException("wangbin.config.persistence.legacy-time-zone必须是合法时区");
        }
        // ConfigManager唯一负责完整恢复；装配不重复扫描，也不读取旧JSON。
        return new WangbinSqliteLocalConfigurationPersistence(store, legacyTimeZone);
    }
}

package com.wangbin.collector.boot.config;

import com.wangbin.collector.config.sqlite.cli.WangbinInstallationBootstrap;
import com.wangbin.collector.config.sqlite.database.SqliteDatabase;
import com.wangbin.collector.config.sqlite.database.SqlitePaths;
import com.wangbin.collector.config.sqlite.mybatis.SqlitePersistenceRuntime;
import com.wangbin.collector.config.sqlite.mybatis.SqliteTransactionManager;
import com.wangbin.collector.config.sqlite.service.impl.SqliteConfigServiceImpl;
import com.zaxxer.hikari.HikariDataSource;
import com.wangbin.collector.core.config.persistence.SqliteConfigService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.boot.autoconfigure.MybatisProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;

/**
 * wangbin SQLite 独立基础设施装配；首次安装受目录所有权与安装保护约束。
 * SQLite 使用独立 HikariCP、MyBatis SessionFactory/Template 和事务管理器，
 * 不复用 TDengine 的默认数据源。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "wangbin.config.persistence", name = "mode", havingValue = "sqlite", matchIfMissing = true)
@ConditionalOnProperty(prefix = "wangbin.config.sqlite", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WangbinConfigSqliteProperties.class)
public class WangbinConfigSqliteConfiguration {
    @Bean(name = "wangbinSqliteDatabase", destroyMethod = "")
    public SqliteDatabase wangbinSqliteDatabase(WangbinConfigSqliteProperties properties) {
        return WangbinInstallationBootstrap.openOrInitializeDatabase(
                SqlitePaths.resolve(properties.getDatabasePath()), properties.getBusyTimeoutMs());
    }

    @Bean(name = "wangbinSqlitePersistenceRuntime", destroyMethod = "close")
    public SqlitePersistenceRuntime wangbinSqlitePersistenceRuntime(
            SqliteDatabase database, WangbinConfigSqliteProperties properties) {
        return SqlitePersistenceRuntime.open(database, properties.getMaximumPoolSize(),
                properties.getMinimumIdle(), properties.getConnectionTimeoutMs());
    }

    @Bean(name = "wangbinSqliteDataSource", destroyMethod = "")
    public HikariDataSource wangbinSqliteDataSource(SqlitePersistenceRuntime runtime) {
        return runtime.dataSource();
    }

    @Bean(name = "wangbinSqliteSqlSessionFactory", destroyMethod = "")
    public SqlSessionFactory wangbinSqliteSqlSessionFactory(SqlitePersistenceRuntime runtime) {
        return runtime.sqlSessionFactory();
    }

    @Bean(name = "wangbinSqliteSqlSessionTemplate", destroyMethod = "")
    public org.mybatis.spring.SqlSessionTemplate wangbinSqliteSqlSessionTemplate(
            SqlitePersistenceRuntime runtime) {
        return runtime.sqlSessionTemplate();
    }

    @Bean(name = "wangbinSqliteTransactionManager", destroyMethod = "")
    public SqliteTransactionManager wangbinSqliteTransactionManager(SqlitePersistenceRuntime runtime) {
        return runtime.transactionManager();
    }

    @Bean(name = "wangbinConfigurationStore", destroyMethod = "")
    public SqliteConfigService wangbinConfigurationStore(SqlitePersistenceRuntime runtime) {
        return new SqliteConfigServiceImpl(runtime);
    }

    /**
     * Exposing the independent SQLite factory means MyBatis auto-configuration can no longer
     * infer the TDengine factory when both data sources exist. Recreate that original binding
     * explicitly instead of allowing storage mappers to bind to SQLite or disappear.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
    @EnableConfigurationProperties({MybatisProperties.class, DataSourceProperties.class})
    @MapperScan(basePackages = "com.wangbin.collector.storage.repository",
            sqlSessionFactoryRef = "tdengineSqlSessionFactory")
    static class TdengineMybatisConfiguration {
        @Bean(name = "dataSource")
        @ConditionalOnMissingBean(name = "dataSource")
        @ConfigurationProperties("spring.datasource.hikari")
        HikariDataSource tdengineDataSource(DataSourceProperties properties) {
            return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        }

        @Bean(name = "tdengineSqlSessionFactory")
        SqlSessionFactory tdengineSqlSessionFactory(
                @Qualifier("dataSource") DataSource dataSource,
                MybatisProperties properties) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setVfs(org.mybatis.spring.boot.autoconfigure.SpringBootVFS.class);
            if (properties.getConfiguration() != null) {
                org.apache.ibatis.session.Configuration configuration =
                        new org.apache.ibatis.session.Configuration();
                properties.getConfiguration().applyTo(configuration);
                factory.setConfiguration(configuration);
            }
            factory.setConfigurationProperties(properties.getConfigurationProperties());
            factory.setTypeAliasesPackage(properties.getTypeAliasesPackage());
            factory.setTypeAliasesSuperType(properties.getTypeAliasesSuperType());
            factory.setTypeHandlersPackage(properties.getTypeHandlersPackage());
            factory.setMapperLocations(properties.resolveMapperLocations());
            return factory.getObject();
        }
    }
}

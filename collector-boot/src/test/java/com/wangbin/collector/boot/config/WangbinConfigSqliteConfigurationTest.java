package com.wangbin.collector.boot.config;

import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import com.wangbin.collector.core.config.persistence.ConfigurationStore;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 wangbin 默认安全首次安装，不劫持 TDengine/MyBatis。 */
class WangbinConfigSqliteConfigurationTest {
    @TempDir
    Path directory;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WangbinConfigSqliteConfiguration.class);

    @Test
    void defaultCreatesPrivateDatabaseAndExplicitDisabledDoesNotCreateFiles() {
        Path database = directory.resolve("not-created/collector.db");
        runner.withPropertyValues("wangbin.config.sqlite.database-path=" + database).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ConfigurationStore.class).doesNotHaveBean(DataSource.class)
                    .doesNotHaveBean(SqlSessionFactory.class);
            assertTrue(Files.isRegularFile(database));
            assertTrue(context.getBean(ConfigurationStore.class).listDevices().isEmpty());
        });
        Path disabled = directory.resolve("disabled/collector.db");
        runner.withPropertyValues("wangbin.config.sqlite.enabled=false", "wangbin.config.sqlite.database-path=" + disabled)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(ConfigurationStore.class);
                    assertFalse(Files.exists(disabled.getParent()));
                });
    }

    @Test
    void existingDirectoryWithoutDatabaseFailsWithoutCreatingDatabase() throws Exception {
        Path database = directory.resolve("not-created/collector.db");
        Files.createDirectory(database.getParent());
        runner.withPropertyValues("wangbin.config.sqlite.enabled=true", "wangbin.config.sqlite.database-path=" + database)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertFalse(Files.exists(database));
                    assertTrue(Files.isDirectory(database.getParent()));
                });
    }

    @Test
    void enabledStoreClosesWithContextWithoutAddingDatasourceOrSqlSessionFactory() {
        Path database = directory.resolve("private/collector.db");
        try (var ignored = SqliteConfigurationStore.createNew(database, 500)) {
            // 已有库必须只打开，不能重新初始化。
        }
        ConfigurationStore[] captured = new ConfigurationStore[1];
        runner.withPropertyValues("wangbin.config.sqlite.enabled=true", "wangbin.config.sqlite.database-path=" + database,
                        "wangbin.config.sqlite.busy-timeout-ms=500")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConfigurationStore.class)
                            .doesNotHaveBean(DataSource.class).doesNotHaveBean(SqlSessionFactory.class);
                    captured[0] = context.getBean(ConfigurationStore.class);
                    assertTrue(captured[0].listDevices().isEmpty());
                });
        assertThrows(IllegalStateException.class, captured[0]::listDevices);
    }

    @Test
    void tdengineAutoConfiguredDatasourceAndMybatisKeepTheirExistingBinding() {
        Path database = directory.resolve("private/collector.db");
        try (var ignored = SqliteConfigurationStore.createNew(database, 500)) {
            // 不调用真实 TDengine 或 Redis；Hikari 不获取连接即可核对绑定对象。
        }
        runner.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, MybatisAutoConfiguration.class))
                .withPropertyValues("wangbin.config.sqlite.enabled=true", "wangbin.config.sqlite.database-path=" + database,
                        "spring.datasource.driver-class-name=com.taosdata.jdbc.rs.RestfulDriver",
                        "spring.datasource.url=jdbc:TAOS-RS://127.0.0.1:1/wangbin_phase1",
                        "spring.datasource.hikari.initialization-fail-timeout=-1")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConfigurationStore.class).hasSingleBean(DataSource.class)
                            .hasSingleBean(SqlSessionFactory.class);
                    DataSource datasource = context.getBean(DataSource.class);
                    assertInstanceOf(HikariDataSource.class, datasource);
                    assertEquals("com.taosdata.jdbc.rs.RestfulDriver", ((HikariDataSource) datasource).getDriverClassName());
                    assertSame(datasource, context.getBean(SqlSessionFactory.class).getConfiguration().getEnvironment().getDataSource());
                    assertNotSame(datasource, context.getBean(ConfigurationStore.class));
                });
    }

    @Test
    void invalidTimeoutFailsBindingInsteadOfSilentlyUsingAnotherValue() {
        runner.withPropertyValues("wangbin.config.sqlite.enabled=true", "wangbin.config.sqlite.busy-timeout-ms=0")
                .run(context -> assertThat(context).hasFailed());
    }
}

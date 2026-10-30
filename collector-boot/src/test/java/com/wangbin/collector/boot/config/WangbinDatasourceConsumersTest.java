package com.wangbin.collector.boot.config;

import com.wangbin.collector.core.config.persistence.SqliteConfigService;
import com.wangbin.collector.monitor.metrics.StorageMetricsSnapshot;
import com.wangbin.collector.monitor.metrics.TdengineMonitorService;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.repository.TelemetryInsertRow;
import com.wangbin.collector.storage.service.DirectJdbcTdengineTelemetryWriter;
import com.wangbin.collector.storage.service.TdengineWriteException;
import com.wangbin.collector.storage.service.TdengineWriteTarget;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 在实际 Boot Bean 图中验证两个 JDBC 使用者的确定性数据源绑定。 */
class WangbinDatasourceConsumersTest {
    @TempDir Path directory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WangbinConfigSqliteConfiguration.class, Consumers.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TdengineProperties.class)
    @Import({TdengineMonitorService.class, DirectJdbcTdengineTelemetryWriter.class})
    static class Consumers { }

    @Test
    void sqliteOnlyKeepsMonitorDisabledAndCrudIndependent() {
        Path path = directory.resolve("only/collector.db");
        HikariDataSource[] pool = new HikariDataSource[1];
        runner.withPropertyValues("telemetry.tdengine.enabled=false", "wangbin.config.sqlite.database-path=" + path)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(DataSource.class).size());
                    assertEquals(0, context.getBeanNamesForType(DirectJdbcTdengineTelemetryWriter.class).length);
                    assertEquals(StorageMetricsSnapshot.Status.DISABLED,
                            context.getBean(TdengineMonitorService.class).getStorageMetrics().getStatus());
                    pool[0] = context.getBean("wangbinSqliteDataSource", HikariDataSource.class);
                    assertEquals(1, context.getBean(SqliteConfigService.class).transaction(
                            w -> w.saveGatewaySetting("sqlite-only", "{}", 0)).revision());
                });
        assertTrue(pool[0].isClosed());
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && thread.getName().startsWith("wangbin-sqlite")));
    }

    @Test
    void bothEnabledMonitorAndWriterUseOnlyExplicitTdengineDataSource() {
        Path path = directory.resolve("both/collector.db");
        AtomicInteger tdengineBorrows = new AtomicInteger();
        DataSource unreachableTdengine = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                tdengineBorrows.incrementAndGet();
                throw new SQLException("受控 TDengine 数据源不可连接");
            }
            @Override public Connection getConnection(String username, String password) throws SQLException {
                return getConnection();
            }
        };
        runner.withBean("dataSource", DataSource.class, () -> unreachableTdengine)
                .withPropertyValues("telemetry.tdengine.enabled=true", "wangbin.config.sqlite.database-path=" + path)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(2, context.getBeansOfType(DataSource.class).size());
                    assertSame(unreachableTdengine, context.getBean("dataSource"));
                    assertNotSame(unreachableTdengine, context.getBean("wangbinSqliteDataSource"));
                    assertSame(context.getBean("wangbinSqliteDataSource"),
                            context.getBean("wangbinSqliteSqlSessionFactory", org.apache.ibatis.session.SqlSessionFactory.class)
                                    .getConfiguration().getEnvironment().getDataSource());
                    assertEquals(StorageMetricsSnapshot.Status.ERROR,
                            context.getBean(TdengineMonitorService.class).getStorageMetrics().getStatus());
                    assertEquals(1, tdengineBorrows.get());
                    var row = new TelemetryInsertRow(1L, "key", "point", "point", "point", "1", "", 100,
                            true, "", "{}", "{}", "{}");
                    assertThrows(TdengineWriteException.class, () -> context.getBean(DirectJdbcTdengineTelemetryWriter.class)
                            .writeSingle(new TdengineWriteTarget("historical", "device"), row));
                    assertEquals(2, tdengineBorrows.get());
                    assertEquals(1, context.getBean(SqliteConfigService.class).transaction(
                            w -> w.saveGatewaySetting("sqlite-still-works", "{}", 0)).revision());
                    assertTrue(context.getBean(SqliteConfigService.class).findGatewaySetting("sqlite-still-works").isPresent());
                    assertEquals(2, tdengineBorrows.get(), "SQLite CRUD 不得访问 TDengine");
                });
    }

    @Test
    void unavailableRealTdengineDoesNotPreventSqliteCrudOrBootstrap() {
        Path path = directory.resolve("unavailable/collector.db");
        runner.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, MybatisAutoConfiguration.class))
                .withPropertyValues("telemetry.tdengine.enabled=true", "wangbin.config.sqlite.database-path=" + path,
                        "spring.datasource.driver-class-name=com.taosdata.jdbc.rs.RestfulDriver",
                        "spring.datasource.url=jdbc:TAOS-RS://127.0.0.1:1/wangbin_p0_probe",
                        "spring.datasource.hikari.initialization-fail-timeout=-1",
                        "spring.datasource.hikari.connection-timeout=500")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("dataSource"));
                    assertTrue(context.containsBean("wangbinSqliteDataSource"));
                    assertEquals(StorageMetricsSnapshot.Status.ERROR,
                            context.getBean(TdengineMonitorService.class).getStorageMetrics().getStatus());
                    assertNotNull(context.getBean(DirectJdbcTdengineTelemetryWriter.class));
                    assertEquals(1, context.getBean(SqliteConfigService.class).transaction(
                            w -> w.saveGatewaySetting("offline-tdengine", "{}", 0)).revision());
                    assertTrue(context.getBean(SqliteConfigService.class).findGatewaySetting("offline-tdengine").isPresent());
                });
    }
}

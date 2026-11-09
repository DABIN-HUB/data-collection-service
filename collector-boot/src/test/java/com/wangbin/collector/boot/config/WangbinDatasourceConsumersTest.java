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
import com.zaxxer.hikari.HikariPoolMXBean;
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
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
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
        PoolEvidence[] evidence = new PoolEvidence[1];
        runner.withPropertyValues("telemetry.tdengine.enabled=false", "wangbin.config.sqlite.database-path=" + path)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(DataSource.class).size());
                    assertEquals(0, context.getBeanNamesForType(DirectJdbcTdengineTelemetryWriter.class).length);
                    assertEquals(StorageMetricsSnapshot.Status.DISABLED,
                            context.getBean(TdengineMonitorService.class).getStorageMetrics().getStatus());
                    pool[0] = context.getBean("wangbinSqliteDataSource", HikariDataSource.class);
                    evidence[0] = poolEvidence(pool[0]);
                    assertEquals(1, context.getBean(SqliteConfigService.class).transaction(
                            w -> w.saveGatewaySetting("sqlite-only", "{}", 0)).revision());
                });
        assertPoolClosedAndTerminated(pool[0], evidence[0]);
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

    /**
     * Hikari 使用固定的 wangbin-sqlite 名称，不能用全局线程名判断本测试是否泄漏。
     * 通过本次 HikariDataSource 持有的 HikariPool 执行器关联资源，并保留线程名作为失败诊断。
     */
    private static PoolEvidence poolEvidence(HikariDataSource dataSource) {
        Object hikariPool = readField(dataSource, "pool");
        List<ExecutorService> executors = new ArrayList<>();
        if (hikariPool != null) {
            for (String fieldName : List.of("houseKeepingExecutorService", "addConnectionExecutor",
                    "closeConnectionExecutor")) {
                Object executor = readField(hikariPool, fieldName);
                if (executor instanceof ExecutorService service) executors.add(service);
            }
        }
        assertFalse(executors.isEmpty(), "必须捕获本次 SQLite Hikari 池的内部执行器");
        HikariPoolMXBean poolMetrics = dataSource.getHikariPoolMXBean();
        assertNotNull(poolMetrics, "必须捕获本次 SQLite Hikari 池的连接指标");
        return new PoolEvidence(dataSource.getPoolName(), List.copyOf(executors), poolMetrics);
    }

    private static void assertPoolClosedAndTerminated(HikariDataSource dataSource, PoolEvidence evidence) {
        assertNotNull(evidence, "必须记录本次 SQLite Hikari 池实例");
        assertTrue(dataSource.isClosed(), "ApplicationContext 关闭后 SQLite Hikari 池必须 closed，"
                + poolDiagnostic(dataSource, evidence));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && evidence.executors().stream().anyMatch(executor -> !executor.isTerminated())) {
            try {
                java.util.concurrent.TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("等待 SQLite Hikari 执行器退出时被中断，pool=" + evidence.poolName()
                        + ", closed=" + dataSource.isClosed());
            }
        }
        if (evidence.executors().stream().anyMatch(executor -> !executor.isTerminated())) {
            fail("SQLite Hikari 资源未在有界等待内退出，" + poolDiagnostic(dataSource, evidence)
                    + ", executors=" + evidence.executors());
        }
        assertEquals(0, evidence.poolMetrics().getActiveConnections(), "SQLite Hikari 不得残留活动连接，"
                + poolDiagnostic(dataSource, evidence));
        assertEquals(0, evidence.poolMetrics().getIdleConnections(), "SQLite Hikari 不得残留空闲连接，"
                + poolDiagnostic(dataSource, evidence));
        assertEquals(0, evidence.poolMetrics().getTotalConnections(), "SQLite Hikari 不得残留池连接，"
                + poolDiagnostic(dataSource, evidence));
    }

    private static String poolDiagnostic(HikariDataSource dataSource, PoolEvidence evidence) {
        List<String> liveThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith(evidence.poolName()))
                .sorted()
                .toList();
        return "pool=" + evidence.poolName() + ", closed=" + dataSource.isClosed()
                + ", liveThreads=" + liveThreads;
    }

    private static Object readField(Object target, String fieldName) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("无法读取 Hikari 资源字段: " + fieldName, exception);
            }
        }
        throw new IllegalStateException("缺少 Hikari 资源字段: " + fieldName);
    }

    private record PoolEvidence(String poolName, List<ExecutorService> executors, HikariPoolMXBean poolMetrics) { }
}

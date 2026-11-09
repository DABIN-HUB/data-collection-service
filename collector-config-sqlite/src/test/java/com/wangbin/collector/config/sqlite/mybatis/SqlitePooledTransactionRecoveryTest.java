package com.wangbin.collector.config.sqlite.mybatis;

import com.wangbin.collector.config.sqlite.database.SqliteDatabase;
import com.wangbin.collector.config.sqlite.service.impl.SqliteConfigServiceImpl;
import com.wangbin.collector.core.config.persistence.ConfigurationCommitUncertainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 SQLite 和 Hikari 连接，仅在提交/只读结束语句边界注入确定性故障。 */
class SqlitePooledTransactionRecoveryTest {
    @TempDir Path directory;

    @Test
    void successfulCommitReusesPhysicalConnectionAndClosesPool() throws Exception {
        for (int cycle = 0; cycle < 5; cycle++) {
            Path path = directory.resolve("success-" + cycle + "/collector.db");
            SqliteDatabase database = SqliteDatabase.createNew(path, 500);
            FaultingConnections connections = new FaultingConnections(database);
            try (var runtime = SqlitePersistenceRuntime.open(database, 1, 1, 500, connections.dataSource())) {
                var service = new SqliteConfigServiceImpl(runtime);
                assertEquals(1, service.transaction(w -> w.saveGatewaySetting("first", "{}", 0)).revision());
                Connection first = connections.lastPhysical();
                assertEquals(1, service.currentRevision());
                assertEquals(2, service.transaction(w -> w.saveGatewaySetting("second", "{}", 0)).revision());
                assertSame(first, connections.lastPhysical());
                assertFalse(first.isClosed());
                var pool = runtime.dataSource().getHikariPoolMXBean();
                assertEquals(0, pool.getActiveConnections());
                assertEquals(1, pool.getIdleConnections());
                assertEquals(1, pool.getTotalConnections());
                assertEquals(0, pool.getThreadsAwaitingConnection());
            }
            assertClosed(connections, path);
        }
    }

    @Test
    void unknownCommitEvictsPhysicalConnectionAndSubsequentCrudBeginsAgain() throws Exception {
        Path path = directory.resolve("unknown/collector.db");
        SqliteDatabase database = SqliteDatabase.createNew(path, 500);
        FaultingConnections connections = new FaultingConnections(database);
        try (var runtime = SqlitePersistenceRuntime.open(database, 1, 0, 500, connections.dataSource())) {
            var service = new SqliteConfigServiceImpl(runtime);
            service.transaction(w -> w.saveGatewaySetting("before", "{}", 0));
            connections.failNextCommit.set(true);
            assertThrows(ConfigurationCommitUncertainException.class,
                    () -> service.transaction(w -> w.saveGatewaySetting("uncertain", "{}", 0)));
            Connection untrusted = connections.faultedPhysical.get();
            assertNotNull(untrusted);
            assertEquals(0, runtime.dataSource().getHikariPoolMXBean().getActiveConnections());
            assertEquals(0, runtime.dataSource().getHikariPoolMXBean().getIdleConnections());
            assertTrue(service.findGatewaySetting("uncertain").isEmpty(), "故障在执行 COMMIT 前注入，不得自动重试");
            assertEquals(1, service.currentRevision());
            assertEquals(2, service.transaction(w -> w.saveGatewaySetting("after", "{}", 0)).revision());
            assertEquals("{}", service.findGatewaySetting("after").orElseThrow().configJson());
            assertNotSame(untrusted, connections.lastPhysical());
        }
        assertClosed(connections, path);
    }

    @Test
    void failedReadEndIsNotReportedAsWriteCommitUnknownAndNextWriteWorks() throws Exception {
        Path path = directory.resolve("read/collector.db");
        SqliteDatabase database = SqliteDatabase.createNew(path, 500);
        FaultingConnections connections = new FaultingConnections(database);
        try (var runtime = SqlitePersistenceRuntime.open(database, 1, 0, 500, connections.dataSource())) {
            var service = new SqliteConfigServiceImpl(runtime);
            connections.failNextEnd.set(true);
            RuntimeException failure = assertThrows(RuntimeException.class, service::currentRevision);
            assertFalse(failure instanceof ConfigurationCommitUncertainException);
            Connection untrusted = connections.faultedPhysical.get();
            assertNotNull(untrusted);
            assertEquals(0, runtime.dataSource().getHikariPoolMXBean().getIdleConnections());
            assertEquals(1, service.transaction(w -> w.saveGatewaySetting("after-read", "{}", 0)).revision());
            assertNotSame(untrusted, connections.lastPhysical());
            assertEquals(1, service.currentRevision());
        }
        assertClosed(connections, path);
    }

    private static void assertClosed(FaultingConnections connections, Path path) throws Exception {
        assertTrue(connections.physical.stream().allMatch(connection -> {
            try { return connection.isClosed(); }
            catch (SQLException exception) { return false; }
        }), "关闭后物理连接不得残留");
        assertFalse(Files.exists(Path.of(path + "-wal")));
        assertFalse(Files.exists(Path.of(path + "-shm")));
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && thread.getName().startsWith("wangbin-sqlite")));
    }

    private static final class FaultingConnections {
        private final SqliteConnectionDataSource delegate;
        private final List<Connection> physical = new CopyOnWriteArrayList<>();
        private final AtomicBoolean failNextCommit = new AtomicBoolean();
        private final AtomicBoolean failNextEnd = new AtomicBoolean();
        private final AtomicReference<Connection> faultedPhysical = new AtomicReference<>();

        private FaultingConnections(SqliteDatabase database) {
            delegate = new SqliteConnectionDataSource(database);
        }

        private Connection lastPhysical() { return physical.get(physical.size() - 1); }

        private DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("getConnection")) return invoke(delegate, method, args);
                        Connection actual = (Connection) invoke(delegate, method, args);
                        physical.add(actual);
                        return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                new Class<?>[]{Connection.class}, (p, m, a) -> {
                                    Object result = invoke(actual, m, a);
                                    if (!m.getName().equals("createStatement")) return result;
                                    Statement statement = (Statement) result;
                                    return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                                            new Class<?>[]{Statement.class}, (s, operation, values) -> {
                                                if (operation.getName().equals("execute") && values != null
                                                        && values.length > 0 && values[0] instanceof String sql
                                                        && ((sql.equals("COMMIT") && failNextCommit.compareAndSet(true, false))
                                                        || (sql.equals("END") && failNextEnd.compareAndSet(true, false)))) {
                                                    faultedPhysical.set(actual);
                                                    throw new SQLException("受控事务结束异常");
                                                }
                                                return invoke(statement, operation, values);
                                            });
                                });
                    });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException exception) { throw exception.getCause(); }
        }
    }
}

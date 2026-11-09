package com.wangbin.collector.config.sqlite.store;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实SQLite事务，仅在提交语句关闭边界注入资源释放故障。 */
class WangbinSqliteCommitResourceTest {
    @Test
    void successfulCommitIsNotReportedAsRollbackWhenStatementCloseFails() throws Exception {
        try (Connection actual = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = actual.createStatement()) {
                statement.execute("CREATE TABLE wangbin_probe (value INTEGER)");
                statement.execute("BEGIN IMMEDIATE");
                statement.execute("INSERT INTO wangbin_probe VALUES (7)");
            }
            Connection boundary = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(actual, args);
                            if (!method.getName().equals("createStatement")) return result;
                            Statement statement = (Statement) result;
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                                    new Class<?>[]{Statement.class}, (p, m, a) -> {
                                        try {
                                            Object value = m.invoke(statement, a);
                                            if (m.getName().equals("close")) throw new SQLException("受控资源关闭失败");
                                            return value;
                                        } catch (InvocationTargetException exception) {
                                            throw exception.getCause();
                                        }
                                    });
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
            var commit = SqliteConfigurationStore.class.getDeclaredMethod("commit", Connection.class);
            assertTrue(commit.trySetAccessible());
            assertDoesNotThrow(() -> commit.invoke(null, boundary));
            try (Statement statement = actual.createStatement();
                 var rows = statement.executeQuery("SELECT value FROM wangbin_probe")) {
                assertTrue(rows.next());
                assertEquals(7, rows.getInt(1));
            }
            try (Statement statement = actual.createStatement()) {
                assertThrows(SQLException.class, () -> statement.execute("ROLLBACK"));
            }
        }
    }
}

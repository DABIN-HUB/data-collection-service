package com.wangbin.collector.config.sqlite.mybatis;

import com.wangbin.collector.config.sqlite.database.SqliteDatabase;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * HikariCP 的物理连接来源。每一条新建物理连接都经过 SqliteDatabase 的
 * 文件、WAL、busy_timeout、外键和同步模式校验，不允许连接池绕过安装边界。
 */
final class SqliteConnectionDataSource implements DataSource {
    private final SqliteDatabase database;
    private volatile int loginTimeoutSeconds;

    SqliteConnectionDataSource(SqliteDatabase database) {
        this.database = java.util.Objects.requireNonNull(database, "SQLite 数据库不能为空");
    }

    @Override
    public Connection getConnection() {
        return database.openConnection();
    }

    @Override
    public Connection getConnection(String username, String password) {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // SQLite 驱动日志不得进入配置负载或业务日志。
    }

    @Override
    public void setLoginTimeout(int seconds) {
        if (seconds < 0) throw new IllegalArgumentException("登录超时时间不能为负数");
        loginTimeoutSeconds = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeoutSeconds;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(SqliteConnectionDataSource.class.getName());
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("SQLite 数据源不支持请求的 unwrap 类型");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}

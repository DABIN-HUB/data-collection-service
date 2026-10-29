package com.wangbin.collector.config.sqlite.store;

import com.wangbin.collector.config.sqlite.service.impl.SqliteConfigServiceImpl;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** 兼容旧的独立仓储构造入口；所有配置操作均继承统一服务，不保留业务 SQL。 */
public final class SqliteConfigurationStore extends SqliteConfigServiceImpl {
    private SqliteConfigurationStore(Path path, int busyTimeoutMs, boolean create) {
        super(path, busyTimeoutMs, create);
    }

    public static SqliteConfigurationStore createNew(Path path, int busyTimeoutMs) {
        return new SqliteConfigurationStore(path, busyTimeoutMs, true);
    }

    public static SqliteConfigurationStore openExisting(Path path, int busyTimeoutMs) {
        return new SqliteConfigurationStore(path, busyTimeoutMs, false);
    }

    /** 保留旧的资源关闭边界反射入口；提交语义仅由统一服务实现。 */
    public static void commit(Connection connection) throws SQLException {
        SqliteConfigServiceImpl.commit(connection);
    }
}

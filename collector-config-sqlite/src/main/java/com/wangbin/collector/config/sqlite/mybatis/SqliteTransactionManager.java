package com.wangbin.collector.config.sqlite.mybatis;

import com.wangbin.collector.config.sqlite.database.SqliteDatabase;
import com.wangbin.collector.core.config.persistence.ConfigurationCommitUncertainException;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 专用事务管理器。Spring 的 setAutoCommit(false) 只会产生延迟事务，
 * 因此写事务在绑定连接前明确执行 BEGIN IMMEDIATE，保留原有 CAS 锁语义。
 */
public final class SqliteTransactionManager extends AbstractPlatformTransactionManager {
    private static final System.Logger LOGGER = System.getLogger(SqliteTransactionManager.class.getName());
    private final HikariDataSource dataSource;

    public SqliteTransactionManager(HikariDataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "SQLite 数据源不能为空");
        setTransactionSynchronization(SYNCHRONIZATION_ON_ACTUAL_TRANSACTION);
        setRollbackOnCommitFailure(false);
    }

    @Override
    protected Object doGetTransaction() {
        Transaction transaction = new Transaction();
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (resource instanceof ConnectionHolder holder) transaction.holder = holder;
        return transaction;
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((Transaction) transaction).holder != null;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        Transaction current = (Transaction) transaction;
        if (current.holder != null) throw new IllegalStateException("SQLite 事务已存在");
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            execute(connection, definition.isReadOnly() ? "BEGIN" : "BEGIN IMMEDIATE");
            ConnectionHolder holder = new ConnectionHolder(connection);
            holder.setSynchronizedWithTransaction(true);
            TransactionSynchronizationManager.bindResource(dataSource, holder);
            current.holder = holder;
            current.readOnly = definition.isReadOnly();
        } catch (SQLException exception) {
            if (connection != null) dataSource.evictConnection(connection);
            closeQuietly(connection);
            throw SqliteDatabase.failure(exception);
        } catch (RuntimeException exception) {
            if (connection != null) dataSource.evictConnection(connection);
            closeQuietly(connection);
            throw exception;
        }
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        Transaction current = (Transaction) status.getTransaction();
        Connection connection = connection(status);
        boolean executed = false;
        current.commitAttempted = true;
        try (Statement statement = connection.createStatement()) {
            statement.execute(current.readOnly ? "END" : "COMMIT");
            executed = true;
            current.completed = true;
        } catch (SQLException exception) {
            current.discardConnection = true;
            if (!executed) {
                if (current.readOnly) throw SqliteDatabase.failure(exception);
                throw new ConfigurationCommitUncertainException();
            }
            LOGGER.log(System.Logger.Level.WARNING, "SQLite COMMIT 已执行但资源关闭失败");
        }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        Transaction current = (Transaction) status.getTransaction();
        try {
            execute(connection(status), "ROLLBACK");
            current.completed = true;
        } catch (SQLException exception) {
            current.discardConnection = true;
            if (current.readOnly) throw SqliteDatabase.failure(exception);
            throw new ConfigurationCommitUncertainException();
        }
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
        status.setRollbackOnly();
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        Transaction current = (Transaction) transaction;
        ConnectionHolder holder = current.holder;
        if (holder == null) return;
        Object bound = TransactionSynchronizationManager.getResource(dataSource);
        if (bound == holder) TransactionSynchronizationManager.unbindResource(dataSource);
        Connection connection = holder.getConnection();
        if (!current.completed && !current.commitAttempted) {
            try {
                execute(connection, "ROLLBACK");
            } catch (SQLException exception) {
                current.discardConnection = true;
                LOGGER.log(System.Logger.Level.WARNING, "SQLite 回滚失败，驱逐事务连接");
            }
        }
        if (!current.discardConnection) {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException exception) {
                current.discardConnection = true;
                LOGGER.log(System.Logger.Level.WARNING, "SQLite 连接状态恢复失败，驱逐事务连接");
            }
        }
        try {
            if (current.discardConnection) dataSource.evictConnection(connection);
        } finally {
            closeQuietly(connection);
            holder.clear();
            current.holder = null;
        }
    }

    private Connection connection(DefaultTransactionStatus status) {
        Transaction current = (Transaction) status.getTransaction();
        if (current.holder == null) throw new TransactionException("SQLite 事务连接未绑定") { };
        return current.holder.getConnection();
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException exception) {
            // 连接关闭错误不能覆盖原始的提交、回滚或启动结果。
        }
    }

    private static final class Transaction {
        private ConnectionHolder holder;
        private boolean commitAttempted;
        private boolean completed;
        private boolean discardConnection;
        private boolean readOnly;
    }
}

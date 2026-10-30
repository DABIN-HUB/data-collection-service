package com.wangbin.collector.config.sqlite.mybatis;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.wangbin.collector.config.sqlite.database.SqliteDatabase;
import com.wangbin.collector.config.sqlite.mapper.SqliteConfigMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SQLite 独立运行时。Spring Boot 和 standalone CLI 共用这一组 Hikari、MyBatis
 * 和事务对象，避免 CLI 退回第二套 JDBC 业务实现。
 */
public final class SqlitePersistenceRuntime implements AutoCloseable {
    private static final String MAPPER_RESOURCE = "mybatis/sqlite/SqliteConfigMapper.xml";
    private final SqliteDatabase database;
    private final HikariDataSource dataSource;
    private final SqlSessionFactory sqlSessionFactory;
    private final SqlSessionTemplate sqlSessionTemplate;
    private final SqliteTransactionManager transactionManager;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SqlitePersistenceRuntime(SqliteDatabase database, HikariDataSource dataSource,
                                     SqlSessionFactory sqlSessionFactory,
                                     SqlSessionTemplate sqlSessionTemplate,
                                     SqliteTransactionManager transactionManager) {
        this.database = database;
        this.dataSource = dataSource;
        this.sqlSessionFactory = sqlSessionFactory;
        this.sqlSessionTemplate = sqlSessionTemplate;
        this.transactionManager = transactionManager;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public static SqlitePersistenceRuntime open(SqliteDatabase database,
                                                int maximumPoolSize,
                                                int minimumIdle,
                                                long connectionTimeoutMs) {
        return open(database, maximumPoolSize, minimumIdle, connectionTimeoutMs,
                new SqliteConnectionDataSource(database));
    }

    static SqlitePersistenceRuntime open(SqliteDatabase database,
                                         int maximumPoolSize,
                                         int minimumIdle,
                                         long connectionTimeoutMs,
                                         DataSource physicalDataSource) {
        if (maximumPoolSize < 1 || minimumIdle < 0 || minimumIdle > maximumPoolSize || connectionTimeoutMs < 250) {
            throw new IllegalArgumentException("SQLite 连接池参数不合法");
        }
        HikariDataSource dataSource = null;
        try {
            HikariConfig hikari = new HikariConfig();
            hikari.setPoolName("wangbin-sqlite");
            hikari.setDataSource(physicalDataSource);
            hikari.setMaximumPoolSize(maximumPoolSize);
            hikari.setMinimumIdle(minimumIdle);
            hikari.setConnectionTimeout(connectionTimeoutMs);
            hikari.setInitializationFailTimeout(connectionTimeoutMs);
            hikari.setAutoCommit(true);
            dataSource = new HikariDataSource(hikari);
            // Hikari may schedule the minimum-idle fill asynchronously. Borrow and return one
            // connection before exposing the runtime so close() cannot race that first physical
            // SQLite connection creation on Windows.
            try (var connection = dataSource.getConnection()) {
                // The physical connection has passed SqliteDatabase's PRAGMA and file checks.
            }

            Environment environment = new Environment("wangbin-sqlite",
                    new SpringManagedTransactionFactory(), dataSource);
            Configuration configuration = new Configuration(environment);
            configuration.setMapUnderscoreToCamelCase(false);
            configuration.addMapper(SqliteConfigMapper.class);
            try (InputStream mapper = SqlitePersistenceRuntime.class.getClassLoader()
                    .getResourceAsStream(MAPPER_RESOURCE)) {
                if (mapper == null) throw new IllegalStateException("缺少 SQLite MyBatis Mapper 资源");
                new XMLMapperBuilder(mapper, configuration, MAPPER_RESOURCE,
                        configuration.getSqlFragments()).parse();
            }
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
            SqlSessionTemplate template = new SqlSessionTemplate(factory);
            SqliteTransactionManager transactionManager = new SqliteTransactionManager(dataSource);
            return new SqlitePersistenceRuntime(database, dataSource, factory, template, transactionManager);
        } catch (java.sql.SQLException exception) {
            if (dataSource != null) dataSource.close();
            throw new IllegalStateException("SQLite 初始连接创建失败", exception);
        } catch (RuntimeException exception) {
            if (dataSource != null) dataSource.close();
            throw exception;
        } catch (IOException exception) {
            if (dataSource != null) dataSource.close();
            throw new IllegalStateException("SQLite MyBatis Mapper 资源读取失败", exception);
        }
    }

    public static SqlitePersistenceRuntime open(SqliteDatabase database) {
        return open(database, 4, 1, Math.max(250L, database.busyTimeoutMs()));
    }

    public SqliteDatabase database() { return database; }
    public HikariDataSource dataSource() { return dataSource; }
    public SqlSessionFactory sqlSessionFactory() { return sqlSessionFactory; }
    public SqlSessionTemplate sqlSessionTemplate() { return sqlSessionTemplate; }
    public SqliteConfigMapper mapper() { return sqlSessionTemplate.getMapper(SqliteConfigMapper.class); }
    public SqliteTransactionManager transactionManager() { return transactionManager; }
    public TransactionTemplate writeTransaction() { return writeTransaction; }
    public TransactionTemplate readTransaction() { return readTransaction; }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            dataSource.close();
        }
    }
}

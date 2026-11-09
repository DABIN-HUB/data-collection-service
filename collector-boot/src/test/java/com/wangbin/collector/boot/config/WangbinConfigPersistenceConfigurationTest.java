package com.wangbin.collector.boot.config;

import com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec;
import com.wangbin.collector.config.sqlite.migration.LegacyJsonMigration;
import com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.persistence.ConfigurationStore;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** 独立临时库验证两种权威模式；不运行SpringBoot整服务，不触碰用户JSON或默认库。 */
class WangbinConfigPersistenceConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void explicitLegacyModeNeedsNoDatabaseOrSqliteBridge() {
        Path absent = directory.resolve("not-created/collector.db");
        runner().withPropertyValues("wangbin.config.persistence.mode=legacy-json",
                "wangbin.config.sqlite.database-path=" + absent).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ConfigurationStore.class)
                    .doesNotHaveBean("wangbinLocalConfigurationPersistence");
            assertFalse(Files.exists(absent.getParent()));
        });
    }

    @Test
    void sqliteModeWithoutInfrastructureFailsInsteadOfFallingBackToJson() {
        Path absent = directory.resolve("not-created/collector.db");
        runner().withPropertyValues("wangbin.config.persistence.mode=sqlite", "wangbin.config.sqlite.enabled=false",
                "wangbin.config.sqlite.database-path=" + absent).run(context -> {
            assertThat(context).hasFailed();
            assertFalse(Files.exists(absent.getParent()));
        });
    }

    @Test
    void unknownModeIsRejectedRatherThanTreatedAsLegacy() {
        runner().withPropertyValues("wangbin.config.persistence.mode=unknown-wangbin-mode")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void sqliteInstalledDirectoryMissingDatabaseFailsWithoutCreatingIt() throws Exception {
        Path absent = directory.resolve("not-created/collector.db");
        Files.createDirectory(absent.getParent());
        sqliteRunner(absent, directory.resolve("not-created/source.json")).run(context -> {
            assertThat(context).hasFailed();
            assertFalse(Files.exists(absent));
        });
    }

    @Test
    void existingMigratedDatabaseProvidesBridgeAndIndependentMybatisInfrastructure() throws Exception {
        Path source = emptySource();
        Path database = migratedDatabase(source);
        byte[] original = Files.readAllBytes(source);
        sqliteRunner(database, source).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ConfigurationStore.class)
                    .hasBean("wangbinLocalConfigurationPersistence")
                    .hasBean("wangbinSqliteDataSource")
                    .hasBean("wangbinSqliteSqlSessionFactory")
                    .hasBean("wangbinSqliteSqlSessionTemplate")
                    .hasBean("wangbinSqliteTransactionManager");
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
            assertThat(context.getBeansOfType(SqlSessionFactory.class)).hasSize(1);
            assertTrue(context.getBean(ConfigurationStore.class).listDevices().isEmpty());
            assertEquals(1, context.getBean(ConfigurationStore.class).listMigrations().size());
        });
        assertArrayEquals(original, Files.readAllBytes(source));
        try (var reopened = SqliteConfigurationStore.openExisting(database, 500)) {
            assertEquals(1, reopened.listMigrations().size());
        }
    }

    @Test
    void legacyAuthorityDoesNotOpenSqliteEvenWhenOldInfrastructureFlagIsPresent() throws Exception {
        Path source = emptySource();
        Path database = migratedDatabase(source);
        runner().withPropertyValues("wangbin.config.persistence.mode=legacy-json", "wangbin.config.sqlite.enabled=true",
                "wangbin.config.sqlite.database-path=" + database).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ConfigurationStore.class)
                    .doesNotHaveBean("wangbinLocalConfigurationPersistence");
        });
    }

    @Test
    void explicitlyInitializedEmptyDatabaseNeedsNoLegacyMigration() throws Exception {
        Path source = emptySource();
        Path database = directory.resolve("private/collector.db");
        try (var ignored = SqliteConfigurationStore.createNew(database, 500)) {
            // 新需求从合法空库开始，启动不读取或迁移旧数据。
        }
        sqliteRunner(database, source).run(context -> assertThat(context).hasNotFailed()
                .hasBean("wangbinLocalConfigurationPersistence"));
        try (var unchanged = SqliteConfigurationStore.openExisting(database, 500)) {
            assertTrue(unchanged.listDevices().isEmpty());
            assertTrue(unchanged.listMigrations().isEmpty());
        }
    }

    @Test
    void legacySourceChangesCannotOverrideOrBlockSqliteAuthority() throws Exception {
        Path source = emptySource();
        Path database = migratedDatabase(source);
        Files.writeString(source, "[ ]\n");
        byte[] changed = Files.readAllBytes(source);
        sqliteRunner(database, source).run(context -> assertThat(context).hasNotFailed()
                .hasBean("wangbinLocalConfigurationPersistence"));
        assertArrayEquals(changed, Files.readAllBytes(source));
        try (var reopened = SqliteConfigurationStore.openExisting(database, 500)) {
            assertEquals(1, reopened.listMigrations().size());
            assertTrue(reopened.listDevices().isEmpty());
        }
    }

    @Test
    void corruptDatabaseFailsAndIsNotReinitialized() throws Exception {
        Path source = emptySource();
        Path database = directory.resolve("private/collector.db");
        Files.createDirectory(database.getParent());
        byte[] corrupt = "wangbin-this-is-not-an-sqlite-database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(database, corrupt);
        sqliteRunner(database, source).run(context -> assertThat(context).hasFailed());
        assertArrayEquals(corrupt, Files.readAllBytes(database));
    }

    @Test
    void sqliteAuthorityDoesNotReadMissingCollectorSnapshot() throws Exception {
        Path source = emptySource();
        Path database = migratedDatabase(source);
        var properties = new CollectorProperties();
        Path missingSnapshot = directory.resolve("absent-legacy/config.json");
        properties.getConfig().setLocalDeviceSnapshotFile(missingSnapshot.toString());
        runner(properties).withPropertyValues(
                "wangbin.config.persistence.mode=sqlite", "wangbin.config.sqlite.enabled=true",
                "wangbin.config.sqlite.database-path=" + database).run(context -> {
            assertThat(context).hasNotFailed().hasBean("wangbinLocalConfigurationPersistence");
            assertFalse(Files.exists(missingSnapshot.getParent()));
        });
    }

    @Test
    void invalidLegacyTimezoneFailsWithoutChangingTheMigratedData() throws Exception {
        Path source = emptySource();
        Path database = migratedDatabase(source);
        sqliteRunner(database, source).withPropertyValues("wangbin.config.persistence.legacy-time-zone=wangbin-invalid-zone")
                .run(context -> assertThat(context).hasFailed());
        try (var store = SqliteConfigurationStore.openExisting(database, 500)) {
            assertTrue(store.listDevices().isEmpty());
            assertEquals(1, store.listMigrations().size());
        }
    }

    private ApplicationContextRunner sqliteRunner(Path database, Path source) {
        return runner().withPropertyValues("wangbin.config.persistence.mode=sqlite", "wangbin.config.sqlite.enabled=true",
                "wangbin.config.sqlite.database-path=" + database,
                "wangbin.config.persistence.migration-source=" + source);
    }

    private ApplicationContextRunner runner() {
        return runner(new CollectorProperties());
    }

    private ApplicationContextRunner runner(CollectorProperties properties) {
        Class<?> configuration = assertDoesNotThrow(() -> Class.forName(
                "com.wangbin.collector.boot.config.WangbinConfigPersistenceConfiguration"), "缺少显式权威模式装配");
        return new ApplicationContextRunner().withUserConfiguration(WangbinConfigSqliteConfiguration.class, configuration)
                .withBean(CollectorProperties.class, () -> properties);
    }

    private Path emptySource() throws Exception {
        Path source = directory.resolve("wangbin-legacy-source.json");
        Files.writeString(source, "[]\n");
        return source;
    }

    private Path migratedDatabase(Path source) {
        Path database = directory.resolve("private/collector.db");
        try (var store = SqliteConfigurationStore.createNew(database, 500)) {
            new LegacyJsonMigration(store, new ConfigurationJsonCodec()).migrate(source);
        }
        return database;
    }
}

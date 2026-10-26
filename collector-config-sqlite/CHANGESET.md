# wangbin SQLite Phase 1 真实变更文件清单

Git可见本期文件共 **50** 项：修改已有跟踪文件 **3** 项，新增文件 **47** 项（包含本清单自身）。不执行add/commit/push。

## 修改已有文件

- `THIRD-PARTY-NOTICES.md`
- `collector-boot/pom.xml`
- `pom.xml`

## 新增：runtime Port与持久化模型（13项）

- `collector-runtime/src/main/java/com/wangbin/collector/core/config/persistence/ConfigurationConflictException.java`
- `collector-runtime/src/main/java/com/wangbin/collector/core/config/persistence/ConfigurationModelChecks.java`
- `collector-runtime/src/main/java/com/wangbin/collector/core/config/persistence/ConfigurationReader.java`
- `collector-runtime/src/main/java/com/wangbin/collector/core/config/persistence/ConfigurationRevision.java`
- `collector-runtime/src/main/java/com/wangbin/collector/core/config/persistence/ConfigurationStore.java`
- `collector-runtime/src/main/java/com/wangbin/collector/core/config/persistence/ConnectionConfiguration.java`
- `collector-runtime/src/main/java/com/wangbin/collector/core/config/persistence/DeviceConfiguration.java`
- `collector-runtime/src/main/java/com/wangbin/collector/core/config/persistence/GatewaySetting.java`
- `collector-runtime/src/main/java/com/wangbin/collector/core/config/persistence/MigrationRecord.java`
- `collector-runtime/src/main/java/com/wangbin/collector/core/config/persistence/PointConfiguration.java`
- `collector-runtime/src/main/java/com/wangbin/collector/core/config/persistence/ProtocolProfile.java`
- `collector-runtime/src/main/java/com/wangbin/collector/core/config/persistence/RemoteSnapshot.java`
- `collector-runtime/src/main/java/com/wangbin/collector/core/config/persistence/StoredDeviceConfiguration.java`

## 新增：boot独立装配与隔离测试（3项）

- `collector-boot/src/main/java/com/wangbin/collector/boot/config/WangbinConfigSqliteConfiguration.java`
- `collector-boot/src/main/java/com/wangbin/collector/boot/config/WangbinConfigSqliteProperties.java`
- `collector-boot/src/test/java/com/wangbin/collector/boot/config/WangbinConfigSqliteConfigurationTest.java`

## 新增：SQLite模块实现、测试、Schema和文档（31项）

- `collector-config-sqlite/CHANGESET.md`
- `collector-config-sqlite/PHASE2-INTERFACES.md`
- `collector-config-sqlite/README.md`
- `collector-config-sqlite/VERIFICATION.md`
- `collector-config-sqlite/pom.xml`
- `collector-config-sqlite/src/main/java/com/wangbin/collector/config/sqlite/cli/WangbinConfigSqliteTool.java`
- `collector-config-sqlite/src/main/java/com/wangbin/collector/config/sqlite/cli/WangbinInstallationBootstrap.java`
- `collector-config-sqlite/src/main/java/com/wangbin/collector/config/sqlite/codec/ConfigurationJsonCodec.java`
- `collector-config-sqlite/src/main/java/com/wangbin/collector/config/sqlite/database/SqliteDatabase.java`
- `collector-config-sqlite/src/main/java/com/wangbin/collector/config/sqlite/database/SqliteFileSecurity.java`
- `collector-config-sqlite/src/main/java/com/wangbin/collector/config/sqlite/database/SqlitePaths.java`
- `collector-config-sqlite/src/main/java/com/wangbin/collector/config/sqlite/migration/LegacyJsonMigration.java`
- `collector-config-sqlite/src/main/java/com/wangbin/collector/config/sqlite/migration/MigrationInspection.java`
- `collector-config-sqlite/src/main/java/com/wangbin/collector/config/sqlite/migration/MigrationResult.java`
- `collector-config-sqlite/src/main/java/com/wangbin/collector/config/sqlite/store/SqliteConfigurationStore.java`
- `collector-config-sqlite/src/main/resources/META-INF/licenses/sqlite-jdbc-3.53.4.0/LICENSE`
- `collector-config-sqlite/src/main/resources/META-INF/licenses/sqlite-jdbc-3.53.4.0/LICENSE.zentus`
- `collector-config-sqlite/src/main/resources/META-INF/licenses/sqlite-jdbc-3.53.4.0/NOTICE`
- `collector-config-sqlite/src/main/resources/META-INF/licenses/sqlite-jdbc-3.53.4.0/NativeDB-ISC-NOTICE.txt`
- `collector-config-sqlite/src/main/resources/META-INF/licenses/sqlite-jdbc-3.53.4.0/PROVENANCE.md`
- `collector-config-sqlite/src/main/resources/db/migration/V001__wangbin_configuration.sql`
- `collector-config-sqlite/src/test/java/com/wangbin/collector/config/sqlite/cli/WangbinConfigSqliteToolTest.java`
- `collector-config-sqlite/src/test/java/com/wangbin/collector/config/sqlite/codec/ConfigurationJsonCodecTest.java`
- `collector-config-sqlite/src/test/java/com/wangbin/collector/config/sqlite/database/SqliteDatabaseTest.java`
- `collector-config-sqlite/src/test/java/com/wangbin/collector/config/sqlite/database/SqliteOwnershipTest.java`
- `collector-config-sqlite/src/test/java/com/wangbin/collector/config/sqlite/database/SqlitePathsTest.java`
- `collector-config-sqlite/src/test/java/com/wangbin/collector/config/sqlite/migration/LegacyJsonMigrationIntegrationTest.java`
- `collector-config-sqlite/src/test/java/com/wangbin/collector/config/sqlite/migration/LegacyJsonMigrationTest.java`
- `collector-config-sqlite/src/test/java/com/wangbin/collector/config/sqlite/store/SqliteConfigurationPayloadBoundaryTest.java`
- `collector-config-sqlite/src/test/java/com/wangbin/collector/config/sqlite/store/SqliteConfigurationStoreTest.java`
- `collector-config-sqlite/verification/phase1-evidence.json`

## 不纳入本期提交的内容

- 原未跟踪 `.hermes.md` 与 `tools/windows-service-prototype/` 不是本期文件；后者的 Prototype.psm1 在执行期间出现外部并发漂移，本任务未写入、未回滚。
- `.hermes/plans/` 中的本期设计、计划、任务和审查过程记录被Git忽略，不修改忽略规则、不强制加入索引。
- `target/` 的JAR、class、Surefire XML和Hermes scratch原始日志属于构建/证据产物，不纳入源码清单。
- 原始设备JSON、含敏感配置的临时数据库、凭据、.env与生产运行数据不在交付或Git清单中。

## 保持不变的生产链路

ConfigManager、LocalDeviceConfigStore、ConfigSyncService、FileConfigLoader、RemoteConfigLoader及原application YAML均未修改，受保护生产文件的8项阶段前后SHA一致。没有驱动、调度、云同步语义、控制器、前端或现有设备启动行为变更。

源码/Schema/构件摘要与定向测试计数见 `verification/phase1-evidence.json`。所有文件仍留在本地工作区，等待用户手动审查和Git操作。

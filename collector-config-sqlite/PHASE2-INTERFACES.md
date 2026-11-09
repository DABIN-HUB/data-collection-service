# wangbin Phase 2 接入接口清单（已接入显式SQLite模式）

当前运行桥为`WangbinLocalConfigurationPersistence`，实现为`WangbinSqliteLocalConfigurationPersistence`。Boot使用仅数据库构造，restore核对数据库聚合与持久修订，不依赖旧JSON/迁移；显式来源构造仅保留旧工具/直接调用兼容。最新新需求验收见`docs/sqlite-persistence/phase2/FRESH-START-REPORT.md`。

## 依赖与边界

Port 包：`com.wangbin.collector.core.config.persistence`，位于 collector-runtime。
实现：`com.wangbin.collector.config.sqlite.store.SqliteConfigurationStore`，位于 collector-config-sqlite。
Boot在`wangbin.config.sqlite.enabled=true`时装配独立仓储；`wangbin.config.persistence.mode=sqlite`时额外装配运行桥供ConfigManager选择。既有构造器和legacy-json模式保留，不热切换权威源。

## 完整 Port 签名

`ConfigurationReader`：

```java
Optional<StoredDeviceConfiguration> findDevice(String deviceId);
List<StoredDeviceConfiguration> listDevices();
Optional<ProtocolProfile> findProtocolProfile(String profileId);
List<ProtocolProfile> listProtocolProfiles();
Optional<GatewaySetting> findGatewaySetting(String settingKey);
List<GatewaySetting> listGatewaySettings();
Optional<RemoteSnapshot> findRemoteSnapshot(String source, String snapshotId, String sourceVersion);
List<RemoteSnapshot> listRemoteSnapshots(String source);
long currentRevision();
List<ConfigurationRevision> listRevisions(long afterRevision, int limit);
Optional<MigrationRecord> findMigration(String migrationId);
List<MigrationRecord> listMigrations();
```

`ConfigurationStore extends ConfigurationReader, AutoCloseable`：

```java
<T> T transaction(Function<Writer, T> action);
void close();
```

`ConfigurationStore.Writer extends ConfigurationReader`：

```java
StoredDeviceConfiguration saveDevice(DeviceConfiguration configuration, long expectedRevision);
void deleteDevice(String deviceId, long expectedRevision);
ProtocolProfile saveProtocolProfile(String profileId, String protocolType, String configJson, long expectedRevision);
void deleteProtocolProfile(String profileId, long expectedRevision);
GatewaySetting saveGatewaySetting(String settingKey, String configJson, long expectedRevision);
void deleteGatewaySetting(String settingKey, long expectedRevision);
RemoteSnapshot saveRemoteSnapshot(String source, String snapshotId, String sourceVersion,
                                  String configJson, long expectedRevision);
void deleteRemoteSnapshot(String source, String snapshotId, String sourceVersion, long expectedRevision);
void recordMigration(MigrationRecord record);
```

- 所有实体保存：expectedRevision=0 只创建；正数必须匹配已有修订；禁止盲覆盖。
- 删除必须匹配正数修订；冲突抛 `ConfigurationConflictException`。
- 设备聚合包含设备、可空连接、按序点位，完整写入和修订同事务。
- Writer 限同线程、当前事务有效期，不允许嵌套事务；回调/提交异常回滚。捕获写异常也不能使失败事务继续提交。
- 远端三元身份只能写远端表，不与本地设备主键混用。
- 迁移成功记录仅允许一次插入，与本批设备配置同事务；不是普通 CRUD 更新记录。

## 配置模型

- `DeviceConfiguration(String deviceId, String deviceJson, ConnectionConfiguration connection, List<PointConfiguration> points)`
- `ConnectionConfiguration(String deviceId, String configJson)`
- `PointConfiguration(String deviceId, String pointId, int sortOrder, Integer status, String configJson)`
- `StoredDeviceConfiguration(DeviceConfiguration configuration, long revision)`
- `ProtocolProfile(String profileId, String protocolType, String configJson, long revision)`
- `GatewaySetting(String settingKey, String configJson, long revision)`
- `RemoteSnapshot(String source, String snapshotId, String sourceVersion, String configJson, long revision)`
- `ConfigurationRevision(long revision, String entityType, String entityId, String operation, Instant createdAt)`
- `MigrationRecord(String migrationId, String sourcePath, String sourceSha256, int deviceCount, int connectionCount, int pointCount, Instant completedAt)`

JSON 字符串是配置事实，不是已脱敏 UI 响应；模型 toString 不输出负载，API/导出仍须遵循现有脱敏规则。

## 编解码与显式管理

`com.wangbin.collector.config.sqlite.codec.ConfigurationJsonCodec`：

```java
JsonNode readJson(String json);
JsonNode validateConfigurationPayload(String json);
DeviceConfiguration decodeLocalDevice(String json);
DeviceConfiguration decodeLocalDevice(JsonNode document);
List<DeviceConfiguration> decodeLocalDevices(JsonNode documents);
DeviceConfiguration fromRuntime(DeviceInfo device, DeviceConnection connection, List<DataPoint> points);
ObjectNode encodeLocalDevice(DeviceConfiguration configuration);
void validateLocalDevice(DeviceConfiguration configuration);
```

`decodeLocalDevice(s)` 用于旧原始聚合 JSON 的白名单提取，不经 typed alarmRule getter。`fromRuntime` 只读取显式配置字段，禁止整体实体序列化。`validateConfigurationPayload` 是模板/设置/远端的已知运行态拒绝边界，不扫描或裁剪合法协议扩展容器。

数据库：`SqliteConfigurationStore.createNew(Path,int)` / `openExisting(Path,int)`。
路径：`SqlitePaths.resolve(String)`。
迁移：`LegacyJsonMigration.inspect(Path)` / `migrate(Path)`，默认来源 `LegacyJsonMigration.defaultSource()`。
未来安装器：`WangbinInstallationBootstrap.initializeNewInstallation(Path,int,boolean)`，未声明全新安装则拒绝，Phase 1 无启动路径调用。

## 未来需要单独设计并授权的生产接点

现有方法保留原实现，本期未调用新 Port：

- `ConfigManager.restorePersistedLocalTemporaryContexts()` / `persistLocalTemporaryContexts()`：选择配置恢复与持久化来源。
- `ConfigManager.saveLocalDeviceConfigWithResult(...)` / `deleteLocalDeviceConfig(String)`：持久事务、缓存发布和删除的次序。
- `ConfigManager.replaceDeviceContextAtomically(..., long expectedVersion)` / `replaceDeviceContextsAtomically(List<DeviceContext>)`：现有 JVM CAS 与持久 revision 的边界。
- `ConfigManager.updateDeviceConfig(DeviceInfo)` / `updateConnectionConfig(String, DeviceConnection)` / `updateDataPoints(String, List<DataPoint>)`：历史兼容入口与完整聚合提交的关系。
- `ConfigManager.getDeviceConfigurationSnapshot(String)`：恢复/读取配置的跨层转换，不能直接把持久化模型当 DeviceContext。

必须继续区分 JVM configVersion、持久化 revision、远端 sourceVersion。运行态恢复默认值、配置发布事件、失败缓存回滚、旧 JSON 过渡、本地/远端身份隔离与敏感占位恢复，均需要 Phase 2 单独批准与验证。

**此清单不构成生产接入已经完成；Phase 1 在基础设施与隔离验收结束。**

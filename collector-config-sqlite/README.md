# wangbin SQLite 配置存储 — Phase 1基础设施与Phase 2接入

## 范围与默认行为

当前代码提供独立 SQLite 配置持久化基础设施，并已通过 runtime Port 和 Boot 接入生产 ConfigManager。生产默认使用 SQLite；显式选择 `legacy-json` 时才保留旧的 LocalDeviceConfigStore。SQLite 模式中数据库是本地配置唯一权威源，新增/编辑/删除事务持久化，启动直接从数据库恢复。合法空库不要求 JSON 迁移标记、旧文件存在或摘要匹配；没有 JSON 双写或回填。协议、调度、远端来源及设备显式启动语义不变；不保存实时采样或遥测历史。runtime 不反向依赖 SQLite 实现。当前真实验收见 `docs/sqlite-persistence/phase2/FRESH-START-REPORT.md`。

`wangbin.config.sqlite.enabled` 默认 **true**。SQLite 模式装配独立仓储；目标库存在时仅打开并校验，目标缺失时由 `WangbinInstallationBootstrap.openOrInitializeInstallation` 在专用目录中原子初始化。不会自动迁移旧 JSON，也没有 DataSource、SqlSessionFactory、事务管理器、Runner 或自动迁移监听器。

## 配置

| 属性 | 默认 / 约束 | 生效时机 |
|---|---|---|
| `wangbin.config.sqlite.enabled` | true；false 时 SQLite 模式启动失败关闭，不回退到 JSON | 重启应用 |
| `wangbin.config.sqlite.database-path` | 可空；空时按下面 OS 规则解析 | 重启应用 |
| `wangbin.config.sqlite.busy-timeout-ms` | 5000，范围 1–60000 | 重启应用 |
| `wangbin.config.persistence.mode` | sqlite；可显式设为 legacy-json。sqlite 模式要求基础设施启用；目标不存在时由安装保护流程原子初始化，已有目标必须是合法数据库 | 重启应用 |

默认路径：

- Windows：`%ProgramData%/WangbinCollector/data/collector.db`；ProgramData 不可用时失败，不能回退到用户目录。
- Linux：`/var/lib/wangbin-collector/collector.db`。
- 验证：调用方提供独立临时目录，不读取/写入生产数据库。

使用专用数据库目录；不得指向文件系统根、用户主目录、项目根等共享目录。初始化权限和服务运行账户必须一致，不通过管理员身份测试推断普通服务账户可用。SQLite 是明文配置数据库，密码和令牌可作为配置事实存储，但不允许落日志/完整审计；OS 权限不是加密。

专用目录、主库及既有 WAL/SHM/journal 必须属于当前操作系统服务身份；不信任可由 `-Duser.name` 伪造的属性，也不把任意已有 owner 当成可信主体。异主文件受控拒绝，不自动 chown。Windows 使用 JDK NTSystem、POSIX 使用 UnixSystem 查询系统身份；定制 Java 17 运行时需要保留 `jdk.security.auth` 模块及相应原生认证支持，否则失败关闭。

每次 JDBC 打开文件前检查所有权及访问权限；权限收紧仍只作用于选定专用目录及库文件，不修改共享祖先。并发关闭连接可能删除 WAL/SHM，仅在明确确认可选附属文件已消失时跳过其检查，不把“无法判断存在”当不存在；主库缺失永远报错。发现旧 WAL/SHM/journal 残留时，新建入口拒绝把目标判为全新安装。

## 显式 API 与管理工具

- `SqlitePaths.resolve(String configuredPath)`：路径解析，无建库副作用。
- `SqliteConfigurationStore.createNew(Path path, int busyTimeoutMs)`：显式新建；目标已存在则拒绝，不覆盖、不“修复”损坏文件。
- `SqliteConfigurationStore.openExisting(Path path, int busyTimeoutMs)`：仅打开已有有效库；缺失、空文件、损坏、不兼容版本、权限异常拒绝。
- `WangbinInstallationBootstrap.initializeNewInstallation(Path, int, boolean)`：仅为未来安装器保留；false 拒绝，true 仍通过原子新建保护。Phase 1 无启动路径调用，不以文件缺失自动推断全新安装。
- `LegacyJsonMigration.inspect(Path)`：只读预检与身份缺失/重复计数。
- `new LegacyJsonMigration(ConfigurationStore, ConfigurationJsonCodec).migrate(Path)`：显式一次性迁移。

独立命令主类 `com.wangbin.collector.config.sqlite.cli.WangbinConfigSqliteTool`，使用 Java 17 和本模块及依赖的运行时 classpath，不启动 Spring：

```text
init --database <专用目录/collector.db> --fresh-install
inspect --source <旧JSON路径>
migrate --database <已有collector.db> --source <旧JSON路径>
```

命令中的 `<...>` 由用户替换为实际路径。省略数据库路径采用 OS 默认，省略来源采用 `~/.data-collection-service/local-device-configs.json`；旧生产配置若自定义了快照路径，应显式传 `--source`，独立工具不加载生产 YAML。`init` 必须带 `--fresh-install`；`migrate` 不会隐式建库。退出 0 表示成功或幂等跳过，2 表示参数/来源预检拒绝，1 表示文件/数据库操作失败。输出只含摘要，不含设备、连接或点位负载。

## 存储与一致性

数据库使用版本化 SQL、应用身份与迁移脚本校验。WAL 为数据库级持久设置；每个新连接分别设置并回读 foreign_keys、busy_timeout、synchronous=FULL。不共享 TDengine 的 Spring DataSource，也不使用 MyBatis。

| 表 | 作用与约束 |
|---|---|
| `dc_device` | 本地 deviceId 主键、白名单 device_json（含配置来源）和持久化 revision |
| `dc_device_connection` | device 外键，一设备一条；`connection_json` 保留通信配置及扩展 |
| `dc_device_point` | 非空 pointId 全局唯一、device 外键、排序/配置状态等检索列、`point_json` |
| `dc_protocol_profile` | profile 身份、protocol_type、connection_json；只保存模板，不改变注册驱动或 Schema |
| `dc_gateway_setting` | 唯一设置键及配置 JSON |
| `dc_config_revision` | 全局递增持久化修订，仅记录实体/操作/时间，无配置负载 |
| `dc_remote_snapshot` | `(source, snapshotId, sourceVersion)` 独立身份，历史版本不互相覆盖，不写本地设备表 |
| `dc_schema_version` | 数据库迁移版本与校验信息；不兼容库不重建 |
| `dc_json_migration` | 原路径、SHA-256、三类计数、成功时间；与导入在同一事务提交 |

设备、点位的主键、外键和唯一性由真实数据库约束兜底。显式索引为 `idx_dc_point_device_sort(device_id, sort_order)`、`idx_dc_point_status(status)`、`idx_dc_point_code(point_code)`、`idx_dc_profile_protocol(protocol_type)`；此外主键和 `(device_id, sort_order)` 唯一约束产生相应索引。分组和来源当前保留在 JSON 内，没有宣称已有对应查询索引。pointCode 不替代 pointId，不能据地址或序号重建历史身份。

当前发布只定义 V001，未知或更高版本拒绝；未来升级必须显式提供经验证的增量脚本及升级逻辑，不把未知库自动当旧库升级。脚本 SHA-256 规范化 CRLF/CR 为 LF 后计算，避免跨操作系统 Git 检出造成假不兼容；其余内容变化仍会拒绝。

`ConfigurationStore.transaction(Function<Writer,T>)` 在同一连接中执行完整事务：回调/提交失败回滚，Writer 可读本事务未提交数据；禁止嵌套、跨线程或逃逸后使用。读取方法返回一致快照。save 的 expectedRevision=0 只创建，正数只更新匹配修订；delete 必须匹配正数修订。冲突使用 `ConfigurationConflictException`。close 幂等且关闭后拒绝访问。

这里的持久化 revision 不替代现有 ConfigManager 当前 JVM 版本，也不替代远端 sourceVersion；正式接入时必须分别处理。

## 精确配置白名单

下面列表与 codec 的显式字段表一致；createTime/updateTime、旧数值 id 只作为兼容元数据。设备 id 是历史字符串别名，不能与 deviceId 冲突。没有把整个运行实体序列化。

### DeviceInfo

`deviceId`, `id`, `deviceName`, `deviceAlias`, `productKey`, `productName`, `groupId`, `groupName`, `protocolType`, `connectionType`, `ipAddress`, `port`, `authConfig`, `collectionInterval`, `reportInterval`, `cloudTarget`, `maxRetryCount`, `createTime`, `updateTime`, `remark`, `configSource`, `temporaryConfig`。

### DeviceConnection

`id`, `connectionType`, `connectionKey`, `host`, `port`, `url`, `connectTimeoutMs`, `readTimeoutMs`, `writeTimeoutMs`, `retries`, `extJson`, `remark`, `connectionId`, `deviceId`, `deviceName`, `timeout`, `heartbeatInterval`, `heartbeatTimeout`, `subscriptionInterval`, `maxFrameLength`, `reconnectDelay`, `maxReconnectTimes`, `maxGroupConnections`, `username`, `password`, `clientId`, `productKey`, `deviceSecret`, `authToken`, `securityPolicy`, `authParams`, `sslEnabled`, `sslCertPath`, `sslKeyPath`, `charset`, `keepAlive`, `bufferSize`, `autoReconnect`, `initialReconnectDelay`, `maxReconnectDelay`, `maxReconnectAttempts`, `reconnectBackoffMultiplier`, `maxPendingMessages`, `dispatchBatchSize`, `dispatchFlushInterval`, `overflowStrategy`, `createTime`, `updateTime`。

### DataPoint

`id`, `unitId`, `commonAddress`, `pointId`, `pointCode`, `pointName`, `pointAlias`, `deviceId`, `deviceName`, `groupId`, `address`, `dataType`, `readWrite`, `scalingFactor`, `offset`, `deadband`, `unit`, `minValue`, `maxValue`, `collectionMode`, `priority`, `cacheEnabled`, `cacheDuration`, `alarmEnabled`, `alarmRule`, `status`, `createTime`, `updateTime`, `precision`, `remark`, `additionalConfig`, `baseCollectionInterval`, `minCollectionInterval`, `maxCollectionInterval`, `pointChangeThreshold`。

### CloudTargetConfig

`enabled`, `deviceType`, `productKey`, `deviceName`, `topologyEnabled`。

### 明确排除

- 设备：status、lastOnlineTime、lastOfflineTime、lastError、retryCount 及全部派生 getter 属性。
- 连接：status、connectTime、disconnectTime、duration、lastError、stats、lastHeartbeatTime、lastDataTime、connectionStats 及派生状态。
- 点位：currentCollectionInterval、stableCount、lastValue、changeRate、lastAdjustTime、reportFieldConflict、所有 configured*/parsed* 报告解析缓存及派生 getter 属性。
- 不保存 Java 连接、任务、线程状态、Redis Stream、遥测或历史采样。

**DataPoint.status 是配置，必须保留**；DeviceInfo/DeviceConnection.status 是运行状态。historyEnabled 位于 additionalConfig，原值保留。现有采集周期注释/实际单位差异不在本期修正，不擅自换算。

### JSON 无损边界

直接从旧原始 JSON 读取，保留 alarmRule 的 string/array/object/null 形态与未知规则字段，不调用会丢未知属性的 typed getter。运行实体转换只读取显式声明字段（包含底层原始 alarmRule），不走整实体序列化。

extJson、authParams、additionalConfig、authConfig 支持既有对象/对象 JSON 字符串；字符串内原文保留。未知协议配置只在这些明确容器内完整保留，不开放未知顶层运行字段。大整数、精确小数与尾零、数组、布尔和 null 保留；authParams 按现有 Map<String,String> 契约仅接受字符串/null值，其他形态拒绝而非擅自转换。JSON 重复键、尾随内容、无效容器拒绝。内容保真不等于全文件空白、键序或数字指数写法逐字节不变；已被调用方转为 Double 的精度不能恢复。

协议模板、网关设置、远端快照的通用 JSON 写入口也拒绝已知运行态字段及 ONLINE/CONNECTED 等状态值，不能作为整实体序列化旁路。数字配置 status 保留；明确的协议扩展/告警容器仍保留未知同名配置键，存储层不以键名猜测这些用户配置的业务含义。

## 迁移保证

来源只读，UTF-8/BOM 兼容，最大64 MiB。预检完整数组、设备/点位身份、双身份别名、local/temporary 来源、连接/点位归属；缺失或重复 pointId 整批拒绝，绝不生成替代身份。

仅导入尚无成功记录的空本地设备目标，不合并或覆盖已有设备。事务内再次检查迁移标记；写完后回读数量、身份、顺序和完整白名单配置，检查来源 SHA 未变化，最后同事务写成功标记。失败不留部分设备/修订/标记；成功后重复执行跳过，即使原文件后续改变也不重新导入。原文件始终不修改、不删除。

现有本机样本为45设备/45连接/749点位，测试仅在显式提供 `-Dwangbin.migration.sample=...` 时读取，并使用独立临时数据库；不把实际样本或包含秘密的数据库提交或交付。实际结果见 VERIFICATION.md。

## Phase 2 准确接入接口（本期不调用）

- `ConfigurationReader.findDevice(String)` / `listDevices()`：读取带持久化修订的设备聚合。
- `ConfigurationStore.transaction(Function<Writer,T>)`：后续应用层完整设备原子写入边界。
- `Writer.saveDevice(DeviceConfiguration,long)` / `deleteDevice(String,long)`：设备聚合 CAS（连接、点位随聚合同事务）。
- `Writer.saveProtocolProfile(String,String,String,long)` / `deleteProtocolProfile(String,long)`。
- `Writer.saveGatewaySetting(String,String,long)` / `deleteGatewaySetting(String,long)`。
- `Writer.saveRemoteSnapshot(String,String,String,String,long)` / `deleteRemoteSnapshot(String,String,String,long)`：只维护分离的远端版本快照。
- `ConfigurationReader.findProtocolProfile/listProtocolProfiles/findGatewaySetting/listGatewaySettings/findRemoteSnapshot/listRemoteSnapshots/currentRevision/listRevisions/findMigration/listMigrations`：准确参数见 runtime Port。
- `ConfigurationJsonCodec.fromRuntime(DeviceInfo,DeviceConnection,List<DataPoint>)` 与 `encodeLocalDevice(DeviceConfiguration)`：白名单边界；后续恢复为运行实体需显式设计转换，不把 SQLite model 当 DeviceContext。

正式切源需单独授权：ConfigManager 的恢复/持久化/CAS/事件顺序，版本跨重启策略，旧 JSON 过渡及远端边界均不在本期实施。

## 依赖与验证边界

锁定 Xerial sqlite-jdbc 3.53.4.0，Windows/Linux native 随上游 JAR，许可证及补充声明在 `META-INF/licenses/sqlite-jdbc-3.53.4.0/`。上游扩展函数历史作者授权仍需商业发布审核，未做 GPG 身份验签。操作系统 ACL/权限不替代加密，默认拒绝不兼容或不可访问数据库。

不提供在线备份接口；不能把运行中的 WAL 数据库主文件单独复制当完整备份。未来备份必须使用 SQLite 一致性机制并继承同等 OS 权限。没有断电/掉盘可靠性或所有硬件平台验收保证。Linux 路径解析、native 文件存在与真实 Linux 执行分别记录，不互相替代。

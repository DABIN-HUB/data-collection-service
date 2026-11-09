# 阶段 04：SQLite 单设备生命周期集成验收

审核基线：`24d2dcd31ebc5582f77541f59b80f3c2b4e89f11`。本轮只改测试、测试作用域依赖和本记录；未改生产代码、数据库 Schema、全量恢复、协议实现、Redis/TDengine 或服务配置。

## TEST-04-01 / 02：SQLite 与确定性并发

`collector-boot/src/test/java/com/wangbin/collector/core/collector/scheduler/WangbinPhase4SqliteDeviceLifecycleTest.java`：JUnit `@TempDir` 独立 SQLite，经 `SqliteConfigService.transaction(writer.saveDevice(...))` 保存 A/B/C；走真正的 `DeviceLifecycleCoordinator.startDevice/stopDevice`，连接/采集管理器以可观察夹具代替，不把该套断言冒充协议实采。

- A/B/C 均有合法设备、Modbus TCP 连接与点位结构。启动 A 只按 ID 加载 A，不查询/连接/调度 C；B 已启动时，A 重复启动不新增采集器或 generation，A 停止/重复停止后运行配置释放，B 的运行代次、调度与连接夹具仍在；A 的 SQLite 设备、连接、点位完整。更新已停止 A 的点位后再启动，读取新地址及新 generation。
- 缺失设备、空点位、无效连接端口均不能伪装成 RUNNING。先以既有 `ConfigManager.init()` 恢复合法 A/B/C，之后破坏 B 点位 JSON；启动 A 不解析 B，也不修改 `init()` 的全量恢复逻辑。
- 门闩阻断 A 的 SQLite 按设备查询，STOP 在旧查询返回前完成；迟到结果不能安装、连接或注册任务。另以门闩控制旧连接，验证 START→STOP→START 的 generation 递增及旧结果不能覆盖新运行快照。
- 注入采集器清理故障时，STOP 返回失败、运行快照保留供重试；清理成功后再释放。阻断 A 清理时，B 仍能通过可观察采集夹具返回点值，generation 不变。配置重启预留取消后无 STARTING/遗留快照；shutdown admission 关闭后新启动被拒绝。

正式 reactor：`mvn -pl collector-boot -am -Dtest=WangbinPhase4SqliteDeviceLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test`。结果：**Tests run: 8, Failures: 0, Errors: 0, Skipped: 0**。

## TEST-04-03：JUnit discovery

最初独立运行 `mvn -pl collector-config-sqlite test` 的 Surefire dump 指向 `LegacyJsonMigrationIntegrationTest` 类选择器：`NoClassDefFoundError: com/wangbin/collector/core/config/persistence/SqliteConfigService`，其 cause 是 `ClassNotFoundException`。这不是 JUnit 发现器版本冲突；测试 classpath 引用本地仓库中未包含新接口的旧 `collector-runtime` SNAPSHOT。`dependency:tree` 确认 SQLite 模块依赖 `collector-runtime:0.0.1-SNAPSHOT`，JUnit Jupiter **5.12.2**、JUnit Platform **1.12.2**；正式 reactor `mvn -pl collector-config-sqlite -am -DskipTests install` 更新依赖构件，安装后的 runtime JAR 确实包含该接口。未修改 Surefire/JUnit 依赖或测试配置。

随后 `mvn -pl collector-config-sqlite test` 全量执行：**Tests run: 151, Failures: 0, Errors: 0, Skipped: 2**。两项跳过是既有用例跳过，非 `Tests run: 0`。

## TEST-04-04：真实 Modbus 点位闭环

阶段 03 的冻结源码（`8ae724d7e1ee83618f297fa52e864b953ade5803`）以 Git archive 在 Hermes 临时目录单独编译运行同名 `ModbusTcpCollectionChainAcceptanceTest`：**1 run, 0 failures, 1 error**。异常根因为 `captureRequestScope` 抛出的“PLC4X Modbus TCP 原连接不可用，等待既有所有者恢复”（与需求中“原始连接未准备好”的描述对应，但实际源码消息不同）。原夹具已建立真实 PLC4X 连接，却绕过采集器 `doConnect()` 注入适配器，遗漏 `acceptingRequests`。只在夹具中断言 adapter 已连接并补齐准入状态；未添加 sleep、跳过协议连接检查或修改协议生产代码。修复后该测试 **1/0/0/0**。

`collector-protocol-modbus/src/test/java/com/wangbin/collector/core/collector/scheduler/WangbinPhase4ModbusSqliteChainTest.java` 使用相同的真实 FakeModbusTcpServer、PLC4X adapter、`ConnectionManager`、`CollectionManager`、采集器切面与 `DeviceBatchExecutor`。在 `ConfigManager.init()` 后经 `SqliteConfigService` 保存 A，手动 START 后按 ID 装载，使用真实调度批任务采集；只隔离遥测后处理的外部持久化副作用。两轮结果为 **generation 1 → 点值 101.0 / 质量 100 / 采样时间 1793322494591 / 请求 1；STOP 后旧批任务不可提交、请求数保持 1；generation 2 → 点值 202.0 / 质量 100 / 采样时间 1793322496747 / 请求 2**。两次采样时间非零、后者不早于前者，且真实 Modbus 从站实际收到两次读请求；最终 STOP 后 SQLite 配置仍在。测试 **1/0/0/0**。该证据不以“connected=true”代替点位值。`collector-protocol-modbus/pom.xml` 仅增加 SQLite 模块的 test-scope 依赖，以复用既有 FakeModbus 服务完成跨模块闭环，不改变运行时依赖。

## 历史生命周期失败与早期阶段回归

当前 `DeviceLifecycleCoordinatorTest`：**39 run, 1 failure, 0 errors, 0 skipped**；同名 `retiredCollectorMustFinishOwnedConnectBeforeReplacementCanUseDeviceConnection` 在阶段 03 Git archive 中单独执行为 **1 run, 1 failure, 0 errors, 0 skipped**。两个堆栈均在测试第 979 行：期望 mock `ConnectionManager.removeConnection("dev-owned-connect")`，实际该 mock 零交互；阶段 02 归档 `docs/sqlite-persistence/phase2/evidence/fresh-verification.json` 也记录同名失败（39/1/0/0）。生产所有者清理路径见 `CollectionManager.destroyRetiredOwnership`：`collector.destroy()`、`collector.releaseManagedConnection()`，仅在旧在途操作结束后释放所有权；该旧测试使用 mock collector 却期待管理器直接移除连接。阶段 04 新增真实 Modbus 重启/新代次验收通过，未见新增资源泄漏；不擅自改阶段 03 历史断言或无关连接实现。**不得把 runtime 全量说成全绿。**

阶段 01～03 关联回归以正式 Boot reactor 重跑旧归档列出的 ConfigManager、SQLite、应用 API、Web、Boot 配置测试：**294 run, 0 failures, 0 errors, 2 skipped**。另阶段 04 相关 reactor：runtime **65/0/0/0**，Modbus **2/0/0/0**，Boot **24/0/0/0**（其中 8 项为新增生命周期用例）。

## 阶段 05 遗留清单（本轮不改）

- `ConfigManager.init()` → `restorePersistedLocalTemporaryContexts()` → `restoreSqliteLocalConfigurations()`：已有 SQLite 配置在启动时仍被**全量**安装进运行缓存；该启动路径不属于按设备手动启动。测试按既有行为先初始化合法全集，之后才保存新设备或破坏 B，确保阶段 04 STOP 与手动 START 的隔离断言有效。
- `ConfigManager.loadAllConfig()` 仍会读取本地权威全集、清空并重装运行缓存；变更事件 `ALL`，或无 deviceId 的 `COLLECTION`，可触发该路径。若它在 STOP 后被调用，可能重新安装已停止设备的运行配置。阶段 04 不模拟这一阶段 05 全局刷新；需要在下一阶段界定运行缓存与持久配置的分离、回放以及 STOP 的交互，不提前删除 `init()` 或改造 `ConfigSyncService`。

所有读取/写入与 TCP 服务均限于 JUnit 隔离目录与自建 loopback 仿真器；未操作现役服务、正式数据库/Redis/AOF。`application.yml` 与 `.hermes.md` 保持原样，未纳入提交。GitHub push 和阶段 05 均留待用户后续批准。

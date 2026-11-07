# WangbinCollector 阶段 08 验收续跑主报告

**判定：`PHASE_08 = PARTIAL`。** GATE-1 的原始无参数命令曾因 Surefire fork 启动环境错误失败，但本轮按要求使用 `-Dsurefire.useManifestOnlyJar=false` 的完整 Maven 测试已通过；GATE-2 仍等待管理员最小只读补证，继续为 `BLOCKED`。已完成的 SQLite/Redis、协议契约和本次 TDengine/浏览器证据不因 GATE-2 缺项回退。本报告不是商业发布、硬件认证或 Redis 构件再分发许可。

- 分支 / 生产源码 HEAD：`feature_2.0` / `b24d6f870dd062844b901634a682531356811f88`。
- 本轮实际执行 JAR SHA-256：`65088cebfee45a189de64d371dfb386f131dc1dc13b8488f5df73d166b4d3a4b`。本地已有受保护的 `application.yml` 工作区修改；构件属于该工作区打包产物，**不声称它是纯净 HEAD 的逐字节构建**。启动时使用 scratch 中的独立外部配置，而非用户配置。
- 证据时间：2026-11-07，本机中国标准时间。以下“101→202”为同一 Boot JVM、同一设备和同一 SQLite 库上的连续浏览器操作，不拼接其他批次的运行轨迹。

## 1. 原始需求、既有验收及回归边界

| 阶段 | 已有结论与证据边界 |
|---|---|
| 01～03 | SQLite 独立配置库、MyBatis 隔离、修订/CAS 和配置 HTTP 已验；历史材料见 `collector-config-sqlite/VERIFICATION.md`、`docs/sqlite-persistence/phase3/VERIFICATION.md`。 |
| 04～05 | 手动 START/STOP、停止态不自动恢复、配置持久性及代次隔离；阶段 08 第二轮使用两个完整 Boot JVM 同库回读、`PRAGMA quick_check=ok`，见 Hermes scratch 的 `wangbin-phase08-round2-report.md` §3（历史独立轨迹，本轮不重跑）。 |
| 06 | 隔离 Redis Cache/Stream、故障期间本地采集前进及恢复后健康回读；见 `wangbin-phase08-round2-report.md` §4～5 与既有 Redis 验收回执，本轮未重新故障注入。 |
| 07 | Windows SCM 真实验收已关闭；本轮**没有**执行服务生命周期操作，新增管理员只读指纹门禁仍待核验。 |
| 08 | Runtime、BACnet、S7、IEC104 的已关闭测试契约批次保留；本轮新增启用 TDengine 的 Boot 实写读回和真实浏览器 101→202 闭环，管理员门禁仍阻断整体 PASS。 |

历史材料中未找到可独立复核完整命令及对应源码 SHA 的标准全量成功原始日志：`r2b-final-head-full-reactor.log` 为 2026-10-28 的旧基线，不可用它证明 `b24d6f8`。本轮先按授权执行无参数标准命令并保留其失败证据，随后按用户指定的临时 Surefire 兼容参数完成目标模块和完整 reactor 复核；两次运行均未跳过测试、未使用 `-fn` 或忽略错误。

### GATE-1：标准 Maven 全量测试与源码关联

| 项目 | 实际结果 |
|---|---|
| 执行前源码 HEAD | `80cf50595a0ec53da1f99eaa0fd2ada909547c93`；生产基线 `b24d6f870dd062844b901634a682531356811f88` |
| 执行前分支 / 工作区 | `feature_2.0`；`collector-boot/src/main/resources/application.yml` 有既有修改，`.hermes.md` 未跟踪；未覆盖、清理或暂存 |
| HEAD 相对生产基线 | 仅提交 `artifacts/phase08-final-acceptance.md`，无生产代码差异 |
| 命令 | `mvn.cmd -B -ntp test`（Maven 3.6.3，Java 17.0.15） |
| 开始 / 结束 | `2026-11-07T20:42:52+08:00` / `2026-11-07T20:44:17+08:00` |
| 原始日志 | `C:/Users/wangbin/AppData/Local/hermes/cache/scratch/phase08-gate1-maven-full-test-20261107.log` |
| 原始日志 SHA-256 | `75598fb170c7de70eaa8d8dbbc64ecd250576157a3493d1483fe79a571dbd259` |
| Maven 最终退出码 | `1` |
| 最终标记 | `BUILD FAILURE`，不是 `BUILD SUCCESS` |

Reactor 在 `collector-protocol-iot` 失败并停止后续模块。已完成模块的 Surefire XML 汇总为：`collector-common` 20/0/0/0、`collector-protocol-spi` 5/0/0/0、`collector-runtime` 322/0/0/0、`collector-config-sqlite` 154/0/0/2、`collector-telemetry` 150/0/0/1（格式为 tests/failures/errors/skipped，合计 651/0/0/3）。`collector-protocol-iot` 未形成此次运行的测试汇总；其 fork dump 为 `collector-protocol-iot/target/surefire-reports/2026-11-07T20-43-14_759.dumpstream`，指出 Surefire Boot Manifest-JAR 含绝对 classpath 路径并报告 `'other' has different root`，随后 fork VM 未正常启动。`collector-protocol-bacnet` 至 `collector-boot` 因 reactor 停止而 `SKIPPED`。因此，**原始无参数命令**的结果为 **BUILD FAILURE（环境启动错误，未宣称测试通过）**；未使用 `-fn`、`-DskipTests` 或忽略错误。兼容参数复核及其独立完整结果见下节。

此轮另有 `phase08-final-package.log` 的 `-DskipTests package` 成功记录，但它不是全量测试证据，不替代上述失败结果。

### GATE-1 兼容参数复核结果

本节是对上面原始无参数失败结果的补充，不改写其结论。目标模块命令先通过后，才执行完整 reactor：

```text
mvn.cmd -B -ntp -pl collector-protocol-iot -am test "-Dsurefire.useManifestOnlyJar=false"
mvn.cmd -B -ntp test "-Dsurefire.useManifestOnlyJar=false"
```

| 项目 | 目标模块复核 | 完整 reactor 复核 |
|---|---|---|
| 执行前源码 HEAD / 分支 | `a5309cf860d999d1cdfc63377794d9a9bc1b2535` / `feature_2.0` | 同左；工作区仍仅有既有 `application.yml` 修改和未跟踪 `.hermes.md`，均未覆盖、清理或暂存 |
| 执行时间 | `2026-11-07T21:40:40+08:00` ～ `2026-11-07T21:41:25+08:00` | `2026-11-07T21:42:47+08:00` ～ `2026-11-07T21:48:12+08:00` |
| 原始日志及 SHA-256 | `C:/Users/wangbin/AppData/Local/hermes/cache/scratch/phase08-gate1-iot-manifest-disabled-20261108.log`; `2006d669235b2df098806bec234dc78d58a752c538437f790921a2c9ad51ccf5` | `C:/Users/wangbin/AppData/Local/hermes/cache/scratch/phase08-gate1-full-manifest-disabled-20261108.log`; `66d539d1b35da3bc4b1e3aa59429976b605013066ac2fbe25447650a20ead201` |
| 最终退出码 / 标记 | `0` / `BUILD SUCCESS` | `0` / `BUILD SUCCESS` |
| Reactor 结果 | 目标模块及其依赖成功 | 17 个 reactor 项目全部 `SUCCESS`，无 `SKIPPED` 项目 |
| 测试统计 | 目标模块汇总 `134/0/0/1` | 全 reactor 汇总 `1839/0/0/4`（tests/failures/errors/skipped） |

Maven 3.6.3 使用 Java 17.0.15（`JAVA_HOME=D:\Program Files\Java\jdk-17`）；POM 实际 Surefire 版本为 `3.5.6`。本机当前 shell 的独立 `java -version` 为 21.0.7，但 Maven 日志和 Maven 自身版本信息确认本次测试 fork 使用的是 Java 17.0.15，不能把 shell 的 Java 21 当成本次测试运行时。Maven 本地仓库解析为 `D:\Program Files\Java\maven_jar`；工程、仓库、Java 临时目录 `C:\Users\wangbin\AppData\Local\Temp` 及 `collector-protocol-iot\target\surefire-reports` 的只读属性均为普通 `Directory`，未发现 `ReparsePoint`。本次兼容参数已绕过上轮 dumpstream 所示的 Manifest-JAR 绝对 classpath / `'other' has different root` fork 启动故障。

因此，GATE-1 可记录为 **PASS（使用 Surefire 兼容参数的完整 Maven 测试通过）**；这不等价于“原始 `mvn.cmd -B -ntp test` 无参数运行通过”，后者仍准确保留为 `BUILD FAILURE`。兼容测试日志只保存在仓库外 scratch，未将旧 XML 或 `-DskipTests package` 结果冒充本次全量结果。

## 2. 隔离资源、Mapper 装配与旧异常归因

开始前只读核对：39095 Boot、36512 Modbus、9337 Chrome/DevTools 均未监听；Docker 中既有 `wangbin-collector-r2b-redis-20261028`（ID 前缀 `8ea418cfdf28`，127.0.0.1:36379、具名数据卷）和 `wangbin-collector-r2b-tdengine-20261028`（`d6d1eef236f9`，127.0.0.1:36030/36041、具名数据卷）自 2026-10-28 创建且仍在运行。二者属于**更早的隔离验收**，不是本轮新建，不停止、不删除、不清卷。本轮仅在独立 SQLite 安装目录、TDengine 唯一库 `wb_p08_b673b6cba7`、Redis DB9 的独立前缀/Stream、127.0.0.1:39095 和 Modbus 36512 下运行；没有操作 R2-A2、REISUN、用户库或 SCM。

先前 `DataRepository.createDatabase` 的 `Invalid bound statement` 出现在旧 scratch 外部配置未声明 `mybatis.mapper-locations` 时。当前 JAR 的默认 `BOOT-INF/classes/application.yml` **包含** `classpath:mapper/**/*.xml`；JAR 内嵌 `collector-storage-0.0.1-SNAPSHOT.jar` **包含** `mapper/storage/DataRepository.xml` 等 Mapper XML；`WangbinConfigSqliteConfiguration.TdengineMybatisConfiguration` 将 `properties.resolveMapperLocations()` 交给 TDengine 专用 `SqlSessionFactoryBean`。`spring.config.location=file:...` 使用外部配置替代默认配置，故旧隔离模板漏配属于**A：隔离配置缺少正式 Mapper 前提**，不是仅凭该失败就认定 B。补上既有路径后，在同一 JAR 的启用态中由正式 Collector 写入并 SQL 读回下述记录；未修改生产代码或受保护的用户配置。

旧值 `140905675` 也已定位：原模拟器 FC03 寄存器为 `2150`、`3275`，32 位 `INT` 拼接为 `140905675`；不是本轮要求的 101。复用既有 FC03 模拟器的隔离包装后，FC03 原始四寄存器读回 `(0, 101, 0, 202)`；页面/后端的正式解码结果另行核对如下，**不**以模拟器设置代替实际采集证据。

## 3. 同一设备的真实浏览器闭环与 TDengine 实写

设备 A `7445c1a3-c20a-404b-9441-67d47e271063`；点位 `7a76d9cf-da92-4d80-90e9-57a2690a59fe`，编码 `phase08_value`，类型 `INT`。本轮以旧已创建的设备续跑，不冒充本轮新建。正式前端 `#/device` 的页面刷新后显示设备；未 START 时 `/api/device/runtime` 为 `STOPPED`、`running=false`、`connected=false`；SQLite 地址 `40001`，revision `3`。生产 JAR 中的前端静态文件与工作区 61 个文件逐字节一致。

| 浏览器操作 / 本轮读回 | 第一轮 | 第二轮 |
|---|---|---|
| 页面操作及 HTTP | 点击 START；`POST /api/device/{id}/start-local` 200；随后页面 STOP，`POST /api/device/{id}/stop` 200 | STOP 状态在页面编辑点位地址 `40001→40003`，保存（`PUT /api/config/local/device/{id}` 200，保存后立即启动未勾选）；随后再次页面 START 200、最终页面 STOP 200 |
| SQLite / 运行 | revision `3`；`generation=1` | 修改后 revision `4`、同一 pointId、地址 `40003`；修改后未 START 时 `STOPPED`、`running=false`、`connected=false`；启动 `generation=2`；最终停止后 `STOPPED`、连接释放、revision `4` 保留 |
| 前端与实时 HTTP | `#/realtime` 显示地址 `40001`、值 `101`、质量良好；`GET /api/data/realtime/compact` 200，`value=101.0`、`quality=100`、采样毫秒 `1794039638986` | 页面显示地址 `40003`、值 `202`、质量良好；实时 API 200，`value=202.0`、`quality=100`、采样毫秒 `1794039768846` |
| Redis Cache / 独立 Stream | `processedValue=101.0`、质量 `100`、sourceGeneration `1`；Stream 精确 deviceId/pointId，值 `101.0`、质量 `100` | Cache `202.0`、质量 `100`、sourceGeneration `2`；Stream 精确 deviceId/pointId，值 `202.0`、质量 `100` |
| TDengine `telemetry_super_v2` | 本次正式写入：`event_ts=1794039638991`，deviceId/pointId 匹配、`value_text=101.0`、质量 `100`、来源 `POLLING`、generation `1` | 本次正式写入：`event_ts=1794039768851`，同一身份、`value_text=202.0`、质量 `100`、来源 `POLLING`、generation `2` |

SQL 为只读查询 `SELECT event_ts, device_id, point_id, point_code, value_text, quality, metadata_json FROM wb_p08_b673b6cba7.telemetry_super_v2 WHERE device_id = <测试设备> AND point_id = <测试点位> ORDER BY ts DESC LIMIT 1`；已核对第二次采样时间大于第一次。历史残留库中本轮前已有记录，以本轮新时间窗口、设备/点位、质量和代次核对；**没有手工 INSERT** 冒充 Collector 写入，不宣称 exactly-once 或零丢失。两次采集的细节快照留于本地 scratch `phase08-final-b673b6cba7/evidence-101.json`、`evidence-202.json`；截图 `06-edit-40003-before-save.png`、`07-edit-40003-saved.png`、`04-realtime-initial.png`（后者为第二次实时页面）及 `03-stopped.png` 仅本地保存，不提交。

## 4. 管理员只读基线与最终资源状态

用户已在管理员 PowerShell 中重新执行候选脚本。原始脱敏回执仍为 `C:\Users\wangbin\AppData\Local\hermes\cache\scratch\wangbin-redis-phase07-real-20261103\ownership-readonly-audit-20261107-130323-005.json`，脚本报告 `Administrator=true`，最终 `Status=BLOCKED`，共有 9 项警告。本轮用户补充的只读摘要确认了 marker、两个 redis.conf 哈希及九项对象的时间/Owner/Protected/Reparse 元数据；但当前工作区和可读取附件中没有可核验的最新 JSON 文件路径或哈希，也没有逐条 DACL ACE 枚举。因此不能仅凭摘要将 GATE-2 标记 PASS。

已取得的管理员只读结果：`WangbinCollector-R2A2-Redis` 与 `WangbinCollector-R2A2-Collector` 均为 `Running`，服务账号分别为 `NT SERVICE\WangbinCollector-R2A2-Redis` 与 `NT SERVICE\WangbinCollector-R2A2-Collector`，注册路径均指向 `C:\Program Files\WangbinCollector-R2A2\20261027-083137\code\...`；Redis 进程 PID `56800`、父进程为 `55424`，监听 `127.0.0.1:26379`。与历史基线的服务 PID、状态、路径均匹配，PID `56800` 可见且可执行文件路径匹配，历史受保护监听存在。父目录及安装文件 ACL 已读取，文件哈希项也已计算；但脚本自身报告的标识和运行时文件警告尚未解释。

当前进程权限未再用于推断服务不存在；本次管理员执行已确认目标服务实际存在。GATE-2 当前状态为 **BLOCKED（审计警告未闭合）**，不是 `ADMINISTRATOR_REQUIRED`。

已确认候选脚本存在，完整读取 151 行；SHA-256 为 `1b274eb8e320fdcbb951f1967e64a358f76c07399f1325ba27dcba6001ded93c`。脚本只调用 Windows 身份、CIM、TCP listener、文件读取、SHA-256、ACL 读取和 scratch 报告写入；未发现 `Start-Service`、`Stop-Service`、`Restart-Service`、`Remove-Service`、`sc.exe`、`Stop-Process`、`taskkill`、`Set-Acl`、Docker 命令、下载/远程执行或删除文件操作。它的唯一写操作是将 JSON 结果写入 `C:\Users\wangbin\AppData\Local\hermes\cache\scratch\wangbin-redis-phase07-real-20261103\`，不是现役服务、配置、ACL 或数据库。脚本管理员不足时会在读取目标资源前抛出 `Administrator read-only access required`，不会自动提权。

本次管理员执行仍只写入 scratch JSON；未执行服务生命周期、进程操作、ACL 修改、Docker 操作或数据库修改。原始回执未入仓库。最新回执的 SHA-256 为 `bac52939dcc0f899b6094ac5cd448001b93283b1fe149aae11455388e55aa4a7`。

九项警告逐项归因如下（`NON-BLOCKING` 仅表示该警告本身已有充分证据解释，不代表 GATE-2 已通过）。用户补充的 marker/hash/time 元数据可支持 W-01～W-09 的对象归属和生命周期归因；DACL ACE allow-list 仍是独立的未闭合证据项：

| WarningId | 实际对象 | 归类 | 只读依据 | 是否单项阻断 |
|---|---|---|---|---|
| W-01 | `ownership.json` marker | `EXPECTED_POST_UNINSTALL`；审计规则对象不适配 | marker 为 `RegistrationState=Uninstalled`；仓库 `Uninstall-WangbinCollectorRedis` 将状态写为 `Uninstalled` 并保留目录/marker；当前审计 `TargetServiceCount=0`、`TargetPortListeners=0`，16379 目标服务已不存在 | 否 |
| W-02 | `redis.conf` marker hash | `HASH_MATCH`；审计比较对象错误 | ZIP `Redis-8.10.3-Windows-x64-msys2/redis.conf` 与 Program Files 发行文件均为 `d165f7eb487871012f057297f921a150f92bf5e6025a7b33024070fd0b80d3ac`；ProgramData 生成配置是另一对象，回执中 `GeneratedConfig` 实际/预期均为 `920e2a3b88c76df1e15491da9dd70861c0a091b05a913e0c29090f8d9d7f2a3c`。发行包静态文件与生成配置不能互相比较 | 否 |
| W-03 | `logs/WangbinCollector-Redis.err.log` | `EXPECTED_RUNTIME_ARTIFACT` | 位于专用 `DataRoot\logs`，Owner 为 `NT SERVICE\WangbinCollector-Redis`，无 `ReparsePoint` 属性；WinSW 生成配置声明 `logpath` 和 `roll-by-size`，既有阶段回执也记录过该文件 | 否 |
| W-04 | `logs/WangbinCollector-Redis.out.log` | `EXPECTED_RUNTIME_ARTIFACT` | 与 W-03 相同；属于 WinSW 运行时 stdout 日志，不是 Redis ZIP 静态发行文件 | 否 |
| W-05 | `data/dump.rdb` | `EXPECTED_RUNTIME_ARTIFACT` | 位于专用 `DataRoot\data`，Owner 为 Redis 服务账号；正式配置包含 `save 900 1`、`save 300 10`、`save 60 10000`，RDB 是预期运行产物 | 否 |
| W-06 | `data/appendonlydir` | `EXPECTED_RUNTIME_ARTIFACT` | 位于专用 Redis data 目录，Owner 为 Redis 服务账号；正式配置包含 `appendonly yes` 和 `appendfsync everysec`，AOF 目录是预期运行产物 | 否 |
| W-07 | `appendonly.aof.1.base.rdb` | `EXPECTED_RUNTIME_ARTIFACT` | 位于 W-06 目录，Owner/ACL 与 data 目录一致，非重解析路径；属于 Redis AOF base 文件 | 否 |
| W-08 | `appendonly.aof.1.incr.aof` | `EXPECTED_RUNTIME_ARTIFACT` | 位于 W-06 目录，Owner/ACL 与 data 目录一致，非重解析路径；属于 Redis AOF 增量文件 | 否 |
| W-09 | `appendonly.aof.1.manifest` | `EXPECTED_RUNTIME_ARTIFACT` | 位于 W-06 目录，Owner/ACL 与 data 目录一致，非重解析路径；属于 Redis AOF manifest 文件 | 否 |

W-01～W-09 均位于阶段 07 测试服务 A 的 `C:\Program Files\WangbinCollector\Redis` / `C:\ProgramData\WangbinCollector\Redis` 范围内，没有证据显示它们属于 R2-A2 的 `C:\Program Files\WangbinCollector-R2A2\20261027-083137` 目录。R2-A2 的两个服务、PID、父子关系、26379 listener 和历史服务指纹仍与基线匹配。补充摘要确认九项对象的 `LastWriteTimeUtc`、Owner、Protected 和 Reparse 字段已导出；但没有提供逐条 ACE 的身份、类型、继承标志、作用范围和权限集合，无法验证“仅 SYSTEM、Administrators 与 Redis Service SID”这一最小权限断言。

九项警告已有逐项归因。用户补充摘要确认：marker 为 `Uninstalled / WangbinCollector-Redis / 16379`；发行 `redis.conf` 的 marker 与实际 SHA-256 均为 `d165f7eb487871012f057297f921a150f92bf5e6025a7b33024070fd0b80d3ac`；生成 `redis.conf` 的 marker 与实际 SHA-256 均为 `920e2a3b88c76df1e15491da9dd70861c0a091b05a913e0c29090f8d9d7f2a3c`；九项对象的路径、Owner、创建时间、修改时间、Reparse 和 Protected 字段已补齐。当前仍缺少可核验的逐条 DACL ACE 证据（IdentityReference/SID、AccessControlType、FileSystemRights、继承标志和作用范围），以及该补充 JSON 的独立路径/SHA-256，故 GATE-2 继续为 `BLOCKED`。不以 marker/hash/time 元数据替代 ACE 核验，也不更新 marker、配置、ACL 或持久化文件。

如需补齐该最后证据，最小只读命令必须在既有输出基础上仅增加 `Get-Acl.Access` 的脱敏 ACE 字段，并将 JSON 文件路径和 SHA-256 一并记录；不读取配置正文、secret、RDB 或 AOF 内容，不修改任何资源：

```powershell
$root = 'C:\ProgramData\WangbinCollector\Redis'
$paths = @(
  'C:\Program Files\WangbinCollector\Redis\bin\redis.conf',
  "$root\config\redis.conf",
  "$root\logs\WangbinCollector-Redis.err.log",
  "$root\logs\WangbinCollector-Redis.out.log",
  "$root\data\dump.rdb",
  "$root\data\appendonlydir",
  "$root\data\appendonlydir\appendonly.aof.1.base.rdb",
  "$root\data\appendonlydir\appendonly.aof.1.incr.aof",
  "$root\data\appendonlydir\appendonly.aof.manifest"
)
$m = Get-Content -LiteralPath "$root\state\ownership.json" -Raw | ConvertFrom-Json
$items = @(
  foreach ($path in $paths) {
    $i = Get-Item -LiteralPath $path -Force -ErrorAction Stop
    $a = Get-Acl -LiteralPath $path -ErrorAction Stop
    $aces = @($a.Access | ForEach-Object {
      [pscustomobject]@{
        Identity = [string]$_.IdentityReference
        Type = [string]$_.AccessControlType
        Rights = [string]$_.FileSystemRights
        Inherited = [bool]$_.IsInherited
        Inheritance = [string]$_.InheritanceFlags
        Propagation = [string]$_.PropagationFlags
      }
    })
    [pscustomobject]@{
      Path = $path
      Kind = if ($i.PSIsContainer) { 'Directory' } else { 'File' }
      Reparse = [bool](($i.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)
      Owner = [string]$a.Owner
      Protected = [bool]$a.AreAccessRulesProtected
      CreationUtc = $i.CreationTimeUtc.ToString('o')
      LastWriteUtc = $i.LastWriteTimeUtc.ToString('o')
      Sha256 = if ($i.PSIsContainer) { $null } else { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
      Aces = $aces
    }
  }
)
[pscustomobject]@{
  MarkerState = $m.RegistrationState
  MarkerServiceName = $m.ServiceName
  MarkerPort = $m.Port
  RedisConfigSha256 = $m.RedisConfigSha256
  DistributionRedisConfSha256 = @($m.RedisDistributionFiles | Where-Object Name -eq 'redis.conf' | Select-Object -ExpandProperty Sha256)
  Items = $items
} | ConvertTo-Json -Depth 10
```

在逐条 ACE 回执及其文件身份取得前，GATE-2 继续保持 `BLOCKED`；不得为了让脚本返回 PASS 而更新 marker、改写哈希、删除运行时文件、清理数据、停止服务或修改 ACL。

最终经正式页面 STOP 后 API 核实 `running=false`、`connected=false`、`phase=STOPPED`；已按进程 PID、创建时间与 scratch 配置/脚本路径精确核对并终止本轮 Boot、模拟器及独立 Chrome。39095、36512、9337 均已无监听。旧 Redis/TDengine 容器保持原 ID 与运行状态，不执行 `FLUSHALL`/`FLUSHDB`、prune 或清库，scratch 原始日志/口令/SQLite 数据库未入仓库。`collector-boot/src/main/resources/application.yml` 保留既有工作区修改、`.hermes.md` 保留未跟踪状态；二者均未修改、暂存或提交。

**遗留与判定：** GATE-1 已通过“使用 Surefire 兼容参数的完整 Maven 测试”，原始无参数 `mvn.cmd -B -ntp test` 的 `BUILD FAILURE` 仍保留，不能改写为无参数通过。GATE-2 的 marker/hash/time/Owner/Protected/Reparse 补证已具备摘要，但当前缺少可核验的最新 JSON 文件身份及逐条 DACL ACE allow-list，无法证明运行时文件权限仅授予 SYSTEM、Administrators 和 Redis Service SID，因此继续为 `BLOCKED`。两个门禁未同时 PASS，因此 `PHASE_08 = PARTIAL`；已完成业务链路保持 `SQLITE_REDIS_INTEGRATION_ACCEPTANCE = PASS`，但不能据此宣布商业发布或真实硬件认证。浏览器和 TDengine 的本次真实闭环保持已通过结论；后续人工 push 与源码复核另行进行。

# WangbinCollector 阶段 08 验收续跑主报告

**判定：`PHASE_08 = PARTIAL`。** GATE-1 本轮标准 Maven 全量执行未通过（Surefire fork 启动环境错误），GATE-2 仍需管理员只读核验（`ADMINISTRATOR_REQUIRED`）。已完成的 SQLite/Redis、协议契约和本次 TDengine/浏览器证据不因这些证据门禁缺项回退。本报告不是商业发布、硬件认证或 Redis 构件再分发许可。

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

历史材料中未找到可独立复核完整命令及对应源码 SHA 的标准全量成功原始日志：`r2b-final-head-full-reactor.log` 为 2026-10-28 的旧基线，不可用它证明 `b24d6f8`。本轮因此按授权执行一次标准命令，结果如下。

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

Reactor 在 `collector-protocol-iot` 失败并停止后续模块。已完成模块的 Surefire XML 汇总为：`collector-common` 20/0/0/0、`collector-protocol-spi` 5/0/0/0、`collector-runtime` 322/0/0/0、`collector-config-sqlite` 154/0/0/2、`collector-telemetry` 150/0/0/1（格式为 tests/failures/errors/skipped，合计 651/0/0/3）。`collector-protocol-iot` 未形成此次运行的测试汇总；其 fork dump 为 `collector-protocol-iot/target/surefire-reports/2026-11-07T20-43-14_759.dumpstream`，指出 Surefire Boot Manifest-JAR 含绝对 classpath 路径并报告 `'other' has different root`，随后 fork VM 未正常启动。`collector-protocol-bacnet` 至 `collector-boot` 因 reactor 停止而 `SKIPPED`。因此 GATE-1 当前为 **BLOCKED/FAIL（环境启动错误，未宣称测试通过）**；未使用 `-fn`、`-DskipTests` 或忽略错误。

此轮另有 `phase08-final-package.log` 的 `-DskipTests package` 成功记录，但它不是全量测试证据，不替代上述失败结果。

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

当前进程权限只读检查为 `False`（`DESKTOP-IKHU04D\wangbin`），所以 R2-A2/REISUN 服务注册身份、服务 DACL、当前进程创建时间/父子关系、监听端口和历史文件目录 ACL 的**管理员完整比对未执行**，状态 `ADMINISTRATOR_REQUIRED`，不能从普通权限下的空结果推断服务不存在。

已确认候选脚本存在，完整读取 151 行；SHA-256 为 `1b274eb8e320fdcbb951f1967e64a358f76c07399f1325ba27dcba6001ded93c`。脚本只调用 Windows 身份、CIM、TCP listener、文件读取、SHA-256、ACL 读取和 scratch 报告写入；未发现 `Start-Service`、`Stop-Service`、`Restart-Service`、`Remove-Service`、`sc.exe`、`Stop-Process`、`taskkill`、`Set-Acl`、Docker 命令、下载/远程执行或删除文件操作。它的唯一写操作是将 JSON 结果写入 `C:\Users\wangbin\AppData\Local\hermes\cache\scratch\wangbin-redis-phase07-real-20261103\`，不是现役服务、配置、ACL 或数据库。脚本管理员不足时会在读取目标资源前抛出 `Administrator read-only access required`，不会自动提权。

在用户自行批准 UAC 后，仅执行以下唯一管理员只读命令（本轮未自动执行，也未尝试绕过 UAC；脚本 SHA 已在执行前核对）：

```powershell
Start-Process powershell.exe -Verb RunAs -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File "C:\Users\wangbin\AppData\Local\hermes\cache\scratch\phase07-ownership-readonly-audit.ps1"'
```

最终经正式页面 STOP 后 API 核实 `running=false`、`connected=false`、`phase=STOPPED`；已按进程 PID、创建时间与 scratch 配置/脚本路径精确核对并终止本轮 Boot、模拟器及独立 Chrome。39095、36512、9337 均已无监听。旧 Redis/TDengine 容器保持原 ID 与运行状态，不执行 `FLUSHALL`/`FLUSHDB`、prune 或清库，scratch 原始日志/口令/SQLite 数据库未入仓库。`collector-boot/src/main/resources/application.yml` 保留既有工作区修改、`.hermes.md` 保留未跟踪状态；二者均未修改、暂存或提交。

**遗留与判定：** GATE-1 本轮实际执行为 `BUILD FAILURE`，原因是 `collector-protocol-iot` 的 Surefire fork 启动环境错误；GATE-2 仍为 `ADMINISTRATOR_REQUIRED`，等待用户自行批准上述只读命令并提供脱敏摘要。两个证据门禁均未 PASS，因此 `PHASE_08 = PARTIAL`；已完成业务链路保持 `SQLITE_REDIS_INTEGRATION_ACCEPTANCE = PASS`，但不能据此宣布商业发布或真实硬件认证。浏览器和 TDengine 的本次真实闭环保持已通过结论；后续人工 push 与源码复核另行进行。

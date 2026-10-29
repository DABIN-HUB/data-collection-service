# 阶段 03：SQLite 持久修订与提交回执

本记录仅覆盖阶段 03；不修改启动全量恢复、采集调度、Redis/TDengine、Windows 服务或后续阶段。测试数据库位于 JUnit 临时目录；真实网络 HTTP 用隔离的 Spring Boot 上下文、`127.0.0.1` 随机端口和独立 SQLite。未部署到 9090，未操作 R2-A2。

## 版本与写入边界

- `persistedRevision` 来自 SQLite 设备记录，仅供本地编辑 `baseRevision` CAS。读取、运行状态变化和无变化写入不推进该修订；真正变更在同一 SQLite 写事务内比较期望修订与目标设备真实修订。
- `configVersion` / `baseVersion` 继续表示 JVM 运行配置代次。采集任务失效、配置事件以及远端配置仍使用运行版本，不以 SQLite revision 代替。旧客户端未提供 `baseRevision` 时，不允许以陈旧的 JVM 快照覆盖 SQLite：目标设备按 ID 从权威 SQLite 读取，写事务内对照记录修订。
- 本地创建、修改、删除、完整 Bundle、设备/连接/点位局部编辑和多设备导入复用 `ConfigManager` 的设备聚合事务；导入中任一修订冲突整体回滚。掩码秘密在锁内从权威设备配置恢复；外部修改吸收后重新建立运行代次，并保留配置事件与旧任务隔离。
- API 本地读取和保存增加独立字段 `persistedRevision` / `baseRevision`；旧 `configVersion` / `baseVersion` 不变。页面 409 保留草稿，自动重载不能覆盖点位草稿，显式“刷新配置”才重载。

## 回执矩阵

| 场景 | HTTP / machineCode | committed | reconciled | reloadRequired |
| --- | --- | --- | --- | --- |
| 请求参数在 SQL 前被拒绝 | 400 / `CONFIG_OPERATION_FAILED` | false | false | false |
| 写事务执行期或 COMMIT 前失败 | 503 / `CONFIG_PERSISTENCE_UNAVAILABLE` | false | false | false |
| 修订冲突 | 409 / `CONFIG_PERSISTENCE_CONFLICT` | false | false | true |
| 提交结果无法确认 | 503 / `CONFIG_COMMIT_UNCONFIRMED` | `UNKNOWN` | false | true |
| SQLite 已提交、正常运行态应用 | 200 / 正常业务成功 | 提交成功 | 应用成功 | false |
| SQLite 已提交、缓存恢复对账成功 | 200 / `CONFIG_COMMITTED_RECONCILED` | true | true | true |
| SQLite 已提交、缓存恢复对账失败 | 200 / `CONFIG_COMMITTED_RECOVERY_REQUIRED` | true | false | true |
| SQLite 已提交、事件发布失败 | 200 / `CONFIG_COMMITTED_APPLY_FAILED` | true | false | true |

`UNKNOWN` 不是回滚声明；客户端不得盲目重复提交，应先核对设备权威修订。部分旧接口只返回设备 ID，局部保存后的修订应从读取接口重新获得。

## 验证证据

- `WangbinSqliteConfigManagerCrudTest`：两个客户端同一版本、外部连接写入、无变化旧请求、整批回滚、局部 CRUD、重启、运行代次、提交后缓存和事件故障，以及删除后旧任务隔离；23 项通过。
- `WangbinPhase3ConfigHttpSqliteTest`：四项独立测试使用隔离 SQLite。经 MockMvc 验证 POST/GET/PUT/DELETE、Bundle、局部修改的实际 MVC 状态/JSON 与数据库修订；注入 SQL 触发器执行期失败、COMMIT 前故障、一次/连续缓存安装故障、事件发布故障。JDBC 代理分别在 COMMIT 执行前与实际执行后模拟 ACK 丢失，核对两种真实数据库结果都返回 `UNKNOWN` 且不重复保存；验证其他设备不受影响。另由真实 loopback 端口用 `HttpClient` 验证已提交事件故障的 HTTP 200/机器码、旧版本 HTTP 409 与读回修订。这个随机端口的最小 Spring 上下文并非生产 9090 部署或完整认证/UI 验收。
- SQLite reactor 定向覆盖全部 16 个测试类：148 项、跳过 2、失败 0（阶段 02 基线 143 项、跳过 2、失败 0；新增 5 项）。应用服务选集：196 项、失败 0；关联 Web：17 项、失败 0；Boot 配置及阶段 03 HTTP 选集：20 项、失败 0。`collector-boot -am` 联合选集构建成功。前端 `lint`、`stylelint`、`typecheck`、`build` 均 exit 0，79 个测试文件、636 项通过（限制 worker 为 4）。未运行 `build:web`，避免同步变更到 Boot 静态资源。
- 全量 runtime 测试：321 项、7 failures、1 error；与阶段 02 已记录的历史数量相同，不将其伪称本阶段通过，不为此修改不相关代码。完整应用启动、现役服务和真实数据库不在本阶段验收操作范围。

仅当上述关联检查、`git diff --check` 与保护文件范围审查通过时，才可按本阶段指令在本地提交；不自动 push。

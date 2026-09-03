# Task 01.1 Reliability Baseline

生成时间：2026-09-02 15:05:07 +0800

本文件记录 `Frontend Production Readiness / 01 API Contract & Runtime Reliability` 的可靠性基线。范围只包括发现和记录，不包含统一 Error Model、AbortController、WebSocket 架构或性能重构。

01.2A 更新：HTTP response boundary 已能显式区分 `ApiResult<T>` data 解包、raw DTO 保留和 envelope metadata 保留；DataController raw DTO、`DeviceRealtimeDataResponse.data` Map、`PointRealtimeResponse`、`getAllDeviceDataSummaries` 设备摘要与 realtime rows 分离、`getRunningDevices`、`isDeviceRunning` 已标记为 `RESOLVED IN 01.2A`。Realtime fan-out、request sequence/AbortController、WebSocket、PointEditor 性能风险未在 01.2A 修改。

01.2B 更新：`monitor.api.ts` 9 个 `/monitor/*` endpoint 已统一为 RAW DTO + 真实 Snapshot/DTO 类型；`config.api.ts` 20 个 endpoint 已补齐稳定 response type，其中 19 个走 `requestApiData<T>()`，`triggerFullConfigSync` 保留 command envelope。API 层 unknown 统计从 47 降到 19；剩余契约漂移集中在 control/device/edge/ops/point/shadow，Realtime P0、History P1、WebSocket、PointEditor 性能风险未在 01.2B 修改。

01.2C 更新：`device.api.ts`、`ops.api.ts`、`edge.api.ts`、`point.api.ts` 已按真实 Java Contract 收尾：Device 查询 API 改为 `requestApiData<T>()`，Device command API 改为 `requestEnvelope<null>()` 保留 message/deviceId；Ops 四个接口改为稳定 typed `ApiResult.data`，并停止把 `deviceId/thread` 当作后端日志查询参数；Edge 接口补齐稳定 request/response 类型但保留 telemetry value 动态；Point API 改为复用 `config.api.ts` 合同。

01.2D 更新：`control.api.ts` 与 `shadow.api.ts` 已完成最后一轮 contract closure：Control 三个写接口统一为 `requestApiData<T>()` 并补齐 `PointWriteRequest` / `PointWriteResultResponse` / `BatchPointWriteResponse` / `DeviceCommandResponse`；Shadow 五个接口统一为 `requestApiData<T>()` 并补齐 typed outer DTO，同时继续保留 `reported/desired/delta/metadata/history` 的动态文档边界。业务 API wrapper unknown 统计已从 19 降到 0，Task 01.2 Contract Typing 可以标记为 COMPLETE；Realtime stale response、Realtime N+1、Request Lifecycle、History partial failure、Alarm partial failure、WebSocket、PointEditor 性能风险仍未在 01.2D 修改。

## 1. 错误处理模式盘点

| 文件 / 区域 | 当前模式 | 分类 | 影响 |
|---|---|---|---|
| `src/api/http.ts` | `ApiResult` code/status 错误抛 `ApiRequestError`；Axios 无响应时转换网络/超时中文消息。 | PAGE_ERROR 基础设施 | API 层可抛出结构化错误，但页面层多数只消费 message。 |
| `src/views/realtime/RealtimeView.vue:193-195` | 全局实时列表失败只 `console.error(error)`，不写页面错误状态。 | SWALLOWED | 生产现场无法从页面判断实时刷新失败原因。 |
| `src/views/realtime/RealtimeView.vue:212-214` | 单点实时查询失败只 `console.error(error)`。 | SWALLOWED | 单点查询失败无可见错误。 |
| `src/views/dashboard/DashboardView.vue:269-278` | 多个 dashboard 请求 `Promise.allSettled`，未逐项记录失败；01.2B 后 monitor refs 改为 typed/null，但失败项仍显示未知/不可用。 | PARTIAL_FAILURE / SILENT_FALLBACK | 页面不会整体崩溃，但缺少失败接口明细。 |
| `src/views/diagnostic/DiagnosticView.vue:173-191` | 多指标 `Promise.allSettled`，失败项进入 `partialWarning`。 | PARTIAL_FAILURE / PAGE_ERROR | 这是当前较好的生产化模式，可作为后续参考。 |
| `src/views/history/HistoryView.vue:321-345` | 主历史、比较历史、关联告警在同一 try；任一失败清空历史/比较/告警。 | ALL_OR_NOTHING | 关联告警失败可能导致已成功历史查询也被清空。 |
| `src/views/alarm/AlarmView.vue:170-187` | 告警历史 + 确认状态串联；确认状态失败会进入 catch 并清空告警列表。 | ALL_OR_NOTHING | 告警历史成功但确认状态失败时，用户看不到告警历史。 |
| `src/views/log/LogView.vue:113-133` | 有 `error` 页面状态；自动/手动刷新共用 loading guard。 | PAGE_ERROR | 基本可观测，但并发刷新会被丢弃。 |
| `src/views/cloud/CloudView.vue:100-111` | 有 `error` 页面状态。 | PAGE_ERROR | 可观测。 |
| `src/views/network/NetworkView.vue:135-172` | 网络诊断失败会构造不可达结果并进入历史，同时 toast 错误。 | PAGE_ERROR / DEFENSIVE | 用户仍可导出失败结果，较适合诊断页面。 |
| `src/features/device/components/DeviceOperationShell.vue:147-157` | 实时预览失败静默置空。 | SILENT_FALLBACK | workbench 顶部可能只显示“未知”，无法区分无数据和请求失败。 |
| `src/components/device/DeviceConfigPanel.vue:292-393` | 协议配置/连接状态/实时行/差异各自有局部 error/message。 | PAGE_ERROR | 基本可观测。 |
| `src/features/point/components/PointEditor.vue:341-355` | 实时值加载失败写 `realtimeError`。 | PAGE_ERROR | 可观测。 |
| `src/features/point/components/PointEditor.vue:358-362` + `point.store.ts:82-93` | 保存失败写 store error，但 `savePoints()` 不额外 toast。 | PAGE_ERROR / TOAST_MISSING | 保存失败是否可见取决于模板是否展示 `pointStore.error`。 |
| `src/features/shadow/components/ShadowPanel.vue:153-155` | `读取全部` 使用 `Promise.allSettled`，单项内部自行写错误对象/toast。 | PARTIAL_FAILURE | 合理，影子、delta、history 可独立失败。 |
| `src/features/collection/components/ConfigOpsPanel.vue:97-99` | 初始化读取 typed sync status 仍 `.catch(() => undefined)`。 | SWALLOWED | 01.2B 只补 contract，初始同步状态失败仍无页面错误。 |
| `src/stores/device.store.ts:39-58` | 设备列表和 runtime 并行；runtime 失败被忽略，设备失败才进入 store error。 | PARTIAL_FAILURE | 设备列表可用优先，但运行态失败无明确提示。 |
| `src/stores/runtime.store.ts:33-47` | health/runtime 任一成功即 connected；两者都失败才写 error。 | PARTIAL_FAILURE | 合理，但没有记录单项失败明细。 |
| `src/stores/websocket-utils.ts:30-35` | WS payload JSON parse 失败返回空数组，无错误记录。 | SWALLOWED | WebSocket 解析错误不可观测。 |

## 2. Request Lifecycle 风险清单

| 严重级别 | 文件 | 函数 / 位置 | 风险 | 触发方式 | 后续处理建议 |
|---|---|---|---|---|---|
| P0 | `src/views/realtime/RealtimeView.vue` | `loadRealtime()` | 未捕获请求开始时的 `realtimeDeviceId`；请求过程中切换设备时，`loading` guard 会拒绝新请求，旧响应可能写入当前视图，甚至用新的 `realtimeDeviceId.value` 作为 fallback deviceId。 | 单设备模式下网络慢，用户从设备 A 切到设备 B。 | Task 01.2/01.3 引入 request sequence 或 AbortController，响应前校验 deviceId。 |
| P0 | `src/views/realtime/RealtimeView.vue` | all-device `loadRealtime()` + 5 秒 timer | 全设备模式每次刷新产生 `N + 1` 个 realtime HTTP 请求；设备数增加时请求线性放大。 | 默认自动刷新开启，设备数 N 较大。 | Task 01.2 先加请求生命周期/退避；后续考虑聚合端点或分批刷新。 |
| RESOLVED IN 01.2A | `src/views/realtime/RealtimeView.vue` | `getAllDeviceDataSummaries()` fallback | `/api/data/devices` 返回 `DeviceListResponse.devices`，01.1 时会经 `normalizeRealtimeRows()` 当实时行；01.2A 已改为 `extractRealtimeDeviceIds()` 只提取设备 ID，设备摘要不再 fallback 显示为实时点位行。 | 全设备 refresh 中某设备 `getDeviceRealtimeData` 失败。 | 后续仍需为失败设备增加可观测错误状态，但不再显示伪点位行。 |
| P1 | `src/views/realtime/RealtimeView.vue` | `loadSingleRealtime()` / route watcher | 单点请求无 sequence/abort，route point 变化后旧响应仍可覆盖 `realtimeSingleResult`。 | 从告警/Workbench 连续跳转不同点位。 | 加请求 token 并在响应前核对 deviceId/pointId。 |
| P1 | `src/components/realtime/RealtimeDataPanel.vue` | `watch(props.deviceId)` | deviceId 变化时立即重连 WS 和发 HTTP；旧 HTTP 响应无校验，可能覆盖新设备 fallback rows。 | Workbench 快速切换设备。 | 加 request sequence；WS close callback 也需要 generation guard。 |
| P1 | `src/stores/websocket.store.ts` | `connectRealtime()` callbacks | `socket` 是模块级单例，callback 捕获旧 `deviceId`；close/onmessage 无 socket generation 校验。旧 socket 的迟到 callback 可更新 store 状态或旧设备 rows。 | 快速切换设备或网络断开重连。 | 引入 socket generation/currentDevice guard；解析错误写入可观测字段。 |
| P1 | `src/views/history/HistoryView.vue` | `loadPoints()` / `loadHistory()` | 查询参数、deviceId、pointRef 在 await 后未校验；旧请求可能覆盖新路由的 points/history。 | route query 快速变化或设备切换。 | 加 sequence/abort；历史和关联告警拆成可部分失败。 |
| P1 | `src/views/alarm/AlarmView.vue` | `loadAlarms()` | filter 变化期间旧请求可写入新 filter 下的 alarms；确认状态失败会清空成功的告警历史。 | 用户快速切换 device/level/keyword。 | 加 sequence；确认状态失败时保留告警历史并显示部分失败。 |
| P1 | `src/components/device/DeviceConfigPanel.vue` | `loadProtocolConfig()` / `loadWorkbenchRows()` | props.device/protocolKey 变化时旧 response 无校验，可覆盖当前协议表单或点位运行行。 | Workbench 切设备、切 protocol、切 tab 时网络慢。 | 对 deviceId/protocolKey 建立请求快照校验。 |
| P1 | `src/features/device/components/DeviceOperationShell.vue` | `loadRealtimePreview()` | 失败静默置空；旧请求无 deviceId 校验。 | route query deviceId 变化或 refresh/clear 后。 | 写入 preview error，并校验请求 deviceId。 |
| P2 | `src/views/log/LogView.vue` | `loadLogs()` + 5 秒 timer | loading guard 会丢弃自动刷新/手动查询中的后发请求；query 变化时可能短暂显示旧日志。 | 自动刷新中修改过滤器或手动点击查询。 | 引入 latest-request wins；自动刷新被手动刷新暂停。 |
| P2 | `src/views/dashboard/DashboardView.vue` | `loadDashboard()` | 并行请求失败不暴露到卡片级错误；旧 dashboard refresh 也无 sequence。 | 刷新中再次点击或网络抖动。 | 可借鉴 Diagnostic partialWarning，并加 sequence。 |
| P2 | `src/views/diagnostic/DiagnosticView.vue` | `loadDiagnostic()` | 已有部分失败提示，但 route deviceId 变化只改 selected device，不取消正在生成的 raw snapshot。 | 诊断运行中切换 route query。 | 低优先级加 sequence。 |
| P2 | `src/stores/device.store.ts` | `refresh()` / `operate()` | 多个页面同时触发 refresh 时无 generation guard；最后返回者覆盖 devices/runtimeMap。 | Dashboard、DeviceList、Workbench 同时刷新或操作后刷新。 | Store 级 request sequence。 |
| P2 | `src/stores/point.store.ts` | `load()` / `save()` | 同一设备并发 load/save 无 sequence；保存后 reload 可能与手动刷新交错。 | PointEditor 中保存、刷新、切设备连续操作。 | per-device loading/sequence；保存期间禁止或排队 load。 |

## 3. Realtime 当前请求模型

### 单设备模式

- 入口：`RealtimeView.loadRealtime()` 中 `if (realtimeDeviceId.value)` 分支。
- 每次 refresh：`1` 个 HTTP 请求：`GET /api/data/device/{deviceId}`。
- 5 秒自动刷新：约 `1 / 5 = 0.2 req/s`。
- 风险：请求过程中切换设备时旧响应可能覆盖新设备展示，见 P0。

### 全设备模式

- 稳态每次 refresh：
  1. `GET /api/data/devices` 一次，获取设备摘要；
  2. 对 `deviceIds` 中每台设备并行请求 `GET /api/data/device/{deviceId}`。
- 稳态请求数量：`N + 1`，其中 `N` 是参与实时刷新的设备数。
- 首次进入页面时，`initializeRealtimeView()` 先执行 `deviceStore.refresh()`，额外触发 `GET /api/config/devices` 和 `GET /api/device/runtime`，因此首屏全设备路径理论请求数约 `N + 3`。
- 如果 `loadRealtime()` 内发现 `deviceStore.devices` 为空，还会先执行一次 `deviceStore.refresh()`，同样会增加 2 个请求。

### 5 秒自动刷新请求量

- 单设备：`0.2 req/s`。
- 全设备稳态：`(N + 1) / 5 req/s = 0.2N + 0.2 req/s`。
- 示例：`N=10` 时约 `2.2 req/s`；`N=100` 时约 `20.2 req/s`；所有请求在同一 refresh 内并行发起。

### row key

- `RealtimeView` 表格 key：``${row.deviceId || realtimeDeviceId}-${row.pointId || row.pointCode || row.address}``，对正常点位稳定。
- `websocket.store.ts` 的 `rowKey()` fallback 使用 `Math.random()`：当 WS 消息缺少 `pointId/pointCode/address` 时 key 不稳定，会导致无法合并同一行。

## 4. WebSocket 当前实现状态

| 问题 | 当前结论 |
|---|---|
| 哪些页面真正使用 websocket store | 只有嵌入组件 `src/components/realtime/RealtimeDataPanel.vue` 使用 `useWebSocketStore()` 并在挂载/设备变化时调用 `connectRealtime()`。 |
| `RealtimeView` 是否实际使用 | 不使用。主路由 `src/views/realtime/RealtimeView.vue` 只走 HTTP polling。 |
| 后端是否有 `/ws/realtime` | 未发现真实 Spring WebSocket endpoint。`collector-application/src/main/java/com/wangbin/collector/api/service/WebSocketService.java` 只是空预留类；搜索到的其他 WebSocket 多为采集协议/TDengine，不是控制台实时推送。 |
| WS URL | `buildRealtimeWebSocketUrl(serverUrl, deviceId)` 生成 `{serverUrl}/ws/realtime?deviceId=...`。 |
| 是否自动重连 | 否。`onerror/onclose` 只更新状态，不做重连。 |
| stale socket callback 风险 | 有。socket 为模块级单例，callbacks 捕获旧 deviceId，没有 generation guard。 |
| 解析错误是否可观测 | 否。`parseRealtimePayload()` JSON parse 失败直接返回 `[]`。 |
| HTTP 与 WS 当前关系 | 嵌入 `RealtimeDataPanel` 优先显示 WS rows，若 WS rows 为空则显示 HTTP rows；主 `RealtimeView` 与 WS 无关系。 |

## 5. PointEditor 性能基线

分析范围：`src/features/point/components/PointEditor.vue`、`src/stores/point.store.ts`、`src/features/point/utils/point-editor-utils.ts`、`point-excel-utils.ts`。

| 项目 | 当前实现 | 风险 |
|---|---|---|
| 大数组 computed | `points`、`selectedIds`、`filteredPoints`、`selectedPoint`、`runtimeMergedRows` 均为 computed。 | `filteredPoints` 每次 keyword/points 变化 O(P)；`selectedPoint` O(P)。通常可接受，但 P 大时需评估。 |
| runtime 合并 | `mergePointRuntime(points, realtimeRows)` 先用 runtime rows 建 Map，再 `points.map`，复杂度 O(P + R)。 | 合并本身合理。 |
| `runtimeOf(point)` | `runtimeOf` 对 `runtimeMergedRows.value.find(...)` 做线性查找。模板每行至少调用 3 次：当前值、质量、耗时；选中卡片也多次调用。 | 明确性能风险：表格渲染 P 行时可能退化为 O(P²)，点位数大时会卡顿。 |
| 每行模板调用函数 | `runtimeOf(row)`、`qualityType()`、`qualityText()`、`displayExtraValue()` 等在 table cell 中调用。 | `displayExtraValue()` 每次会 `buildPointExtraModel`，协议字段列较多时额外放大。 |
| deep watch | 未发现 `{ deep: true }` 的深 watch；主要 watch `props.deviceId` 和 `[selectedPointId, pointFields.length]`。 | 深 watch 风险低。 |
| CSV 导入 | `validatePointImportFile()` 限制 `.csv`、最大 1MB；`parsePointCsv()` 限制 2000 行。 | 解析在主线程，2000 行内可接受；预览表渲染 2000 行仍可能卡顿。 |
| CSV preview | `buildPointImportPreview()` 检查重复 pointCode/address，确认后一次性 replace。 | 合理；大 preview 可作为后续 UI 性能任务。 |
| 批量编辑 | `applyBatch()` 对 points 做一次 map，仅修改 selected ids。 | O(P)，可接受。 |
| 保存后 reload | `point.store.save()` 成功后 `await this.load(deviceId)`。 | 数据一致性好；但保存/刷新并发无 sequence，见 lifecycle P2。 |

结论：`runtimeOf(point)` 存在大点位数量下重复线性查找问题。Task 01.1 只记录，不引入 virtual table / worker / pagination。

## 6. Reliability Backlog

### P0（克制，仅生产不可接受风险）

1. `RealtimeView.loadRealtime()` 设备切换旧响应覆盖新设备展示，可能造成设备 A 数据显示在设备 B 上下文。
2. Realtime 全设备模式默认 5 秒刷新产生 `N + 1` HTTP 请求，设备数大时高频请求放大。
3. RESOLVED IN 01.2A：Realtime 全设备 fallback 不再把 `DeviceListResponse.devices` 设备摘要当作实时点位行显示；后续仍需补失败设备错误状态。

### P1

1. `RealtimeView.loadSingleRealtime()` route/query 快速变化时旧单点响应覆盖新结果。
2. `HistoryView.loadHistory()` 主历史、比较历史、关联告警 all-or-nothing；关联告警失败会清空成功历史。
3. `AlarmView.loadAlarms()` 告警历史成功但确认状态失败时会清空告警列表。
4. `DeviceConfigPanel` 协议配置/连接配置/实时行读取缺 request snapshot 校验。
5. `DeviceOperationShell.loadRealtimePreview()` 失败静默置空，且旧请求可覆盖新设备预览。
6. WebSocket store 无自动重连、无 generation guard、parse 错误不可观测。
7. `PointEditor.runtimeOf()` O(P²) 渲染风险。
8. RESOLVED IN 01.2B：`monitor.api.ts` 全 unknown 已清理，Dashboard/Diagnostic 获得 typed Monitor DTO 输入；页面级 partial failure 仍未改。

### P2

1. Dashboard 多指标失败缺卡片级错误明细。
2. Log 自动刷新与手动查询共享 loading guard，后发查询可能被跳过。
3. Device store 多页面并发 refresh 无 generation guard。
4. Point store 保存后 reload 与手动刷新可能交错。
5. ConfigOpsPanel 初始化 sync status 失败 `.catch(() => undefined)`，无提示。
6. RESOLVED IN 01.2C：`getOpsLogs` 现在只向后端发送 `level/logger/keyword/limit`；`deviceId/thread` 改为当前结果内本地过滤，并在日志页明确标注服务端能力边界。

## 7. Task 01.2 推荐修改范围 / 01.2A-01.2D 结果

Task 01.2 建议只处理“API 类型与真实响应边界”，不要进入 UI 重构。01.2A 已完成 response boundary 的核心修正：

1. RESOLVED IN 01.2A：`src/api/http.ts` 增加 `apiData` / `raw` / `envelope` response mode，错误校验与 `data` extraction 分离。
2. RESOLVED IN 01.2A：DataController 8 个接口使用 RAW DTO：`PointRealtimeResponse`、`DeviceRealtimeDataResponse`、`DeviceListResponse`、`DevicePointListResponse`、`AdaptiveResetResponse`、`HistoryDataResponse`、`AlarmHistoryDataResponse`。
3. RESOLVED IN 01.2A：`DeviceRealtimeDataResponse.data` 修正为 `Record<string, PointRealtimePayload>`；`normalizeRealtimeRows` primary path 明确为 DTO→`RealtimePointRow`。
4. RESOLVED IN 01.2A：`getRunningDevices` 返回 `string[]`；`isDeviceRunning` 使用 `envelope` 读取顶层 `running`，对调用方仍返回 boolean。
5. 保留：`LEGACY_COMPAT` normalizer 未删除，避免破坏历史响应兼容。
6. 未处理：AbortController、request sequence、WebSocket、Realtime fan-out、PointEditor `runtimeOf` 性能；这些进入 Task 01.3+。

01.2B 已完成 Monitor RAW Contract + Config Stable Contract：

1. RESOLVED IN 01.2B：`monitor.api.ts` 9 个 endpoint 全部使用 `requestRaw<T>()`，返回类型分别为 `ConsoleRuntimeStatusSnapshot`、`CacheMetricsSnapshot`、`DeviceStatusSnapshot`、`CollectorMetrics[]`、`SystemResourceSnapshot`、`ExceptionStatsSnapshot`、`CloudReportMetricsResponse`、`StorageMetricsSnapshot`、`PerformanceStatsSnapshot`。
2. RESOLVED IN 01.2B：`runtime.api.ts.getRuntimeStatus` 不再维护重复实现，改为 re-export `monitor.api.ts` 的同一 RAW wrapper。
3. RESOLVED IN 01.2B：`config.api.ts` 20 个 endpoint 已按 ConfigController 契约 typed；稳定 response 走 `requestApiData<T>()`，`triggerFullConfigSync` 作为 `ApiResult<null>` command envelope 保留 message。
4. RESOLVED IN 01.2B：`CollectionView` 配置摘要、`ConfigOpsPanel` sync/import 结果、`DeviceConfigPanel` 连接配置读取、`DashboardView` 主要 monitor refs 改为 typed access；diagnostic builders 与动态导入导出 helpers 保留 normalizer。
5. 剩余：control/device/edge/ops/point/shadow 的 response DTO 补齐；错误模型、请求生命周期和性能重构未开始。

01.2C 已完成 Device / Ops / Edge / Point Contract Closure：

1. RESOLVED IN 01.2C：`device.api.ts` 中 `getDeviceStatus`、`getAllDeviceStatistics`、`getRunningDevices`、`getDeviceRuntime` 已显式使用 `requestApiData<T>()`；`startDevice`、`startLocalDevice`、`stopDevice`、`reloadDevices` 改为 `requestEnvelope<null>()`，保留 command metadata。
2. RESOLVED IN 01.2C：`ops.api.ts` 四个接口全部改为稳定 typed `requestApiData<T>()`；`OpsLogResponse.items` 作为 primary contract，`logs/records/rows` 明确降级为 `LEGACY_COMPAT`。
3. RESOLVED IN 01.2C：日志页不再把 `deviceId/thread` 伪装成后端过滤条件；服务端真实支持 `level/logger/keyword/limit`，设备/线程改为前端本地过滤并增加说明文案。
4. RESOLVED IN 01.2C：`edge.api.ts` 返回 `EdgeTelemetryIngressResult`，请求补齐 `EdgeTelemetryBatchRequest` / `EdgeTelemetryItem` / `EdgeProtocolType`；动态 telemetry `value` 继续保留 `unknown`。
5. RESOLVED IN 01.2C：`point.api.ts` 不再维护第二套 points contract，改为复用 `config.api.ts` 的 `getDevicePointsConfig` / `updateDevicePointsConfig`。
6. 01.2C 完成后剩余仅限 `control.api.ts` 与 `shadow.api.ts` 的稳定 contract 收尾；Request Lifecycle、Realtime fan-out、WebSocket、PointEditor 性能风险仍未开始。

01.2D 已完成 Control + Shadow Contract Closure：

1. RESOLVED IN 01.2D：`control.api.ts` 的 `writeDevicePoint`、`writeDevicePoints`、`executeDeviceCommand` 已全部改为显式 `requestApiData<T>()`，并对齐 `PointWriteRequest` / `PointWriteResultResponse` / `BatchPointWriteFieldResponse` / `BatchPointWriteResponse` / `DeviceCommandRequest` / `DeviceCommandResponse`。
2. RESOLVED IN 01.2D：`PointWriteRequest.values` 的 key 语义已按 `DevicePointResolver` 与 `ControlCommandApplicationService.writePoints()` 对齐记录为 pointRef 解析链：`reportField → pointAlias → pointCode → pointId → pointName`；单点/批量写入的动态 value 继续保留 `unknown`。
3. RESOLVED IN 01.2D：`shadow.api.ts` 的 `getShadow`、`getShadowDelta`、`getShadowHistory`、`updateShadowDesired`、`clearShadowDesired` 已全部改为 typed `requestApiData<T>()`；`types/shadow.ts` 只强类型化 outer DTO，不硬编码 inner properties。
4. RESOLVED IN 01.2D：`ShadowDesiredUpdateRequest` 已按 `ShadowController` 对齐兼容 `state.desired` / `desired` / `properties` / `params` / 顶层业务字段，并保留 `source`、`shadowVersion`、`expectedVersion` 表达；未新增任何新功能或修改 reserved-key 规则。
5. RESOLVED IN 01.2D：`ControlPanel`、`ShadowPanel`、`control-utils.ts`、`shadow-utils.ts` 已改为消费 typed outer contract，但继续保留动态 JSON parse、`typeof` / `Array.isArray` defensive handling 与 history/document normalizer。
6. Task 01.2 Contract Typing COMPLETE：业务 API wrapper 的 `Promise<unknown>` 已清零；仅 `src/api/http.ts` transport 内部仍保留 generic `request<unknown>` / `requestRaw<unknown>`，不属于业务 contract 漏洞。
7. NEXT：Task 01.3 — Request Lifecycle Reliability，只处理 request sequence / AbortController、Realtime stale response、Realtime N+1 fan-out、partial-failure observability、WebSocket readiness；不要回头重做 01.2 的 contract typing。

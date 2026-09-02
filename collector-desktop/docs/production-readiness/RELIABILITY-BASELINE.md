# Task 01.1 Reliability Baseline

生成时间：2026-09-02 15:05:07 +0800

本文件记录 `Frontend Production Readiness / 01 API Contract & Runtime Reliability` 的可靠性基线。范围只包括发现和记录，不包含统一 Error Model、AbortController、WebSocket 架构或性能重构。

## 1. 错误处理模式盘点

| 文件 / 区域 | 当前模式 | 分类 | 影响 |
|---|---|---|---|
| `src/api/http.ts` | `ApiResult` code/status 错误抛 `ApiRequestError`；Axios 无响应时转换网络/超时中文消息。 | PAGE_ERROR 基础设施 | API 层可抛出结构化错误，但页面层多数只消费 message。 |
| `src/views/realtime/RealtimeView.vue:193-195` | 全局实时列表失败只 `console.error(error)`，不写页面错误状态。 | SWALLOWED | 生产现场无法从页面判断实时刷新失败原因。 |
| `src/views/realtime/RealtimeView.vue:212-214` | 单点实时查询失败只 `console.error(error)`。 | SWALLOWED | 单点查询失败无可见错误。 |
| `src/views/dashboard/DashboardView.vue:269-278` | 多个 dashboard 请求 `Promise.allSettled`，未逐项记录失败；失败项保持 `{}` 并显示未知/不可用。 | PARTIAL_FAILURE / SILENT_FALLBACK | 页面不会整体崩溃，但缺少失败接口明细。 |
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
| `src/features/collection/components/ConfigOpsPanel.vue:97-99` | 初始化读取 sync status `.catch(() => undefined)`。 | SWALLOWED | 初始同步状态失败无页面错误。 |
| `src/stores/device.store.ts:39-58` | 设备列表和 runtime 并行；runtime 失败被忽略，设备失败才进入 store error。 | PARTIAL_FAILURE | 设备列表可用优先，但运行态失败无明确提示。 |
| `src/stores/runtime.store.ts:33-47` | health/runtime 任一成功即 connected；两者都失败才写 error。 | PARTIAL_FAILURE | 合理，但没有记录单项失败明细。 |
| `src/stores/websocket-utils.ts:30-35` | WS payload JSON parse 失败返回空数组，无错误记录。 | SWALLOWED | WebSocket 解析错误不可观测。 |

## 2. Request Lifecycle 风险清单

| 严重级别 | 文件 | 函数 / 位置 | 风险 | 触发方式 | 后续处理建议 |
|---|---|---|---|---|---|
| P0 | `src/views/realtime/RealtimeView.vue` | `loadRealtime()` | 未捕获请求开始时的 `realtimeDeviceId`；请求过程中切换设备时，`loading` guard 会拒绝新请求，旧响应可能写入当前视图，甚至用新的 `realtimeDeviceId.value` 作为 fallback deviceId。 | 单设备模式下网络慢，用户从设备 A 切到设备 B。 | Task 01.2/01.3 引入 request sequence 或 AbortController，响应前校验 deviceId。 |
| P0 | `src/views/realtime/RealtimeView.vue` | all-device `loadRealtime()` + 5 秒 timer | 全设备模式每次刷新产生 `N + 1` 个 realtime HTTP 请求；设备数增加时请求线性放大。 | 默认自动刷新开启，设备数 N 较大。 | Task 01.2 先加请求生命周期/退避；后续考虑聚合端点或分批刷新。 |
| P0 | `src/views/realtime/RealtimeView.vue` | `getAllDeviceDataSummaries()` fallback | `/api/data/devices` 返回 `DeviceListResponse.devices`，当前用 `normalizeRealtimeRows()` 当实时行；当单设备详情失败时可能把设备摘要行显示在实时点位表。 | 全设备 refresh 中某设备 `getDeviceRealtimeData` 失败。 | Task 01.2 明确 all-device summary DTO 与 realtime row 的边界；失败设备应显示错误状态而不是伪点位行。 |
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
3. Realtime 全设备 fallback 可能把 `DeviceListResponse.devices` 设备摘要当作实时点位行显示，造成错误业务数据展示。

### P1

1. `RealtimeView.loadSingleRealtime()` route/query 快速变化时旧单点响应覆盖新结果。
2. `HistoryView.loadHistory()` 主历史、比较历史、关联告警 all-or-nothing；关联告警失败会清空成功历史。
3. `AlarmView.loadAlarms()` 告警历史成功但确认状态失败时会清空告警列表。
4. `DeviceConfigPanel` 协议配置/连接配置/实时行读取缺 request snapshot 校验。
5. `DeviceOperationShell.loadRealtimePreview()` 失败静默置空，且旧请求可覆盖新设备预览。
6. WebSocket store 无自动重连、无 generation guard、parse 错误不可观测。
7. `PointEditor.runtimeOf()` O(P²) 渲染风险。
8. `monitor.api.ts` 全 unknown，Dashboard/Cloud/Diagnostic 对重要指标缺类型保护。

### P2

1. Dashboard 多指标失败缺卡片级错误明细。
2. Log 自动刷新与手动查询共享 loading guard，后发查询可能被跳过。
3. Device store 多页面并发 refresh 无 generation guard。
4. Point store 保存后 reload 与手动刷新可能交错。
5. ConfigOpsPanel 初始化 sync status 失败 `.catch(() => undefined)`，无提示。
6. `getOpsLogs` 前端传 `deviceId/thread` 但后端 `OpsController.logs` 未接收，契约需明确。

## 7. Task 01.2 推荐修改范围

Task 01.2 建议只处理“API 类型与真实响应边界”，不要进入 UI 重构：

1. 为 stable backend DTO 补前端 API 类型：优先 `config.api.ts`、`monitor.api.ts`、`control.api.ts`、`shadow.api.ts`。
2. 修正不真实的 API 类型：`getDeviceRealtimeData`、`isDeviceRunning`、`getRunningDevices`。
3. 把 `DeviceRealtimeDataResponse.data` 从数组候选修正为后端真实 Map，并让 `normalizeRealtimeRows` 明确从真实 DTO 转 ViewModel。
4. 保留 `LEGACY_COMPAT` normalizer，但在类型和注释中说明兼容原因。
5. 暂不改 AbortController、WebSocket、Realtime fan-out、PointEditor 性能；这些进入 Task 01.3+。

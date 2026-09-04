# Task 01.1 Reliability Baseline

生成时间：2026-09-02 15:05:07 +0800

本文件记录 `Frontend Production Readiness / 01 API Contract & Runtime Reliability` 的可靠性基线。范围只包括发现和记录，不包含统一 Error Model、AbortController、WebSocket 架构或性能重构。

01.2A 更新：HTTP response boundary 已能显式区分 `ApiResult<T>` data 解包、raw DTO 保留和 envelope metadata 保留；DataController raw DTO、`DeviceRealtimeDataResponse.data` Map、`PointRealtimeResponse`、`getAllDeviceDataSummaries` 设备摘要与 realtime rows 分离、`getRunningDevices`、`isDeviceRunning` 已标记为 `RESOLVED IN 01.2A`。Realtime fan-out、request sequence/AbortController、WebSocket、PointEditor 性能风险未在 01.2A 修改。

01.2B 更新：`monitor.api.ts` 9 个 `/monitor/*` endpoint 已统一为 RAW DTO + 真实 Snapshot/DTO 类型；`config.api.ts` 20 个 endpoint 已补齐稳定 response type，其中 19 个走 `requestApiData<T>()`，`triggerFullConfigSync` 保留 command envelope。API 层 unknown 统计从 47 降到 19；剩余契约漂移集中在 control/device/edge/ops/point/shadow，Realtime P0、History P1、WebSocket、PointEditor 性能风险未在 01.2B 修改。

01.2C 更新：`device.api.ts`、`ops.api.ts`、`edge.api.ts`、`point.api.ts` 已按真实 Java Contract 收尾：Device 查询 API 改为 `requestApiData<T>()`，Device command API 改为 `requestEnvelope<null>()` 保留 message/deviceId；Ops 四个接口改为稳定 typed `ApiResult.data`，并停止把 `deviceId/thread` 当作后端日志查询参数；Edge 接口补齐稳定 request/response 类型但保留 telemetry value 动态；Point API 改为复用 `config.api.ts` 合同。

01.2D 更新：`control.api.ts` 与 `shadow.api.ts` 已完成最后一轮 contract closure：Control 三个写接口统一为 `requestApiData<T>()` 并补齐 `PointWriteRequest` / `PointWriteResultResponse` / `BatchPointWriteResponse` / `DeviceCommandResponse`；Shadow 五个接口统一为 `requestApiData<T>()` 并补齐 typed outer DTO，同时继续保留 `reported/desired/delta/metadata/history` 的动态文档边界。业务 API wrapper unknown 统计已从 19 降到 0，Task 01.2 Contract Typing 可以标记为 COMPLETE；Realtime stale response、Realtime N+1、Request Lifecycle、History partial failure、Alarm partial failure、WebSocket、PointEditor 性能风险仍未在 01.2D 修改。

01.3A 更新：`RealtimeView` 主查询与单点查询已改为 latest-request-wins：请求开始时捕获 `mode/deviceId/pointId` snapshot，并将“latest generation ownership”与“context commit eligibility”分离；业务结果/错误提交继续要求 generation + context 同时匹配，loading finalization 只依赖 latest generation ownership。设备切换、全设备/单设备切换、route 单点切换时，旧响应不再覆盖新上下文。`RealtimeDataPanel` 的 HTTP fallback 也补了相同 generation guard。`Realtime N + 1`、WebSocket generation/reconnect、History/Alarm/DeviceConfigPanel/Store lifecycle 仍保持 OPEN。

01.3B 更新：`HistoryView`、`AlarmView`、`DeviceConfigPanel`、`DeviceOperationShell` 的读请求已补 latest-request-wins：按页面/通道分别捕获 query 或 device snapshot，并把 generation ownership 与 commit eligibility 分离；stale success / stale error 不再写入新页面上下文，`finally` 只由 latest generation 释放 loading。`AlarmView` 的 acknowledgement fetch 已改为先 fetch 后按当前 query 统一 commit，`DeviceConfigPanel` 采用 protocol/status/workbench/diff 四个独立 owner，并修复 same-protocol device switch 仍需重新读取 B 设备连接配置的问题。`History` / `Alarm` partial failure、Realtime N+1、WebSocket generation/reconnect、device/point store lifecycle 仍保持 OPEN。

01.3C 更新：`device.store`、`point.store`、`protocol.store`、`runtime.store`、`LogView`、`DashboardView` 已完成 request lifecycle 收口：Store read refresh 改为 store-instance scoped generation，`point.store` 进一步收敛为 per-device read/write lifecycle；`LogView` 区分服务端 query 与本地 `device/thread` filter，timer 对相同 pending query 跳过但不会吞掉 changed server query，最近异常定位也补了独立 lookup owner；`DashboardView` 采用 refresh cycle generation，旧 cycle 的慢 metric、`lastRefresh` 和 `loading` 不再覆盖最新 cycle。`DiagnosticView` 已审计：当前只有 `onMounted` 与按钮触发完整诊断，按钮在 loading 时 disabled，route `deviceId` watcher 只同步选择设备、不触发第二个 diagnostic cycle，因此 `AUDITED / NO CHANGE`。至此 Task 01.3 Request Lifecycle Reliability 可标记为 COMPLETE；剩余 OPEN 仅属于 Realtime N+1 / WebSocket（Task 01.4）、Partial Failure（Task 01.5）和 PointEditor 性能（Task 01.6）。

01.3C-R1 更新：`PointEditor.loadRealtime()` 已补 component-instance scoped realtime owner，按 immutable `deviceId` snapshot 执行 `getDeviceRealtimeData()` 与 `normalizeRealtimeRows()`；设备切换时 stale success / stale error 不再污染新设备，`finally` 只由 latest request 释放 `realtimeLoading`，无设备与 unmount 场景也会 invalidate 旧请求。Task 01.3 Request Lifecycle Reliability 继续保持 COMPLETE；仍 OPEN 的仅有 Realtime N+1 / WebSocket、History/Alarm/Dashboard partial failure，以及 `PointEditor.runtimeOf()` O(P²) 性能问题。

## 1. 错误处理模式盘点

| 文件 / 区域 | 当前模式 | 分类 | 影响 |
|---|---|---|---|
| `src/api/http.ts` | `ApiResult` code/status 错误抛 `ApiRequestError`；Axios 无响应时转换网络/超时中文消息。 | PAGE_ERROR 基础设施 | API 层可抛出结构化错误，但页面层多数只消费 message。 |
| `src/views/realtime/RealtimeView.vue` | RESOLVED IN 01.3A：主实时查询当前请求失败会写 `realtimeError`，并保留最后一次成功数据；stale 请求静默丢弃。 | PAGE_ERROR + LATEST_REQUEST_WINS | 生产现场能区分“最新刷新失败”和“旧请求被丢弃”。 |
| `src/views/realtime/RealtimeView.vue` | RESOLVED IN 01.3A：单点实时查询当前请求失败会写 `singleRealtimeError`；旧请求不会覆盖当前点位结果。 | PAGE_ERROR + LATEST_REQUEST_WINS | 单点查询失败不再只剩控制台日志。 |
| `src/views/dashboard/DashboardView.vue` | RESOLVED IN 01.3C：dashboard refresh cycle 已补 generation guard；旧 cycle 的 metric、`lastRefresh` 与 `dashboardLoading` 不再覆盖最新 cycle。失败项仍显示未知/不可用，未拆卡片级提示。 | PARTIAL_FAILURE / LATEST_REQUEST_WINS | stale metric 已解决；partial failure visibility 仍留给 Task 01.5。 |
| `src/views/diagnostic/DiagnosticView.vue` | AUDITED IN 01.3C / NO CHANGE：多指标 `Promise.allSettled` 失败项进入 `partialWarning`；页面只有 `onMounted` 与按钮触发完整诊断，按钮在 loading 时 disabled，route `deviceId` watcher 只同步选择设备。 | PARTIAL_FAILURE / PAGE_ERROR | 当前没有真实 overlapping diagnostic cycle；device store refresh race 已由 store 层治理。 |
| `src/views/history/HistoryView.vue` | RESOLVED IN 01.3B：`loadPoints()` / `loadHistory()` / `loadRelatedAlarms(snapshot)` 已改为 latest-request-wins + snapshot commit guard；旧设备/旧点位/旧 route 查询结果与错误不会再污染当前页面。主历史、比较历史、关联告警仍保持同一 try 的 all-or-nothing。 | LATEST_REQUEST_WINS + ALL_OR_NOTHING | stale query 已解决；partial failure 仍留给 Task 01.5。 |
| `src/views/alarm/AlarmView.vue` | RESOLVED IN 01.3B：`loadAlarms()` 与 `refreshAlarmAcknowledgements()` 已改为 latest-request-wins；ack fetch 先返回结果再由当前 query/list 统一 commit，不再隐式全局写状态。告警历史 + ack 仍保持当前 all-or-nothing。 | LATEST_REQUEST_WINS + ALL_OR_NOTHING | stale query / stale ack refresh 已解决；ack 失败仍会使当前最新查询失败，留给 Task 01.5。 |
| `src/views/log/LogView.vue` | RESOLVED IN 01.3C：服务端 query（`level/logger/keyword/limit`）已补 latest-request-wins；`deviceId/thread` 保持当前结果内本地过滤，timer 对同 query pending 会跳过。 | PAGE_ERROR + LATEST_REQUEST_WINS | changed server query 不再被旧 loading 吞掉，exception lookup 也不会覆盖用户后改的查询条件。 |
| `src/views/cloud/CloudView.vue:100-111` | 有 `error` 页面状态。 | PAGE_ERROR | 可观测。 |
| `src/views/network/NetworkView.vue:135-172` | 网络诊断失败会构造不可达结果并进入历史，同时 toast 错误。 | PAGE_ERROR / DEFENSIVE | 用户仍可导出失败结果，较适合诊断页面。 |
| `src/features/device/components/DeviceOperationShell.vue` | RESOLVED IN 01.3B：实时预览已按 `selectedDeviceId` snapshot + latest generation guard 提交；当前设备失败仍维持置空，stale failure 静默丢弃。 | LATEST_REQUEST_WINS + SILENT_FALLBACK | 串台已解决；失败 UX 仍保持当前轻量模式。 |
| `src/components/device/DeviceConfigPanel.vue` | RESOLVED IN 01.3B：协议配置、连接状态、workbench rows、diff 已拆成独立 owner；device/protocol snapshot 不匹配的旧响应不会再写入当前面板。 | PAGE_ERROR + LATEST_REQUEST_WINS | same-protocol device switch 也会重新读取当前设备连接配置。 |
| `src/features/point/components/PointEditor.vue` | RESOLVED IN 01.3C-R1：`loadRealtime()` 已按 component-instance latest-request-wins + immutable `deviceId` snapshot 保护；stale success / stale error 不再污染当前设备，latest request 才能释放 `realtimeLoading`。 | PAGE_ERROR + LATEST_REQUEST_WINS | PointEditor 实时值在设备切换与卸载场景下不再串台。 |
| `src/features/point/components/PointEditor.vue` + `src/stores/point.store.ts` | RESOLVED IN 01.3C：PointEditor 已改为按当前 `deviceId` 读取 `point.store` 的 `loading/saving/error`，保存失败仍走 store error，不额外 toast。 | PAGE_ERROR / DEVICE_SCOPED_STATE | 其他设备后台 load/save 不再锁住当前编辑器；failure UX 仍保持当前轻量模式。 |
| `src/features/shadow/components/ShadowPanel.vue:153-155` | `读取全部` 使用 `Promise.allSettled`，单项内部自行写错误对象/toast。 | PARTIAL_FAILURE | 合理，影子、delta、history 可独立失败。 |
| `src/features/collection/components/ConfigOpsPanel.vue:97-99` | 初始化读取 typed sync status 仍 `.catch(() => undefined)`。 | SWALLOWED | 01.2B 只补 contract，初始同步状态失败仍无页面错误。 |
| `src/stores/device.store.ts` | RESOLVED IN 01.3C：`refresh()` 已补 store-instance generation；latest refresh 统一裁决 `devices/runtimeMap/error/loading`，并继续保留 runtime 成功 + device 失败时的 partial semantics。 | PARTIAL_FAILURE + LATEST_REQUEST_WINS | stale refresh 已解决；partial failure observability 未扩展。 |
| `src/stores/point.store.ts` | RESOLVED IN 01.3C：`load/save` 已改为 per-device lifecycle：同设备 latest-wins，不同设备并发互不影响；保存后 reload 自动进入该设备新的 read generation。 | PAGE_ERROR + DEVICE_SCOPED_LIFECYCLE | save 保持副作用语义，PointEditor 现按设备读取状态。 |
| `src/stores/protocol.store.ts` | RESOLVED IN 01.3C：`refresh()` 使用 store-instance generation，`loadFields(protocol)` 使用 per-protocol generation。 | PAGE_ERROR + LATEST_REQUEST_WINS | 协议列表与字段读取不再被旧请求覆盖。 |
| `src/stores/runtime.store.ts` | RESOLVED IN 01.3C：`refresh()` 使用 store-instance generation，`health/runtime/connected/error/lastUpdatedAt/loading` 只由 latest refresh cycle 控制。 | PARTIAL_FAILURE + LATEST_REQUEST_WINS | 仍保留“单项成功即 connected”的当前语义。 |
| `src/stores/websocket-utils.ts:30-35` | WS payload JSON parse 失败返回空数组，无错误记录。 | SWALLOWED | WebSocket 解析错误不可观测。 |

## 2. Request Lifecycle 风险清单

| 严重级别 | 文件 | 函数 / 位置 | 风险 | 触发方式 | 后续处理建议 |
|---|---|---|---|---|---|
| RESOLVED IN 01.3A | `src/views/realtime/RealtimeView.vue` | `loadRealtime()` | 主实时查询已捕获 `mode/deviceId` snapshot，并使用独立 generation 控制 latest-request-wins；设备切换不会再被旧 `loading` 阻塞，旧响应也不会再覆盖新设备或全设备上下文。 | 单设备模式下网络慢，用户从设备 A 切到设备 B，或单设备/全设备来回切换。 | 已通过 race tests 验证；后续只处理 N+1 与更广页面 lifecycle。 |
| P0 | `src/views/realtime/RealtimeView.vue` | all-device `loadRealtime()` + 5 秒 timer | 全设备模式每次刷新产生 `N + 1` 个 realtime HTTP 请求；设备数增加时请求线性放大。 | 默认自动刷新开启，设备数 N 较大。 | Task 01.2 先加请求生命周期/退避；后续考虑聚合端点或分批刷新。 |
| RESOLVED IN 01.2A | `src/views/realtime/RealtimeView.vue` | `getAllDeviceDataSummaries()` fallback | `/api/data/devices` 返回 `DeviceListResponse.devices`，01.1 时会经 `normalizeRealtimeRows()` 当实时行；01.2A 已改为 `extractRealtimeDeviceIds()` 只提取设备 ID，设备摘要不再 fallback 显示为实时点位行。 | 全设备 refresh 中某设备 `getDeviceRealtimeData` 失败。 | 后续仍需为失败设备增加可观测错误状态，但不再显示伪点位行。 |
| RESOLVED IN 01.3A | `src/views/realtime/RealtimeView.vue` | `loadSingleRealtime()` / route watcher | 单点查询已使用独立 generation，并在响应前核对 `deviceId/pointId` snapshot；旧响应不会覆盖当前 `realtimeSingleResult`。 | 从告警/Workbench 连续跳转不同点位。 | 已通过 route-style race tests 验证；后续如需 Browser abort 可再加资源优化。 |
| RESOLVED IN 01.3A | `src/components/realtime/RealtimeDataPanel.vue` | `watch(props.deviceId)` 的 HTTP fallback | Workbench 嵌入实时面板的 HTTP fallback 已补 `panel` 级 generation guard；旧 HTTP 响应不会再覆盖新的 `props.deviceId` 上下文。 | Workbench 快速切换设备。 | 本次只处理 HTTP fallback stale response；WebSocket close/onmessage generation 仍保持 OPEN。 |
| P1 | `src/stores/websocket.store.ts` | `connectRealtime()` callbacks | `socket` 是模块级单例，callback 捕获旧 `deviceId`；close/onmessage 无 socket generation 校验。旧 socket 的迟到 callback 可更新 store 状态或旧设备 rows。 | 快速切换设备或网络断开重连。 | 引入 socket generation/currentDevice guard；解析错误写入可观测字段。 |
| RESOLVED IN 01.3B | `src/views/history/HistoryView.vue` | `loadPoints()` / `loadHistory()` | `deviceId/pointRef/compare/start/end/limit` 已按 snapshot + generation 校验；route/device 快速变化时旧请求不会再覆盖当前 points/history。 | route query 快速变化或设备切换。 | partial failure 仍保持 OPEN，不在 01.3B 处理。 |
| RESOLVED IN 01.3B | `src/views/alarm/AlarmView.vue` | `loadAlarms()` / `refreshAlarmAcknowledgements()` | filter snapshot、ack list snapshot 与 latest generation 已生效；changed-context query 不再被旧 loading 吞掉。 | 用户快速切换 device/level/keyword。 | partial failure 仍保持 OPEN，不在 01.3B 处理。 |
| RESOLVED IN 01.3B | `src/components/device/DeviceConfigPanel.vue` | `loadProtocolConfig()` / `loadConnectionStatus()` / `loadWorkbenchRows()` / `showDiff()` | props.device/protocolKey 变化后的旧 response 不再覆盖当前协议表单、状态、点位行或 diff。 | Workbench 切设备、切 protocol、切 tab 时网络慢。 | store lifecycle 与更大范围请求治理留给后续任务。 |
| RESOLVED IN 01.3B | `src/features/device/components/DeviceOperationShell.vue` | `loadRealtimePreview()` | `selectedDeviceId` snapshot 校验已生效；旧 preview success/failure 均不会再污染当前设备。 | route query deviceId 变化或 refresh/clear 后。 | preview failure UX 保持当前模式。 |
| RESOLVED IN 01.3C | `src/views/log/LogView.vue` | `loadLogs()` + 5 秒 timer | 服务端日志查询已改为 latest-request-wins；timer 只跳过相同 pending query，不再吞掉 changed server query。 | 自动刷新中修改 level/logger/keyword/limit 或手动点击查询。 | local-only `device/thread` 过滤保持无后端请求。 |
| RESOLVED IN 01.3C | `src/views/dashboard/DashboardView.vue` | `loadDashboard()` | dashboard refresh cycle 已有 generation；旧 cycle 的慢 metric 不再覆盖最新 cycle。 | `onMounted()` 与 `handleLocalSaved()` 交错，或手动刷新与慢接口并发。 | partial failure visibility 仍保持 OPEN，不在 01.3C 处理。 |
| AUDITED IN 01.3C / NO CHANGE | `src/views/diagnostic/DiagnosticView.vue` | `loadDiagnostic()` | 页面当前没有真实 overlapping trigger：按钮 loading 时 disabled，route watcher 不触发 `loadDiagnostic()`。 | 只会存在单次完整诊断周期；route 改变仅同步选中设备。 | 保持现状；后续如新增并发触发源再加 owner。 |
| RESOLVED IN 01.3C | `src/stores/device.store.ts` | `refresh()` / `operate()` | 多页面并发 refresh 已补 generation guard，旧 refresh 不再覆盖 latest `devices/runtimeMap/error/loading`。 | Dashboard、DeviceList、Workbench 同时刷新或操作后刷新。 | write 操作保持原语义，仅后续 refresh 参与 generation。 |
| RESOLVED IN 01.3C | `src/stores/point.store.ts` | `load()` / `save()` | 同设备 latest-wins 与 per-device loading/error/saving 已落地；save 后 reload 与 manual load 时序均已覆盖测试。 | PointEditor 中保存、刷新、切设备连续操作。 | PointEditor runtime 性能不在本阶段处理。 |
| RESOLVED IN 01.3C-R1 | `src/features/point/components/PointEditor.vue` | `loadRealtime()` | PointEditor 实时值读取已补 `deviceId` snapshot、component owner 与 unmount invalidate；旧请求不会再覆盖新设备 realtime rows 或错误。 | PointEditor 中快速切换设备、清空设备或组件卸载。 | 保持 `runtimeOf()` 等性能实现不变，后续单独处理 01.6。 |
| RESOLVED IN 01.3C | `src/stores/protocol.store.ts` | `refresh()` / `loadFields()` | 协议列表 refresh 与同 protocol 字段读取已补 generation；不同 protocol 字段读取彼此独立。 | 多页面并发 refresh 或重复展开同协议字段。 | 缓存结构保持 `fieldsByProtocol`，未引入 TTL。 |
| RESOLVED IN 01.3C | `src/stores/runtime.store.ts` | `refresh()` | latest refresh 已统一裁决 `health/runtime/connected/error/lastUpdatedAt/loading`。 | 登录页与其他入口连续触发 runtime refresh。 | partial success 语义保持不变。 |

## 3. Realtime 当前请求模型

### 单设备模式

- 入口：`RealtimeView.loadRealtime()` 中 `if (realtimeDeviceId.value)` 分支。
- 每次 refresh：`1` 个 HTTP 请求：`GET /api/data/device/{deviceId}`。
- 5 秒自动刷新：约 `1 / 5 = 0.2 req/s`。
- 风险：请求过程中切换设备时旧响应可能覆盖新设备展示，见 P0。

### 全设备模式

- 稳态每次 refresh：
  1. `GET /api/data/realtime` 一次，返回全部设备的实时点位聚合结果；
  2. 前端按 `devices[].deviceId` 展开并归一化为 `RealtimePointRow[]`。
- 稳态请求数量：`1`，不再随设备数 `N` 线性增长。
- 首次进入页面时，`initializeRealtimeView()` 仍会先执行 `deviceStore.refresh()`，额外触发 `GET /api/config/devices` 和 `GET /api/device/runtime`；但全设备 realtime transport 本身已收敛为单次 `GET /api/data/realtime`。
- 聚合接口只解决 HTTP request count；响应 payload 与前端归一化处理仍随总点位数 `P` 增长，复杂度为 `O(P)`。

### 5 秒自动刷新请求量

- 单设备：`0.2 req/s`。
- 全设备稳态：`1 / 5 req/s = 0.2 req/s`。
- `N=10`、`N=100` 等不同设备规模下，请求数量都保持 `O(1)`；变化的是响应 payload 与点位处理量，不是 HTTP 次数。

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
| 保存后 reload | `point.store.save()` 成功后 `await this.load(deviceId)`。 | 数据一致性好；save/load lifecycle 已在 01.3C 收口，当前剩余问题是 `runtimeOf()` 的 O(P²) 渲染成本。 |

结论：`runtimeOf(point)` 存在大点位数量下重复线性查找问题。Task 01.1 只记录，不引入 virtual table / worker / pagination。

## 6. Reliability Backlog

Task 01.3 Request Lifecycle Reliability COMPLETE：与 stale read / refresh ownership 直接相关的 backlog 已在 01.3A / 01.3B / 01.3C 收口。以下仍 OPEN 项仅属于 01.4 Realtime Reliability、01.5 Partial Failure 和 01.6 PointEditor Performance。

### P0（克制，仅生产不可接受风险）

1. RESOLVED IN 01.3A：`RealtimeView.loadRealtime()` 设备切换/全设备切换的旧响应覆盖问题已修复，主实时查询改为 latest-request-wins。
2. RESOLVED IN 01.4A：Realtime 全设备模式改为 `1 × GET /api/data/realtime` 聚合查询，HTTP 请求数不再随设备数 `N` 线性增长；后端查询优先走单轮 `cacheManager.getAll(...)` 批量聚合。
3. RESOLVED IN 01.2A：Realtime 全设备 fallback 不再把 `DeviceListResponse.devices` 设备摘要当作实时点位行显示；后续仍需补失败设备错误状态。

### P1

1. RESOLVED IN 01.3A：`RealtimeView.loadSingleRealtime()` route/query 快速变化时的旧单点响应覆盖问题已修复。
2. RESOLVED IN 01.3B：`HistoryView.loadPoints()` / `loadHistory()` 已补 query snapshot、latest-request-wins 和 changed-context submit。
3. `HistoryView` 主历史、比较历史、关联告警仍保持 all-or-nothing；关联告警失败会清空成功历史。
4. RESOLVED IN 01.3B：`AlarmView.loadAlarms()` 与 `refreshAlarmAcknowledgements()` 已补 filter/list snapshot、latest-request-wins 和 ack fetch 后统一 commit。
5. `AlarmView` 当前最新查询里，告警历史成功但确认状态失败时仍会清空告警列表。
6. RESOLVED IN 01.3B：`DeviceConfigPanel` 协议配置/连接状态/实时行/diff 读取已补 request snapshot 校验，并拆为独立 owner。
7. RESOLVED IN 01.3B：`DeviceOperationShell.loadRealtimePreview()` 旧请求覆盖问题已修复；stale failure 不再清空新设备预览。
8. RESOLVED IN 01.3C：`device.store.refresh()`、`point.store.load/save()`、`protocol.store.refresh()/loadFields()`、`runtime.store.refresh()` 已完成 generation ownership 修复。
9. RESOLVED IN 01.3C：`LogView.loadLogs()` 与 `DashboardView.loadDashboard()` 已完成 latest cycle / latest query ownership 修复；`DiagnosticView` 已审计为 `AUDITED / NO CHANGE`。
10. RESOLVED IN 01.3C-R1：`PointEditor.loadRealtime()` 设备切换 stale success/error/loading ownership 已完成收口。
11. WebSocket store 无自动重连、无 generation guard、parse 错误不可观测；`RealtimeDataPanel` 仅 HTTP fallback stale response 已在 01.3A 修复，WS 本身仍未处理。
12. `PointEditor.runtimeOf()` O(P²) 渲染风险。
13. RESOLVED IN 01.2B：`monitor.api.ts` 全 unknown 已清理，Dashboard/Diagnostic 获得 typed Monitor DTO 输入；页面级 partial failure 仍未改。

### P2

1. Dashboard 多指标失败缺卡片级错误明细。
2. ConfigOpsPanel 初始化 sync status 失败 `.catch(() => undefined)`，无提示。
3. RESOLVED IN 01.2C：`getOpsLogs` 现在只向后端发送 `level/logger/keyword/limit`；`deviceId/thread` 改为当前结果内本地过滤，并在日志页明确标注服务端能力边界。

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

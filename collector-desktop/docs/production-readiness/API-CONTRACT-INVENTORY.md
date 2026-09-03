# Task 01.1 API Contract Inventory & Baseline

生成时间：2026-09-02 15:05:07 +0800

## 1. 基线与分析范围

- 分支：`feature_2.0`
- 基线提交：`449735151d9fdb17e3b8aae55c0b368a2844f0fb`
- 开始分析时工作区：`git status --short --branch --untracked-files=all` 仅输出 `## feature_2.0...github/feature_2.0`，未检测到已有未提交修改。
- 前端范围：`collector-desktop/src/api/`、`src/views/`、`src/features/`、`src/stores/`、`src/types/`。
- 后端范围：Controller 实际位于 `collector-web/src/main/java/com/wangbin/collector/api/controller/`；Application Service 与 DTO 位于 `collector-application/src/main/java/com/wangbin/collector/api/`；监控与协议 Schema 的返回模型分别位于 `collector-monitor/`、`collector-protocol-spi/`、`collector-runtime/` 等真实模块。
- 本文只建立契约盘点，不修改业务代码、不改 Electron 请求链路、不重构 DTO。
- 01.2A 更新：前端 HTTP 层已增加显式 `apiData` / `raw` / `envelope` response mode；DataController raw DTO、`isDeviceRunning`、`getRunningDevices` 的核心边界问题已标记为 `RESOLVED IN 01.2A`，历史风险条目保留供后续任务追踪。
- 01.2B 更新：`monitor.api.ts` 9 个 `/monitor/*` endpoint 已统一改为 `requestRaw<T>()` 并补齐真实 Snapshot/DTO 类型；`config.api.ts` 20 个 endpoint 已按 ConfigController 契约改为 `requestApiData<T>()` 或必要的 `requestEnvelope<T>()`，稳定 Response DTO 已集中到 `types/config.ts`。

## 2. HTTP 与 Electron 请求边界

### Renderer HTTP

- 默认服务地址：`collector-desktop/src/api/http.ts:5`，`http://127.0.0.1:9090/collector`。
- Axios timeout：`http.ts:91-93`，默认 `8000ms`。
- 请求头：`http.ts:95-100`，renderer 直接请求时统一写入 `X-Collector-Token`。
- Task 01.1 发现：`http.ts` 曾在响应对象存在 `data` 字段时直接返回 `data`，导致 raw DTO 自身的 `data` 可能被误拆。
- RESOLVED IN 01.2A：`http.ts` 已改为显式 response mode：`apiData` 用于 `ApiResult<T>` 解包；`raw` 用于直接业务 DTO 且不剥离 DTO 自身 `data`；`envelope` 用于保留 `ApiResult` 顶层 `deviceId` / `count` / `running` / `extra` 等 metadata。错误校验与 data extraction 已分离，Browser Axios 与 Electron preload proxy 共用同一 renderer HTTP 边界。
- 错误消息本地化：`http.ts:215-233`，401/403/网络错误/超时转换为中文消息。
- 注意：当前 `request<T>()` 未暴露 `AbortSignal`，页面无法取消旧请求；这属于 Task 01.2 之后的可靠性改造候选。

### Electron HTTP Proxy 背景

- preload 暴露 `window.collectorDesktop.request()`：`collector-desktop/electron/preload/index.cts:33-38`。
- main 注册 `collector:http-request`：`collector-desktop/electron/main/main.ts:242-245`。
- 代理目标限制：`http-proxy-utils.ts:28-45`，只能访问当前采集服务 origin 和 context path。
- 请求头限制：`http-proxy-utils.ts:21`、`59-73`，仅透传 `Accept`、`Content-Type`，由代理统一注入 `X-Collector-Token`。
- 代理超时：`http-proxy-utils.ts:22-23`、`106-108`、`187-193`，默认 `8000ms`，最大 `30000ms`。
- 查询参数序列化：`http-proxy-utils.ts:76-95`，空值跳过，数组按重复 key 输出。

## 3. 当前前端 API 模块清单

统计口径：只统计调用 `request<T>()` 的真实 HTTP API 函数，不统计 API 文件内的 `normalize*` 辅助函数。

| API 模块 | HTTP API 数 | `Promise<unknown>` / `Record<string, unknown>` 数 | 备注 |
|---|---:|---:|---|
| `config.api.ts` | 20 | 0 | RESOLVED IN 01.2B：稳定 ConfigController response 全部 typed；19 个走 `requestApiData<T>()`，`triggerFullConfigSync` 保留 command envelope。 |
| `control.api.ts` | 3 | 3 | 写点、批量写、协议命令仍返回 unknown；命令值/结果本身包含动态结构，外壳留到后续。 |
| `data.api.ts` | 8 | 0 | RESOLVED IN 01.2A：DataController 直接返回业务 DTO，本模块已明确使用 RAW DTO response boundary；历史/告警 normalize 作为 LEGACY_COMPAT 保留。 |
| `device.api.ts` | 10 | 6 | 01.2A 已修复 running 相关契约；01.2B 顺带让重复的 `getConfigDevices` 使用显式 `requestApiData<T>()`。剩余 unknown 集中在 start/stop/reload/status/statistics。 |
| `edge.api.ts` | 1 | 1 | 后端已有 `EdgeTelemetryIngressResult`，前端仍 unknown。 |
| `monitor.api.ts` | 9 | 0 | RESOLVED IN 01.2B：9 个 `/monitor/*` endpoint 均为 RAW DTO，并已映射真实 Snapshot/DTO 类型。 |
| `ops.api.ts` | 4 | 3 | 日志本地类型为兼容形态；确认/网络诊断未 typed。 |
| `point.api.ts` | 2 | 1 | 读取点位配置 typed，保存结果 unknown。 |
| `protocol.api.ts` | 3 | 0 | 协议 Schema typed。 |
| `runtime.api.ts` | 2 | 0 | `/health` typed；`/monitor/runtime` 通过 `monitor.api.ts.getRuntimeStatus` 复用同一 RAW contract。 |
| `shadow.api.ts` | 5 | 5 | 影子当前均 unknown，历史天然 Map 动态。 |
| **合计** | **67** | **19** | 01.2B 后 Monitor 与 Config 稳定 response 缺口已清理；剩余 unknown 集中在 control/device/edge/ops/point/shadow 的动态或未处理契约。 |

## 4. 完整 API Contract Inventory

### 4.1 `config.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `getConfigSummary` | GET | `/api/config/summary` | 无 | `Promise<ConfigSummaryResponse>` | `ConfigController.getSummary` → `ConfigConsoleApplicationService.getSummary` | `ApiResult<ConfigSummaryResponse>` | `CollectionView`, `DiagnosticView` | `CollectionView` 已改为 typed access；Diagnostic summary builder 保留 view model | RESOLVED IN 01.2B：`requestApiData`，TS DTO 来源于 Java。 |
| `getConfigDevices` | GET | `/api/config/devices` | 无 | `Promise<ConfigDeviceListResponse>` | `ConfigController.getAllDevices` → `ConfigConsoleApplicationService.getAllDevices` | `ApiResult<ConfigDeviceListResponse>` | `device.store` 经 `device.api.ts` 兼容入口；`config.api.ts` 也导出 | `normalizeDeviceViewModelWithRuntimeStatus` | RESOLVED IN 01.2B：重复入口 contract 一致，均为 `requestApiData`。 |
| `createLocalDevice` | POST | `/api/config/local/devices` | body: `LocalDeviceConfigRequest`，协议字段局部动态 | `Promise<LocalDeviceConfigResponse>` | `ConfigController.createLocalDevice` → `ConfigConsoleApplicationService.createLocalDevice` | `ApiResult<LocalDeviceConfigResponse>` | `LocalDeviceEditor` | 本地编辑器 payload builder | RESOLVED IN 01.2B：响应 typed；请求中的 `connection/extJson/additionalConfig` 保留动态。 |
| `getLocalDevice` | GET | `/api/config/local/device/{deviceId}` | path: `deviceId` | `Promise<LocalDeviceConfigResponse>` | `ConfigController.getLocalDevice` → `ConfigConsoleApplicationService.getLocalDevice` | `ApiResult<LocalDeviceConfigResponse>` | `DeviceListView` | `extractLocalDeviceBundle` | RESOLVED IN 01.2B。 |
| `updateLocalDevice` | PUT | `/api/config/local/device/{deviceId}` | body: `LocalDeviceConfigRequest`，协议字段局部动态 | `Promise<LocalDeviceConfigResponse>` | `ConfigController.updateLocalDevice` → `ConfigConsoleApplicationService.updateLocalDevice` | `ApiResult<LocalDeviceConfigResponse>` | `LocalDeviceEditor` | 本地编辑器 payload builder | RESOLVED IN 01.2B。 |
| `deleteLocalDevice` | DELETE | `/api/config/local/device/{deviceId}` | path: `deviceId` | `Promise<DeviceIdResponse>` | `ConfigController.deleteLocalDevice` → `ConfigConsoleApplicationService.deleteLocalDevice` | `ApiResult<DeviceIdResponse>` | `device.store` | Store 刷新列表 | RESOLVED IN 01.2B：复用 `DeviceIdResponse`。 |
| `getDeviceConfig` | GET | `/api/config/device/{deviceId}` | path: `deviceId` | `Promise<DeviceConfigDetailResponse>` | `ConfigController.getDevice` → `ConfigConsoleApplicationService.getDevice` | `ApiResult<DeviceConfigDetailResponse>` | 当前未发现生产引用 | 无 | RESOLVED IN 01.2B；unused/reserved 但 response typed。 |
| `updateDeviceConfig` | PUT | `/api/config/device/{deviceId}` | body: `DeviceInfo` | `Promise<DeviceIdResponse>` | `ConfigController.updateDevice` → `ConfigConsoleApplicationService.updateDevice` | `ApiResult<DeviceIdResponse>` | 当前未发现生产引用 | 无 | RESOLVED IN 01.2B；unused/reserved。 |
| `getDevicePointsConfig` | GET | `/api/config/device/{deviceId}/points` | query: `includeAdaptive`，默认 `true` | `Promise<DevicePointConfigResponse>` | `ConfigController.getDevicePoints` → `ConfigConsoleApplicationService.getDevicePoints` | `ApiResult<DevicePointConfigResponse>` | `HistoryView` | `HistoryView.extractPoints` 兼容数组/嵌套 | MATCHED；01.2B 改为显式 `requestApiData`。 |
| `updateDevicePointsConfig` | PUT | `/api/config/device/{deviceId}/points` | body: `DataPoint[]` | `Promise<DeviceIdResponse>` | `ConfigController.updatePoints` → `ConfigConsoleApplicationService.updatePoints` | `ApiResult<DeviceIdResponse>` | 当前未发现生产引用；`point.api.ts.saveDevicePointConfig` 使用同端点 | 无 | RESOLVED IN 01.2B；unused/repeated，但 response typed。 |
| `getDeviceConnection` | GET | `/api/config/device/{deviceId}/connection` | path: `deviceId` | `Promise<DeviceConnectionConfigResponse>` | `ConfigController.getDeviceConnection` → `ConfigConsoleApplicationService.getDeviceConnection` | `ApiResult<DeviceConnectionConfigResponse>` | `DeviceConfigPanel` | 直接读取 `response.connection` | RESOLVED IN 01.2B：响应外壳 typed；连接配置动态 Map 保留。 |
| `updateDeviceConnection` | PUT | `/api/config/device/{deviceId}/connection` | body: `DeviceConnection`，协议扩展字段局部动态 | `Promise<DeviceIdResponse>` | `ConfigController.updateConnection` → `ConfigConsoleApplicationService.updateConnection` | `ApiResult<DeviceIdResponse>` | `DeviceConfigPanel` | `buildConnectionPayload` | RESOLVED IN 01.2B：复用 `DeviceIdResponse`。 |
| `getDeviceDiff` | GET | `/api/config/device/{deviceId}/diff` | path: `deviceId` | `Promise<ConfigDiffResponse>` | `ConfigController.diff` → `ConfigConsoleApplicationService.diff` | `ApiResult<ConfigDiffResponse>` | `DeviceConfigPanel`, `device.store` | JSON 展示或 Store 透传 | RESOLVED IN 01.2B。 |
| `refreshDeviceConfig` | POST | `/api/config/device/{deviceId}/refresh` | path: `deviceId` | `Promise<DeviceIdResponse>` | `ConfigController.refreshDevice` → `ConfigConsoleApplicationService.refreshDevice` | `ApiResult<DeviceIdResponse>` | `DeviceListView`, `DeviceOperationShell` | `normalizeDeviceConfigActionResult` 兼容 ApiResult/typed payload | RESOLVED IN 01.2B。 |
| `clearDeviceConfig` | POST | `/api/config/device/{deviceId}/clear` | path: `deviceId` | `Promise<DeviceIdResponse>` | `ConfigController.clearDevice` → `ConfigConsoleApplicationService.clearDevice` | `ApiResult<DeviceIdResponse>` | `DeviceListView`, `DeviceOperationShell` | `normalizeDeviceConfigActionResult` 兼容 ApiResult/typed payload | RESOLVED IN 01.2B。 |
| `triggerFullConfigSync` | POST | `/api/config/sync` | 无 | `Promise<ApiResult<null>>` | `ConfigController.triggerFullSync` → `ConfigConsoleApplicationService.triggerFullSync` | `ApiResult<Object>`，当前 data 为 `null` | `ConfigOpsPanel`, `device.store` | JSON/Toast | COMMAND_ENVELOPE：调用方需要 `message`，保留 envelope，不误用 `apiData`。 |
| `triggerPartialConfigSync` | POST | `/api/config/sync/{type}` | path: `type`; query: `deviceId?` | `Promise<DeviceIdResponse>` | `ConfigController.triggerPartialSync` → `ConfigConsoleApplicationService.triggerPartialSync` | `ApiResult<DeviceIdResponse>` | `ConfigOpsPanel` | JSON/Toast | RESOLVED IN 01.2B。 |
| `getConfigSyncStatus` | GET | `/api/config/sync/status` | 无 | `Promise<ConfigSyncStatusResponse>` | `ConfigController.getSyncStatus` → `ConfigConsoleApplicationService.getSyncStatus` | `ApiResult<ConfigSyncStatusResponse>` | `ConfigOpsPanel` | `normalizeSyncStatusItems` typed | RESOLVED IN 01.2B。 |
| `exportConfigs` | GET | `/api/config/export` | 无 | `Promise<ConfigExportResponse>` | `ConfigController.exportConfigs` → `ConfigConsoleApplicationService.exportConfigs` | `ApiResult<ConfigExportResponse>` | `ConfigOpsPanel`, `DeviceListView` | `normalizeConfigExportText` | RESOLVED IN 01.2B；bundle 内协议扩展字段保留动态。 |
| `importConfigs` | POST | `/api/config/import` | body: `ConfigImportRequest` | `Promise<ConfigImportResult>` | `ConfigController.importConfigs` → `ConfigConsoleApplicationService.importConfigs` | `ApiResult<ConfigImportResult>` | `ConfigOpsPanel`, `DeviceListView` | `parseConfigImportText`、`buildConfigImportRequest` | RESOLVED IN 01.2B；导入 bundle 内协议扩展字段保留动态。 |

### 4.2 `control.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `writeDevicePoint` | POST | `/api/control/device/{deviceId}/point/{pointRef}` | body: `PointWriteRequest`，当前 `unknown` | `Promise<unknown>` | `ControlController.writePoint` → `ControlCommandApplicationService.writePoint` | `ApiResult<PointWriteResultResponse>` | `ControlPanel` | `buildSinglePointControlPayload`、JSON 展示 | SHOULD_TYPE；写入值本身允许动态。 |
| `writeDevicePoints` | POST | `/api/control/device/{deviceId}/points` | body: `PointWriteRequest.values`，当前 `unknown` | `Promise<unknown>` | `ControlController.writePoints` → `ControlCommandApplicationService.writePoints` | `ApiResult<BatchPointWriteResponse>` | `ControlPanel` | `parseControlJson` | SHOULD_TYPE；字段值 Map 动态合理。 |
| `executeDeviceCommand` | POST | `/api/control/device/{deviceId}/command` | body: `DeviceCommandRequest`，当前 `unknown` | `Promise<unknown>` | `ControlController.executeCommand` → `ControlCommandApplicationService.executeCommand` | `ApiResult<DeviceCommandResponse>`，其中 `params`/`result` 动态 | `ControlPanel` | `parseControlJson`、JSON 展示 | DYNAMIC_OK：协议命令结果天然动态，但外壳可后续 typed。 |

### 4.3 `data.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `getPointRealtimeData` | GET | `/api/data/device/{deviceId}/point/{pointId}` | path: `deviceId`, `pointId` | `Promise<PointRealtimeResponse>` | `DataController.getPointData` → `RealtimeDataApplicationService.getPointData` | `PointRealtimeResponse`（非 ApiResult） | `RealtimeView` | `normalizeSinglePointRealtimeRow` | RESOLVED IN 01.2A：使用 RAW DTO，单点响应 DTO 已 typed；normalizer 保留 DTO→ViewModel。 |
| `getDeviceRealtimeData` | GET | `/api/data/device/{deviceId}` | query: `pointIds?`，前端拼成逗号字符串 | `Promise<DeviceRealtimeDataResponse>` | `DataController.getDeviceData` → `RealtimeDataApplicationService.getDeviceData` | `DeviceRealtimeDataResponse`（非 ApiResult） | `RealtimeView`, `PointEditor`, `DeviceConfigPanel`, `DeviceOperationShell`, `RealtimeDataPanel` | `normalizeRealtimeRows` | RESOLVED IN 01.2A：使用 RAW DTO，前端 `data` 已对齐 `Record<string, PointRealtimePayload>`；仍需后续确认 Spring 对逗号字符串 `List<String>` 的绑定。 |
| `getAllDeviceDataSummaries` | GET | `/api/data/devices` | 无 | `Promise<DeviceListResponse>` | `DataController.getAllDevices` → `RealtimeDataApplicationService.getAllDevices` | `DeviceListResponse`（非 ApiResult） | `RealtimeView` | `extractRealtimeDeviceIds` | RESOLVED IN 01.2A：接口已 typed 为 RAW DTO；`DeviceListResponse.devices` 只用于提取全设备查询的 `deviceId`，不再被 normalizer 当作实时点位行。 |
| `getDevicePointSummaries` | GET | `/api/data/device/{deviceId}/points` | path: `deviceId` | `Promise<DevicePointListResponse>` | `DataController.getDevicePoints` → `RealtimeDataApplicationService.getDevicePoints` | `DevicePointListResponse`（非 ApiResult） | 当前未发现生产引用 | 无 | RESOLVED IN 01.2A：RAW DTO typed；unused/reserved。 |
| `resetAdaptiveConfig` | POST | `/api/data/device/{deviceId}/reset-adaptive` | path: `deviceId` | `Promise<AdaptiveResetResponse>` | `DataController.resetAdaptiveConfig` → `RealtimeDataApplicationService.resetAdaptiveConfig` | `AdaptiveResetResponse`（非 ApiResult） | 当前未发现生产引用 | 无 | RESOLVED IN 01.2A：RAW DTO typed；unused/reserved。 |
| `getPointHistory` | GET | `/api/data/history/device/{deviceId}/point/{pointId}` | query: `startTs?`, `endTs?`, `limit?` | `Promise<HistoryDataResponse>` | `DataController.getPointHistory` → `RealtimeDataApplicationService.getPointHistory` | `HistoryDataResponse`（非 ApiResult） | `HistoryView` | `normalizeHistoryRows` | RESOLVED IN 01.2A：RAW DTO typed；LEGACY_COMPAT normalizer 保留以兼容 `records/rows/items/data/values/points`。 |
| `getRecentAlarms` | GET | `/api/data/history/alarms` | query: `deviceId?`, `pointId?`, `pointCode?`, `level?`, `ruleId?`, `startTs?`, `endTs?`, `limit?` | `Promise<AlarmHistoryDataResponse>` | `DataController.getRecentAlarmHistory` → `RealtimeDataApplicationService.getRecentAlarmHistory` | `AlarmHistoryDataResponse`（非 ApiResult） | `AlarmView`, `DashboardView`, `DiagnosticView`, `AlarmTablePanel` | `normalizeAlarmHistoryRows` / `normalizeAlarmRows` | RESOLVED IN 01.2A：RAW DTO typed；LEGACY_COMPAT normalizer 保留以兼容历史行字段。 |
| `getDeviceAlarmHistory` | GET | `/api/data/history/device/{deviceId}/alarms` | path: `deviceId`; query 同上 | `Promise<AlarmHistoryDataResponse>` | `DataController.getAlarmHistory` → `RealtimeDataApplicationService.getAlarmHistory` | `AlarmHistoryDataResponse`（非 ApiResult） | `AlarmView`, `HistoryView` | `normalizeAlarmHistoryRows` | RESOLVED IN 01.2A：RAW DTO typed；LEGACY_COMPAT 保留。 |

### 4.4 `device.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `getConfigDevices` | GET | `/api/config/devices` | 无 | `Promise<ConfigDeviceListResponse>` | `ConfigController.getAllDevices` → `ConfigConsoleApplicationService.getAllDevices` | `ApiResult<ConfigDeviceListResponse>` | `device.store` | `normalizeDeviceViewModelWithRuntimeStatus` | MATCHED。 |
| `startDevice` | POST | `/api/device/{deviceId}/start` | path: `deviceId` | `Promise<unknown>` | `DeviceController.startDevice` → `DeviceConsoleApplicationService.startDevice` | `ApiResult<Object>` | `device.store` | Store 只看成功/失败 | DYNAMIC_OK：当前后端 Object 响应只用于操作成功状态。 |
| `startLocalDevice` | POST | `/api/device/{deviceId}/start-local` | path: `deviceId` | `Promise<unknown>` | `DeviceController.startLocalDevice` → `DeviceConsoleApplicationService.startLocalDevice` | `ApiResult<Object>` | `device.store`, `LocalDeviceEditor` | Store/Editor 只看成功/失败 | DYNAMIC_OK。 |
| `stopDevice` | POST | `/api/device/{deviceId}/stop` | path: `deviceId` | `Promise<unknown>` | `DeviceController.stopDevice` → `DeviceConsoleApplicationService.stopDevice` | `ApiResult<Object>` | `device.store` | Store 只看成功/失败 | DYNAMIC_OK。 |
| `reloadDevices` | POST | `/api/device/reload` | 无 | `Promise<unknown>` | `DeviceController.reloadAllDevices` → `DeviceConsoleApplicationService.reloadAllDevices` | `ApiResult<Object>` | `device.store` | Store 只看成功/失败 | DYNAMIC_OK。 |
| `getDeviceStatus` | GET | `/api/device/{deviceId}/status` | path: `deviceId` | `Promise<unknown>` | `DeviceController.getDeviceStatus` → `DeviceConsoleApplicationService.getDeviceStatus` | `ApiResult<DeviceStatusResponse>` | `DeviceConfigPanel`, `DeviceRuntimePanel`, `device.store` | `normalizeDeviceStatusDetail` | SHOULD_TYPE。 |
| `getAllDeviceStatistics` | GET | `/api/device/statistics` | 无 | `Promise<unknown>` | `DeviceController.getAllStatistics` → `DeviceConsoleApplicationService.getAllStatistics` | `ApiResult<Map<String, DeviceStatisticsResponse>>` | 当前未发现生产引用 | 无 | SHOULD_TYPE；unused/reserved。 |
| `getRunningDevices` | GET | `/api/device/running` | 无 | `Promise<string[]>` | `DeviceController.getRunningDevices` → `DeviceConsoleApplicationService.getRunningDevices` | `ApiResult<List<String>>` | `DeviceRuntimePanel` | `normalizeRunningDeviceIds` 兼容保留 | RESOLVED IN 01.2A：HTTP 层按 `apiData` 解包后 API 返回 `string[]`。 |
| `getDeviceRuntime` | GET | `/api/device/runtime` | 无 | `Promise<DeviceRuntimeSnapshot[]>` | `DeviceController.getDeviceRuntimeSnapshots` → `DeviceConsoleApplicationService.getDeviceRuntimeSnapshots` | `ApiResult<List<DeviceRuntimeSnapshot>>` | `device.store`, `DeviceRuntimePanel` | `normalizeDeviceRuntimeRows` 兜底 | MATCHED。 |
| `isDeviceRunning` | GET | `/api/device/{deviceId}/running` | path: `deviceId` | `Promise<boolean>` | `DeviceController.isDeviceRunning` → `DeviceConsoleApplicationService.isDeviceRunning` | `ApiResult<Object>`，顶层 `running` 字段 | `DeviceRuntimePanel` | `normalizeDeviceRunningFlag` 兼容保留 | RESOLVED IN 01.2A：API 使用 ENVELOPE 读取顶层 `running`，调用方仍得到真实 boolean。 |

### 4.5 `edge.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `ingestEdgeTelemetry` | POST | `/api/edge/telemetry` | body: `EdgeTelemetryBatchRequest`，当前 `unknown` | `Promise<unknown>` | `EdgeTelemetryController.ingest` → `EdgeTelemetryIngressService.ingest` | `ApiResult<EdgeTelemetryIngressResult>` | `EdgeTelemetryPanel` | `normalizeEdgeTelemetryResult` | SHOULD_TYPE；请求最多 1000 items，前端可 typed 外壳并保留 value 动态。 |

### 4.6 `monitor.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `getRuntimeStatus` | GET | `/monitor/runtime` | 无 | `Promise<ConsoleRuntimeStatusSnapshot>` | `MonitorController.runtimeStatus` → `ConsoleRuntimeStatusApplicationService.getRuntimeStatus` | `ConsoleRuntimeStatusSnapshot` | `DashboardView`, `DiagnosticView`, `runtime.store` 经 `runtime.api.ts` 兼容入口 | Dashboard typed access；Diagnostic summary builders | RESOLVED IN 01.2B：RAW DTO，`runtime.api.ts` re-export 同一 wrapper。 |
| `getCacheMetrics` | GET | `/monitor/cache` | 无 | `Promise<CacheMetricsSnapshot>` | `MonitorController.cacheMetrics` | `CacheMetricsSnapshot` | `DashboardView`, `DiagnosticView` | Dashboard typed access；Diagnostic builder 保留 view model | RESOLVED IN 01.2B：RAW DTO。 |
| `getDeviceConnectionMetrics` | GET | `/monitor/devices` | 无 | `Promise<DeviceStatusSnapshot>` | `MonitorController.deviceStatus` | `DeviceStatusSnapshot` | `DiagnosticView` | `buildDiagnosticRows` | RESOLVED IN 01.2B：RAW DTO。 |
| `getCollectorPerformance` | GET | `/monitor/performance` | 无 | `Promise<CollectorMetrics[]>` | `MonitorController.collectorPerformance` | `List<CollectorMetrics>` | `DiagnosticView` | raw diagnostic package | RESOLVED IN 01.2B：RAW DTO，List 已映射为 `CollectorMetrics[]`。 |
| `getSystemResources` | GET | `/monitor/system` | 无 | `Promise<SystemResourceSnapshot>` | `MonitorController.systemResources` | `SystemResourceSnapshot` | `DashboardView`, `DiagnosticView` | Dashboard typed gauges；Diagnostic builder | RESOLVED IN 01.2B：RAW DTO。 |
| `getExceptionStats` | GET | `/monitor/errors` | 无 | `Promise<ExceptionStatsSnapshot>` | `MonitorController.exceptionStats` | `ExceptionStatsSnapshot` | `DiagnosticView`, `LogView` | Diagnostic builder；LogView 仍保留独立日志 normalizer | RESOLVED IN 01.2B：RAW DTO。 |
| `getCloudReportMetrics` | GET | `/monitor/report` | 无 | `Promise<CloudReportMetricsResponse>` | `MonitorController.cloudReportMetrics` | `CloudReportMetricsResponse` | `CloudView`, `DashboardView`, `DiagnosticView` | cloud/diagnostic summary builders | RESOLVED IN 01.2B：RAW DTO；handlers/status statistics 为动态 Map。 |
| `getStorageMetrics` | GET | `/monitor/storage` | 无 | `Promise<StorageMetricsSnapshot>` | `MonitorController.storageMetrics` | `StorageMetricsSnapshot` | `DashboardView`, `DiagnosticView` | Dashboard typed state；Diagnostic builder | RESOLVED IN 01.2B：RAW DTO。 |
| `getPerformanceDetail` | GET | `/monitor/perf/detail` | 无 | `Promise<PerformanceStatsSnapshot>` | `MonitorController.performanceDetail` | `PerformanceStatsSnapshot` | `DashboardView`, `DiagnosticView` | Dashboard typed rejection counters；Diagnostic builder | RESOLVED IN 01.2B：RAW DTO。 |

### 4.7 `ops.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `getOpsLogs` | GET | `/api/ops/logs` | query: `level?`, `logger?`, `keyword?`, `limit?`；前端还传 `deviceId`、`thread`，后端当前不接收 | `Promise<OpsLogResponse>` | `OpsController.logs` → `OpsConsoleApplicationService.logs` | `ApiResult<OpsLogResponse>`，后端字段为 `items` | `LogView`, `DiagnosticView`, `LogPanel` | `normalizeLogRows` 兼容 `logs/records/rows/items` | LEGACY_COMPAT；同时存在前端多传查询参数的契约漂移。 |
| `queryAlarmAcknowledgements` | POST | `/api/ops/alarms/acknowledgements/query` | body: `{ alarmIds: string[] }` | `Promise<Record<string, unknown>>` | `OpsController.acknowledgementStates` → `OpsConsoleApplicationService.acknowledgementStates` | `ApiResult<Map<String, AlarmAcknowledgement>>` | `AlarmView` | `normalizeAlarmAcknowledgementMap` | SHOULD_TYPE：应为 `Record<string, AlarmAcknowledgementRecord>`。 |
| `acknowledgeAlarm` | POST | `/api/ops/alarms/{alarmId}/acknowledge` | body: `AlarmAcknowledgementRequest`，当前本地 payload | `Promise<unknown>` | `OpsController.acknowledge` → `OpsConsoleApplicationService.acknowledge` | `ApiResult<AlarmAcknowledgement>` | `AlarmView`, `AlarmTablePanel` | `applyAlarmAcknowledgement` | SHOULD_TYPE。 |
| `diagnoseNetwork` | POST | `/api/ops/network/diagnose` | body: `NetworkDiagnosticRequest`，当前 `unknown` | `Promise<unknown>` | `OpsController.diagnose` → `OpsConsoleApplicationService.diagnose` | `ApiResult<NetworkDiagnosticResult>` | `NetworkView` | `normalizeNetworkDiagnosticResult` | SHOULD_TYPE；请求与响应均已有稳定后端 record/class。 |

### 4.8 `point.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `getDevicePointConfig` | GET | `/api/config/device/{deviceId}/points` | query: `includeAdaptive`，默认 `true` | `Promise<DevicePointConfigResponse>` | `ConfigController.getDevicePoints` → `ConfigConsoleApplicationService.getDevicePoints` | `ApiResult<DevicePointConfigResponse>` | `point.store`, `PointEditor` 间接使用 | `normalizePointRows` | MATCHED。 |
| `saveDevicePointConfig` | PUT | `/api/config/device/{deviceId}/points` | body: `DataPoint[]` | `Promise<unknown>` | `ConfigController.updatePoints` → `ConfigConsoleApplicationService.updatePoints` | `ApiResult<DeviceIdResponse>` | `point.store` | 保存后 `pointStore.load` 重新读取 | SHOULD_TYPE。 |

### 4.9 `protocol.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `listProtocols` | GET | `/api/protocols` | 无 | `Promise<ProtocolSchema[]>` | `ProtocolController.listProtocols` → `ProtocolSchemaService.getAllSchemas` | `ApiResult<List<ProtocolSchema>>` | `protocol.store` | `protocolMap` | MATCHED。 |
| `getProtocol` | GET | `/api/protocols/{protocol}` | path: `protocol` | `Promise<ProtocolSchema>` | `ProtocolController.getProtocol` → `ProtocolSchemaService.getSchema` | `ApiResult<ProtocolSchema>` | `DeviceConfigPanel`, `LocalDeviceEditor` | schema 驱动表单 | MATCHED。 |
| `getProtocolFields` | GET | `/api/protocols/{protocol}/fields` | path: `protocol` | `Promise<ProtocolFieldConfig[]>` | `ProtocolController.getConnectionFields` → `ProtocolSchemaService.getConnectionFields` | `ApiResult<List<ProtocolFieldConfig>>` | `protocol.store` | schema 驱动表单 | MATCHED。 |

### 4.10 `runtime.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `getHealth` | GET | `/health` | 无 | `Promise<HealthStatus>` | `HealthController.health` → `SystemHealthService.getSystemHealth` | `HealthStatus`（非 ApiResult） | `runtime.store`, `LoginView` 间接 | 无 | MATCHED。 |
| `getRuntimeStatus` | GET | `/monitor/runtime` | 无 | `Promise<ConsoleRuntimeStatusSnapshot>` | `MonitorController.runtimeStatus` → `ConsoleRuntimeStatusApplicationService.getRuntimeStatus` | `ConsoleRuntimeStatusSnapshot`（非 ApiResult） | `runtime.store`, `LoginView` 间接 | Runtime store 汇总 | MATCHED；与 `monitor.api.ts.getRuntimeStatus` 重复。 |

### 4.11 `shadow.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `getShadow` | GET | `/api/shadow/{deviceId}` | path: `deviceId` | `Promise<unknown>` | `ShadowController.getShadow` → `ShadowManager.getShadowDocument` | `ApiResult<DeviceShadowResponse>` | `ShadowPanel` | `summarizeShadowState`、JSON 展示 | SHOULD_TYPE；state/metadata 内部动态合理。 |
| `getShadowDelta` | GET | `/api/shadow/{deviceId}/delta` | path: `deviceId` | `Promise<unknown>` | `ShadowController.getDelta` → `ShadowManager.getShadowDelta` | `ApiResult<DeviceShadowDeltaResponse>` | `ShadowPanel` | `summarizeShadowState` | SHOULD_TYPE；delta Map 动态合理。 |
| `getShadowHistory` | GET | `/api/shadow/{deviceId}/history` | query: `limit`，默认 `50` | `Promise<unknown>` | `ShadowController.getHistory` → `ShadowManager.getShadowHistory` | `ApiResult<List<Map<String,Object>>>` | `ShadowPanel` | `normalizeShadowHistoryRows` | DYNAMIC_OK：历史记录结构是 shadow document 操作快照。 |
| `updateShadowDesired` | POST | `/api/shadow/{deviceId}/desired` | body: `desired/state/properties/params` 等兼容形态 | `Promise<unknown>` | `ShadowController.updateDesired` → `ShadowManager.updateDesired` | `ApiResult<DeviceShadowResponse>` | `ShadowPanel` | `parseShadowJsonOrThrow`、`summarizeShadowState` | SHOULD_TYPE；请求体兼容多形态，响应可 typed。 |
| `clearShadowDesired` | DELETE | `/api/shadow/{deviceId}/desired` | query: `fields?`，前端逗号字符串 | `Promise<unknown>` | `ShadowController.clearDesired` → `ShadowManager.clearDesired` | `ApiResult<DeviceShadowResponse>` | `ShadowPanel` | `summarizeShadowState` | SHOULD_TYPE；需确认 Spring 对逗号字符串 `List<String>` 的绑定。 |

## 5. `Promise<unknown>` 分类

统计口径：包括 `Promise<unknown>`、`Promise<unknown[]>`、`Promise<Record<string, unknown>>` 以及 API 层 `request<unknown>` 返回。

| 分类 | 数量 | API |
|---|---:|---|
| SHOULD_TYPE | 13 | 01.2B 后仍待处理：`control.api.ts`: `writeDevicePoint`, `writeDevicePoints`; `device.api.ts`: `getDeviceStatus`, `getAllDeviceStatistics`; `edge.api.ts`: `ingestEdgeTelemetry`; `ops.api.ts`: `queryAlarmAcknowledgements`, `acknowledgeAlarm`, `diagnoseNetwork`; `point.api.ts`: `saveDevicePointConfig`; `shadow.api.ts`: `getShadow`, `getShadowDelta`, `updateShadowDesired`, `clearShadowDesired`。 |
| LEGACY_COMPAT | 0 | API 层返回类型不再以 legacy unknown 计数；兼容逻辑继续保留在 normalizer。 |
| DYNAMIC_OK | 6 | `control.api.ts`: `executeDeviceCommand`; `device.api.ts`: `startDevice`, `startLocalDevice`, `stopDevice`, `reloadDevices`; `shadow.api.ts`: `getShadowHistory`。这些接口当前要么后端就是 `ApiResult<Object>` 操作结果，要么数据天然动态。 |

## 6. Normalize / Extract / Resolve / Parse 清单

| 文件 | 函数 | 分类 | 存在理由 | 后续建议 |
|---|---|---|---|---|
| `src/api/http.ts` | `unwrapApiResponse`, `resolveHttpErrorMessage`, `resolveNetworkMessage` | DEFENSIVE | 统一处理 ApiResult/raw DTO 与错误本地化。 | 保留；Task 01.2 可评估增加 request id / abort 支持。 |
| `src/features/realtime/utils/realtime-utils.ts` | `normalizeRealtimeRows`, `normalizeSinglePointRealtimeRow`, `extractRealtimeDeviceIds`, `normalizeTopLevelPointMap` | BACKEND_COMPAT + VIEW_MODEL + LEGACY_COMPAT | RESOLVED IN 01.2A：primary path 已改为后端真实 `DeviceRealtimeDataResponse.data: Record<string, PointRealtimePayload>` / `PointRealtimeResponse.data` → `RealtimePointRow`；`DeviceListResponse.devices` 改由 `extractRealtimeDeviceIds` 提取设备 ID，不再作为实时点位行；`points/values/rows/items/data` 和 top-level point map 兼容分支保留。 | 后续只在确认无历史响应后再收窄兼容分支；不要在 01.2A 删除。 |
| `src/api/data.api.ts` | `normalizeAlarmRows` | DUPLICATED | 与 `features/alarm/utils/alarm-history-utils.normalizeAlarmHistoryRows` 功能重叠，供 device-scoped `AlarmTablePanel` 使用。 | Task 01.2/01.4 可统一 alarm normalizer。 |
| `src/features/history/utils/history-data-utils.ts` | `normalizeHistoryRows` / `extractRows` | BACKEND_COMPAT | 兼容 `records/rows/items/data/values/points`。 | 后端 `HistoryDataResponse.data` 稳定后，可标注 legacy 分支。 |
| `src/features/alarm/utils/alarm-history-utils.ts` | `normalizeAlarmHistoryRows`, `normalizeAlarmRow`, `extractRows` | BACKEND_COMPAT + VIEW_MODEL | 后端 alarm history 是 `List<Map<String,Object>>`，前端需要规范字段名和展示字段。 | 保留；可增加后端字段到 TS 明确类型。 |
| `src/features/alarm/utils/alarm-utils.ts` | `buildAlarmIdentity`, `normalizeAlarmAcknowledgementMap`, `mergeAlarmAcknowledgementStates` | VIEW_MODEL + DEFENSIVE | 为无稳定 alarmId 的历史 Map 构造稳定前端 identity，并兼容 ApiResult/raw map。 | 保留；若后端保证 alarmId，可收窄 hash fallback。 |
| `src/api/ops.api.ts` | `normalizeLogRows` | LEGACY_COMPAT | 后端 `OpsLogResponse` 目前字段为 `items`，前端还兼容 `logs/records/rows`。 | 记录为兼容层；不要盲删。 |
| `src/views/history/HistoryView.vue` | `extractPoints`, `resolveTargetDeviceId`, `resolvePointRef` | BACKEND_COMPAT + VIEW_MODEL | 路由 query、设备选择、点位引用解析；`extractPoints` 兼容多层 response。 | 可在 DTO typed 后收窄 `extractPoints`。 |
| `src/stores/device.store.ts` | `normalizeDeviceViewModel*`, `resolveDeviceStatus`, `resolvePointCount`, `resolveDeviceStartMode` | VIEW_MODEL + DEFENSIVE | 后端设备配置与运行快照合并为 UI 设备状态。 | 保留；与类型补强同步。 |
| `src/stores/point.store.ts` / `point-editor-utils.ts` | `normalizePointRows`, `mergePointRuntime`, `buildPointExtraModel`, `applyPointExtraModel` | VIEW_MODEL | 点位配置转编辑态、协议扩展字段映射、实时值合并。 | 保留；性能风险见基线文档。 |
| `src/features/point/utils/point-excel-utils.ts` | `parsePointCsv`, `normalizeImportedValue` | VIEW_MODEL + DEFENSIVE | CSV 导入到点位模型；1MB/2000 行限制。 | 保留。 |
| `src/features/diagnostic/utils/device-runtime-utils.ts` | `normalizeRunningDeviceIds`, `normalizeDeviceRuntimeRows`, `normalizeDeviceStatusDetail`, `normalizeDeviceRunningFlag` | BACKEND_COMPAT + VIEW_MODEL | RESOLVED IN 01.2A：`getRunningDevices` API 已返回 `string[]`，`isDeviceRunning` API 已从 envelope 顶层 `running` 提取 boolean；RESOLVED IN 01.2C：`getDeviceStatus` 已有稳定 TS DTO，normalizer 只保留为页面兼容层。 | 保留；不再承担顶层 unknown 兜底的主 contract。 |
| `src/features/network/utils/network-utils.ts` | `buildNetworkDiagnosticPayload`, `normalizeNetworkDiagnosticResult` | VIEW_MODEL + DEFENSIVE | RESOLVED IN 01.2C：请求已收窄到 `NetworkDiagnosticRequest`，API 返回 `NetworkDiagnosticResult`；失败时仍会生成不可达结果供页面与导出使用。 | 保留；失败分支和扩展 details 仍需 defensive normalize。 |
| `src/features/network/utils/edge-telemetry-utils.ts` | `parseEdgeTelemetryJson`, `normalizeEdgeTelemetryResult`, `parseTypedValue` | DYNAMIC_OK + VIEW_MODEL | RESOLVED IN 01.2C：边缘遥测请求/响应外壳已 typed；`value` 继续是动态字段。 | 保留；不为动态 telemetry 值制造伪强类型。 |
| `src/features/shadow/utils/shadow-utils.ts` | `normalizeShadowHistoryRows`, `parseShadowJson*`, `summarizeShadowState` | DYNAMIC_OK + VIEW_MODEL | 影子 state/delta/history 是动态文档，需要 JSON 解析和摘要。 | 保留；响应外壳可 typed。 |
| `src/stores/websocket-utils.ts` | `normalizeRealtimeMessage`, `parseRealtimePayload` | DEFENSIVE | 兼容 WS array / `points` / `data` / single point。 | 解析错误当前不可观测，见可靠性基线。 |

## 7. 页面 → API Mapping

| Page / Feature | 初始化请求 | 手动刷新请求 | 定时请求 | 设备 / Route Query 触发 | 用户操作请求 |
|---|---|---|---|---|---|
| Login | `appStore.initialize()` 读取本地/Electron 配置；无自动 HTTP。 | `testServerConnection()` → `/health`，有 token 时再请求 `/api/protocols`；随后 `runtimeStore.refresh()` → `/health` + `/monitor/runtime`。 | 无 | 无 | 登录按钮复用连接测试；外部文档通过 Electron `openExternal`。 |
| Dashboard | `loadDashboard()`：`deviceStore.refresh()` → `/api/config/devices` + `/api/device/runtime`；`getRecentAlarms`; `getCloudReportMetrics`; `getRuntimeStatus`; `getSystemResources`; `getCacheMetrics`; `getStorageMetrics`; `getPerformanceDetail`。 | 顶部刷新同初始化模型。 | 无 | 无 | 新建本地设备前可能 `protocolStore.refresh()` → `/api/protocols`；保存后 reload dashboard。 |
| DeviceList | `refreshDeviceListContext()`：`deviceStore.refresh()` + `protocolStore.refresh()`。 | 同初始化。 | 无 | `route.query.deviceId` 只更新 selected device，不直接请求。 | 同步远端配置、启动/停止、本地删除、配置 refresh/clear、导入/导出、本地编辑器 create/update/get。 |
| DeviceWorkbench | `DeviceOperationShell` 初始化：`appStore.initialize()`、`deviceStore.refresh()`、`getDeviceRealtimeData` 预览。 | shell 刷新实时预览；`DeviceConfigPanel` 连接检查/读取配置/刷新点位运行数据。 | 无 | `route.query.deviceId` 触发选择设备并 reload realtime preview；`activeTab`/`protocolKey` watcher 触发协议配置读取。 | start/stop via `device.store`; `getProtocol`, `getDeviceConnection`, `updateDeviceConnection`, `getDeviceDiff`; 嵌入 PointEditor。 |
| PointEditor | `onMounted`：`pointStore.load()` → `/api/config/device/{deviceId}/points`；随后 `getDeviceRealtimeData`。 | 刷新配置、刷新实时值。 | 无 | `props.deviceId` watcher 触发重新加载点位和实时值。 | CSV 导入/预览、批量编辑、本地保存 → `saveDevicePointConfig` 后自动 reload。 |
| Realtime | `initializeRealtimeView()`：`appStore.initialize()`、`deviceStore.refresh()`、`loadRealtime()`。 | `refreshRealtime()` 调 `loadRealtime()`；单点查询调 `getPointRealtimeData`。 | `realtimeAuto=true` 时每 5 秒 `loadRealtime()`。 | `route.query.deviceId/pointId` 只触发单点查询，不会自动切换列表的 `realtimeDeviceId`。 | 选择设备触发 `loadRealtime()`；表格“查单点”触发单点查询。 |
| History | 初始化：`appStore.initialize()`、`deviceStore.refresh()`、`applyRouteQuery(autoQuery)`；必要时 `getDevicePointsConfig` + `getPointHistory` + related `getDeviceAlarmHistory`。 | 查询/刷新历史。 | 无 | route query 变更触发 `applyRouteQuery`，可能重新加载点位并自动查询。 | 导出本地 JSON，不新增后端请求。 |
| Alarm | 初始化：`appStore.initialize()`、`deviceStore.refresh()`、`loadAlarms()`；全局用 `getRecentAlarms`，单设备用 `getDeviceAlarmHistory`，随后 `queryAlarmAcknowledgements`。 | 查询/刷新告警历史；刷新确认状态。 | 无 | route query 变更后如果 filter 变化则 `loadAlarms()`。 | `acknowledgeAlarm`; 跳转日志/网络只更新路由。 |
| Log | 初始化：`appStore.initialize()`、`deviceStore.refresh()`、`getOpsLogs`。 | 查询/刷新日志；最近异常定位先 `getExceptionStats` 再 `getOpsLogs`。 | 自动刷新开启时每 5 秒 `getOpsLogs`。 | route query 变更后如果 filter 变化则 `loadLogs()`。 | 导出本地文件，无后端写入。 |
| Collection | 初始化：`appStore.initialize()`、`deviceStore.refresh()`、`protocolStore.refresh()`、`getConfigSummary`。 | 页面刷新同初始化；ConfigOpsPanel 刷新同步状态。 | 无 | `route.query.deviceId` 只更新 selected device。 | 导入/导出配置、全量/局部配置同步。 |
| Network | 初始化：`appStore.initialize()`、route query 预填、`deviceStore.refresh()`。 | 无全局刷新；手动网络检测为 `diagnoseNetwork`。 | 无 | route query 预填 target/port/type/device。 | `diagnoseNetwork`; `EdgeTelemetryPanel` 提交 `ingestEdgeTelemetry`。 |
| Cloud | `onMounted` 调 `getCloudReportMetrics`。 | 顶部刷新同初始化。 | 无 | 无 | 仅本地展示/导出。 |
| Diagnostic | `runDiagnostic()`：`deviceStore.refresh()` + 10 个 monitor/config 请求并行，允许部分失败。 | 顶部“运行完整诊断”同初始化。 | 无 | `route.query.deviceId` 更新 selected device。 | 下载诊断包时额外抽样 `getRecentAlarms`、`getOpsLogs`。 |
| Control | `DeviceOperationShell` 初始化会刷设备和实时预览。 | shell 刷新实时预览。 | 无 | `route.query.deviceId` 选设备并刷新预览。 | `ControlPanel`: `writeDevicePoint`, `writeDevicePoints`, `executeDeviceCommand`。 |
| Shadow | `DeviceOperationShell` 初始化会刷设备和实时预览；ShadowPanel 自身不自动读取影子。 | 读取全部使用 `Promise.allSettled([getShadow, getShadowDelta, getShadowHistory])`。 | 无 | `props.deviceId` watcher 清空影子展示状态，不自动请求。 | `updateShadowDesired`, `clearShadowDesired`, shadow package 本地导出。 |
| Embedded `RealtimeDataPanel` | 挂载时 `connectRealtime()` + `getDeviceRealtimeData`。 | 刷新实时值。 | `autoRefresh` 为 true 时按 `refreshIntervalMs`，最小 1 秒。 | `props.deviceId` watcher 重连 WS、重拉 HTTP。 | 用户点击连接实时通道。 |
| Embedded `AlarmTablePanel` | 挂载时按 `deviceId` 调 `getDeviceAlarmHistory` 或全局 `getRecentAlarms`。 | 刷新告警。 | 无 | `props.deviceId` 变化后 reload。 | 单条确认 `acknowledgeAlarm`。 |
| Embedded `LogPanel` | 挂载时 `getOpsLogs`。 | 刷新日志。 | 无 | `props.deviceId` 变化后 reload。 | 无后端写入。 |

## 8. API → Backend DTO 对照汇总

统计口径：按 67 个前端 HTTP API 条目映射状态统计。

| 状态 | API 条目数 | 说明 / 代表项 |
|---|---:|---|
| MATCHED | 54 | 01.2C 新增：`device.api.ts` 查询接口、`ops.api.ts` 四个接口、`edge.api.ts`、`point.api.ts` 已与真实 Java DTO / envelope 对齐。 |
| PARTIAL | 0 | 01.2C 后仍不保留“半 typed 顶层 wrapper”；动态结构改归入 DYNAMIC/LEGACY。 |
| MISSING | 5 | 剩余缺口只集中在 `control.api.ts` 3 个写操作和 `shadow.api.ts` 2 个 outer DTO。 |
| DYNAMIC | 7 | 后端当前就是 command envelope 或内部 payload 天然动态：设备 start/stop/reload、全量 sync、协议 command、shadow history/文档。 |
| LEGACY_COMPAT | 1 | `getOpsLogs` 已以 `items` 为 primary contract，但 normalizer 仍兼容 `logs/records/rows` 历史字段。 |

关键后端 DTO 对照：

| Backend DTO / Contract | Frontend Type | 状态 | 备注 |
|---|---|---|---|
| `ConfigDeviceListResponse` | `types/device.ts ConfigDeviceListResponse` | MATCHED | `config.api.ts` 与 `device.api.ts` 重复入口均 typed，并显式 `requestApiData`。 |
| `DevicePointConfigResponse` | `types/point.ts DevicePointConfigResponse` | RESOLVED IN 01.2C / MATCHED | `point.api.ts` 不再维护第二套 contract，读取与保存都复用 `config.api.ts` 的稳定 points DTO。 |
| `DeviceRealtimeDataResponse` | `types/monitor.ts DeviceRealtimeDataResponse` | RESOLVED IN 01.2A | 后端 `data: Map<String, PointRealtimePayload>` 已对齐为 `Record<string, PointRealtimePayload>`；normalizer 负责 DTO→ViewModel。 |
| `PointRealtimeResponse` | `types/monitor.ts PointRealtimeResponse` | RESOLVED IN 01.2A | 单点 raw DTO 已 typed；`data` 为 `PointRealtimePayload`。 |
| `HistoryDataResponse` | `types/monitor.ts HistoryDataResponse`；`HistoryRow` 为行模型 | RESOLVED IN 01.2A / LEGACY_COMPAT | 后端 `data: List<Map<String,Object>>` typed；前端继续兼容多字段。 |
| `AlarmHistoryDataResponse` | `types/monitor.ts AlarmHistoryDataResponse`；`AlarmRow` 为行模型 | RESOLVED IN 01.2A / LEGACY_COMPAT | 后端 `data: List<Map<String,Object>>` typed；历史字段动态合理。 |
| `DeviceStatusResponse` | `types/device.ts DeviceStatusResponse`；`DeviceStatusDetail` 仅作为 view model | RESOLVED IN 01.2C / MATCHED | `device.api.ts.getDeviceStatus` 已显式 `requestApiData<DeviceStatusResponse>`；normalizer 只做页面展示态兼容。 |
| `DeviceRuntimeSnapshot` | `types/device.ts DeviceRuntimeSnapshot` | MATCHED | 布尔/时间/phase 字段对齐。 |
| `ConfigSummaryResponse` | `types/config.ts ConfigSummaryResponse` | RESOLVED IN 01.2B | `CollectionView` 已改为 typed access；Diagnostic builder 保留 view model 输入。 |
| `ConfigSyncStatusResponse` | `types/config.ts ConfigSyncStatusResponse`；`normalizeSyncStatusItems` 输出展示项 | RESOLVED IN 01.2B | 后端字段稳定，helper 输入已 typed。 |
| `ConfigExportResponse` / `ConfigImportResult` | `types/config.ts ConfigExportResponse` / `ConfigImportResult` | RESOLVED IN 01.2B | 可 typed 外壳已补齐，bundle 内 domain 对象保留扩展字段。 |
| `LocalDeviceConfigResponse` / `LocalDeviceConfigRequest` | `types/config.ts LocalDeviceConfigResponse` / `LocalDeviceConfigRequest`，编辑器仍使用 feature-local draft/payload | RESOLVED IN 01.2B / DYNAMIC_OK | API response typed；请求中的 protocol-specific config、connection extJson、point additionalConfig 保留动态。 |
| `DeviceConnectionConfigResponse` | `types/config.ts DeviceConnectionConfigResponse`；`ConnectionPayload` 仍为表单 view model | RESOLVED IN 01.2B / DYNAMIC_OK | 响应外壳 typed，`DeviceConfigPanel` 直接读取 `response.connection`；协议扩展 Map 保留动态。 |
| `DeviceIdResponse` | `types/config.ts DeviceIdResponse` | RESOLVED IN 01.2B | 多个配置写操作统一复用该 DTO。 |
| `PointWriteResultResponse` / `BatchPointWriteResponse` | 无 | MISSING | 控制写入结果仅 JSON 展示。 |
| `DeviceCommandResponse` | 无 | DYNAMIC | `result` 动态，但 response shell 可 typed。 |
| `CloudReportMetricsResponse` | `types/monitor.ts CloudReportMetricsResponse` | RESOLVED IN 01.2B / DYNAMIC_OK | 重要监控 DTO 已按 Java nested classes typed；handlers/status statistics 为动态 Map。 |
| `ConsoleRuntimeStatusSnapshot` | `types/monitor.ts ConsoleRuntimeStatusSnapshot`；`types/runtime.ts` re-export | RESOLVED IN 01.2B | `monitor.api.ts` 与 `runtime.api.ts` 复用同一 RAW wrapper。 |
| `CacheMetricsSnapshot`, `DeviceStatusSnapshot`, `SystemResourceSnapshot`, `ExceptionStatsSnapshot`, `StorageMetricsSnapshot`, `PerformanceStatsSnapshot`, `CollectorMetrics` | `types/monitor.ts` | RESOLVED IN 01.2B / DYNAMIC_OK | 监控 Snapshot 已 typed；内部 `Map<String,Object>`/`protocolMetrics`/`deviceStats` 保留动态。 |
| `OpsLogResponse` | `types/ops.ts OpsLogResponse` | RESOLVED IN 01.2C / LEGACY_COMPAT | 后端主字段明确为 `items`；`logs/records/rows` 仅作为历史兼容保留。 |
| `AlarmAcknowledgement` | `types/ops.ts AlarmAcknowledgement` | RESOLVED IN 01.2C / MATCHED | API 已返回 `Record<string, AlarmAcknowledgement>`；告警页和 helper 直接消费 typed acknowledgement。 |
| `NetworkDiagnosticResult` | `types/ops.ts NetworkDiagnosticResult`；`NormalizedNetworkDiagnosticResult` 为展示模型 | RESOLVED IN 01.2C / MATCHED | 原始 request/response DTO 已补齐；页面 normalizer 仅负责展示文案与失败回填。 |
| `EdgeTelemetryIngressResult` | `types/edge.ts EdgeTelemetryIngressResult` | RESOLVED IN 01.2C / MATCHED | `edge.api.ts` 已显式 `requestApiData<EdgeTelemetryIngressResult>`；请求外壳同步补齐。 |
| `ProtocolSchema` / `ProtocolFieldConfig` | `types/protocol.ts` | MATCHED | 协议动态字段通过 schema 表达。 |
| `DeviceShadowResponse` / `DeviceShadowDeltaResponse` | 无独立 TS DTO | MISSING/DYNAMIC | response shell 稳定，state/delta/metadata 内部动态。 |
| `List<Map<String,Object>>` shadow history | `ShadowHistoryRow` view model | DYNAMIC | 历史记录天然动态。 |

## 9. 01.2C 完成后剩余输入

1. RESOLVED IN 01.2A：`getDeviceRealtimeData`、`PointRealtimeResponse`、`DeviceRealtimeDataResponse.data` Map contract、`getRunningDevices`、`isDeviceRunning`、DataController raw DTO response boundary。
2. RESOLVED IN 01.2B：`monitor.api.ts` 9 个 RAW DTO endpoint、`config.api.ts` 稳定 response DTO、`runtime.api.ts.getRuntimeStatus` 重复契约已统一。
3. Task 01.2D 只建议继续处理 `control.api.ts` 与 `shadow.api.ts`：前者补 `PointWriteRequest` / `PointWriteResultResponse` / `BatchPointWriteResponse` / `DeviceCommandResponse`，后者补 `DeviceShadowResponse` / `DeviceShadowDeltaResponse` 的稳定 outer DTO。
4. `getOpsLogs` drift 已在 01.2C 明确：后端真实只支持 `level/logger/keyword/limit`，前端设备/线程条件改为当前结果内本地过滤，仍存在“非服务端精确过滤”的 UX/backend capability gap。
5. 保留 `DYNAMIC_OK` 的动态 payload/value/result，不为了“零 unknown”创造无业务价值 DTO；尤其是 shadow 文档、control result payload 与 edge telemetry value。

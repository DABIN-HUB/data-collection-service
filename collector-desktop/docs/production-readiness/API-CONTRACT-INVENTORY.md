# Task 01.1 API Contract Inventory & Baseline

生成时间：2026-09-02 15:05:07 +0800

## 1. 基线与分析范围

- 分支：`feature_2.0`
- 基线提交：`449735151d9fdb17e3b8aae55c0b368a2844f0fb`
- 开始分析时工作区：`git status --short --branch --untracked-files=all` 仅输出 `## feature_2.0...github/feature_2.0`，未检测到已有未提交修改。
- 前端范围：`collector-desktop/src/api/`、`src/views/`、`src/features/`、`src/stores/`、`src/types/`。
- 后端范围：Controller 实际位于 `collector-web/src/main/java/com/wangbin/collector/api/controller/`；Application Service 与 DTO 位于 `collector-application/src/main/java/com/wangbin/collector/api/`；监控与协议 Schema 的返回模型分别位于 `collector-monitor/`、`collector-protocol-spi/`、`collector-runtime/` 等真实模块。
- 本文只建立契约盘点，不修改业务代码、不改 Electron 请求链路、不重构 DTO。

## 2. HTTP 与 Electron 请求边界

### Renderer HTTP

- 默认服务地址：`collector-desktop/src/api/http.ts:5`，`http://127.0.0.1:9090/collector`。
- Axios timeout：`http.ts:91-93`，默认 `8000ms`。
- 请求头：`http.ts:95-100`，renderer 直接请求时统一写入 `X-Collector-Token`。
- 统一解包：`http.ts:179-195`，当响应对象存在 `data` 字段时返回 `data`；当 `status=error` 或 `code != 200` 时抛出 `ApiRequestError`。
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
| `config.api.ts` | 20 | 19 | 配置治理 API 最主要的类型缺口，除点位配置读取外几乎全为 unknown。 |
| `control.api.ts` | 3 | 3 | 写点、批量写、协议命令均返回 unknown；命令结果本身包含动态结构。 |
| `data.api.ts` | 8 | 7 | 实时、历史、告警混合；历史/告警存在兼容 normalize。 |
| `device.api.ts` | 10 | 7 | 设备操作多为 `ApiResult<Object>`；运行快照已 typed。 |
| `edge.api.ts` | 1 | 1 | 后端已有 `EdgeTelemetryIngressResult`，前端仍 unknown。 |
| `monitor.api.ts` | 9 | 9 | 监控 API 全 unknown；部分 DTO 已在后端稳定。 |
| `ops.api.ts` | 4 | 3 | 日志本地类型为兼容形态；确认/网络诊断未 typed。 |
| `point.api.ts` | 2 | 1 | 读取点位配置 typed，保存结果 unknown。 |
| `protocol.api.ts` | 3 | 0 | 协议 Schema typed。 |
| `runtime.api.ts` | 2 | 0 | `/health` 与 `/monitor/runtime` typed。 |
| `shadow.api.ts` | 5 | 5 | 影子当前均 unknown，历史天然 Map 动态。 |
| **合计** | **67** | **55** | API-local normalizer：`data.api.ts normalizeAlarmRows`、`ops.api.ts normalizeLogRows` 未计入 HTTP API 总数。 |

## 4. 完整 API Contract Inventory

### 4.1 `config.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `getConfigSummary` | GET | `/api/config/summary` | 无 | `Promise<unknown>` | `ConfigController.getSummary` → `ConfigConsoleApplicationService.getSummary` | `ApiResult<ConfigSummaryResponse>` | `CollectionView`, `DiagnosticView` | `CollectionView`/`DiagnosticView` 以 `asRecord` 读取 | SHOULD_TYPE：后端 DTO 稳定，前端缺 `ConfigSummaryResponse`。 |
| `getConfigDevices` | GET | `/api/config/devices` | 无 | `Promise<unknown>` | `ConfigController.getAllDevices` → `ConfigConsoleApplicationService.getAllDevices` | `ApiResult<ConfigDeviceListResponse>` | 当前生产代码未直接使用；`device.api.ts` 有同名 typed 函数 | 无 | SHOULD_TYPE：重复 API 封装且本模块版本未 typed。 |
| `createLocalDevice` | POST | `/api/config/local/devices` | body: `LocalDeviceConfigRequest` 形态，目前 `unknown` | `Promise<unknown>` | `ConfigController.createLocalDevice` → `ConfigConsoleApplicationService.createLocalDevice` | `ApiResult<LocalDeviceConfigResponse>` | `LocalDeviceEditor` | `extractLocalDeviceBundle`、本地编辑器 payload builder | SHOULD_TYPE：响应 DTO 稳定；请求含协议扩展字段，可保留局部动态字段。 |
| `getLocalDevice` | GET | `/api/config/local/device/{deviceId}` | path: `deviceId` | `Promise<unknown>` | `ConfigController.getLocalDevice` → `ConfigConsoleApplicationService.getLocalDevice` | `ApiResult<LocalDeviceConfigResponse>` | `DeviceListView` | `extractLocalDeviceBundle` | SHOULD_TYPE。 |
| `updateLocalDevice` | PUT | `/api/config/local/device/{deviceId}` | body: `LocalDeviceConfigRequest` 形态，目前 `unknown` | `Promise<unknown>` | `ConfigController.updateLocalDevice` → `ConfigConsoleApplicationService.updateLocalDevice` | `ApiResult<LocalDeviceConfigResponse>` | `LocalDeviceEditor` | `extractLocalDeviceBundle` | SHOULD_TYPE；请求中连接/点位扩展仍允许动态。 |
| `deleteLocalDevice` | DELETE | `/api/config/local/device/{deviceId}` | path: `deviceId` | `Promise<unknown>` | `ConfigController.deleteLocalDevice` → `ConfigConsoleApplicationService.deleteLocalDevice` | `ApiResult<DeviceIdResponse>` | `device.store` | `device.store.deleteLocal` 刷新列表 | SHOULD_TYPE。 |
| `getDeviceConfig` | GET | `/api/config/device/{deviceId}` | path: `deviceId` | `Promise<unknown>` | `ConfigController.getDevice` → `ConfigConsoleApplicationService.getDevice` | `ApiResult<DeviceConfigDetailResponse>` | 当前未发现生产引用 | 无 | SHOULD_TYPE；同时是 unused/reserved API。 |
| `updateDeviceConfig` | PUT | `/api/config/device/{deviceId}` | body: `DeviceInfo`，当前 `unknown` | `Promise<unknown>` | `ConfigController.updateDevice` → `ConfigConsoleApplicationService.updateDevice` | `ApiResult<DeviceIdResponse>` | 当前未发现生产引用 | 无 | SHOULD_TYPE；unused/reserved。 |
| `getDevicePointsConfig` | GET | `/api/config/device/{deviceId}/points` | query: `includeAdaptive`，默认 `true` | `Promise<DevicePointConfigResponse>` | `ConfigController.getDevicePoints` → `ConfigConsoleApplicationService.getDevicePoints` | `ApiResult<DevicePointConfigResponse>` | `HistoryView` | `HistoryView.extractPoints` 兼容数组/嵌套 | MATCHED，但 `HistoryView.extractPoints` 是历史兼容层。 |
| `updateDevicePointsConfig` | PUT | `/api/config/device/{deviceId}/points` | body: `DataPoint[]` 或 `unknown` | `Promise<unknown>` | `ConfigController.updatePoints` → `ConfigConsoleApplicationService.updatePoints` | `ApiResult<DeviceIdResponse>` | 当前未发现生产引用；`point.api.ts.saveDevicePointConfig` 使用同端点 | 无 | SHOULD_TYPE；重复封装。 |
| `getDeviceConnection` | GET | `/api/config/device/{deviceId}/connection` | path: `deviceId` | `Promise<unknown>` | `ConfigController.getDeviceConnection` → `ConfigConsoleApplicationService.getDeviceConnection` | `ApiResult<DeviceConnectionConfigResponse>` | `DeviceConfigPanel` | `normalizeConnectionPayload` | SHOULD_TYPE；连接配置含协议动态字段，响应外壳仍可 typed。 |
| `updateDeviceConnection` | PUT | `/api/config/device/{deviceId}/connection` | body: `DeviceConnection` 形态，目前 `unknown` | `Promise<unknown>` | `ConfigController.updateConnection` → `ConfigConsoleApplicationService.updateConnection` | `ApiResult<DeviceIdResponse>` | `DeviceConfigPanel` | `buildConnectionPayload` | SHOULD_TYPE；请求体需保留协议扩展字段。 |
| `getDeviceDiff` | GET | `/api/config/device/{deviceId}/diff` | path: `deviceId` | `Promise<unknown>` | `ConfigController.diff` → `ConfigConsoleApplicationService.diff` | `ApiResult<ConfigDiffResponse>` | `DeviceConfigPanel`, `device.store` | JSON 展示或 Store 透传 | SHOULD_TYPE。 |
| `refreshDeviceConfig` | POST | `/api/config/device/{deviceId}/refresh` | path: `deviceId` | `Promise<unknown>` | `ConfigController.refreshDevice` → `ConfigConsoleApplicationService.refreshDevice` | `ApiResult<DeviceIdResponse>` | `DeviceListView`, `DeviceOperationShell` | `normalizeDeviceConfigActionResult` | SHOULD_TYPE。 |
| `clearDeviceConfig` | POST | `/api/config/device/{deviceId}/clear` | path: `deviceId` | `Promise<unknown>` | `ConfigController.clearDevice` → `ConfigConsoleApplicationService.clearDevice` | `ApiResult<DeviceIdResponse>` | `DeviceListView`, `DeviceOperationShell` | `normalizeDeviceConfigActionResult` | SHOULD_TYPE。 |
| `triggerFullConfigSync` | POST | `/api/config/sync` | 无 | `Promise<unknown>` | `ConfigController.triggerFullSync` → `ConfigConsoleApplicationService.triggerFullSync` | `ApiResult<Object>`，当前 data 为 `null` | `ConfigOpsPanel`, `device.store` | JSON/Toast | DYNAMIC_OK：后端无业务 DTO，当前只需要提交成功/失败。 |
| `triggerPartialConfigSync` | POST | `/api/config/sync/{type}` | path: `type`; query: `deviceId?` | `Promise<unknown>` | `ConfigController.triggerPartialSync` → `ConfigConsoleApplicationService.triggerPartialSync` | `ApiResult<DeviceIdResponse>` | `ConfigOpsPanel` | JSON/Toast | SHOULD_TYPE。 |
| `getConfigSyncStatus` | GET | `/api/config/sync/status` | 无 | `Promise<unknown>` | `ConfigController.getSyncStatus` → `ConfigConsoleApplicationService.getSyncStatus` | `ApiResult<ConfigSyncStatusResponse>` | `ConfigOpsPanel` | `normalizeSyncStatusItems` | SHOULD_TYPE。 |
| `exportConfigs` | GET | `/api/config/export` | 无 | `Promise<unknown>` | `ConfigController.exportConfigs` → `ConfigConsoleApplicationService.exportConfigs` | `ApiResult<ConfigExportResponse>` | `ConfigOpsPanel`, `DeviceListView` | `normalizeConfigExportText` | SHOULD_TYPE；导出文本转换是 ViewModel。 |
| `importConfigs` | POST | `/api/config/import` | body: `ConfigImportRequest`，当前 `unknown` | `Promise<unknown>` | `ConfigController.importConfigs` → `ConfigConsoleApplicationService.importConfigs` | `ApiResult<ConfigImportResult>` | `ConfigOpsPanel`, `DeviceListView` | `parseConfigImportText`、`buildConfigImportRequest` | SHOULD_TYPE；请求可 typed 为 `ConfigImportRequest`。 |

### 4.2 `control.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `writeDevicePoint` | POST | `/api/control/device/{deviceId}/point/{pointRef}` | body: `PointWriteRequest`，当前 `unknown` | `Promise<unknown>` | `ControlController.writePoint` → `ControlCommandApplicationService.writePoint` | `ApiResult<PointWriteResultResponse>` | `ControlPanel` | `buildSinglePointControlPayload`、JSON 展示 | SHOULD_TYPE；写入值本身允许动态。 |
| `writeDevicePoints` | POST | `/api/control/device/{deviceId}/points` | body: `PointWriteRequest.values`，当前 `unknown` | `Promise<unknown>` | `ControlController.writePoints` → `ControlCommandApplicationService.writePoints` | `ApiResult<BatchPointWriteResponse>` | `ControlPanel` | `parseControlJson` | SHOULD_TYPE；字段值 Map 动态合理。 |
| `executeDeviceCommand` | POST | `/api/control/device/{deviceId}/command` | body: `DeviceCommandRequest`，当前 `unknown` | `Promise<unknown>` | `ControlController.executeCommand` → `ControlCommandApplicationService.executeCommand` | `ApiResult<DeviceCommandResponse>`，其中 `params`/`result` 动态 | `ControlPanel` | `parseControlJson`、JSON 展示 | DYNAMIC_OK：协议命令结果天然动态，但外壳可后续 typed。 |

### 4.3 `data.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `getPointRealtimeData` | GET | `/api/data/device/{deviceId}/point/{pointId}` | path: `deviceId`, `pointId` | `Promise<unknown>` | `DataController.getPointData` → `RealtimeDataApplicationService.getPointData` | `PointRealtimeResponse`（非 ApiResult） | `RealtimeView` | `normalizeSinglePointRealtimeRow` | SHOULD_TYPE；后端单点响应 DTO 已稳定。 |
| `getDeviceRealtimeData` | GET | `/api/data/device/{deviceId}` | query: `pointIds?`，前端拼成逗号字符串 | `Promise<DeviceRealtimeDataResponse>` | `DataController.getDeviceData` → `RealtimeDataApplicationService.getDeviceData` | `DeviceRealtimeDataResponse`（非 ApiResult） | `RealtimeView`, `PointEditor`, `DeviceConfigPanel`, `DeviceOperationShell`, `RealtimeDataPanel` | `normalizeRealtimeRows` | PARTIAL：后端 `data` 是 `Map<String, PointRealtimePayload>`；前端类型将 `data` 声明为数组候选，依赖 normalize 兜底。另需确认 Spring 对逗号字符串 `List<String>` 的绑定。 |
| `getAllDeviceDataSummaries` | GET | `/api/data/devices` | 无 | `Promise<unknown>` | `DataController.getAllDevices` → `RealtimeDataApplicationService.getAllDevices` | `DeviceListResponse`（非 ApiResult） | `RealtimeView` | `normalizeRealtimeRows` | SHOULD_TYPE；当前将设备摘要交给 realtime rows normalizer，fallback 显示存在语义风险。 |
| `getDevicePointSummaries` | GET | `/api/data/device/{deviceId}/points` | path: `deviceId` | `Promise<unknown>` | `DataController.getDevicePoints` → `RealtimeDataApplicationService.getDevicePoints` | `DevicePointListResponse`（非 ApiResult） | 当前未发现生产引用 | 无 | SHOULD_TYPE；unused/reserved。 |
| `resetAdaptiveConfig` | POST | `/api/data/device/{deviceId}/reset-adaptive` | path: `deviceId` | `Promise<unknown>` | `DataController.resetAdaptiveConfig` → `RealtimeDataApplicationService.resetAdaptiveConfig` | `AdaptiveResetResponse`（非 ApiResult） | 当前未发现生产引用 | 无 | SHOULD_TYPE；unused/reserved。 |
| `getPointHistory` | GET | `/api/data/history/device/{deviceId}/point/{pointId}` | query: `startTs?`, `endTs?`, `limit?` | `Promise<unknown>` | `DataController.getPointHistory` → `RealtimeDataApplicationService.getPointHistory` | `HistoryDataResponse`（非 ApiResult） | `HistoryView` | `normalizeHistoryRows` | LEGACY_COMPAT：后端当前 `data` 稳定，但前端兼容 `records/rows/items/data/values/points`。 |
| `getRecentAlarms` | GET | `/api/data/history/alarms` | query: `deviceId?`, `pointId?`, `pointCode?`, `level?`, `ruleId?`, `startTs?`, `endTs?`, `limit?` | `Promise<unknown>` | `DataController.getRecentAlarmHistory` → `RealtimeDataApplicationService.getRecentAlarmHistory` | `AlarmHistoryDataResponse`（非 ApiResult） | `AlarmView`, `DashboardView`, `DiagnosticView`, `AlarmTablePanel` | `normalizeAlarmHistoryRows` / `normalizeAlarmRows` | LEGACY_COMPAT：兼容 `alarms/records/rows/items/data`。 |
| `getDeviceAlarmHistory` | GET | `/api/data/history/device/{deviceId}/alarms` | path: `deviceId`; query 同上 | `Promise<unknown>` | `DataController.getAlarmHistory` → `RealtimeDataApplicationService.getAlarmHistory` | `AlarmHistoryDataResponse`（非 ApiResult） | `AlarmView`, `HistoryView` | `normalizeAlarmHistoryRows` | LEGACY_COMPAT。 |

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
| `getRunningDevices` | GET | `/api/device/running` | 无 | `Promise<unknown[]>` | `DeviceController.getRunningDevices` → `DeviceConsoleApplicationService.getRunningDevices` | `ApiResult<List<String>>` | `DeviceRuntimePanel` | `normalizeRunningDeviceIds` | SHOULD_TYPE：应至少改为 `Promise<string[]>`。 |
| `getDeviceRuntime` | GET | `/api/device/runtime` | 无 | `Promise<DeviceRuntimeSnapshot[]>` | `DeviceController.getDeviceRuntimeSnapshots` → `DeviceConsoleApplicationService.getDeviceRuntimeSnapshots` | `ApiResult<List<DeviceRuntimeSnapshot>>` | `device.store`, `DeviceRuntimePanel` | `normalizeDeviceRuntimeRows` 兜底 | MATCHED。 |
| `isDeviceRunning` | GET | `/api/device/{deviceId}/running` | path: `deviceId` | `Promise<boolean>` | `DeviceController.isDeviceRunning` → `DeviceConsoleApplicationService.isDeviceRunning` | `ApiResult<Object>`，顶层 `running` 字段 | `DeviceRuntimePanel` | `normalizeDeviceRunningFlag` | PARTIAL：`request<boolean>` 实际可能返回带 `running` 的对象而不是 boolean；页面 normalizer 兜底后可工作，但 API 类型不真实。 |

### 4.5 `edge.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `ingestEdgeTelemetry` | POST | `/api/edge/telemetry` | body: `EdgeTelemetryBatchRequest`，当前 `unknown` | `Promise<unknown>` | `EdgeTelemetryController.ingest` → `EdgeTelemetryIngressService.ingest` | `ApiResult<EdgeTelemetryIngressResult>` | `EdgeTelemetryPanel` | `normalizeEdgeTelemetryResult` | SHOULD_TYPE；请求最多 1000 items，前端可 typed 外壳并保留 value 动态。 |

### 4.6 `monitor.api.ts`

| Frontend Function | Method | URL | Query / Body | Frontend Return Type | Backend Endpoint / Service | Backend Response DTO | Used By | Normalize / ViewModel | Contract Risk |
|---|---|---|---|---|---|---|---|---|---|
| `getRuntimeStatus` | GET | `/monitor/runtime` | 无 | `Promise<unknown>` | `MonitorController.runtimeStatus` → `ConsoleRuntimeStatusApplicationService.getRuntimeStatus` | `ConsoleRuntimeStatusSnapshot` | `DashboardView`, `DiagnosticView` | `asRecord` / summary builders | SHOULD_TYPE；`runtime.api.ts` 已有 typed 同端点，当前存在重复封装。 |
| `getCacheMetrics` | GET | `/monitor/cache` | 无 | `Promise<unknown>` | `MonitorController.cacheMetrics` | `CacheMetricsSnapshot` | `DashboardView`, `DiagnosticView` | `asRecord` | SHOULD_TYPE。 |
| `getDeviceConnectionMetrics` | GET | `/monitor/devices` | 无 | `Promise<unknown>` | `MonitorController.deviceStatus` | `DeviceStatusSnapshot` | `DiagnosticView` | `buildDiagnosticRows` | SHOULD_TYPE。 |
| `getCollectorPerformance` | GET | `/monitor/performance` | 无 | `Promise<unknown>` | `MonitorController.collectorPerformance` | `List<CollectorMetrics>` | `DiagnosticView` | raw diagnostic rows | SHOULD_TYPE。 |
| `getSystemResources` | GET | `/monitor/system` | 无 | `Promise<unknown>` | `MonitorController.systemResources` | `SystemResourceSnapshot` | `DashboardView`, `DiagnosticView` | `asRecord` / gauges | SHOULD_TYPE。 |
| `getExceptionStats` | GET | `/monitor/errors` | 无 | `Promise<unknown>` | `MonitorController.exceptionStats` | `ExceptionStatsSnapshot` | `DiagnosticView`, `LogView` | `LogView.extractArray` | SHOULD_TYPE。 |
| `getCloudReportMetrics` | GET | `/monitor/report` | 无 | `Promise<unknown>` | `MonitorController.cloudReportMetrics` | `CloudReportMetricsResponse` | `CloudView`, `DashboardView`, `DiagnosticView` | cloud/diagnostic summary builders | SHOULD_TYPE；重要 DTO 目前缺 TS 对照。 |
| `getStorageMetrics` | GET | `/monitor/storage` | 无 | `Promise<unknown>` | `MonitorController.storageMetrics` | `StorageMetricsSnapshot` | `DashboardView`, `DiagnosticView` | `asRecord` | SHOULD_TYPE。 |
| `getPerformanceDetail` | GET | `/monitor/perf/detail` | 无 | `Promise<unknown>` | `MonitorController.performanceDetail` | `PerformanceStatsSnapshot` | `DashboardView`, `DiagnosticView` | `asRecord` | SHOULD_TYPE。 |

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
| SHOULD_TYPE | 45 | `config.api.ts`: `getConfigSummary`, `getConfigDevices`, `createLocalDevice`, `getLocalDevice`, `updateLocalDevice`, `deleteLocalDevice`, `getDeviceConfig`, `updateDeviceConfig`, `updateDevicePointsConfig`, `getDeviceConnection`, `updateDeviceConnection`, `getDeviceDiff`, `refreshDeviceConfig`, `clearDeviceConfig`, `triggerPartialConfigSync`, `getConfigSyncStatus`, `exportConfigs`, `importConfigs`; `control.api.ts`: `writeDevicePoint`, `writeDevicePoints`; `data.api.ts`: `getPointRealtimeData`, `getAllDeviceDataSummaries`, `getDevicePointSummaries`, `resetAdaptiveConfig`; `device.api.ts`: `getDeviceStatus`, `getAllDeviceStatistics`, `getRunningDevices`; `edge.api.ts`: `ingestEdgeTelemetry`; `monitor.api.ts`: 全 9 个； `ops.api.ts`: `queryAlarmAcknowledgements`, `acknowledgeAlarm`, `diagnoseNetwork`; `point.api.ts`: `saveDevicePointConfig`; `shadow.api.ts`: `getShadow`, `getShadowDelta`, `updateShadowDesired`, `clearShadowDesired`。 |
| LEGACY_COMPAT | 3 | `data.api.ts`: `getPointHistory`, `getRecentAlarms`, `getDeviceAlarmHistory`。后端 DTO 当前可定位，但前端仍兼容 `records/rows/items/data/alarms/values/points` 等历史形态。 |
| DYNAMIC_OK | 7 | `config.api.ts`: `triggerFullConfigSync`; `control.api.ts`: `executeDeviceCommand`; `device.api.ts`: `startDevice`, `startLocalDevice`, `stopDevice`, `reloadDevices`; `shadow.api.ts`: `getShadowHistory`。这些接口当前要么后端就是 `ApiResult<Object>` 操作结果，要么数据天然动态。 |

## 6. Normalize / Extract / Resolve / Parse 清单

| 文件 | 函数 | 分类 | 存在理由 | 后续建议 |
|---|---|---|---|---|
| `src/api/http.ts` | `unwrapApiResponse`, `resolveHttpErrorMessage`, `resolveNetworkMessage` | DEFENSIVE | 统一处理 ApiResult/raw DTO 与错误本地化。 | 保留；Task 01.2 可评估增加 request id / abort 支持。 |
| `src/features/realtime/utils/realtime-utils.ts` | `normalizeRealtimeRows`, `normalizeSinglePointRealtimeRow`, `normalizeTopLevelPointMap` | BACKEND_COMPAT + TYPE_GAP | 兼容 raw array、`points/values/rows/items/devices/data`、top-level point map；同时掩盖 `DeviceRealtimeDataResponse.data` Map 与 TS 数组声明不一致。 | 不直接删除；先补真实 DTO，再收窄兼容分支。 |
| `src/api/data.api.ts` | `normalizeAlarmRows` | DUPLICATED | 与 `features/alarm/utils/alarm-history-utils.normalizeAlarmHistoryRows` 功能重叠，供 device-scoped `AlarmTablePanel` 使用。 | Task 01.2/01.4 可统一 alarm normalizer。 |
| `src/features/history/utils/history-data-utils.ts` | `normalizeHistoryRows` / `extractRows` | BACKEND_COMPAT | 兼容 `records/rows/items/data/values/points`。 | 后端 `HistoryDataResponse.data` 稳定后，可标注 legacy 分支。 |
| `src/features/alarm/utils/alarm-history-utils.ts` | `normalizeAlarmHistoryRows`, `normalizeAlarmRow`, `extractRows` | BACKEND_COMPAT + VIEW_MODEL | 后端 alarm history 是 `List<Map<String,Object>>`，前端需要规范字段名和展示字段。 | 保留；可增加后端字段到 TS 明确类型。 |
| `src/features/alarm/utils/alarm-utils.ts` | `buildAlarmIdentity`, `normalizeAlarmAcknowledgementMap`, `mergeAlarmAcknowledgementStates` | VIEW_MODEL + DEFENSIVE | 为无稳定 alarmId 的历史 Map 构造稳定前端 identity，并兼容 ApiResult/raw map。 | 保留；若后端保证 alarmId，可收窄 hash fallback。 |
| `src/api/ops.api.ts` | `normalizeLogRows` | LEGACY_COMPAT | 后端 `OpsLogResponse` 目前字段为 `items`，前端还兼容 `logs/records/rows`。 | 记录为兼容层；不要盲删。 |
| `src/views/history/HistoryView.vue` | `extractPoints`, `resolveTargetDeviceId`, `resolvePointRef` | BACKEND_COMPAT + VIEW_MODEL | 路由 query、设备选择、点位引用解析；`extractPoints` 兼容多层 response。 | 可在 DTO typed 后收窄 `extractPoints`。 |
| `src/stores/device.store.ts` | `normalizeDeviceViewModel*`, `resolveDeviceStatus`, `resolvePointCount`, `resolveDeviceStartMode` | VIEW_MODEL + DEFENSIVE | 后端设备配置与运行快照合并为 UI 设备状态。 | 保留；与类型补强同步。 |
| `src/stores/point.store.ts` / `point-editor-utils.ts` | `normalizePointRows`, `mergePointRuntime`, `buildPointExtraModel`, `applyPointExtraModel` | VIEW_MODEL | 点位配置转编辑态、协议扩展字段映射、实时值合并。 | 保留；性能风险见基线文档。 |
| `src/features/point/utils/point-excel-utils.ts` | `parsePointCsv`, `normalizeImportedValue` | VIEW_MODEL + DEFENSIVE | CSV 导入到点位模型；1MB/2000 行限制。 | 保留。 |
| `src/features/diagnostic/utils/device-runtime-utils.ts` | `normalizeRunningDeviceIds`, `normalizeDeviceRuntimeRows`, `normalizeDeviceStatusDetail`, `normalizeDeviceRunningFlag` | BACKEND_COMPAT + TYPE_GAP | 兼容 ApiResult data 与顶层字段；弥补 `isDeviceRunning` frontend type 与 backend envelope 不一致。 | Task 01.2 应优先修正 API 类型。 |
| `src/features/network/utils/network-utils.ts` | `buildNetworkDiagnosticPayload`, `normalizeNetworkDiagnosticResult` | VIEW_MODEL + DEFENSIVE | 表单输入 → 后端请求；后端结果 → UI 展示模型；失败时生成不可达结果。 | 保留；补 TS DTO 后收窄输入输出。 |
| `src/features/network/utils/edge-telemetry-utils.ts` | `parseEdgeTelemetryJson`, `normalizeEdgeTelemetryResult`, `parseTypedValue` | DYNAMIC_OK + VIEW_MODEL | 边缘遥测 value 天然动态，但 response result 有稳定计数字段。 | 请求/响应外壳可 typed，value 继续 unknown。 |
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
| MATCHED | 9 | `device.api.getConfigDevices`, `device.api.getDeviceRuntime`, `point.api.getDevicePointConfig`, `protocol.api.*`, `runtime.api.*`, `config.api.getDevicePointsConfig`。 |
| PARTIAL | 5 | `getDeviceRealtimeData`（后端 Map vs 前端 array 候选）、`isDeviceRunning`（后端 envelope vs frontend boolean）、`getRunningDevices`（应为 string[]）、`config.api.getConfigDevices`（重复未 typed）、`monitor.api.getRuntimeStatus`（typed 函数在 runtime.api 中重复存在）。 |
| MISSING | 42 | 后端已有明确 DTO/record/class，但对应 API 仍返回 unknown；集中在 `config.api.ts`、`monitor.api.ts`、`control.api.ts`、`shadow.api.ts`。 |
| DYNAMIC | 7 | 后端当前就是 `ApiResult<Object>` 操作结果或数据天然动态：设备 start/stop/reload、全量 sync、协议 command、shadow history。 |
| LEGACY_COMPAT | 4 | `getPointHistory`, `getRecentAlarms`, `getDeviceAlarmHistory`, `getOpsLogs`。 |

关键后端 DTO 对照：

| Backend DTO / Contract | Frontend Type | 状态 | 备注 |
|---|---|---|---|
| `ConfigDeviceListResponse` | `types/device.ts ConfigDeviceListResponse` | MATCHED/PARTIAL | `device.api.ts` matched；`config.api.ts` 同端点仍 unknown。 |
| `DevicePointConfigResponse` | `types/point.ts DevicePointConfigResponse` | MATCHED | 读取 typed；保存结果 `DeviceIdResponse` 缺 TS。 |
| `DeviceRealtimeDataResponse` | `types/monitor.ts DeviceRealtimeDataResponse` | PARTIAL | 后端 `data: Map<String, PointRealtimePayload>`；前端 `data?: RealtimePointRow[]` 并依赖 normalizer。 |
| `PointRealtimeResponse` | 无独立 TS 响应 DTO | MISSING | 仅通过 `RealtimePointRow` 和 normalizer 展示。 |
| `HistoryDataResponse` | 无独立 TS 响应 DTO；`HistoryRow` 为行模型 | LEGACY_COMPAT | 后端 `data: List<Map<String,Object>>`，前端兼容多字段。 |
| `AlarmHistoryDataResponse` | 无独立 TS 响应 DTO；`AlarmRow` 为行模型 | LEGACY_COMPAT | 后端 `data: List<Map<String,Object>>`。 |
| `DeviceStatusResponse` | `DeviceStatusDetail` view model | PARTIAL | 前端没有原始 DTO；normalizer 合并 running/isRunning。 |
| `DeviceRuntimeSnapshot` | `types/device.ts DeviceRuntimeSnapshot` | MATCHED | 布尔/时间/phase 字段对齐。 |
| `ConfigSummaryResponse` | 无 | MISSING | Dashboard/Collection/Diagnostic 以 `unknown/asRecord` 读取。 |
| `ConfigSyncStatusResponse` | 无；`normalizeSyncStatusItems` 输出展示项 | MISSING | 后端字段稳定。 |
| `ConfigExportResponse` / `ConfigImportResult` | 无；配置导入导出 utils 使用动态对象 | MISSING | 可 typed 外壳，bundle 内 domain 对象保留扩展字段。 |
| `LocalDeviceConfigResponse` / `LocalDeviceConfigRequest` | `LocalDeviceBundle` 等 feature-local 类型 | PARTIAL | 编辑器有 view model，但 API 层无请求/响应 DTO。 |
| `DeviceConnectionConfigResponse` | `ConnectionPayload` view model | PARTIAL | 协议扩展动态合理，响应外壳缺 TS。 |
| `DeviceIdResponse` | 无 | MISSING | 多个配置写操作返回该 DTO。 |
| `PointWriteResultResponse` / `BatchPointWriteResponse` | 无 | MISSING | 控制写入结果仅 JSON 展示。 |
| `DeviceCommandResponse` | 无 | DYNAMIC | `result` 动态，但 response shell 可 typed。 |
| `CloudReportMetricsResponse` | 无 | MISSING | 重要监控 DTO，当前 Dashboard/Cloud/Diagnostic 全部 `unknown`。 |
| `ConsoleRuntimeStatusSnapshot` | `types/runtime.ts ConsoleRuntimeStatusSnapshot` | MATCHED/PARTIAL | `runtime.api.ts` typed；`monitor.api.ts` 同端点 unknown。 |
| `CacheMetricsSnapshot`, `DeviceStatusSnapshot`, `SystemResourceSnapshot`, `ExceptionStatsSnapshot`, `StorageMetricsSnapshot`, `PerformanceStatsSnapshot`, `CollectorMetrics` | 无或 `Record<string, unknown>` | MISSING | 监控 API 全 unknown。 |
| `OpsLogResponse` | `ops.api.ts OpsLogResponse` | LEGACY_COMPAT | 前端接受 `logs/records/rows/items`；后端只声明 `items`。 |
| `AlarmAcknowledgement` | `AlarmAcknowledgementRecord` | PARTIAL | API 返回 `Record<string, unknown>`；feature normalizer 再转。 |
| `NetworkDiagnosticResult` | `NormalizedNetworkDiagnosticResult` view model | PARTIAL | 缺原始 API DTO；已有 view model。 |
| `EdgeTelemetryIngressResult` | 无；`normalizeEdgeTelemetryResult` view model | MISSING | 后端 result 字段稳定。 |
| `ProtocolSchema` / `ProtocolFieldConfig` | `types/protocol.ts` | MATCHED | 协议动态字段通过 schema 表达。 |
| `DeviceShadowResponse` / `DeviceShadowDeltaResponse` | 无独立 TS DTO | MISSING/DYNAMIC | response shell 稳定，state/delta/metadata 内部动态。 |
| `List<Map<String,Object>>` shadow history | `ShadowHistoryRow` view model | DYNAMIC | 历史记录天然动态。 |

## 9. 立即可用的 Task 01.2 输入

1. 优先补 API 层类型，不改页面行为：`config.api.ts`、`monitor.api.ts`、`control.api.ts`、`shadow.api.ts`。
2. 先修正“类型与真实响应不一致”的 API：`getDeviceRealtimeData`、`isDeviceRunning`、`getRunningDevices`。
3. 将 `monitor.api.ts.getRuntimeStatus` 与 `runtime.api.ts.getRuntimeStatus` 的重复契约统一，避免同一端点一处 typed、一处 unknown。
4. 对 `getOpsLogs` 前端多传的 `deviceId/thread` 与后端未接收参数建立明确决策：要么后端支持过滤，要么前端只做本地过滤并标注文案。
5. 保留 `DYNAMIC_OK` 的动态 payload/value/result，不为了“零 unknown”创造无业务价值 DTO。

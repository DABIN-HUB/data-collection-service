# collector-desktop 前端架构重构进度

更新时间：2026-08-28 14:58:06 +0800

## 当前状态

- 当前目标分支：`feature_2.0`
- 最近提交：
  - `787c57c` 优化
  - `5c6bb9d` 修改
  - `da42b6d` 前端修改
  - `a16d805` 修改
  - `cd572d3` 前端修改
- Phase 1：已完成并通过验证。
- Phase 2：Dashboard 迁移已完成并通过验证。
- Phase 3：Realtime 迁移已完成并通过验证。
- Phase 4：Log 迁移已完成并通过验证。
- Phase 5：Alarm 迁移已完成并通过验证。
- 下一阶段：Phase 6 迁移 Network。

## Baseline 验证结果

执行时间：2026-08-28 10:46 左右，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 29 个测试文件、144 个测试通过 |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 7 |

Baseline 已存在的提示：Vite/Rollup 对 `@vueuse/core` 的 `/* #__PURE__ */` 注释给出 warning；Element Plus vendor chunk 超过 500 kB。两者为 baseline warning，本阶段未处理。

## Phase 1 完成内容

- 新增 `src/router/route-names.ts`，集中路由名称和 Legacy 过渡期 route -> module 映射。
- 从 `LegacyConsoleView.vue` 提取左侧导航数据到 `src/app/navigation.ts`。
- 新增 `src/app/AppSidebar.vue`，左侧菜单改为 `RouterLink`，选中态由 `route.path` 单向判断。
- 新增 `src/app/AppTopbar.vue`，承载节点、服务状态和时间等全局顶部信息。
- 新增 `src/app/AppShell.vue`，形成 `AppSidebar + main(AppTopbar + RouterView)` 外壳。
- 修改 Router：根路由组件改为 `AppShell`；业务子路由 Phase 1 暂时仍渲染 `LegacyConsoleView.vue`。
- 新增过渡路由 `/device/workbench`，使设备工作台不再借用 `/device + activeModule.value = "workbench"`。
- 从 `LegacyConsoleView.vue` 删除 Sidebar、App Shell、Topbar 模板和相关图标/nav/token/clock 状态。
- 将旧宿主中的 `activeModule` 改为由 `route.path` 推导的 `computed`，不再手动赋值。
- 旧宿主内部跳转只执行 `router.push()`；`/control`、`/shadow`、`/device/workbench` 只单向同步页面本地 `workbenchTab`。
- 新增 `src/styles/tokens.css`，定义深色工业控制台颜色、spacing、radius、control height 等 token，并在 `main.ts` 中引入。
- 更新 Router 测试，覆盖 `/device/workbench` 和 route -> Legacy module 过渡映射。
- 为了让本次新增长期文档可被 git 跟踪，调整 `.gitignore` 仅放开 `collector-desktop/AGENTS.md` 与 `collector-desktop/docs/frontend-refactor/**`。

## Phase 1 验证结果

执行时间：2026-08-28 10:54 左右，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 29 个测试文件、145 个测试通过 |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 13 |

Phase 1 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 2：Dashboard 迁移完成内容

- 新增 `src/views/dashboard/DashboardView.vue`，承载原 `activeModule === "overview"` 的概览页面。
- `/dashboard` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `DashboardView.vue`。
- `/realtime`、`/history`、`/alarm`、`/device`、`/device/workbench`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/control`、`/shadow` 当时仍保持 `LegacyConsoleView.vue` 过渡状态。
- Dashboard 新增独立 `loadDashboard()`，进入 `/dashboard` 只加载首页需要的数据，不再依赖 `LegacyConsoleView.onMounted()` 或 `LegacyConsoleView.refreshAll()`。
- Dashboard 设备列表、在线数、离线数、异常数、点位总数和运行状态来源切到 `useDeviceStore()`，不再在 Dashboard 内长期维护 `devices` / `runtimeMap` 副本。
- Dashboard 页面级展示状态保留在 View 内：最近告警、上报指标、运行状态、系统资源、缓存指标、存储指标、性能明细、刷新时间、本地设备编辑弹窗状态。
- 从 `LegacyConsoleView.vue` 删除原 Dashboard template，消除“复制一份 DashboardView 后旧实现仍存在”的双实现风险。
- 从 `LegacyConsoleView.vue` 删除 Dashboard 专属计算/辅助逻辑：`overviewCards`、`runtimeState`、`lastRefreshText`、拓扑 tone/detail、资源仪表盘 `resourceGauges`、Dashboard 风险文案辅助等。
- `LegacyConsoleView.onMounted()` 不再调用整站 `refreshAll()`；改为先加载旧页面共同需要的协议/设备基础数据，再按当前 Legacy route 加载对应页面数据。
- `loadOverview()` 去掉 Dashboard 专属的 `getAllDeviceStatistics()` / `deviceStats`，保留 Collection / Cloud / Diagnostic 仍需要的监控、配置摘要和上报链路数据。
- Dashboard 专属样式从 `legacy-console.css` 迁入 `DashboardView.vue` scoped style；`legacy-console.css` 中已删除 `overview-*`、`home-dashboard-*`、`home-panel*`、`home-event-*`、`home-risk-*`、`topology-*`、`resource-dashboard/resource-gauge/resource-ring/resource-runtime/resource-load`、`cache-ring`、`heading-note` 等 Dashboard 专属规则。
- `workbench.css` 未发现 Dashboard 专属选择器，本阶段未修改。

## Phase 2 验证结果

执行时间：2026-08-28 11:47 左右，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 29 个测试文件、147 个测试通过；`router.test.ts` 覆盖 `/dashboard -> DashboardView` 和其它过渡页面仍走 `LegacyConsoleView` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `DashboardView` chunk |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 16 |
| `git diff --check` | 通过 | 无空白错误；Git 仅提示 Web 构建产物 LF/CRLF 工作区换行提示 |

Phase 2 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 3：Realtime 迁移完成内容

- 新增 `src/views/realtime/RealtimeView.vue`，承载原 `activeModule === "realtime"` 的主菜单“实时数据查询”页面。
- `/realtime` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `RealtimeView.vue`；当前 `/dashboard` 仍直接指向 `DashboardView.vue`。
- `/history`、`/alarm`、`/device`、`/device/workbench`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/control`、`/shadow` 当时继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- Realtime 新增独立 `loadRealtime()`，进入 `/realtime` 后自行加载实时数据，不再依赖 `LegacyConsoleView.onMounted()`、`LegacyConsoleView.refreshAll()` 或 Legacy 内部 `loadRealtime()`。
- Realtime 设备列表来源切到 `useDeviceStore()` 的 `devices`；必要时调用 `deviceStore.refresh()`，不再从 Legacy 传入设备列表，也不在 RealtimeView 维护长期设备领域副本。
- Realtime 页面本地状态保留在 View 内：`realtimeAuto`、`realtimeDeviceId`、`realtimeKeyword`、`realtimeRows`、`realtimeSingleDeviceId`、`realtimeSinglePointId`、`realtimeSingleResult`、`loading`、`singleLoading`。
- 全部设备模式保留：先调用 `getAllDeviceDataSummaries()` 得到设备摘要，再合并 `deviceStore.devices` 的设备 ID，使用 `Promise.allSettled()` 分别调用 `getDeviceRealtimeData(deviceId)`，失败或空结果时回退到对应 summary 行。
- 单设备模式保留：选择设备后直接调用 `getDeviceRealtimeData(realtimeDeviceId)` 并通过 `normalizeRealtimeRows(response, realtimeDeviceId)` 归一化。
- 单点查询保留：使用 `getPointRealtimeData(deviceId, pointId)`，结果继续通过 `normalizeSinglePointRealtimeRow()` 优先解析；表格“查单点”会填充设备和 pointId / pointCode / address，并立即执行单点查询。
- 5 秒自动刷新 Timer 迁入 `RealtimeView.vue` 生命周期：`onMounted()` 初次加载后 `syncTimer()`，`onBeforeUnmount()` 清理；Legacy 中主实时页面的 `realtimeTimer` 已删除。
- `src/views/legacy/realtime-utils.ts` 与 `src/views/legacy/realtime-utils.test.ts` 已迁移到 `src/features/realtime/utils/`。
- `RealtimeDataPanel.vue` 已改为 import `features/realtime/utils/realtime-utils` 中的 `normalizeRealtimeRows()`，删除其内部重复 normalization 实现，继续保留单设备 WebSocket + HTTP fallback 场景。
- `DeviceConfigPanel.vue` 的工作台点位运行数据也复用新的 `normalizeRealtimeRows()`，避免继续保留另一套同名归一化函数。
- 从 `LegacyConsoleView.vue` 删除主 Realtime template、页面级 Realtime state、`filteredRealtimeRows`、`realtimeSummary`、`loadRealtime()`、`loadSingleRealtime()`、`pickRealtimePoint()`、Realtime 格式化 helper 与主 Realtime 自动刷新 Timer。
- `LegacyConsoleView.vue` 仍保留 `selectedRealtimeRows` 与 `loadSelectedRealtime()`，仅服务 `DeviceWorkbench` 当前选中设备工作台，不属于主 `/realtime` 页面。
- 搜索 `legacy-console.css` 与 `workbench.css` 后未发现 `realtime-summary-cards` / `realtime-single-panel` 对应专属样式规则；`quality-badge`、`exact-toolbar`、`exact-table-card`、`section-heading` 等仍为多页面共享样式，按阶段边界暂不复制到 `RealtimeView.vue` scoped style。

## Phase 3 验证结果

执行时间：2026-08-28 13:46-13:50，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 29 个测试文件、149 个测试通过；新增 `src/features/realtime/utils/realtime-utils.test.ts`，`router.test.ts` 覆盖 `/dashboard -> DashboardView`、`/realtime -> RealtimeView` 和其它过渡页面仍走 `LegacyConsoleView` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `RealtimeView` 与 `realtime-utils` chunks |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 19 |
| `git diff --check` | 通过 | 无输出，exit code 0 |

Phase 3 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 4：Log 迁移完成内容

- 新增 `src/views/log/LogView.vue`，承载原 `activeModule === "log"` 的主菜单“日志”页面。
- `/log` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `LogView.vue`；当前 `/dashboard`、`/realtime` 继续分别直接指向 `DashboardView.vue`、`RealtimeView.vue`。
- `/history`、`/alarm`、`/device`、`/device/workbench`、`/collect`、`/cloud`、`/diagnostic`、`/network`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- Log 新增独立 `loadLogs()`，进入 `/log` 后自行调用 `getOpsLogs(buildLogQueryParams(...))` 并通过 `normalizeLogRows()` 归一化，不再依赖 `LegacyConsoleView.onMounted()`、`LegacyConsoleView.refreshAll()` 或 Legacy 内部 `loadLogs()`。
- Log 设备过滤下拉来源切到 `useDeviceStore()` 的 `devices`，进入页面时调用 `deviceStore.refresh()`，不从 Legacy 传入设备列表。
- Log 页面本地状态保留在 View 内：`logs`、`logLevel`、`logDeviceId`、`logLogger`、`logThread`、`logKeyword`、`logLimit`、`logAutoRefresh`、`loading`、`error`、`exceptionLoading`。
- 查询参数继续通过 `buildLogQueryParams()` 构造，只向后端发送既有 `level`、`logger`、聚合后的 `keyword`、`limit`，不新增后端不存在的 `deviceId` / `thread` 参数。
- 前端精筛继续使用 `filterLogRows()`，摘要继续使用 `summarizeLogRows()`。
- TXT / JSON 导出继续使用 `exportLogRowsAsText()`、`exportLogRowsAsJson()` 和 `buildLogExportFilename()`。
- 错误日志快速定位保留：设置 `logLevel = "ERROR"` 后重新加载日志。
- 最近异常定位保留：LogView 点击时单独请求 `getExceptionStats()`，使用 `buildLogSearchFromException()` 填充关键词后加载日志；不调用 `loadOverview()`、`runDiagnostic()` 或 `refreshAll()`。
- Alarm 页“定位日志”交互已调整为跳转 `/log?deviceId=...&keyword=...`，由新 `LogView.vue` 读取 route query 并独立加载日志。
- 5 秒自动刷新 Timer 迁入 `LogView.vue` 生命周期：`watch(logAutoRefresh)` 启停，`onBeforeUnmount()` 清理；Legacy 中主 Log 页的 `logTimer` 已删除。
- `src/views/legacy/log-utils.ts` 与 `src/views/legacy/log-utils.test.ts` 已迁移到 `src/features/log/utils/`。
- `LogPanel.vue` 已改为复用 `features/log/utils/log-utils.ts` 中的 `buildLogQueryParams()`、`filterLogRows()`、`exportLogRowsAsText()`、`buildLogExportFilename()`，继续服务 DeviceWorkbench 单设备日志场景。
- 搜索确认 `exportLogRows()` 只剩 ops-utils 与 LogPanel 引用后，将它从 `src/views/ops/ops-utils.ts` 删除，并删除 `ops-utils.test.ts` 中对应测试；Log TXT 导出统一由 feature log utils 承担。
- 从 `LegacyConsoleView.vue` 删除主 Log template、页面级 Log state、`filteredLogs`、`logSummary`、`loadLogs()`、`showErrorLogs()`、`searchLatestExceptionLogs()`、`downloadLogs()`、`shortLoggerName()` 与主 Log 自动刷新 Timer。
- 为保留 Diagnostic 诊断包里的日志样本能力，Legacy 未保留主 Log 页面状态，而是在导出诊断包时通过 `loadDiagnosticLogSample()` 最小化请求 `getOpsLogs({ limit: 50 })`；这不影响 `/log` 独立页面。
- Log 专属样式从 `legacy-console.css` 迁入 `LogView.vue` scoped style，包括 `modao-log-*` 与 `log-toolbar` 布局；公共 `section-heading`、`exact-page`、`exact-toolbar`、`exact-table-card`、`exact-diagnostic-card` 等仍保留公共 legacy 样式。

## Phase 4 验证结果

执行时间：2026-08-28 14:16-14:22，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 29 个测试文件、149 个测试通过；`src/features/log/utils/log-utils.test.ts` 保留并通过，`router.test.ts` 覆盖 `/dashboard -> DashboardView`、`/realtime -> RealtimeView`、`/log -> LogView` 和其它过渡页面仍走 `LegacyConsoleView` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `LogView` 与 `log-utils` chunks |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 24 |
| `git diff --check` | 通过 | exit code 0；仅有 Git 对部分 Web 构建产物 LF/CRLF 的提示，无空白错误 |

Phase 4 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 4 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5174/#/dashboard` 能打开，页面文本显示“控制台总览”，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5174/#/realtime` 能打开，页面文本显示“实时数据查询”，仍由 `RealtimeView.vue` 承载。
- `/log`：dev server `http://127.0.0.1:5174/#/log` 能打开，页面文本显示“日志”“自动刷新”“刷新日志”“全部级别”“全部设备”“记录器名称 logger”“线程名 thread”“错误日志快速定位”“最近异常定位”“导出文本”“导出 JSON”和日志摘要卡片。
- `/device`：dev server `http://127.0.0.1:5174/#/device` 能打开，仍显示 Legacy 设备管理页面。
- `/alarm`：dev server `http://127.0.0.1:5174/#/alarm` 能打开，仍显示 Legacy 告警历史中心。
- 搜索确认 `LegacyConsoleView.vue` 中已无 `activeModule === 'log'`、主 Log 页面 state、主 Log `loadLogs()` / `downloadLogs()` / 快速定位函数、`logTimer`。
- 搜索确认 `src/views/legacy/` 下已无 `log-utils.ts` / `log-utils.test.ts`。
- 搜索确认 `src/styles/legacy-console.css` 与 `src/styles/workbench.css` 已无主 Log 页面 `modao-log-*` / `log-toolbar` 专属选择器。

## Phase 4 新增文件

- `collector-desktop/src/views/log/LogView.vue`
- `collector-desktop/src/features/log/utils/log-utils.ts`
- `collector-desktop/src/features/log/utils/log-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/LogView-Bhpwtpjx.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/LogView-DSQ3DSbd.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/log-utils-BEPZH2al.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/monitor.api-DGr9dOcd.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/data.api-BuLcx8kQ.js`（`build:web` 生成）

## Phase 4 修改文件

- `collector-desktop/src/components/log/LogPanel.vue`
- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/src/views/ops/ops-utils.ts`
- `collector-desktop/src/views/ops/ops-utils.test.ts`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 4 删除文件

- `collector-desktop/src/views/legacy/log-utils.ts`
- `collector-desktop/src/views/legacy/log-utils.test.ts`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 5：Alarm 迁移完成内容

- 新增 `src/views/alarm/AlarmView.vue`，承载原 `activeModule === "alarm"` 的主菜单“告警历史中心”页面。
- `/alarm` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `AlarmView.vue`；当前 `/dashboard`、`/realtime`、`/log` 继续分别直接指向 `DashboardView.vue`、`RealtimeView.vue`、`LogView.vue`。
- `/history`、`/device`、`/device/workbench`、`/collect`、`/cloud`、`/diagnostic`、`/network`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- Alarm 新增独立 `loadAlarms()`，进入 `/alarm` 后自行加载告警历史，不再依赖 `LegacyConsoleView.onMounted()`、`LegacyConsoleView.refreshAll()` 或 Legacy 内部 `loadAlarms()`。
- Alarm 设备过滤下拉来源切到 `useDeviceStore()` 的 `devices`，进入页面时调用 `deviceStore.refresh()`，不从 Legacy 传入设备列表。
- Alarm 页面本地状态保留在 View 内：`alarms`、`alarmDeviceId`、`alarmLevelFilter`、`alarmHours`、`alarmKeyword`、`alarmLimit`、`alarmAcknowledgements`、`alarmAckDialogVisible`、`selectedAlarmForAck`、`alarmAckNote`、`acknowledgingAlarmId`、`loading`、`ackStatusLoading`、`error`。
- 查询语义保持不变：有 `alarmDeviceId` 时调用 `getDeviceAlarmHistory(deviceId, query)`；无设备时调用 `getRecentAlarms(query)`。
- 查询参数继续通过 `buildAlarmHistoryQuery()` 构造，保持 `level`、`pointCode/keyword`、`ruleId`、`startTs`、`endTs`、`limit` 等既有后端契约，不修改后端接口。
- 告警历史响应继续通过 `normalizeAlarmHistoryRows()` 兼容 `alarms/records/rows/items/data` 以及 snake_case 字段。
- 确认状态批量查询保留：根据 `buildAlarmIdentity()` 生成告警 ID，调用 `queryAlarmAcknowledgements()`，通过 `normalizeAlarmAcknowledgementMap()` 与 `mergeAlarmAcknowledgementStates()` 合并 acknowledgement 明细。
- 单条告警确认保留：打开确认 Dialog，填写处理说明，使用 `buildAlarmAckPayload(note, alarmId)` 生成幂等 key，调用 `acknowledgeAlarm()`，成功后通过 `applyAlarmAcknowledgement()` 局部回写当前列表。
- acknowledgement 展示保留：`describeAlarmAcknowledgement()` 继续展示确认人、确认时间、确认说明；Dialog 继续展示 idempotency key。
- Alarm -> Log 联动保留 Router Query：`AlarmView` 使用 `buildAlarmTroubleshootTarget()` 后跳转 `/log?deviceId=...&keyword=...`，由 `LogView.vue` 独立接收并查询日志。
- Alarm -> Network 联动改为 Router Query：`AlarmView` 使用 `buildAlarmTroubleshootTarget()` 后跳转 `/network?target=...&port=...`；Network 仍在 Legacy 阶段，本 Phase 只做最小 query 兼容，不迁移 `NetworkView`。
- `LegacyConsoleView.vue` 的 Network 旧页面新增最小 `target/port` query 读取逻辑，支持 AlarmView 通过路由交接检测目标。
- `src/views/legacy/alarm-history-utils.ts` 与 `src/views/legacy/alarm-history-utils.test.ts` 已迁移到 `src/features/alarm/utils/`，测试保留并通过。
- `src/views/ops/ops-utils.ts` 中 Alarm 专属纯函数已迁移到 `src/features/alarm/utils/alarm-utils.ts`，包括 `buildAlarmAckPayload()`、`buildAlarmIdentity()`、`mergeAlarmAcknowledgementStates()`、`normalizeAlarmAcknowledgementMap()`、`describeAlarmAcknowledgement()`、`applyAlarmAcknowledgement()`、`buildAlarmTroubleshootTarget()`、`summarizeAlarms()`，并补充 `alarmCurrentValue()`、`alarmLevelText()` 的主 Alarm 页面展示测试。
- `ops-utils.ts` 继续保留 Report / Diagnostic / Network 相关函数，未提前拆出其它领域。
- `AlarmTablePanel.vue` 已改为复用 `features/alarm/utils/alarm-utils.ts` 中的 `buildAlarmAckPayload()` 与 `summarizeAlarms()`；其 `alarmContent()`、`levelText()`、`alarmStatusText()` 因主页面/工作台文案语义不同，本阶段不强行统一。
- 从 `LegacyConsoleView.vue` 删除主 Alarm template、页面级 Alarm state、`alarmHistorySummary`、`alarmScopeText`、ack dialog computed、`loadAlarms()`、`refreshAlarmAcknowledgements()`、`openAlarmAcknowledgementDialog()`、`closeAlarmAcknowledgementDialog()`、`submitAlarmAcknowledgement()`、`locateAlarmLogs()`、`diagnoseAlarmNetwork()`、`alarmCurrentValue()`、`alarmLevelText()` 等主 Alarm 实现。
- 为保留 Diagnostic 诊断包里的告警样本能力，Legacy 未保留主 Alarm 页面状态，而是在导出诊断包时通过 `loadDiagnosticAlarmSample()` 最小化请求 `getRecentAlarms({ limit: 20 })`。
- Alarm 专属样式从 `legacy-console.css` 迁入 `AlarmView.vue` scoped style，包括 `alarm-ack-table`、`alarm-ack-detail`、`alarm-action-row`、`alarm-ack-backdrop`、`alarm-ack-dialog`、`alarm-ack-target`、`alarm-ack-idempotency`；公共 `section-heading`、`exact-page`、`exact-toolbar`、`exact-table-card`、`status-badge`、`exact-diagnostic-card` 等仍保留公共 legacy 样式。

## Phase 5 验证结果

执行时间：2026-08-28 14:53-14:58，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 30 个测试文件、151 个测试通过；新增 `src/features/alarm/utils/alarm-utils.test.ts`，`src/features/alarm/utils/alarm-history-utils.test.ts` 保留并通过，`router.test.ts` 覆盖 `/dashboard -> DashboardView`、`/realtime -> RealtimeView`、`/log -> LogView`、`/alarm -> AlarmView` 和其它过渡页面仍走 `LegacyConsoleView` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `AlarmView` 与 `alarm-utils` chunks |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 28 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 5 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 5 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5175/#/dashboard` 能打开，页面文本显示“控制台总览”，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5175/#/realtime` 能打开，页面文本显示“实时数据查询”，仍由 `RealtimeView.vue` 承载。
- `/log`：dev server `http://127.0.0.1:5175/#/log?deviceId=dev-1&keyword=temp` 能打开，仍由 `LogView.vue` 承载，query 交接状态下页面不崩溃。
- `/alarm`：dev server `http://127.0.0.1:5175/#/alarm` 能打开，页面文本显示“告警历史中心”“确认状态批量查询”“刷新告警历史”“全部设备最近告警”“全部级别”“最近 24 小时/3 天/7 天”“告警总数/未确认/已确认/严重/警告”“当前值”“确认状态”“确认信息”“操作”。
- `/alarm?level=WARNING&keyword=temp&hours=72&limit=20`：能打开，query 交接状态下页面不崩溃。
- `/device`：dev server `http://127.0.0.1:5175/#/device` 能打开，仍显示 Legacy 设备管理页面。
- `/network?target=127.0.0.1&port=502`：dev server 能打开，Network Legacy 页面已显示 `TCP`、目标 `127.0.0.1`、端口 `502`，证明 Alarm -> Network 的 route query 交接最小兼容可用。
- `/device/workbench`：dev server 能打开，设备工作台未选择设备时不崩溃，仍显示“告警历史”分区入口；`AlarmTablePanel.vue` 编译、类型检查和构建均通过。
- 搜索确认 `LegacyConsoleView.vue` 中已无 `activeModule === 'alarm'`、主 Alarm 页面 state、主 Alarm `loadAlarms()` / acknowledgement dialog / Log 和 Network 定位函数。
- 搜索确认 `src/views/legacy/` 下已无 `alarm-history-utils.ts` / `alarm-history-utils.test.ts`。
- 搜索确认 `src/styles/legacy-console.css` 与 `src/styles/workbench.css` 已无主 Alarm 页面 `alarm-ack-*` / `alarm-action-*` 专属选择器。

## Phase 5 新增文件

- `collector-desktop/src/views/alarm/AlarmView.vue`
- `collector-desktop/src/features/alarm/utils/alarm-history-utils.ts`
- `collector-desktop/src/features/alarm/utils/alarm-history-utils.test.ts`
- `collector-desktop/src/features/alarm/utils/alarm-utils.ts`
- `collector-desktop/src/features/alarm/utils/alarm-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/AlarmView--Upfw_c7.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/AlarmView-D2FKGM9K.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/alarm-utils-4gXIzPsW.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/ops.api-B0jzCTFs.js`（`build:web` 生成）

## Phase 5 修改文件

- `collector-desktop/src/components/alarm/AlarmTablePanel.vue`
- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/views/dashboard/DashboardView.vue`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/src/views/legacy/LegacyHistoryPanel.vue`
- `collector-desktop/src/views/ops/ops-utils.ts`
- `collector-desktop/src/views/ops/ops-utils.test.ts`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 5 删除文件

- `collector-desktop/src/views/legacy/alarm-history-utils.ts`
- `collector-desktop/src/views/legacy/alarm-history-utils.test.ts`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## 已知问题与回归风险

- `LegacyConsoleView.vue` 仍然承载 History / Device / Collection / Cloud / Diagnostic / Network / Workbench / Control / Shadow 等旧业务页面，是后续逐页迁移的主要对象。
- 为避免破坏旧页面，Legacy 内部仍暂时保留 `devices/runtimeMap/selectedDeviceId`、`selectedRealtimeRows`、监控指标、配置摘要、上报链路、网络诊断等共享或工作台状态；具体页面迁移时再收敛到 Pinia 或页面级 composable。
- 主 `/log` 页面继续保持 HTTP 查询语义，没有修改后端 `/api/ops/logs` 契约。
- 主 `/alarm` 页面继续保持现有告警历史与确认 API 语义，没有修改后端历史告警或告警确认契约。
- 本地 dev server 检查在后端采集服务未启动/不可达状态下完成，验证了页面和路由可打开、空数据/服务不可达显示不崩溃；真实告警数据查询、确认状态查询、单条确认提交、Alarm -> Log / Network 真实业务结果仍依赖后端服务与运行数据环境。
- Vite dev server 首次尝试使用 5174 时发现端口已占用，改用 5175 完成 smoke check；用于 smoke check 的 5175 dev server 已停止。
- Web 静态产物 hash 因 `build:web` 更新，属于验证命令产生的预期变更。

## 下一步

等待确认后进入 Phase 6：迁移 Network。

Phase 6 只迁移 Network，不自动进入 History、Device、Collection、Cloud、Diagnostic 或其它业务页面。

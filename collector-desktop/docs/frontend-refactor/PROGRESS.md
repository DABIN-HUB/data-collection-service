# collector-desktop 前端架构重构进度

更新时间：2026-09-02 09:11:44 +0800

## 当前状态

- 当前目标分支：`feature_2.0`
- 最近提交：
  - `20e360d` 修改
  - `857360a` 修改
  - `737c3c9` 修改
  - `47670f6` xg
  - `e4f7add` xg
- Phase 1：已完成并通过验证。
- Phase 2：Dashboard 迁移已完成并通过验证。
- Phase 3：Realtime 迁移已完成并通过验证。
- Phase 4：Log 迁移已完成并通过验证。
- Phase 5：Alarm 迁移已完成并通过验证。
- Phase 6：Network 迁移已完成并通过验证。
- Phase 7：Cloud 迁移已完成并通过验证。
- Phase 8：Diagnostic 迁移已完成并通过验证。
- Phase 9：History 迁移已完成并通过验证。
- Phase 10：Collection 迁移已完成并通过验证。
- Phase 11：Device List 迁移已完成并通过验证。
- Phase 12：Device Workbench 迁移已完成并通过验证。
- Phase 13：Local Device Editor 整理与迁移已完成并通过验证。
- Phase 14：Point Feature 迁移与整理已完成并通过验证。
- Phase 15：Remove Legacy Host 已完成并通过验证。
- Phase 16：CSS Migration / Cleanup 已完成并通过验证。
- 当前阶段：Phase 17 Final Code Quality Gate，执行最终代码质量门禁、死代码审查和文档收尾。

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

## Phase 6：Network 迁移完成内容

- 新增 `src/views/network/NetworkView.vue`，承载原 `activeModule === "network"` 的“网络检测”页面。
- `/network` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `NetworkView.vue`；当前 `/dashboard`、`/realtime`、`/log`、`/alarm`、`/network` 均为独立 View。
- `/history`、`/device`、`/device/workbench`、`/collect`、`/cloud`、`/diagnostic`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- Network 新增独立 `runNetwork()`，按页面表单状态调用 `buildNetworkDiagnosticPayload()`，再调用 `diagnoseNetwork()`，然后通过 `normalizeNetworkDiagnosticResult()` 写入 `networkResult` 并用 `appendNetworkHistory(..., 10)` 保留最多 10 条历史。
- Network 页面状态保留在 View 内：`networkType`、`networkDeviceId`、`networkTarget`、`networkPort`、`networkTimeout`、`networkOperating`、`networkResult`、`networkHistory`。
- Network 设备列表来源切到 `useDeviceStore()` 的 `devices`，进入页面时调用 `deviceStore.refresh()`，不再维护另一套长期 `devices = ref([])`。
- PING / TRACE / TCP 表单语义保持不变：仍通过 `NETWORK_DIAGNOSTIC_TYPES` 渲染，`buildNetworkDiagnosticPayload()` 统一校验；PING/TRACE 不发送无意义 port；TCP 必须校验 1-65535；timeout 保持 100-10000 ms 限制。
- 设备配置带入保留：`fillNetworkFromSelectedDevice()` 与 `applyNetworkDevice()` 使用 `resolveNetworkTargetFromDevice()`，不在 View 中重新写 `device.ipAddress || device.host` 第二套解析逻辑。
- Alarm -> Network route query 由 `NetworkView.vue` 接管：支持 `target`、`port`、`type`、`deviceId`；`target+port` 会填充目标并切到 TCP，只有 `target` 且无显式 `type` 时保持 PING；query 变化时通过 watcher 重新应用，不自动发起网络检测。
- 结果行继续通过 `networkResultRows = computed(() => networkResult.value ? buildNetworkResultRows(networkResult.value) : [])` 构造；TRACE `details` 路由明细继续显示。
- 报告导出继续使用 `buildNetworkExportText(networkHistory.value)`，View 只负责 Blob 下载。
- `src/views/legacy/network-utils.ts` 与 `src/views/legacy/network-utils.test.ts` 已迁移到 `src/features/network/utils/`，保留 `NETWORK_DIAGNOSTIC_TYPES`、`buildNetworkDiagnosticPayload()`、`resolveNetworkTargetFromDevice()`、`normalizeNetworkDiagnosticResult()`、`buildNetworkResultRows()`、`appendNetworkHistory()`、`buildNetworkExportText()` 及相关类型。
- `src/views/legacy/LegacyEdgeTelemetryPanel.vue` 已迁移为 `src/features/network/components/EdgeTelemetryPanel.vue`。
- `src/views/legacy/edge-telemetry-utils.ts` 与 `src/views/legacy/edge-telemetry-utils.test.ts` 已迁移到 `src/features/network/utils/`。
- `EdgeTelemetryPanel.vue` 仅调整目录与依赖路径，继续使用 `ingestEdgeTelemetry()`，保留快捷表单、原始 JSON、gatewayId、protocol、configVersion、deviceId、pointRef、valueType、value、quality、timestamp、sequence、提交遥测、刷新时间戳/序号、请求预览和响应预览。
- `EdgeTelemetryPanel.vue` 继续通过 `select-device` 事件通知父级，`NetworkView.vue` 将页面本地 `networkDeviceId` 作为 `selected-device-id` 传入，不操作全局 selectedDeviceId。
- `src/views/ops/ops-utils.ts` 中无用 Network 重复函数 `formatNetworkResult()` 已删除，并删除对应 `ops-utils.test.ts` 测试；Report / Diagnostic 函数仍保留在 `ops-utils.ts`，未提前拆分。
- 从 `LegacyConsoleView.vue` 删除主 Network template、Network 页面级 state、`networkResultRows`、`runNetwork()`、`applyNetworkDevice()`、`fillNetworkFromSelectedDevice()`、`syncNetworkMode()`、`downloadNetworkReport()`，以及 Phase 5 临时加入 Legacy 的 `applyNetworkRouteQuery()` / route query watcher。
- 诊断包不再依赖已迁出 Network 页面会话状态；Legacy 中不再保留 `networkHistory` 作为跨页面缓存。
- Network 专属样式从 `legacy-console.css` 迁入 `NetworkView.vue` scoped style，包括 `network-toolbar`、`network-toolbar-actions`、`network-summary-cards`、`network-result-panel .json-view`、`network-result-grid`、`network-trace-lines`、`network-history-table`。
- EdgeTelemetry 专属样式从 `legacy-console.css` 迁入 `EdgeTelemetryPanel.vue` scoped style，包括 `edge-mode-row`、`edge-action-row`、`edge-form-grid textarea`、`edge-json-grid`。
- 公共 `section-heading`、`exact-page`、`exact-page-body`、`exact-toolbar`、`exact-table-card`、`exact-surface`、`exact-diagnostic-card`、`exact-config-item`、`status-badge`、`json-view`、`form-grid` 等仍保留公共 legacy 样式。

## Phase 6 验证结果

执行时间：2026-08-28 15:21-15:26，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 30 个测试文件、152 个测试通过；`src/features/network/utils/network-utils.test.ts` 与 `src/features/network/utils/edge-telemetry-utils.test.ts` 保留并通过，`router.test.ts` 覆盖 `/network -> NetworkView` 和 `/network?target=127.0.0.1&port=502` resolve |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `NetworkView` chunk |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 30 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误，Git 仅提示部分 Web 构建产物 LF/CRLF 工作区换行转换 |

Phase 6 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 6 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5176/#/dashboard` 能打开，页面文本显示“控制台总览”，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5176/#/realtime` 能打开，页面文本显示“实时数据查询”，仍由 `RealtimeView.vue` 承载。
- `/log`：dev server `http://127.0.0.1:5176/#/log` 能打开，仍由 `LogView.vue` 承载。
- `/alarm`：dev server `http://127.0.0.1:5176/#/alarm` 能打开，仍由 `AlarmView.vue` 承载。
- `/network?target=127.0.0.1&port=502`：dev server 能打开，页面文本显示“网络检测”“PING 可达性”“TRACE 路由跟踪”“TCP 端口”“从设备配置带入”“开始检测”“导出检测结果”“检测方式 TCP”“检测结果 127.0.0.1:502”“尚未执行网络检测”“最多保留 10 条”“边缘遥测调试”。说明 Alarm -> Network 的 `target/port` query 由 `NetworkView.vue` 接收，并且没有自动执行网络检测。
- `/network?target=127.0.0.1&type=TRACE`：dev server 能打开，页面文本显示“检测方式 TRACE”“检测结果 127.0.0.1”，证明显式 type query 可接收。
- `/device`：dev server `http://127.0.0.1:5176/#/device` 能打开，仍显示 Legacy 设备管理页面。
- 搜索确认 `LegacyConsoleView.vue` 中已无 `activeModule === 'network'`、Network 页面 state、主 Network `runNetwork()` / route query watcher / report export 函数。
- 搜索确认 `src/views/legacy/` 下已无 `network-utils*`、`edge-telemetry-utils*`、`LegacyEdgeTelemetryPanel.vue`。
- 搜索确认 `src/styles/legacy-console.css` 与 `src/styles/workbench.css` 已无 Network / EdgeTelemetry 专属选择器。

## Phase 6 新增文件

- `collector-desktop/src/views/network/NetworkView.vue`
- `collector-desktop/src/features/network/components/EdgeTelemetryPanel.vue`
- `collector-desktop/src/features/network/utils/network-utils.ts`
- `collector-desktop/src/features/network/utils/network-utils.test.ts`
- `collector-desktop/src/features/network/utils/edge-telemetry-utils.ts`
- `collector-desktop/src/features/network/utils/edge-telemetry-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/NetworkView-9MUMl5H9.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/NetworkView-BvkqIyXT.js`（`build:web` 生成）

## Phase 6 修改文件

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

## Phase 6 删除文件

- `collector-desktop/src/views/legacy/LegacyEdgeTelemetryPanel.vue`
- `collector-desktop/src/views/legacy/edge-telemetry-utils.ts`
- `collector-desktop/src/views/legacy/edge-telemetry-utils.test.ts`
- `collector-desktop/src/views/legacy/network-utils.ts`
- `collector-desktop/src/views/legacy/network-utils.test.ts`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 7：Cloud 迁移完成内容

- 新增 `src/views/cloud/CloudView.vue`，承载原 `activeModule === "cloud"` 的“云平台配置 / 上报链路”页面。
- `/cloud` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `CloudView.vue`；当前 `/dashboard`、`/realtime`、`/log`、`/alarm`、`/network`、`/cloud` 均为独立 View。
- `/history`、`/device`、`/device/workbench`、`/collect`、`/diagnostic`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- `CloudView.vue` 只持有页面级 `reportMetrics`、`loading`、`error`、`lastRefresh` 状态，不引入 `useDeviceStore()`，不新建 `cloud.store.ts`。
- `CloudView.vue` 新增独立 `loadCloud()` / `refreshCloud()`，只调用 `getCloudReportMetrics()`，不复制或调用 `loadOverview()`。
- “刷新链路”按钮改为调用 `refreshCloud()`，只刷新 `/monitor/report` 指标，不刷新运行状态、系统资源、配置摘要、缓存、设备连接、性能、异常、存储等其它监控接口。
- 页面展示能力保持：云上报状态、云上报是否启用、待发送、待 ACK、隔离消息、上报模式、云服务商、可上报点位、批量聚合、ACK 提交点、ACK 超时、可靠发件箱、ACK 成功/失败、链路风险和原始上报链路 JSON。
- 没有新增编辑云配置、保存配置、MQTT 参数编辑、云服务商配置表单、测试连接或配置提交功能。
- 新增 `src/features/cloud/utils/cloud-report-utils.ts`，收敛 Cloud / Report 纯转换逻辑：`cloudStatusText()`、`buildCloudEnabledText()`、`buildCloudSummaryCards()`、`buildCloudStrategyRows()`、`buildCloudOperationalRows()`、`buildCloudRisks()`、`summarizeReportMetrics()`。
- 新增 `src/features/cloud/utils/cloud-report-utils.test.ts`，覆盖 status 中文映射、启用显示、Outbox、ACK runtime、configured、batch、risks 和原 `summarizeReportMetrics()` 摘要逻辑。
- `summarizeReportMetrics()` 已从 `src/views/ops/ops-utils.ts` 迁出到 `src/features/cloud/utils/cloud-report-utils.ts`，对应测试也从 `ops-utils.test.ts` 迁出。
- `src/views/ops/ops-utils.ts` 现在只保留 `buildDiagnosticAdvice()` 及其私有辅助函数，继续作为 Diagnostic 过渡 helper，不提前迁 Diagnostic。
- 从 `LegacyConsoleView.vue` 删除主 Cloud template、Cloud 页面 computed：`cloudStatusTextValue`、`cloudEnabledText`、`cloudSummaryCards`、`cloudStrategyRows`、`cloudOperationalRows`、`cloudRisks`。
- `cloudStatusText()` 已不再在 `LegacyConsoleView.vue` 本地定义；Diagnostic 仍需要 status 中文映射，所以改为小范围复用 `features/cloud/utils/cloud-report-utils.ts` 中的 `cloudStatusText()`，没有重构 Diagnostic 页面结构。
- `LegacyConsoleView.vue` 的 `loadActiveLegacyModule()` 已删除 `module === "cloud"` 分支，现在只在 `module === "collect"` 时调用 `loadOverview()`，`module === "diag"` 仍走 `runDiagnostic()`。
- Legacy 中的 `reportMetrics` 暂时保留，因为 Diagnostic 的 `diagnosticRows`、`buildDiagnosticRaw()`、诊断包导出仍依赖云端上报指标；这是 Legacy Diagnostic 的独立快照，不与 `CloudView.vue` 或 `DashboardView.vue` 共享 mutable ref。
- Cloud 专属样式 `exact-cloud-grid`、`exact-cloud-status`、`exact-cloud-icon`、`cloud-stat-row` 已从 `legacy-console.css` 迁入 `CloudView.vue` scoped style。
- `modao-property-grid`、`modao-property-item`、`modao-risk-list`、`modao-risk-item` 仍被 `LegacyDiagnosticDetailPanel.vue` 使用，暂时保留在 `legacy-console.css` 中。
- 公共 `section-heading`、`heading-title-line`、`heading-actions`、`heading-online`、`exact-page`、`exact-page-body`、`exact-surface`、`exact-surface-head`、`exact-json-panel`、`json-view` 仍保留公共 legacy 样式。

## Phase 7 验证结果

执行时间：2026-08-31 08:39-08:49，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 31 个测试文件、169 个测试通过；新增 `src/features/cloud/utils/cloud-report-utils.test.ts`，`router.test.ts` 覆盖 `/cloud -> CloudView` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `CloudView` 与 `cloud-report-utils` chunks |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 33 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 7 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 7 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5177/#/dashboard` 能打开，页面文本显示“控制台总览”，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5177/#/realtime` 能打开，页面文本显示“实时数据查询”，仍由 `RealtimeView.vue` 承载。
- `/log`：dev server `http://127.0.0.1:5177/#/log` 能打开，仍由 `LogView.vue` 承载。
- `/alarm`：dev server `http://127.0.0.1:5177/#/alarm` 能打开，仍由 `AlarmView.vue` 承载。
- `/network?target=127.0.0.1&port=502`：dev server 能打开，仍由 `NetworkView.vue` 承载。
- `/cloud`：dev server `http://127.0.0.1:5178/#/cloud` 能打开，页面文本显示“云平台配置”“刷新链路”“未知”“上报策略”“Outbox / ACK 明细”“链路风险”“查看原始上报链路 JSON”；后端不可达时展示“无法连接采集服务，请检查服务地址和后端是否已启动”，页面未崩溃。
- `/device`：dev server `http://127.0.0.1:5177/#/device` 能打开，仍显示 Legacy 设备管理页面。
- `/diagnostic`：dev server `http://127.0.0.1:5177/#/diagnostic` 能打开，仍显示 Legacy 系统诊断页面；云端上报诊断行可展示“未知”。
- 代码检查确认 `CloudView.vue` 中没有 `getRuntimeStatus()`、`getSystemResources()`、`getConfigSummary()`、`getCacheMetrics()`、`getDeviceConnectionMetrics()`、`getCollectorPerformance()`、`getExceptionStats()`、`getStorageMetrics()`、`getPerformanceDetail()`、`useDeviceStore()`、`deviceStore` 或 `loadOverview()`。
- 代码检查确认 `CloudView.vue` 只直接调用 `getCloudReportMetrics()`；`AppShell` 的 `appStore.initialize()` 只做本地配置/桌面桥初始化，不发起 Overview 监控接口请求。
- 代码检查确认 `LegacyConsoleView.vue` 中已无 `activeModule === 'cloud'`、Cloud 页面 computed、Cloud 页面 template 和 `module === "cloud"` 初始化分支。
- 搜索确认 `src/styles/legacy-console.css` 中已无 `exact-cloud-grid`、`exact-cloud-status`、`exact-cloud-icon`、`cloud-stat-row`。
- 搜索确认仓库未新增 `cloud.store.ts`。

## Phase 7 新增文件

- `collector-desktop/src/views/cloud/CloudView.vue`
- `collector-desktop/src/features/cloud/utils/cloud-report-utils.ts`
- `collector-desktop/src/features/cloud/utils/cloud-report-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/CloudView-BAlPB5eO.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/CloudView-D7KgGvmM.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/cloud-report-utils-DuSH22lD.js`（`build:web` 生成）

## Phase 7 修改文件

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

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 8：Diagnostic 迁移完成内容

- 新增 `src/views/diagnostic/DiagnosticView.vue`，承载原 `activeModule === "diag"` 的“系统实时状态诊断”页面。
- `/diagnostic` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `DiagnosticView.vue`；当前 `/dashboard`、`/realtime`、`/log`、`/alarm`、`/network`、`/cloud`、`/diagnostic` 均为独立 View。
- `/history`、`/device`、`/device/workbench`、`/collect`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- `DiagnosticView.vue` 页面级聚合快照保留在 View 内：`runtimeStatus`、`systemResource`、`reportMetrics`、`configSummary`、`cacheMetrics`、`deviceConnectionMetrics`、`collectorPerformance`、`exceptionStats`、`storageMetrics`、`performanceDetail`、`diagnosticRaw`、`loading`、`error`。
- `DiagnosticView.vue` 使用 `useDeviceStore()` 提供设备领域状态：`devices`、`selectedDeviceId`、`selectedDevice`、`onlineCount`、`totalPointCount`，进入页面时按需调用 `deviceStore.refresh()`，不再维护长期 `devices = ref([])` / `selectedDeviceId = ref("")` 副本。
- 新增独立 `loadDiagnostic()`，先应用 `route.query.deviceId`，再通过 `Promise.allSettled()` 并行加载 Diagnostic 真正需要的监控数据：`getRuntimeStatus()`、`getSystemResources()`、`getCloudReportMetrics()`、`getConfigSummary()`、`getCacheMetrics()`、`getDeviceConnectionMetrics()`、`getCollectorPerformance()`、`getExceptionStats()`、`getStorageMetrics()`、`getPerformanceDetail()`。
- `runDiagnostic()` 现在只调用 `loadDiagnostic()`，不再调用 Legacy `loadOverview()` 或 `refreshAll()`；`onMounted()` 保持进入 `/diagnostic` 自动运行完整诊断。
- `loadDiagnostic()` 独立容错：单个监控接口失败只记录部分不可用提示，已有数据继续展示；后端整体不可达时页面显示“无法连接采集服务，请检查服务地址和后端是否已启动”，不崩溃。
- 诊断包导出迁入 `DiagnosticView.vue`，继续包含 `generatedAt`、`selectedDeviceId`、`selectedDevice`、`overview` / `diagnosticRaw`、`alarms`、`logs`、`runtimeSummary`。
- 诊断包中的告警样本继续在导出时最小化调用 `getRecentAlarms({ limit: 20 })` 并通过 `normalizeAlarmHistoryRows()` 归一化，不依赖 `AlarmView` 状态。
- 诊断包中的日志样本继续在导出时最小化调用 `getOpsLogs({ limit: 50 })` 并通过 `normalizeLogRows()` 归一化，不依赖 `LogView` 状态。
- Device -> Diagnostic 跨页跳转改为 Router Query：`openDeviceRuntimeStatus()` 现在跳转 `{ path: "/diagnostic", query: { deviceId } }`，不再 `switchModule("diag")`。
- `DiagnosticView.vue` 支持 `/diagnostic?deviceId=dev-1`：读取 `route.query.deviceId` 后调用 `deviceStore.selectDevice(deviceId)`，并在 query 后续变化时同步更新；`DeviceRuntimePanel` 通过 props 接收选中设备。
- `src/views/legacy/LegacyDiagnosticDetailPanel.vue` 已迁移并改名为 `src/features/diagnostic/components/DiagnosticDetailPanel.vue`，新 DiagnosticView 不再反向依赖 `views/legacy/*`。
- `src/views/legacy/LegacyDeviceRuntimePanel.vue` 已迁移并改名为 `src/features/diagnostic/components/DeviceRuntimePanel.vue`，组件继续通过 `devices`、`selectedDeviceId` props 与 `select-device` 事件保持低耦合，不直接依赖整个 `deviceStore`。
- `DeviceRuntimePanel` 继续使用 `getRunningDevices()`、`getDeviceRuntime()`、`getDeviceStatus()`、`isDeviceRunning()`，保留刷新运行列表、查询单设备状态、检查是否运行、运行态摘要、运行设备表格和单设备状态 JSON。
- `src/views/legacy/diagnostic-detail-utils.ts` 与测试已迁移到 `src/features/diagnostic/utils/diagnostic-detail-utils.ts` / `.test.ts`，保留 `buildCacheDetail()`、`buildDeviceConnectionRows()`、`buildPerformanceDetail()`、`buildExceptionDetail()`、`buildStorageDetail()`。
- `src/views/legacy/device-runtime-utils.ts` 与测试已迁移到 `src/features/diagnostic/utils/device-runtime-utils.ts` / `.test.ts`，保留 `normalizeRunningDeviceIds()`、`normalizeDeviceRuntimeRows()`、`normalizeDeviceStatusDetail()`、`normalizeDeviceRunningFlag()`、`buildDeviceRuntimeSummary()`。
- 新增 `src/features/diagnostic/utils/diagnostic-utils.ts` / `.test.ts`，迁移 `buildDiagnosticAdvice()`，并抽出 `buildResourceSummary()`、`buildDiagnosticCards()`、`buildDiagnosticRows()`、`buildDiagnosticRaw()`、`buildDiagnosticRuntimeSummary()`、`hasDiagnosticData()` 等纯数据转换。
- `src/views/ops/ops-utils.ts` 与 `src/views/ops/ops-utils.test.ts` 已无剩余有效职责并删除；`src/views/ops/` 目录已为空并删除。
- 从 `LegacyConsoleView.vue` 删除主 Diagnostic template、`LegacyDiagnosticDetailPanel` / `LegacyDeviceRuntimePanel` imports、Diagnostic 页面 state/computed/function/API imports：`runtimeStatus`、`systemResource`、`reportMetrics`、`cacheMetrics`、`deviceConnectionMetrics`、`collectorPerformance`、`exceptionStats`、`storageMetrics`、`performanceDetail`、`diagnosticRaw`、`resourceSummary`、`diagnosticCards`、`diagnosticRows`、`runDiagnostic()`、`buildDiagnosticRaw()`、`downloadDiagnosticPackage()`、`loadDiagnosticAlarmSample()`、`loadDiagnosticLogSample()`。
- `configSummary` 与 `getConfigSummary()` 继续保留在 Legacy，因为 `/collect` 的 `collectionSummaryItems` 仍真实依赖配置摘要。
- `LegacyConsoleView.vue` 已删除 `loadOverview()`；Collection 的“刷新概览”和 `loadActiveLegacyModule("collect")` 改为只调用 `loadConfigSummary()`。
- `refreshAll()` 已收敛为 `Promise.allSettled([loadProtocols(), loadDevices(), loadConfigSummary()])`，不再调用 `runDiagnostic()` 或加载已迁出的 Dashboard / Cloud / Diagnostic 监控接口。
- Diagnostic 专属样式 `diagnostic-detail-panel`、`device-runtime-panel`、`diagnostic-detail-grid`、`diagnostic-detail-cards`、`diagnostic-sub-card`、`diagnostic-connection-table`、`diagnostic-exception-table` 已从 `legacy-console.css` 迁入 `DiagnosticDetailPanel.vue` / `DeviceRuntimePanel.vue` scoped style。
- 公共样式 `section-heading`、`exact-page`、`exact-page-body`、`exact-surface`、`exact-table-card`、`exact-diagnostic-cards`、`exact-diagnostic-card`、`status-badge`、`exact-json-panel`、`json-view`、`modao-property-grid`、`modao-property-item`、`modao-risk-list`、`modao-risk-item` 仍被多个页面使用，按 Phase 边界暂不复制或删除。

## Phase 8 验证结果

执行时间：2026-08-31 09:11-09:15，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 31 个测试文件、175 个测试通过；新增 `src/features/diagnostic/utils/diagnostic-utils.test.ts`，迁移后的 diagnostic/detail/runtime utils 测试通过，`router.test.ts` 覆盖 `/diagnostic -> DiagnosticView` 和 `/diagnostic?deviceId=dev-1` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `DiagnosticView` 与 `device-runtime-utils` chunks，Diagnostic 纯工具随页面 chunk 打包 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 36 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 8 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 8 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5179/#/dashboard` 能打开，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5179/#/realtime` 能打开，仍由 `RealtimeView.vue` 承载。
- `/log`：dev server `http://127.0.0.1:5179/#/log` 能打开，仍由 `LogView.vue` 承载。
- `/alarm`：dev server `http://127.0.0.1:5179/#/alarm` 能打开，仍由 `AlarmView.vue` 承载。
- `/network?target=127.0.0.1&port=502`：dev server 能打开，仍由 `NetworkView.vue` 承载。
- `/cloud`：dev server `http://127.0.0.1:5179/#/cloud` 能打开，仍由 `CloudView.vue` 承载。
- `/diagnostic`：dev server `http://127.0.0.1:5179/#/diagnostic` 能打开，页面文本显示“系统实时状态诊断”“运行完整诊断”“导出诊断包”“诊断摘要”“诊断项列表”“诊断详情增强”“缓存服务明细”“性能详情”“设备连接指标”“异常统计 Top”“最慢设备 Top”“最近异常”“运行设备状态”“查看原始诊断 JSON”；后端不可达时显示“无法连接采集服务，请检查服务地址和后端是否已启动”，页面未崩溃。
- `/diagnostic?deviceId=dev-1`：dev server 能打开，单设备状态 JSON 中带入 `deviceId: "dev-1"`，后端不可达时展示“单设备状态查询失败”，页面未崩溃。
- `/device`：dev server `http://127.0.0.1:5179/#/device` 能打开，仍显示 Legacy 设备管理页面；设备“运行状态”动作代码已改为 `/diagnostic?deviceId=...` query 跳转。
- `/collect`：dev server `http://127.0.0.1:5179/#/collect` 能打开，仍显示 Legacy 采集配置页面；代码检查确认 `LegacyConsoleView.vue` 已无 `loadOverview`、`getRuntimeStatus`、`getCloudReportMetrics`、`getPerformanceDetail` 等 Diagnostic 监控接口引用，只保留 `loadConfigSummary()`。
- 代码检查确认 `LegacyConsoleView.vue` 中已无 `activeModule === 'diag'`、`runDiagnostic`、`downloadDiagnosticPackage`、`buildDiagnosticRaw`、`diagnosticRaw`、Diagnostic metrics state 和 Diagnostic 监控 API imports。
- 搜索确认 `src/views/legacy/` 下已无 `LegacyDiagnosticDetailPanel.vue`、`LegacyDeviceRuntimePanel.vue`、`diagnostic-detail-utils*`、`device-runtime-utils*`。
- 搜索确认 `src/views/ops/` 目录已删除。
- 搜索确认 `src/styles/legacy-console.css` 中已无 Diagnostic 专属 selector：`diagnostic-detail-panel`、`diagnostic-detail-cards`、`diagnostic-detail-grid`、`diagnostic-sub-card`、`diagnostic-connection-table`、`diagnostic-exception-table`、`device-runtime-panel`、`runtime-device-toolbar`、`runtime-summary-cards`、`runtime-device-table`。
- Vite dev server 使用 5179 完成 smoke check；用于 smoke check 的 5179 dev server 已停止。

## Phase 8 新增文件

- `collector-desktop/src/views/diagnostic/DiagnosticView.vue`
- `collector-desktop/src/features/diagnostic/components/DiagnosticDetailPanel.vue`
- `collector-desktop/src/features/diagnostic/components/DeviceRuntimePanel.vue`
- `collector-desktop/src/features/diagnostic/utils/diagnostic-detail-utils.ts`
- `collector-desktop/src/features/diagnostic/utils/diagnostic-detail-utils.test.ts`
- `collector-desktop/src/features/diagnostic/utils/device-runtime-utils.ts`
- `collector-desktop/src/features/diagnostic/utils/device-runtime-utils.test.ts`
- `collector-desktop/src/features/diagnostic/utils/diagnostic-utils.ts`
- `collector-desktop/src/features/diagnostic/utils/diagnostic-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/DiagnosticView-D9ULrdcb.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/DiagnosticView-BijKmlcA.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/device-runtime-utils-BYamEGO2.js`（`build:web` 生成）

## Phase 8 修改文件

- `collector-desktop/src/components/device/DeviceConfigPanel.vue`
- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 8 删除文件

- `collector-desktop/src/views/legacy/LegacyDiagnosticDetailPanel.vue`
- `collector-desktop/src/views/legacy/LegacyDeviceRuntimePanel.vue`
- `collector-desktop/src/views/legacy/diagnostic-detail-utils.ts`
- `collector-desktop/src/views/legacy/diagnostic-detail-utils.test.ts`
- `collector-desktop/src/views/legacy/device-runtime-utils.ts`
- `collector-desktop/src/views/legacy/device-runtime-utils.test.ts`
- `collector-desktop/src/views/ops/ops-utils.ts`
- `collector-desktop/src/views/ops/ops-utils.test.ts`
- `collector-desktop/src/views/ops/`（空目录删除）

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 9：History 迁移完成内容

- `src/views/legacy/LegacyHistoryPanel.vue` 已移动并改名为 `src/views/history/HistoryView.vue`，承载原“历史趋势”页面，不保留第二套 Legacy History 实现。
- `/history` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `HistoryView.vue`；当前 `/dashboard`、`/realtime`、`/history`、`/log`、`/alarm`、`/network`、`/cloud`、`/diagnostic` 均为独立 View。
- `/device`、`/device/workbench`、`/collect`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- `HistoryView.vue` 删除 `props.devices`、`props.selectedDeviceId`、`props.selectedPointRef` 和 `emit("selectDevice")`，不再通过 Legacy 父子 props 获取设备状态。
- `HistoryView.vue` 使用 `useDeviceStore()` 作为设备领域状态来源：设备下拉来自 `deviceStore.devices`，设备选择调用 `deviceStore.selectDevice(deviceId)`，进入页面时调用 `deviceStore.refresh()`。
- History 页面本地状态继续留在 View 内：`deviceId`、`pointRef`、`comparePointRefs`、`points`、`historyRows`、`comparePointRows`、`relatedAlarms`、`loading`、`limit`、`startTime`、`endTime`，没有新增 `history.store.ts`。
- `onMounted()` 现在按独立页面初始化：`appStore.initialize()` -> `deviceStore.refresh()` -> 应用 route query -> 确定设备 -> 加载点位。
- 普通 `/history` 不会默认请求历史数据；有可用设备时只选择当前或第一台设备并加载点位，保留用户点击“查询历史”的交互。
- 支持 `/history?deviceId=xxx&pointId=xxx`，并兼容 `/history?deviceId=xxx&pointRef=xxx`。query 指定点位能在当前设备点位中匹配时，自动执行一次历史查询。
- route query 后续变化通过 watcher 响应，且不反向 `router.replace()`，避免 query watcher 循环。
- 设备不存在时优先使用可用设备或保持未选择；点位不存在时不自动查询，保留用户手动选择能力，页面不崩溃。
- 手动切换设备时继续执行 `handleDeviceChange()` / `loadPoints()` 语义：更新 `deviceStore.selectedDeviceId`，清空旧点位、对比点位、历史数据、对比曲线和相关告警，再加载新设备点位。
- 点位配置继续使用 `getDevicePointsConfig(deviceId)`，响应解析继续兼容 `points`、`data`、`items`、`records`、`rows`。
- 历史查询继续使用 `getPointHistory(deviceId, pointRef, { startTs, endTs, limit })`，多点位对比仍按主 `pointRef` + `comparePointRefs` 分别请求，没有新增虚构批量接口。
- 相关告警继续使用 `getDeviceAlarmHistory(deviceId, { pointCode, pointId, startTs, endTs, limit: 20 })`，并复用 `features/alarm/utils/alarm-history-utils.ts` 中的 `normalizeAlarmHistoryRows()`，不依赖 `AlarmView` 状态。
- 趋势导出继续使用 `buildHistoryTrendExportText()`，导出内容保持 `deviceId`、`pointRef`、`pointLabel`、`generatedAt`、`series`、`relatedAlarms`，文件名仍为 `collector-history-{device}-{point}-{timestamp}.json` 形式。
- `src/views/legacy/history-trend-utils.ts` 与测试已移动到 `src/features/history/utils/history-trend-utils.ts` / `.test.ts`，保留 `buildHistoryTrendSeries()`、`buildHistoryTrendExportText()`、`buildHistoryTrendSummaryCards()`，未修改 SVG polyline 趋势算法。
- `HistoryRow` 与 `normalizeHistoryRows()` 已从 `src/views/runtime/runtime-utils.ts` 移动到 `src/features/history/utils/history-data-utils.ts`，对应“归一化历史数据响应”测试移动到 `history-data-utils.test.ts`。
- `src/views/runtime/runtime-utils.ts` 未整体迁移，继续只保留非 History 职责：`normalizeDeviceOptions()`、`buildSinglePointWritePayload()`、`buildBatchWriteTemplate()`、`buildCommandTemplate()`、`parseJsonOrThrow()`。
- 代码搜索确认 `runtime-utils.ts` 当前除自身测试外无生产引用；因其剩余函数属于 Control / Shadow / 后续运行操作边界，本 Phase 不删除。
- DeviceWorkbench -> History 联动改为 Router Query：`openWorkbenchHistory()` 现在跳转 `{ path: "/history", query: { deviceId, pointId: pointRef } }`，不再写 `historySelectedPointRef` 或 `switchModule("history")`。
- `LegacyConsoleView.vue` 已删除 `<LegacyHistoryPanel ... />`、`LegacyHistoryPanel` import 和 `historySelectedPointRef`。
- `src/router/route-names.ts` 中 `LegacyModuleKey` 已删除 `"history"`，`legacyModuleByRoutePath` / `routePathByLegacyModule` 也不再把 History 定义为 Legacy Module；`RouteNames.HISTORY` 保留为正式路由名。
- History 专属样式已从 `legacy-console.css` / `workbench.css` 移入 `HistoryView.vue` scoped style，包括历史查询栏、对比点位多选、摘要卡片、SVG 曲线、图例、统计行、相关告警表等规则。
- 公共样式 `section-heading`、`heading-title-line`、`heading-online`、`exact-page`、`exact-page-body`、`exact-toolbar`、`exact-diagnostic-cards`、`exact-diagnostic-card`、`surface-grid`、`surface-card`、`surface-card-head`、`exact-table-card`、`exact-table-title`、`runtime-table`、`json-view`、`empty-state` 继续保留公共样式，没有复制一整套公共 CSS。

## Phase 9 验证结果

执行时间：2026-08-31 09:40-09:44，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 32 个测试文件、177 个测试通过；新增 `src/features/history/utils/history-data-utils.test.ts`，迁移后的 `history-trend-utils.test.ts` 通过，`router.test.ts` 覆盖 `/history -> HistoryView` 与 `/history?deviceId=dev-1&pointId=temp-1` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `HistoryView` chunk |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 38 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 9 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 9 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5180/#/dashboard` 能打开，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5180/#/realtime` 能打开，仍由 `RealtimeView.vue` 承载。
- `/history`：dev server `http://127.0.0.1:5180/#/history` 能打开，页面文本显示“历史趋势”“设备”“点位”“开始”“结束”“条数”“对比点位”“查询历史”“导出趋势”“主曲线最新值”“采样总数”“数值范围”“点位历史曲线”“查询结果 JSON”“相关告警”“历史数据表”“暂无历史数据”；无后端数据时页面不崩溃，普通进入未自动执行无效历史查询。
- `/history?deviceId=dev-1&pointId=temp-001`：dev server 能打开，查询结果 JSON 中带入 `deviceId: "dev-1"`；因本地后端采集服务不可达，点位配置无法加载，`pointRef` 保持空并显示“无法连接采集服务，请检查服务地址和后端是否已启动”，页面未崩溃。
- `/history?deviceId=dev-1&pointRef=temp-001`：dev server 能打开，兼容别名 query，后端不可达时同样不崩溃。
- `/log`：dev server `http://127.0.0.1:5180/#/log` 能打开，仍由 `LogView.vue` 承载。
- `/alarm`：dev server `http://127.0.0.1:5180/#/alarm` 能打开，仍由 `AlarmView.vue` 承载。
- `/network?target=127.0.0.1&port=502`：dev server 能打开，仍由 `NetworkView.vue` 承载。
- `/cloud`：dev server `http://127.0.0.1:5180/#/cloud` 能打开，仍由 `CloudView.vue` 承载。
- `/diagnostic`：dev server `http://127.0.0.1:5180/#/diagnostic` 能打开，仍由 `DiagnosticView.vue` 承载。
- `/device`：dev server `http://127.0.0.1:5180/#/device` 能打开，仍显示 Legacy 设备管理页面。
- `/collect`：dev server `http://127.0.0.1:5180/#/collect` 能打开，仍显示 Legacy 采集配置页面。
- 代码检查确认 `LegacyConsoleView.vue` 中已无 `activeModule === 'history'`、`LegacyHistoryPanel`、`historySelectedPointRef`，`openWorkbenchHistory()` 已改为 `/history?deviceId=...&pointId=...`。
- 搜索确认 `src/views/legacy/` 下已无 `LegacyHistoryPanel.vue`、`history-trend-utils.ts`、`history-trend-utils.test.ts`。
- 搜索确认 `src/styles/legacy-console.css` 与 `src/styles/workbench.css` 中已无 History 专属 selector：`legacy-history-panel`、`history-query-bar`、`history-filter-main`、`history-filter-field`、`history-filter-bottom`、`history-compare-field`、`history-compare-select`、`history-query-actions`、`history-summary-cards`、`history-chart`、`history-chart-dark`、`history-legend`、`history-stat-row`、`history-json-view`、`history-alarm-card`、`history-alarm-table`、`history-toolbar`。
- Vite dev server 使用 5180 完成 smoke check；用于 smoke check 的 5180 dev server 已停止。

## Phase 9 新增文件

- `collector-desktop/src/views/history/HistoryView.vue`
- `collector-desktop/src/features/history/utils/history-trend-utils.ts`
- `collector-desktop/src/features/history/utils/history-trend-utils.test.ts`
- `collector-desktop/src/features/history/utils/history-data-utils.ts`
- `collector-desktop/src/features/history/utils/history-data-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/HistoryView-D18EPo6x.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/HistoryView-CCRsoyj6.js`（`build:web` 生成）

## Phase 9 修改文件

- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/styles/workbench.css`
- `collector-desktop/src/views/history/HistoryView.vue`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/src/views/runtime/runtime-utils.ts`
- `collector-desktop/src/views/runtime/runtime-utils.test.ts`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 9 删除文件

- `collector-desktop/src/views/legacy/LegacyHistoryPanel.vue`
- `collector-desktop/src/views/legacy/history-trend-utils.ts`
- `collector-desktop/src/views/legacy/history-trend-utils.test.ts`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 10：Collection 迁移完成内容

- 新增 `src/views/collection/CollectionView.vue`，承载原 `activeModule === "collect"` 的“数据采集配置”页面。
- `/collect` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `CollectionView.vue`；当前 `/dashboard`、`/realtime`、`/history`、`/alarm`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/collect` 均为独立 View。
- `/device`、`/device/workbench`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移 Device、DeviceWorkbench、Control、Shadow、LocalDeviceEditor 或 PointEditor。
- `CollectionView.vue` 使用 `useDeviceStore()` 提供设备领域状态：`deviceStore.devices`、`deviceStore.selectedDeviceId`、`deviceStore.selectedDevice`，进入页面和导入/同步/保存后按需调用 `deviceStore.refresh()`。
- `CollectionView.vue` 使用 `useProtocolStore()` 提供协议领域状态：模板使用 `protocolStore.protocols`，进入页面调用 `protocolStore.refresh()`，不再维护长期 `protocols = ref([])` 或直接长期调用 `listProtocols()`。
- `configSummary` 继续作为 Collection 页面聚合快照留在 `CollectionView.vue`，通过 `loadConfigSummary()` 调用 `getConfigSummary()`；没有新增 `collection.store.ts`。
- 进入 `/collect` 的初始化只执行 `appStore.initialize()`，并行刷新 `deviceStore.refresh()`、`protocolStore.refresh()`、`loadConfigSummary()`；`ConfigOpsPanel` 自身读取同步状态 `getConfigSyncStatus()`。没有请求 Diagnostic / Cloud / History / Alarm / Network 的监控接口。
- `src/views/legacy/LegacyConfigOpsPanel.vue` 已迁移并改名为 `src/features/collection/components/ConfigOpsPanel.vue`，CollectionView 不再 import `views/legacy/*`。
- `ConfigOpsPanel.vue` 保留低耦合 props / emit：`devices`、`selectedDeviceId`、`imported`、`synced`；组件自身不直接依赖整个 `deviceStore`。
- `ConfigOpsPanel` 保留配置导出、下载 JSON、配置导入、`reloadAfterImport`、全量同步、局部同步、同步类型、目标设备、同步状态和同步结果。
- `ConfigOpsPanel` 的 `imported` / `synced` 事件现在只触发 `refreshCollectionContext()`，刷新 Collection 真正依赖的设备、协议和配置摘要，不再调用 Legacy `refreshAll()` 或其它页面 loader。
- 通用配置导入导出函数已从 `src/views/legacy/config-utils.ts` 迁移到 `src/features/config/utils/config-transfer-utils.ts`，包括 `normalizeConfigExportText()`、`parseConfigImportText()`、`buildConfigImportRequest()`、`countConfigImportBundles()`、`buildConfigExportFilename()`。
- Collection 配置同步函数已迁移到 `src/features/collection/utils/config-sync-utils.ts`，包括 `CONFIG_SYNC_TYPES` 和 `normalizeSyncStatusItems()`。
- `buildDeviceListEmptyText()` 继续留在 `src/views/legacy/config-utils.ts`，该文件已缩减为 Device 列表过渡 helper，没有把 Device 专属空态错误放入 Collection feature。
- `config-utils.test.ts` 已按职责拆分：通用导入导出测试迁入 `features/config/utils/config-transfer-utils.test.ts`，同步状态测试迁入 `features/collection/utils/config-sync-utils.test.ts`，Device 空态测试保留在 `views/legacy/config-utils.test.ts`。
- 配置导入导出后端 API 保持不变：继续使用 `exportConfigs()`、`importConfigs()`、`reloadAfterImport`，并继续兼容 `{ "bundles": [...] }`、bundle 数组和单个 bundle JSON。
- 配置同步 API 保持不变：继续使用 `triggerFullConfigSync()`、`triggerPartialConfigSync()`、`getConfigSyncStatus()`，同步类型仍为 `device`、`points`、`connection`、`collection`、`all`。
- 局部同步目标设备逻辑保持：需要设备的同步类型优先使用 `syncDeviceId`，其次使用 `props.selectedDeviceId`，否则为 `undefined`；不强制所有局部同步必须指定设备。
- `CollectionView.vue` 支持 `/collect?deviceId=xxx`，query 变化时会重新应用设备上下文；设备存在时选择该设备，设备列表为空时保留 query 上下文，设备不存在且存在其它设备时回退到可用设备，不自动执行同步操作。
- Legacy Device 的 `openDeviceDiff()` 已改为 Router Query 跳转 `{ path: "/collect", query: { deviceId } }`，保留“已切换到采集配置，可查看当前设备相关配置”提示，不再 `switchModule("collect")`。
- `src/router/route-names.ts` 中 `LegacyModuleKey` 已删除 `"collect"`，`legacyModuleByRoutePath` / `routePathByLegacyModule` 也不再把 `/collect` 定义为 Legacy Module；`RouteNames.COLLECTION` 保留为正式路由名。
- `LegacyConsoleView.vue` 已删除 Collection template、`LegacyConfigOpsPanel` import、`configSummary`、`collectionSummaryItems`、`selectedProtocol`、`loadConfigSummary()`、`protocolDefaultPort()`、`protocolMode()`、`protocolCapability()`、`openProtocolConfig()`。
- `loadActiveLegacyModule()` 不再处理 `collect`，当前只为剩余 Workbench 入口加载选中设备实时数据。
- Legacy 的刷新入口已从 `refreshAll()` 收敛为 `refreshDeviceContext()`，只刷新 `loadProtocols()` 与 `loadDevices()`，不再加载已迁出的 Collection `configSummary`。
- Collection 协议 Schema 展示已从错误的 `<section><summary>` 改为正确的 `<details><summary>`，保持视觉和功能不变，并消除 Collection 区域的 `<summary>` 非 `<details>` 子元素 warning。
- Collection 专属样式已从全局 legacy CSS 中退出：`exact-config-grid` 和 Collection 使用的 `capability-badge` 改为 `CollectionView.vue` / `ConfigOpsPanel.vue` scoped style；ConfigOpsPanel 的导出视图、导入文本域、同步表单和同步状态网格也收口到组件 scoped style。
- 公共样式 `section-heading`、`heading-title-line`、`heading-online`、`heading-actions`、`exact-page`、`exact-page-body`、`exact-surface`、`exact-surface-head`、`exact-table-card`、`exact-table-title`、`exact-config-item`、`exact-json-panel`、`json-view`、`surface-grid`、`surface-card`、`form-grid`、`inline-actions` 继续保留公共样式，没有复制一整套公共 CSS。

## Phase 10 验证结果

执行时间：2026-08-31 10:22-10:23，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 34 个测试文件、179 个测试通过；新增 `src/features/config/utils/config-transfer-utils.test.ts`、`src/features/collection/utils/config-sync-utils.test.ts`，`router.test.ts` 覆盖 `/collect -> CollectionView` 与 `/collect?deviceId=dev-1` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `CollectionView` 和 `config-transfer-utils` chunks |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 41 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 10 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 10 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5181/#/dashboard` 能打开，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5181/#/realtime` 能打开，仍由 `RealtimeView.vue` 承载。
- `/history`：dev server `http://127.0.0.1:5181/#/history` 能打开，仍由 `HistoryView.vue` 承载。
- `/alarm`：dev server `http://127.0.0.1:5181/#/alarm` 能打开，仍由 `AlarmView.vue` 承载。
- `/log`：dev server `http://127.0.0.1:5181/#/log` 能打开，仍由 `LogView.vue` 承载。
- `/network?target=127.0.0.1&port=502`：dev server 能打开，仍由 `NetworkView.vue` 承载。
- `/cloud`：dev server `http://127.0.0.1:5181/#/cloud` 能打开，仍由 `CloudView.vue` 承载。
- `/diagnostic`：dev server `http://127.0.0.1:5181/#/diagnostic` 能打开，仍由 `DiagnosticView.vue` 承载。
- `/collect`：dev server `http://127.0.0.1:5181/#/collect` 能打开，页面文本显示“数据采集配置”“刷新概览”“全局采集配置”“设备配置”“点位总数”“连接配置”“配置来源”“配置导入导出与同步”“配置导出”“下载 JSON”“配置导入”“导入后刷新设备”“全量同步”“局部同步”“同步类型”“目标设备”“同步状态”“协议配置列表”“协议名称”“规范编码”“默认端口”“采集方式”“能力状态”“操作”。后端不可达时显示“无法连接采集服务，请检查服务地址和后端是否已启动”，页面未崩溃。
- `/collect?deviceId=dev-1`：dev server 能打开，后端不可达/无设备时页面不崩溃，不自动执行同步操作。
- `/device`：dev server `http://127.0.0.1:5181/#/device` 能打开，仍显示 Legacy 设备管理页面。
- 代码检查确认 `CollectionView.vue` / `ConfigOpsPanel.vue` 没有引用 Diagnostic / Cloud / History / Alarm / Network 监控 API；`LegacyConsoleView.vue` 已无 `activeModule === 'collect'`、`LegacyConfigOpsPanel`、`loadConfigSummary()`、`configSummary`、`collectionSummaryItems`、`selectedProtocol`。
- 搜索确认 `src/views/legacy/` 下已无 `LegacyConfigOpsPanel.vue`。
- 搜索确认 `src/styles/legacy-console.css` 与 `src/styles/workbench.css` 中已无 Collection 专属 selector：`exact-global-config`、`exact-config-grid`、`config-ops-panel`、`config-export-view`、`config-import-textarea`、`config-sync-form`、`config-sync-status-grid`、`capability-badge`。
- 搜索 `<summary>` 确认剩余 summary 均为 `<details>` 子元素；dev server 输出未再出现 Collection 旧 `<summary>` HTML warning。
- Vite dev server 使用 5181 完成 smoke check；用于 smoke check 的 5181 dev server 已停止。

## Phase 10 新增文件

- `collector-desktop/src/views/collection/CollectionView.vue`
- `collector-desktop/src/features/collection/components/ConfigOpsPanel.vue`
- `collector-desktop/src/features/collection/utils/config-sync-utils.ts`
- `collector-desktop/src/features/collection/utils/config-sync-utils.test.ts`
- `collector-desktop/src/features/config/utils/config-transfer-utils.ts`
- `collector-desktop/src/features/config/utils/config-transfer-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/CollectionView-BDtsIuwO.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/CollectionView-CjcJC7SP.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/config-transfer-utils-CxKDpqe5.js`（`build:web` 生成）

## Phase 10 修改文件

- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/src/views/legacy/config-utils.ts`
- `collector-desktop/src/views/legacy/config-utils.test.ts`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 10 删除文件

- `collector-desktop/src/views/legacy/LegacyConfigOpsPanel.vue`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 11：Device List 迁移完成内容

- 新增 `src/views/device/DeviceListView.vue`，承载原 `activeModule === "device"` 的“设备管理”页面。
- `/device` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `DeviceListView.vue`；当前 `/dashboard`、`/realtime`、`/history`、`/alarm`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/device` 均为独立 View。
- `/device/workbench`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移 DeviceWorkbench、Control、Shadow、DeviceConfigPanel、LocalDeviceEditor 或 PointEditor。
- `DeviceListView.vue` 使用 `useDeviceStore()` 作为设备领域状态唯一来源：模板和筛选基于 `deviceStore.devices`，选中状态使用 `deviceStore.selectedDeviceId`，加载/错误使用 `deviceStore.loading` 与 `deviceStore.error`。
- `DeviceListView.vue` 不再维护 Legacy 设备副本：没有 `devices = ref([])`、`deviceRuntimeMap = ref({})`、`selectedDeviceId = ref("")`、`deviceLoading = ref(false)`、`deviceLoadError = ref("")`。
- 设备展示优先使用 `DeviceViewModel` 字段：设备 ID 使用 `device.normalizedId`，设备名称使用 `device.displayName`，协议显示使用 `device.displayProtocol`，状态展示使用 `device.status`。
- Device List 页面局部 UI 状态仍保留在 View 内：`deviceKeyword`、`protocolFilter`、`statusFilter`、`localEditorVisible`、`editingBundle`、`configImportInput`、`configFileExporting`、`configFileImporting`、`deviceConfigOperatingId`。
- `filteredDevices` 作为页面 computed 基于 `deviceStore.devices` 计算，搜索条件不进入 Pinia。
- `DeviceListView.vue` 使用 `useProtocolStore()`，协议筛选下拉和 `LocalDeviceEditor` 均使用 `protocolStore.protocols`；进入页面调用 `protocolStore.refresh()`，不维护长期 `protocols = ref([])`，不直接长期调用 `listProtocols()`。
- 页面初始化按边界执行：`await appStore.initialize()` 后并行 `deviceStore.refresh()` 与 `protocolStore.refresh()`，再应用 `route.query.deviceId`；没有请求 Realtime、Diagnostic、Alarm、Collection、Cloud 等其它页面数据。
- 支持 `/device?deviceId=xxx`：当设备列表中存在该设备时调用 `deviceStore.selectDevice(deviceId)`，设备不存在或后端不可达时保持安全空状态，不反向修改 query。
- 设备启动改为 `await deviceStore.startSmart(deviceId)`，复用 Store 内部 `resolveDeviceStartMode()`，保持本地设备走 `/start-local`、远端设备走 `/start` 的 smart start 语义。
- 设备停止改为 `await deviceStore.stop(deviceId)`，不在 DeviceListView 直接调用 `stopDevice()`。
- `deviceStore` 新增 `syncRemoteDevices()`，明确保留旧“同步远端配置”的完整语义：`triggerFullConfigSync()` -> `reloadDevices()` -> `refresh()`；DeviceListView 的“同步远端配置”使用该 Store action。
- 删除本地设备继续由 View 使用 `ElMessageBox.confirm()` 承担确认弹窗，再调用 `deviceStore.deleteLocal(deviceId)`；文案继续强调“不会删除远端配置”。
- 设备配置刷新 / 清理缓存继续由 `operateDeviceConfig(deviceId, type)` 调用 `refreshDeviceConfig()` / `clearDeviceConfig()`，保留确认弹窗、操作 loading、响应归一化和中文提示；成功后调用 `deviceStore.refresh()`，不再调用 Legacy `loadDevices()`。
- `src/views/legacy/device-config-actions-utils.ts` 已迁移到 `src/features/device/utils/device-config-actions-utils.ts`，测试迁移到 `src/features/device/utils/device-config-actions-utils.test.ts`。
- `buildDeviceListEmptyText()` 已迁移到 `src/features/device/utils/device-list-utils.ts`，测试迁移到 `src/features/device/utils/device-list-utils.test.ts`。
- `src/views/legacy/config-utils.ts` 与 `src/views/legacy/config-utils.test.ts` 已删除，没有保留空壳 legacy util。
- `DeviceListView.vue` 统一复用 `stores/device.store.ts` export 的 `isLocalDevice()`，不再定义第三套本地设备判断。
- 状态展示只保留小范围 `localizeDeviceStatus()` / `statusBadgeClass()`，优先使用 `device.status`；状态文案继续支持“在线 / 离线 / 异常 / 已停止 / 未知”。
- 连接地址 helper 保持简单：优先 `ipAddress + port`，小范围兼容 `host` / `url`，未重构连接地址模型。
- 新增本地设备继续打开 `LocalDeviceEditor`，`editingBundle = null`，保存后关闭弹窗并刷新 `deviceStore` / `protocolStore`。
- 本地设备编辑继续通过 `getLocalDevice(deviceId)` -> `extractLocalDeviceBundle()` -> `editingBundle` -> `LocalDeviceEditor`，未修改 `LocalDeviceEditor` 或 `LocalDeviceBundle` DTO。
- 远端设备“编辑”和“配置”均跳转 `/device/workbench?deviceId=xxx`，保持进入 DeviceWorkbench config 分区的旧行为，不改成编辑弹窗。
- “控制”跳转 `/control?deviceId=xxx`，“影子”跳转 `/shadow?deviceId=xxx`。
- “差异”保持旧产品行为：跳转 `/collect?deviceId=xxx`，不因 `deviceStore.loadDiff()` 存在而新增 Diff Dialog 或 Diff 页面。
- “运行状态”跳转 `/diagnostic?deviceId=xxx`，“告警历史”跳转 `/alarm?deviceId=xxx`，不共享其它页面 state。
- Legacy Workbench 增加最小 route query 过渡逻辑：`applyRouteDeviceQuery()` 读取 `route.query.deviceId` 并写入 Legacy 局部 `selectedDeviceId`；watch `route.query.deviceId` 支持同一路由内设备切换，且只做 Query -> Legacy state 单向同步。
- 直接打开 `/device/workbench?deviceId=dev-1`、`/control?deviceId=dev-1`、`/shadow?deviceId=dev-1` 时，Legacy Workbench 能显示 `dev-1` 上下文；后端无设备或不可达时不崩溃。
- Legacy Workbench “返回列表”已从 `switchModule("device")` 改为 `router.push({ path: "/device", query: selectedDeviceId ? { deviceId } : {} })`，返回后由新 DeviceListView 识别 query 并选中设备。
- `src/router/route-names.ts` 中 `LegacyModuleKey` 已删除 `"device"`，`legacyModuleByRoutePath` 与 `routePathByLegacyModule` 也删除 `/device` 的 Legacy 映射；`RouteNames.DEVICE` 保留正式路由名。
- `LegacyConsoleView.vue` 已删除 Device List template，以及只属于 Device List 的 `deviceKeyword`、`protocolFilter`、`statusFilter`、`filteredDevices`、`deviceListEmptyText`、`deviceLoading`、`deviceLoadError`、`localEditorVisible`、`editingBundle`、`configImportInput`、`configFileExporting`、`configFileImporting`、`syncDevices()`、`deleteLocal()`、`openLocalEditor()`、`handleLocalSaved()`、`exportDeviceConfigData()`、`openConfigImportFile()`、`handleConfigImportFile()`、`editDevice()`、`openDeviceDiff()`、`openDeviceOperation()`、本地 `isLocalDevice()`、`localizeDeviceStatus()`、`statusBadgeClass()`。
- `LegacyConsoleView.vue` 中的 `protocols` / `loadProtocols()` / `listProtocols()` 已删除；迁完 Device List 后 Legacy Workbench 不再真实使用 protocols。
- `LegacyConsoleView.vue` 继续保留 Workbench 必需的 `devices`、`deviceRuntimeMap`、`selectedDeviceId`、`selectedDevice`、`selectedDeviceView`、`selectedRuntimeSnapshot`、`selectedRealtimeRows`、`loadDevices()`、`loadSelectedRealtime()`、`startSelectedDevice()`、`stopSelectedDevice()`、`operateDeviceConfig()`、`openSelectedDeviceRuntimeStatus()`、`openSelectedDeviceAlarmHistory()`、`openWorkbenchHistory()`、`openWorkbenchRealtime()`、`deviceAddress()`。
- Device List 专属 CSS 已从 `src/styles/legacy-console.css` 迁入 `DeviceListView.vue` scoped style：`exact-device-list`、`exact-device-card`、`exact-device-main`、`exact-device-meta`、`exact-device-actions` 及其响应式规则。
- `workbench.css` 中的 `local-editor`、`local-device-panel`、`device-operation-panel`、`local-editor-title`、`local-editor-tabs`、`local-editor-layout`、`device-operation-rail`、`device-operation-body`、`local-checklist`、`local-editor-stat` 等 Workbench / LocalDeviceEditor 样式未提前迁移。
- 公共样式 `section-heading`、`heading-title-line`、`heading-online`、`heading-actions`、`exact-page`、`exact-page-body`、`exact-toolbar`、`exact-toolbar-group`、`exact-toolbar-filters`、`status-badge`、`exact-empty` 继续保留公共样式。

## Phase 11 验证结果

执行时间：2026-08-31 11:00-11:01，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm test` | 通过 | 34 个测试文件、182 个测试通过；新增 `features/device/utils/device-list-utils.test.ts`，迁移后的 `device-config-actions-utils.test.ts` 通过，`router.test.ts` 覆盖 `/device -> DeviceListView`、`/device?deviceId=dev-1`、`/device/workbench?deviceId=dev-1`、`/control?deviceId=dev-1`、`/shadow?deviceId=dev-1` |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `DeviceListView` chunk |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 45 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 11 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 11 路由与页面检查结果

- `/device`：dev server `http://127.0.0.1:5182/#/device` 能打开，页面文本显示“设备管理”“0 台设备”“刷新列表”“导出配置数据”“导入配置数据”“新增本地设备”“全部协议”“全部状态”“在线”“离线”“异常”“同步远端配置”。后端不可达时显示“设备配置加载失败：无法连接采集服务，请检查服务地址和后端是否已启动”，页面未崩溃。
- `/device?deviceId=dev-1`：dev server 能打开，后端不可达/无设备时页面不崩溃，不反向修改 query。
- `/device/workbench?deviceId=dev-1`：dev server 能打开，Legacy Workbench 显示 `dev-1`，未先经过 DeviceList 也能接收 query；后端不可达时显示安全空状态。
- `/control?deviceId=dev-1`：dev server 能打开，手动控制分区显示设备上下文 `dev-1`，说明 Control 过渡入口能接收 query。
- `/shadow?deviceId=dev-1`：dev server 能打开，设备影子分区显示设备上下文 `dev-1`，说明 Shadow 过渡入口能接收 query。
- `/collect?deviceId=dev-1`：dev server 能打开，仍由 `CollectionView.vue` 承载，后端不可达时不崩溃。
- `/diagnostic?deviceId=dev-1`：dev server 能打开，单设备状态 JSON 带入 `deviceId: "dev-1"`，后端不可达时显示“单设备状态查询失败”。
- `/alarm?deviceId=dev-1`：dev server 能打开，页面显示“设备 dev-1 · 0 条 · 已确认 0”，后端不可达时不崩溃。
- `/dashboard`：dev server 能打开，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server 能打开，仍由 `RealtimeView.vue` 承载。
- `/history`：dev server 能打开，仍由 `HistoryView.vue` 承载。
- 额外 smoke：`/cloud`、`/log`、`/network?target=127.0.0.1&port=502` 均能打开，仍由对应独立 View 承载。
- 代码检查确认 `/dashboard`、`/realtime`、`/history`、`/alarm`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/device` 均直接解析到独立 View；`/device/workbench`、`/control`、`/shadow` 仍解析到 `LegacyConsoleView.vue`。
- 代码检查确认 `DeviceListView.vue` 没有 `listProtocols`、`startDevice`、`stopDevice` 直接调用；使用 `deviceStore` 27 处、`protocolStore` 3 处。
- 代码检查确认 `LegacyConsoleView.vue` 已无 `activeModule === 'device'`、Device List 专属状态/函数、`LocalDeviceEditor`、`listProtocols`、`protocols`。
- 搜索确认 `src/views/legacy/` 下已无 `config-utils*`、`device-config-actions-utils*`。
- 搜索确认 `src/styles/legacy-console.css` 与 `src/styles/workbench.css` 中已无 Device List 专属 selector：`exact-device-list`、`exact-device-card`、`exact-device-main`、`exact-device-meta`、`exact-device-actions`。
- Vite dev server 使用 5182 完成 smoke check；用于 smoke check 的 5182 dev server 已停止。

## Phase 11 新增文件

- `collector-desktop/src/views/device/DeviceListView.vue`
- `collector-desktop/src/features/device/utils/device-config-actions-utils.ts`
- `collector-desktop/src/features/device/utils/device-config-actions-utils.test.ts`
- `collector-desktop/src/features/device/utils/device-list-utils.ts`
- `collector-desktop/src/features/device/utils/device-list-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/DeviceListView-CjH3v4r8.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/DeviceListView--S1lRRz4.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/device-config-actions-utils-BQm5k-Fc.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/protocol.store-BDoIQlUI.js`（`build:web` 生成）

## Phase 11 修改文件

- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/stores/device.store.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 11 删除文件

- `collector-desktop/src/views/legacy/config-utils.ts`
- `collector-desktop/src/views/legacy/config-utils.test.ts`
- `collector-desktop/src/views/legacy/device-config-actions-utils.ts`
- `collector-desktop/src/views/legacy/device-config-actions-utils.test.ts`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 12：Device Workbench 迁移完成内容

- 新增 `src/views/device/DeviceWorkbenchView.vue`，承载原 `/device/workbench` 的设备配置工作台外层宿主。
- `/device/workbench` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `DeviceWorkbenchView.vue`；最终结构为 `AppShell -> AppTopbar -> RouterView -> DeviceWorkbenchView.vue`。
- `/control` 与 `/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移 Control、Shadow、LegacyManualShadowPanels、LocalDeviceEditor、PointEditor 或 DeviceConfigPanel 内部大结构。
- `DeviceWorkbenchView.vue` 继续组合 `<DeviceConfigPanel />` 作为主体，未复制、重写或拆分 `DeviceConfigPanel.vue` 内部的设备基础信息、运行控制、连接检查、协议连接配置、配置差异、点位列表、实时数据、告警、日志和 PointEditor。
- `DeviceWorkbenchView.vue` 使用 `useDeviceStore()` 作为设备领域状态来源：设备列表来自 `deviceStore.devices`，运行快照来自 `deviceStore.runtimeMap`，当前选择来自 `deviceStore.selectedDeviceId` 与 `deviceStore.selectedDevice`。
- `DeviceWorkbenchView.vue` 没有维护第二套 `const devices = ref([])`、`deviceRuntimeMap` 或 `const selectedDeviceId = ref("")`。
- `DeviceConfigPanel` 直接接收 `:device="deviceStore.selectedDevice"`，没有在新 View 中调用 `normalizeDeviceViewModelWithRuntimeStatus()` 构建第二套 `selectedDeviceView`。
- 页面初始化按独立路由执行：`appStore.initialize()` -> `deviceStore.refresh()` -> `applyRouteDevice()` -> `loadRealtimePreview()`；没有额外手工调用 `getConfigDevices()` / `getDeviceRuntime()`。
- 完整支持 `/device/workbench?deviceId=dev-1`：route query 单向写入 Store；设备存在时选择该设备，设备不存在但设备列表存在时回退第一台设备，设备列表为空时保留 query 上下文并让 `DeviceConfigPanel` 进入 `device = null` 空状态。
- route query 后续变化由 `watch(route.query.deviceId)` 处理，只做 `Route Query -> deviceStore`，不反向 `router.replace()` 或 `router.push()`，避免 query/store 循环。
- 顶部设备信息改用 Store：设备名称优先 `deviceStore.selectedDevice.displayName`，deviceId 优先 `normalizedId` / `deviceStore.selectedDeviceId`，协议优先 `displayProtocol`，采集周期来自 `collectionInterval`。
- 顶部地址继续使用页面级简单 `deviceAddress()`：优先 `ipAddress/host + port`，小范围兼容 `url`，未新增设备详情 API。
- 运行状态摘要改为基于 `deviceStore.selectedDevice.runtime` 与 `deviceStore.runtimeMap[selectedDeviceId]` 计算；连接状态同时参考 runtime 与页面级实时预览行数。
- 顶部和左侧“实时点位 N 个”使用页面局部 `realtimePreviewRows`，通过 `getDeviceRealtimeData(deviceId)` + `normalizeRealtimeRows()` 生成，只服务 Workbench 外层摘要，不放入 Pinia，也不访问 `DeviceConfigPanel` 内部 ref。
- `DeviceConfigPanel` 的 `start(deviceId)` 事件由 `DeviceWorkbenchView` 接收后调用 `deviceStore.startSmart(deviceId)`，保持本地设备走 `startLocalDevice`、远端设备走 `startDevice` 的语义；成功后刷新实时预览。
- `DeviceConfigPanel` 的 `stop(deviceId)` 事件由 `DeviceWorkbenchView` 接收后调用 `deviceStore.stop(deviceId)`，成功后刷新实时预览；新 View 不直接调用 `stopDevice()`。
- 外层左侧栏“刷新配置 / 清理缓存”继续使用 `refreshDeviceConfig()` / `clearDeviceConfig()`，并复用 `features/device/utils/device-config-actions-utils.ts` 中的 `DEVICE_CONFIG_ACTIONS`、`normalizeDeviceConfigActionResult()`、`buildDeviceConfigActionMessage()`。
- `deviceConfigOperatingId` 继续作为页面局部按钮 loading 状态保存在 `DeviceWorkbenchView.vue`，未进入 `deviceStore`。
- 配置缓存刷新 / 清理成功后执行 `deviceStore.refresh()`、重新应用 route query，并刷新 realtime preview。
- “返回列表”继续走 Router Query：`/device?deviceId=当前设备`，没有回退到 `switchModule("device")`。
- “运行状态”继续跳转 `/diagnostic?deviceId=xxx`；“告警历史”继续跳转 `/alarm?deviceId=xxx`，不直接调用 DiagnosticView / AlarmView，也不共享它们的页面状态。
- `DeviceConfigPanel` 的 `open-history` 事件由 `DeviceWorkbenchView` 跳转 `/history?deviceId=target.deviceId&pointId=target.pointRef`，并保留中文提示。
- `DeviceConfigPanel` 的 `open-realtime` 事件由 `DeviceWorkbenchView` 跳转 `/realtime?deviceId=target.deviceId&pointId=target.pointRef`，并保留中文提示。
- 新 Workbench 顶部三项外层 Tab 改为路由导航：“工作台”当前 active，“批量和协议命令”跳 `/control?deviceId=xxx`，“设备影子”跳 `/shadow?deviceId=xxx`；新 View 不维护 `workbenchTab = "control" / "shadow"`。
- `LegacyConsoleView.vue` 中 Control / Shadow 顶部三项 Tab 做最小兼容修改：Tab active 状态由 `resolveWorkbenchRouteTab(route.path)` 推导，点击“工作台”跳 `/device/workbench?deviceId=xxx`，点击“批量和协议命令”跳 `/control?deviceId=xxx`，点击“设备影子”跳 `/shadow?deviceId=xxx`。
- `LegacyConsoleView.vue` 不再 import / render `DeviceConfigPanel`，也不再负责 `/device/workbench`。
- `LegacyConsoleView.vue` 删除只属于 config Workbench 的 `startSelectedDevice()`、`stopSelectedDevice()`、`resetSelectedAdaptive()`、`openWorkbenchHistory()`、`openWorkbenchRealtime()` 以及 `resetAdaptiveConfig`、`startDevice`、`startLocalDevice`、`stopDevice`、`resolveDeviceStartMode` imports。
- `LegacyConsoleView.vue` 保留 Control / Shadow 仍需要的过渡外壳、当前设备上下文、`ManualShadowPanels`、`devices`、`deviceRuntimeMap`、`selectedDeviceId`、`selectedDevice`、`selectedDeviceView`、`selectedRuntimeSnapshot`、`selectedRealtimeRows`、`loadDevices()`、`loadSelectedRealtime()`、`operateDeviceConfig()`、运行状态/告警历史跳转和 `deviceAddress()`。
- `src/router/route-names.ts` 删除 `LegacyModuleKey`、`resolveLegacyModuleByRoutePath()` 和 `routePathForLegacyModule()`，避免未知 Legacy route 继续 fallback 到 `overview` / Dashboard。
- `src/router/route-names.ts` 新增并保留最小路由 helper：`WorkbenchRouteTab = "control" | "shadow"`、`WorkbenchNavigationTab = "config" | "control" | "shadow"`、`resolveWorkbenchRouteTab()`、`routePathForWorkbenchTab()`。
- `src/router/router.test.ts` 更新覆盖：`/device -> DeviceListView`、`/device/workbench -> DeviceWorkbenchView`、`/device/workbench?deviceId=dev-1 -> DeviceWorkbenchView`、`/control?deviceId=dev-1 -> LegacyConsoleView`、`/shadow?deviceId=dev-1 -> LegacyConsoleView`，以及 Workbench 路由 helper 不再把 `/device/workbench`、`/dashboard`、`/device` 解析为 Legacy fallback。
- 本 Phase 未迁移 Workbench CSS 到 scoped style：当前 `deviceOperationPanel` 外壳、外层 Tab、左侧 rail、`device-config-workbench-pane`、`manual-shadow-pane`、`device-operation-body`、DeviceConfigPanel 内部和 ManualShadowPanels 仍共享同一套 `workbench.css` 全局样式，避免复制第二套 CSS。
- `LegacyConsoleView.vue` 保留自身 `legacy-page-host` scoped style；`DeviceWorkbenchView.vue` 没有新增重复 scoped CSS。

## Phase 12 验证结果

执行时间：2026-08-31 11:25-11:31，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 34 个测试文件、184 个测试通过；`router.test.ts` 新增/更新 `/device/workbench -> DeviceWorkbenchView`、Control/Shadow Legacy 和 Workbench route helper 断言 |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `DeviceWorkbenchView` chunk，`LegacyConsoleView` chunk 明显收敛 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 46 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 12 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。一次额外尝试 `npm test -- --runInBand` 因 Vitest 不支持 `--runInBand` 失败，随后已按项目标准命令重新执行 `npm test` 并通过。

## Phase 12 路由与页面检查结果

- `/device`：dev server `http://127.0.0.1:5183/#/device` 能打开，仍由 `DeviceListView.vue` 承载，后端不可达时显示“设备配置加载失败：无法连接采集服务，请检查服务地址和后端是否已启动”。
- `/device/workbench`：dev server 能打开，页面文本显示“设备配置”“请选择设备”“返回列表”“01 工作台”“02 批量和协议命令”“03 设备影子”“当前设备”“刷新配置”“清理缓存”“运行状态”“告警历史”，并显示 `DeviceConfigPanel` 的空状态“请从左侧设备树或设备列表选择设备”。
- `/device/workbench?deviceId=dev-1`：dev server 能打开，顶部和左侧上下文显示 `dev-1`，后端不可达/无设备时 `DeviceConfigPanel` 保持空状态，不崩溃，不反向修改 query。
- `/control?deviceId=dev-1`：dev server 能打开，仍由 `LegacyConsoleView.vue + LegacyManualShadowPanels.vue` 承载，显示 `dev-1` 上下文和“手动控制”主体。
- `/shadow?deviceId=dev-1`：dev server 能打开，仍由 `LegacyConsoleView.vue + LegacyManualShadowPanels.vue` 承载，显示 `dev-1` 上下文和“设备影子”主体。
- `/history?deviceId=dev-1&pointId=p1`：dev server 能打开，仍由 `HistoryView.vue` 承载，查询结果 JSON 带入 `deviceId: "dev-1"`，后端不可达时不崩溃。
- `/realtime?deviceId=dev-1&pointId=p1`：dev server 能打开，仍由 `RealtimeView.vue` 承载，后端不可达时不崩溃。
- `/diagnostic?deviceId=dev-1`：dev server 能打开，仍由 `DiagnosticView.vue` 承载，单设备状态 JSON 带入 `deviceId: "dev-1"`，后端不可达时显示“单设备状态查询失败”。
- `/alarm?deviceId=dev-1`：dev server 能打开，仍由 `AlarmView.vue` 承载，页面显示“设备 dev-1 · 0 条 · 已确认 0”，后端不可达时不崩溃。
- 额外 smoke：`/dashboard`、`/cloud`、`/log`、`/network?target=127.0.0.1&port=502` 均能打开，仍由对应独立 View 承载。
- 代码检查确认 `/dashboard`、`/realtime`、`/history`、`/alarm`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/device`、`/device/workbench` 均直接解析到独立 View；`/control`、`/shadow` 仍解析到 `LegacyConsoleView.vue`。
- 代码检查确认 `LegacyConsoleView.vue` 已无 `DeviceConfigPanel`、`activeModule`、`resetSelectedAdaptive`、`openWorkbenchHistory`、`openWorkbenchRealtime`、`startSelectedDevice`、`stopSelectedDevice`、`resetAdaptiveConfig`、`startDevice`、`startLocalDevice`、`stopDevice`。
- 代码检查确认 `DeviceWorkbenchView.vue` 没有 `normalizeDeviceViewModelWithRuntimeStatus`、`const devices = ref`、`deviceRuntimeMap` 或 `const selectedDeviceId = ref`。
- 受当前 in-app preview session 限制，`drive_preview` 无法执行真实点击；Tab 点击链路通过源码 `openWorkbenchTab()` / `routePathForWorkbenchTab()`、router test 和直接打开目标 URL 验证 URL 与显示内容一致。
- Vite dev server 使用 5183 完成 smoke check；用于 smoke check 的 5183 dev server 已停止。

## Phase 12 新增文件

- `collector-desktop/src/views/device/DeviceWorkbenchView.vue`
- `collector-boot/src/main/resources/static/desktop/assets/DeviceWorkbenchView-DigYNbxw.js`（`build:web` 生成）

## Phase 12 修改文件

- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 12 删除文件

- 无源码文件删除。
- `build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 13：Local Device Editor 整理与迁移完成内容

- `src/components/device/LocalDeviceEditor.vue` 已正式移动到 `src/features/device/components/LocalDeviceEditor.vue`，继续作为 DeviceList、Collection、Dashboard 三个独立页面共享的本地临时设备编辑器。
- `src/components/device/local-device-utils.ts` 与 `src/components/device/local-device-utils.test.ts` 已移动到 `src/features/device/utils/local-device-utils.ts` / `.test.ts`，旧路径不保留第二套长期副本。
- `local-device-utils.ts` 继续 export `AdaptiveConfig`、`CloudTargetConfig`、`LocalDeviceDraft`、`LocalDevicePayload`、`LocalDeviceBundle`、`ProtocolPointNotes`，以及 `DEFAULT_ADAPTIVE_CONFIG`、`extractLocalDeviceBundle()`、`buildLocalDevicePayload()`、`validateLocalDeviceDraft()`、`buildProtocolPointNotes()`、`normalizeLocalPoints()`、`normalizeAdaptive()`。
- `LocalDeviceEditor.vue` 删除内部重复 `interface LocalDeviceBundle`，统一从 `features/device/utils/local-device-utils` import `LocalDeviceBundle`；代码搜索确认 `LocalDeviceBundle` 只有 `local-device-utils.ts` 一个正式接口定义。
- `DeviceListView.vue`、`CollectionView.vue`、`DashboardView.vue` 均已改为 import `@/features/device/components/LocalDeviceEditor.vue`，需要 `LocalDeviceBundle` / `extractLocalDeviceBundle()` 的地方统一 import `@/features/device/utils/local-device-utils`。
- `LocalDeviceEditor.vue` 保持低耦合 props：`modelValue`、`editingBundle`、`protocols`；组件内部未引入 `useDeviceStore()` 或 `useProtocolStore()`，仍由父页面传入协议列表。
- 协议 Schema 按需加载保持不变：编辑器仍在打开或协议切换时调用 `getProtocol(protocol)` 获取完整 `connectionFields`、`pointFields`、`pointAddressHints`、`dataTypes`，不会把父页面传入的列表协议元数据误认为完整 Schema。
- `DashboardView.vue` 的 LocalDeviceEditor 协议来源已收敛到 `useProtocolStore()`：模板传入 `protocolStore.protocols`，`openLocalEditor()` 仅在协议列表为空时 `await protocolStore.refresh()`，有 `protocolStore.error` 时提示并停止打开；已删除 Dashboard 自己维护的 `protocols ref`、`listProtocols` import 和 `ProtocolSchema` type import。
- `DeviceListView.vue` 与 `CollectionView.vue` 本 Phase 只调整 LocalDeviceEditor / local-device-utils import，继续保持原有 `protocolStore`、`deviceStore`、配置同步、导入导出、Router Query 等业务边界。
- 新增 `src/features/device/utils/local-device-editor-utils.ts` / `.test.ts`，仅承接输入输出明确、不依赖 Vue ref/computed/props 的纯 helper，没有新增 `useLocalDeviceEditor` composable，也没有创建 `local-device.store.ts`。
- 迁出的 pure helper 包括：`normalizeInitialPoints()`、`defaultPointTemplate()`、`defaultAddress()`、`normalizeCloudTarget()`、`alarmRules()`、`serializeAlarmRules()`、`parsePointsJson()`、`sanitizePointForSave()`、`removeDeprecatedCloudIdentityConfig()`、`cloudTargetSummary()`、`cloudPointStatus()`、`statusLabel()`、`parseBooleanOption()`、`parseFieldValue()`、`toNumber()`、`findDuplicatePointCode()`、`createUniqueCode()`、`buildReadonlyItems()`、`isOpcUaProtocol()`、`hasValue()`、`pruneEmpty()`。
- 留在组件内的仍是 UI / Vue 状态操作：`setActiveStep()`、`moveStep()`、`reset()`、`onProtocolChanged()`、`selectPoint()`、`updatePointField()`、`save()`、`close()`、keydown/modal 生命周期、协议 Schema state 和点位选择 state。
- 本 Phase 没有拆 `LocalDeviceSetupStep.vue`、`LocalDeviceCloudStep.vue`、`LocalDeviceJsonStep.vue` 或复杂 Point Step。原因是当前模板拆分会制造大量 props/emits，尤其点位详情区与 JSON/云/协议 Schema 之间仍有紧耦合；Phase 13 只做目录与纯函数边界整理。
- 四个主步骤完整保留：`01 基础连接`、`02 点位建模`、`03 云平台上报`、`04 JSON 高级`；没有新增 Route、没有新增 Wizard，也没有调整步骤顺序。
- 基础连接能力保持：设备 ID、设备名称、协议、基础/最小/最大采集周期、点位变化阈值、cloudTarget、Topic preview、ProtocolDynamicForm 动态连接参数、必填字段统计和校验仍在原编辑器流程中。
- 新增模式保持：`deviceId` 可编辑，默认协议仍为 `MODBUS_TCP` 或父级协议列表第一项，默认 adaptive 使用 `DEFAULT_ADAPTIVE_CONFIG`，无点位时自动建立一个默认点位，`overwrite` 与 `startAfterSave` 默认关闭。
- 编辑模式保持：`editingBundle` 存在时继续读取旧 device / connection / points / cloudTarget，设备 ID 禁止修改，`overwrite` 默认启用，协议、adaptive、动态连接参数和点位全部回填。
- 协议切换保持：`onProtocolChanged()` 继续清空/重建 connection model、重新归一化 points、同步 JSON，并触发 `ensureProtocolSchema(protocol)` 获取完整 Schema；默认地址/类型逻辑由纯 helper 覆盖 MQTT、OPC UA、SIEMENS S7、Modbus。
- `ProtocolDynamicForm.vue` 和 `protocol-form-utils.ts` 未迁移、未重写；LocalDeviceEditor 继续使用 `buildConnectionPayload()`、`buildProtocolInitialModel()`、`extractProtocolModel()`、`getPathValue()`、`setPathValue()`、`validateProtocolModel()`。
- 点位编辑能力保持：新增点位、复制点位、删除点位、搜索点位、选择点位仍在组件内；点位详情 Tabs `基础信息`、`数据处理`、`上报 / 缓存参数`、`协议扩展`、`告警规则`、`只读信息` 均保留。
- Phase 13 未提前迁移 Point Feature：未移动 `src/components/point/PointEditor.vue`，未创建 point store，未统一两个点位编辑器，未重做点位表格。
- `alarmRule` 语义保持为保存前 JSON 字符串；`local-device-utils.test.ts` 保留并补充覆盖点位 adaptive、reportField/reportEnabled、alarmRule JSON string、cloudTarget、connection extJson、temporaryConfig、configSource 等保存格式。
- 云上报语义保持：设备级 `cloudTarget` 继续负责 `productKey`、`deviceName`、`deviceType`、`topologyEnabled`；点位级 `additionalConfig.reportField`、`reportEnabled`、`eventEnabled`、`streamEnabled`、`historyEnabled` 保持；保存前仍清理废弃 `reportBindings`、`reportProductKey`、`reportDeviceName`、`cloudBindings`。
- JSON 高级模式保持：点位 JSON 数组编辑、格式化 JSON、应用 JSON 到列表和解析失败明确错误仍在；列表变更与 JSON 同步仍由组件状态操作维护。
- 保存流程保持：补 connection 默认值 -> normalize points -> sanitize points -> `validateLocalDeviceDraft()` -> `validateProtocolModel()` -> `buildConnectionPayload()` -> `buildLocalDevicePayload()` -> `updateLocalDevice()` 或 `createLocalDevice()` -> `startAfterSave` 时 `startLocalDevice()` -> `emit("saved", deviceId)` -> 关闭。
- `saved(deviceId)` 契约保持，三个父页面仍以保存后的 deviceId 刷新/选择设备上下文；没有改成无参数事件或 payload 事件。
- 关闭行为保持：Teleport 到 body、点击背景关闭、关闭按钮、Escape 关闭、`document.body.classList.toggle("modal-active", visible)`、unmount 时移除 keydown listener 和 `modal-active`。
- CSS 本 Phase 未从 `workbench.css` 迁出到 scoped style。已搜索确认 `local-editor`、`local-device-panel`、`local-device-web-dialog`、`local-editor-title`、`local-editor-tabs`、`local-editor-layout`、`local-editor-rail`、`local-editor-body`、`local-checklist`、`local-section-card` 等仍被 DeviceWorkbench / Legacy Control / Legacy Shadow / DeviceConfigPanel / ManualShadowPanels / LocalDeviceEditor 共同使用，避免复制全局样式。
- 搜索中识别到若干 Editor-only selector（如 `local-setup-*`、`local-cloud-*`、`local-point-*`、`point-detail-*`、`local-options`、`local-editor-footer`、`local-connection-meta` 等）主要只由 LocalDeviceEditor 模板与 `workbench.css` 使用；考虑到这些规则夹在当前共享 modal/workbench 样式块内且共享外壳仍未迁完，本 Phase 记录边界但不复制/拆半套 CSS，等待 Control / Shadow 和最终 `workbench.css` 阶段统一处理。
- 代码搜索确认旧路径引用为 0：`components/device/LocalDeviceEditor`、`components/device/local-device-utils` 均无命中；`src/components/device/LocalDeviceEditor.vue`、`src/components/device/local-device-utils.ts`、`src/components/device/local-device-utils.test.ts` 均已不存在。
- 代码检查确认 `/dashboard`、`/realtime`、`/history`、`/alarm`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/device`、`/device/workbench` 仍直接解析到独立 View；`/control`、`/shadow` 仍解析到 `LegacyConsoleView.vue`。

## Phase 13 验证结果

执行时间：2026-08-31 13:51-14:31，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 35 个测试文件、193 个测试通过；新增 `src/features/device/utils/local-device-editor-utils.test.ts`，迁移后的 `local-device-utils.test.ts` 通过 |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成新的 `DeviceListView`、`CollectionView`、`DashboardView`、`DeviceWorkbenchView` 等 hash 产物 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 46 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 13 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 13 路由与页面检查结果

- `/device`：dev server `http://127.0.0.1:5184/#/device` 能打开，仍由 `DeviceListView.vue` 承载；页面显示“设备管理”“新增本地设备”等入口，后端不可达/CORS 失败时页面不崩溃。
- `/device` 新增本地设备 smoke：在带 CORS 的本地 mock 采集服务下点击“新增本地设备”，LocalDeviceEditor 正常打开；四个步骤存在，默认协议为 `MODBUS_TCP`，至少一个默认点位；关闭按钮、Escape、背景点击均能关闭，关闭后 `modal-active` 清理。
- `/device` 编辑本地设备 smoke：mock 本地设备点击“编辑”后通过 `getLocalDevice()` -> `extractLocalDeviceBundle()` -> `LocalDeviceEditor` 回填；设备 ID 禁止编辑，设备名称、协议、点位、cloudTarget/adaptive 进入编辑状态，`overwrite` 默认启用。
- `/device` 保存 smoke：mock 服务下新增本地设备并勾选“保存后立即本地启动”，确认调用 `createLocalDevice()` 1 次与 `startLocalDevice()` 1 次，保存后弹窗关闭并触发父页面刷新链路。
- `/collect`：dev server 能打开，仍由 `CollectionView.vue` 承载；mock 协议列表下点击协议行“配置设备”能打开 LocalDeviceEditor，Editor 接收 `protocolStore.protocols`，默认协议和默认点位正常。
- `/dashboard`：dev server 能打开，仍由 `DashboardView.vue` 承载；mock smoke 先清空请求日志后进入 Dashboard，页面启动阶段没有请求 `/api/protocols`；点击“新增本地设备”后才通过 `protocolStore.refresh()` 请求协议列表并打开 LocalDeviceEditor。
- `/device/workbench?deviceId=local-smoke-1`：dev server 能打开，仍由 `DeviceWorkbenchView.vue` 承载，显示 Workbench / Control / Shadow 三项外层 Tab。
- `/control?deviceId=local-smoke-1`：dev server 能打开，仍由 `LegacyConsoleView.vue + LegacyManualShadowPanels.vue` 承载，显示“手动控制”和设备上下文。
- `/shadow?deviceId=local-smoke-1`：dev server 能打开，仍由 `LegacyConsoleView.vue + LegacyManualShadowPanels.vue` 承载，显示“设备影子”和设备上下文。
- 代码检查确认 `LocalDeviceEditor.vue` 没有 `useDeviceStore()`、`useProtocolStore()` 或 `local-device.store.ts`；`getProtocol()`、`createLocalDevice()`、`updateLocalDevice()`、`startLocalDevice()` 仍是它直接依赖的后端 API 边界。
- 代码检查确认 `DashboardView.vue` 已无 `listProtocols`、`ProtocolSchema` 和 `protocols = ref<ProtocolSchema[]>([])`；只保留 `protocolStore` 按需加载。
- 因当前 in-app preview `drive_preview` 会返回 “The in-app browser only takes actions in the session the user is looking at.”，交互 smoke 通过独立 Headless Chrome + CDP 完成；dev server、headless Chrome、mock 后端进程均在验证后停止。

## Phase 13 新增文件

- `collector-desktop/src/features/device/utils/local-device-editor-utils.ts`
- `collector-desktop/src/features/device/utils/local-device-editor-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/DeviceWorkbenchView-DrTXVYw_.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/DeviceListView-DywcU1fT.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/DeviceListView-SI_Y4VxW.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/CollectionView-CP0xkHgY.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/CollectionView-UMtYFAkz.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/DashboardView-Cjx-EyaJ.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/DashboardView-CxVFgIqF.css`（`build:web` 生成）

## Phase 13 修改 / 移动文件

- `collector-desktop/src/features/device/components/LocalDeviceEditor.vue`（从 `src/components/device/LocalDeviceEditor.vue` 移动后整理）
- `collector-desktop/src/features/device/utils/local-device-utils.ts`（从 `src/components/device/local-device-utils.ts` 移动）
- `collector-desktop/src/features/device/utils/local-device-utils.test.ts`（从 `src/components/device/local-device-utils.test.ts` 移动后补充断言）
- `collector-desktop/src/views/device/DeviceListView.vue`
- `collector-desktop/src/views/collection/CollectionView.vue`
- `collector-desktop/src/views/dashboard/DashboardView.vue`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 13 删除文件

- `collector-desktop/src/components/device/LocalDeviceEditor.vue`
- `collector-desktop/src/components/device/local-device-utils.ts`
- `collector-desktop/src/components/device/local-device-utils.test.ts`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## 已知问题与回归风险

- `/device`、`/collect`、`/dashboard` 的 LocalDeviceEditor 交互 smoke 使用带 CORS 的本地 mock 采集服务完成，用于验证前端路由、弹窗、协议加载、保存调用顺序和关闭行为；真实设备、真实点位、真实启动/停止、配置缓存刷新/清理、协议配置读写、历史/实时跳转后的真实数据仍依赖后端服务与设备数据环境。
- Control / Shadow 仍在 `LegacyConsoleView.vue` 中过渡，仍保留 Legacy 局部 `devices`、`deviceRuntimeMap`、`selectedDeviceId`、`selectedRealtimeRows`、`loadDevices()`、`loadSelectedRealtime()`、`operateDeviceConfig()`；这些将在后续 Control / Shadow / Legacy Host 阶段继续收敛。
- `DeviceConfigPanel.vue`、`PointEditor.vue`、`RealtimeDataPanel.vue`、`AlarmTablePanel.vue`、`LogPanel.vue` 本 Phase 未拆分；`LocalDeviceEditor.vue` 只迁出纯 helper，模板和 UI 状态仍按阶段边界保留在组件内。
- `workbench.css` 中同时服务 DeviceWorkbench、Legacy Control、Legacy Shadow、DeviceConfigPanel、ManualShadowPanels、LocalDeviceEditor 的共享样式仍暂时保留为全局样式，未复制到新 View；等 Control/Shadow 迁出和最终样式阶段再统一拆解。
- Web 静态产物 hash 因 `build:web` 更新，属于验证命令产生的预期变更。

## Phase 13 后续说明

Phase 13 完成后已按确认进入 Phase 14。本段保留为历史说明；Control、Shadow 和最终 Legacy Host 清理仍未在 Phase 13 中执行。

## Phase 14：Point Feature 迁移与整理完成内容

- `src/components/point/PointEditor.vue` 已正式移动到 `src/features/point/components/PointEditor.vue`，旧路径删除；`DeviceConfigPanel.vue` 的 import 已统一改为 `@/features/point/components/PointEditor.vue`。
- `src/components/point/PointBatchEditDialog.vue` 已移动到 `src/features/point/components/PointBatchEditDialog.vue`，继续保持 `modelValue`、`selectedCount`、`apply(payload)` 契约；批量编辑字段仍为 `alarmEnabled`、`dataType`、`readWrite`、`unit`、`baseCollectionInterval`。
- `src/components/point/PointGenerateDialog.vue` 已移动到 `src/features/point/components/PointGenerateDialog.vue`，继续保持数量、起始地址、地址步长、编码前缀、名称前缀、数据类型、读写类型和 `generate(options)` 契约；没有新增 S7、OPC UA、IEC104 等协议地址递增规则。
- `src/components/point/point-editor-utils.ts` 与测试已移动到 `src/features/point/utils/point-editor-utils.ts` / `.test.ts`，保留 `BuildIncrementalPointsOptions`、`PointBatchEditPayload`、`PointImportPreview`、`PointLocationTarget`、`PointExtraModel`，以及 `buildIncrementalPoints()`、`applyPointBatchEdit()`、`buildPointImportPreview()`、`buildPointLocationTarget()`、`normalizePointRows()`、`buildPointExtraModel()`、`applyPointExtraModel()`、`mergePointRuntime()`、`formatJsonForTextarea()`、`parseJsonTextarea()`。
- `src/components/point/point-excel-utils.ts` 与测试已移动到 `src/features/point/utils/point-excel-utils.ts` / `.test.ts`。文件名暂不改为 csv-utils，避免本阶段扩大 rename 范围；实际行为仍是 CSV：只支持 `.csv`、最大 1 MB、最大 2000 行，继续支持既有中文表头。
- `src/stores/point.store.ts` 继续留在共享 `src/stores/`，但 import 已改为依赖 `@/features/point/utils/point-editor-utils`；没有新增 `features/point/point.store.ts`、`point-editor.store.ts` 或 `pointDraftStore`。
- 新增 `src/stores/point.store.test.ts`，覆盖 `addEmptyPoint()`、`appendGeneratedPoints()`、`replacePoints()`、`applyBatch()`、`removeSelected()`、`setSelectedIds()`，验证 Store 继续通过迁移后的 Point Feature utils 执行轻量 actions。
- `PointEditor.vue` 仍只接收 `deviceId`、`protocol`、`protocolCode` props，不直接使用 `useDeviceStore()`、`useProtocolStore()` 或 `useRouter()`；设备和协议上下文继续由 `DeviceConfigPanel.vue` 传入。
- `PointEditor.vue` 继续使用 `usePointStore()` 作为持久化点位编辑唯一状态源：点位数组来自 `pointStore.getPoints(deviceId)`，选中项来自 `pointStore.getSelectedIds(deviceId)`，保存仍调用 `pointStore.save(deviceId)` -> `saveDevicePointConfig()`。组件本地只保留 `keyword`、`selectedPointId`、`detailTab`、Dialog 可见性、JSON textarea、realtimeRows 等 UI 临时状态。
- `PointEditor.vue` 的实时数据加载已收敛为 `getDeviceRealtimeData(deviceId)` -> `normalizeRealtimeRows(response, deviceId)` -> `realtimeRows`，不再使用 `response.points || response.data || response.values || []` 第三套兼容逻辑；`mergePointRuntime()` 仍按 `pointId`、`pointCode`、`address` 匹配实时点位。
- `PointEditor.vue` 原有新增点位、批量生成、批量编辑、批量删除、搜索、CSV 导入、CSV 导出、实时值刷新、配置刷新、点位保存、多选、当前点位详情、基础信息、数据处理、上报参数、协议扩展、告警、additionalConfig JSON、alarmRule JSON、当前值、质量、处理耗时、查看实时、查看历史、导入预览、重复编码提示、重复地址提示均保留。
- CSV Import Preview 流程保持：选择文件 -> `validatePointImportFile()` -> `parsePointCsv()` -> `buildPointImportPreview()` -> Preview Dialog -> 确认导入 -> `pointStore.replacePoints()`；重复编码/地址仍是 warning，不升级为阻断 error。
- 动态协议字段语义保持：`ProtocolSchema.pointFields` 继续通过 `buildPointExtraModel()` / `applyPointExtraModel()` 映射到 `additionalConfig`，没有修改 Protocol Schema 或 Point API。
- `open-history` / `open-realtime` emit 契约保持，目标仍由 `buildPointLocationTarget()` 生成，路由跳转仍由 `DeviceConfigPanel` -> `DeviceWorkbenchView` 承接，Point Feature 不直接持有 Router。
- `LocalDeviceEditor.vue` 未嵌入或复用 `<PointEditor />`，也未改用 `usePointStore()`；本地设备草稿点位仍是 LocalDevicePayload 的一部分，继续随 device / connection / cloudTarget 一起保存，避免尚未保存的新设备污染全局点位状态或触发 point API。
- 从 Phase 13 的 `features/device/utils/local-device-editor-utils.ts` 中迁出通用点位草稿 helper 到 `src/features/point/utils/point-draft-utils.ts` / `.test.ts`：`alarmRules()`、`serializeAlarmRules()`、`parsePointsJson()`、`statusLabel()`、`parseBooleanOption()`、`parseFieldValue()`、`toNumber()`、`findDuplicatePointCode()`、`createUniqueCode()`、`buildReadonlyItems()` 以及相关轻量类型。迁移原因是这些能力是点位编辑通用数据规则，不依赖 Local Device Draft 的保存事务。
- Device 专属 Draft helper 继续留在 `features/device/utils/local-device-editor-utils.ts`：`normalizeInitialPoints()`、`defaultPointTemplate()`、`defaultAddress()`、`normalizeCloudTarget()`、`sanitizePointForSave()`、`removeDeprecatedCloudIdentityConfig()`、`cloudTargetSummary()`、`cloudPointStatus()`、`firstPointValue()`、`isOpcUaProtocol()`、`hasValue()`、`isPlainObject()`、`cloneData()`。保留原因是它们与 Local Device 草稿初始化、cloudTarget/adaptive、保存前清理和旧 Web 兼容默认值强相关。
- CSS 本 Phase 没有大规模搬迁：`point-toolbar`、`point-workbench-grid`、`point-table-panel`、`point-detail-panel`、`point-detail-tabs`、`point-json-textarea`、`point-import-preview-*` 等仍在 `workbench.css` 中；原因是其中部分选择器仍与 DeviceWorkbench、LocalDeviceEditor 或全局 workbench 结构共用，避免复制一套 scoped CSS 与全局 CSS 并存。后续 Control/Shadow/Legacy Host 清理阶段再统一拆解。
- 代码检查确认 `src/components/point/` 已无旧文件且目录不存在；`components/point`、`@/components/point`、`./point-editor-utils`、`./point-excel-utils` 搜索均为 0；`PointEditor.vue` 内 `useDeviceStore()`、`useProtocolStore()`、`useRouter()` 搜索均为 0；`LocalDeviceEditor.vue` 内 `usePointStore()` 搜索为 0。
- 代码检查确认 `/dashboard`、`/realtime`、`/history`、`/alarm`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/device`、`/device/workbench` 仍解析到独立 View；`/control`、`/shadow` 仍解析到 `LegacyConsoleView.vue`。

## Phase 14 验证结果

执行时间：2026-09-01 08:53-09:26，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 37 个测试文件、200 个测试通过；包含迁移后的 point utils、point draft utils、point excel/csv utils、point.store 测试 |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 46 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 14 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。这些不是本阶段新增失败。

## Phase 14 路由与页面检查结果

- `/device`：独立 `DeviceListView.vue` 保持；LocalDeviceEditor “新增本地设备”入口 smoke 通过。
- `/device/workbench?deviceId=dev-1`：独立 `DeviceWorkbenchView.vue` 保持；点击“编辑点位”后 `PointEditor.vue` 正常显示。
- `/dashboard`：独立 `DashboardView.vue` 保持；LocalDeviceEditor “新增本地设备”入口回归 smoke 通过。
- `/collect`：独立 `CollectionView.vue` 保持；协议列表“配置设备”入口回归 smoke 通过。
- `/control?deviceId=dev-1`：仍由 `LegacyConsoleView.vue` 承载，页面显示“手动控制”。本 Phase 未迁移 Control。
- `/shadow?deviceId=dev-1`：仍由 `LegacyConsoleView.vue` 承载，页面显示“设备影子”。本 Phase 未迁移 Shadow。
- PointEditor smoke 使用本地 mock 采集服务验证：点位加载、新增点位、批量生成、多选、批量编辑、CSV 导入、导入预览、重复编码/地址 warning、确认导入、CSV 导出、刷新实时值、保存、协议扩展字段、additionalConfig JSON、alarmRule JSON、查看实时、查看历史均可走通。
- PointEditor 后端失败态 smoke 使用 `dev-fail` mock 设备验证：点位配置接口和实时数据接口返回失败时，编辑器显示“配置加载失败”“实时数据加载失败”，页面仍保持可显示和可操作的空/失败状态。
- LocalDeviceEditor 三入口回归 smoke：`/device`、`/collect`、`/dashboard` 均能打开四步编辑器，并验证点位新增/复制/删除、告警规则、云配置、JSON 高级入口无回归。

## Phase 14 新增文件

- `collector-desktop/src/features/point/utils/point-draft-utils.ts`
- `collector-desktop/src/features/point/utils/point-draft-utils.test.ts`
- `collector-desktop/src/stores/point.store.test.ts`

## Phase 14 修改 / 移动文件

- `collector-desktop/src/features/point/components/PointEditor.vue`（从 `src/components/point/PointEditor.vue` 移动）
- `collector-desktop/src/features/point/components/PointBatchEditDialog.vue`（从 `src/components/point/PointBatchEditDialog.vue` 移动）
- `collector-desktop/src/features/point/components/PointGenerateDialog.vue`（从 `src/components/point/PointGenerateDialog.vue` 移动）
- `collector-desktop/src/features/point/utils/point-editor-utils.ts`（从 `src/components/point/point-editor-utils.ts` 移动）
- `collector-desktop/src/features/point/utils/point-editor-utils.test.ts`（从 `src/components/point/point-editor-utils.test.ts` 移动）
- `collector-desktop/src/features/point/utils/point-excel-utils.ts`（从 `src/components/point/point-excel-utils.ts` 移动）
- `collector-desktop/src/features/point/utils/point-excel-utils.test.ts`（从 `src/components/point/point-excel-utils.test.ts` 移动）
- `collector-desktop/src/components/device/DeviceConfigPanel.vue`
- `collector-desktop/src/features/device/components/LocalDeviceEditor.vue`
- `collector-desktop/src/features/device/utils/local-device-editor-utils.ts`
- `collector-desktop/src/features/device/utils/local-device-editor-utils.test.ts`
- `collector-desktop/src/stores/point.store.ts`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 14 删除文件

- `collector-desktop/src/components/point/PointEditor.vue`
- `collector-desktop/src/components/point/PointBatchEditDialog.vue`
- `collector-desktop/src/components/point/PointGenerateDialog.vue`
- `collector-desktop/src/components/point/point-editor-utils.ts`
- `collector-desktop/src/components/point/point-editor-utils.test.ts`
- `collector-desktop/src/components/point/point-excel-utils.ts`
- `collector-desktop/src/components/point/point-excel-utils.test.ts`

`src/components/point/` 目录为空后已不存在。`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 14 已知问题与回归风险

- 本阶段 smoke 使用本地 mock 采集服务和 Headless Chrome/CDP 验证前端流程；真实设备、真实协议连接、真实点位保存后的设备行为、真实历史/实时数据仍依赖实际后端与设备环境。
- PointEditor 模板仍较大，本阶段没有拆 `PointBasicTab`、`PointDataTab`、`PointReportTab`、`PointProtocolTab`、`PointAlarmTab`，避免产生大量双向绑定 props；可选 `PointImportPreviewDialog` 也未拆，因为当前内联预览逻辑仍足够清晰且不影响 feature 目录边界。
- `workbench.css` 仍承载 PointEditor、DeviceWorkbench、LocalDeviceEditor、Control/Shadow 过渡样式，最终样式拆解留到 Legacy Host 清理阶段统一处理。
- `/control`、`/shadow` 仍由 `LegacyConsoleView.vue` 承载，`LegacyConsoleView.vue` 未删除；Phase 15 前必须先分析 Control、Shadow、`LegacyManualShadowPanels.vue` 和 Legacy Host 的完整迁出方式。

## Phase 15：Remove Legacy Host 完成内容

- 新增 `src/views/control/ControlView.vue` 与 `src/views/shadow/ShadowView.vue`，`/control`、`/shadow` 已直接由独立 View 承载，不再进入 Legacy Host。
- 新增 `src/features/device/components/DeviceOperationShell.vue`，由 `AppShell -> AppTopbar -> RouterView` 下的独立页面复用设备操作外壳；它负责 `appStore.initialize()`、`deviceStore.refresh()`、`route.query.deviceId` 单向同步、当前设备摘要、实时点位预览、运行/连接摘要、配置刷新、清理缓存、返回设备列表、运行状态、告警历史和工作台/控制/影子三路 Router 导航。
- Control 业务从 `LegacyManualShadowPanels.vue` 拆到 `src/features/control/components/ControlPanel.vue`；`ControlView.vue` 只组合 `DeviceOperationShell active-tab="control"` 与 `ControlPanel`。
- Control 新增 `src/features/control/utils/control-utils.ts` 与测试，覆盖单点值转换、单点 payload、批量模板、命令模板和 JSON 解析。单点写入、批量写点位、协议命令继续使用 `writeDevicePoint()`、`writeDevicePoints()`、`executeDeviceCommand()`，后端 URL 与 payload 语义未改。
- Shadow 业务从 `LegacyManualShadowPanels.vue` 拆到 `src/features/shadow/components/ShadowPanel.vue`；`ShadowView.vue` 只组合 `DeviceOperationShell active-tab="shadow"` 与 `ShadowPanel`。
- `shadow-utils.ts` 与测试从 `src/views/legacy/` 移到 `src/features/shadow/utils/`，保留 `ShadowHistoryRow`、`ShadowStateSummary`、`normalizeShadowHistoryRows()`、`summarizeShadowState()`，并补充 `parseShadowJson()`、`formatShadowTime()`、`compactJson()`、导出 payload 和文件名 helper 测试。
- Shadow 继续使用 `getShadow()`、`getShadowDelta()`、`getShadowHistory()`、`updateShadowDesired()`、`clearShadowDesired()`；desired 默认 payload 仍为 `{ "desired": {} }`，提交时仍发送当前 JSON 对象本身，不改成只发送内层对象。
- Router 已改为 `/control -> ControlView`、`/shadow -> ShadowView`，并删除 `LegacyConsoleView` lazy import；`router.test.ts` 覆盖 `/control`、`/control?deviceId=dev-1`、`/shadow`、`/shadow?deviceId=dev-1` 以及已迁移业务路由独立解析。
- 删除 `src/views/legacy/LegacyConsoleView.vue`、`src/views/legacy/LegacyManualShadowPanels.vue`、旧 `shadow-utils.ts` / `shadow-utils.test.ts`，`src/views/legacy/` 目录已不存在。
- 复查 `src/views/runtime/runtime-utils.ts` / `runtime-utils.test.ts` 仅剩旧模板测试用途，且其中批量模板已与当前生产 Control payload 不一致；本阶段已删除 `src/views/runtime/`，没有把过时 helper 重新引入 Control。
- 本阶段未重写 `DeviceWorkbenchView.vue`、`DeviceListView.vue`、`PointEditor.vue`、`LocalDeviceEditor.vue`，未修改后端、Electron 和 CSS 主体；`legacy-console.css` 与 `workbench.css` 按 Phase 16 之前的共享样式继续保留。

## Phase 15 验证结果

执行时间：2026-09-01 10:22-10:23，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 37 个测试文件、206 个测试通过；新增 `control-utils.test.ts`，迁移后 `shadow-utils.test.ts` 继续通过，Router 测试覆盖 Control/Shadow 独立 View |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过，生成独立 `ControlView`、`ShadowView` 与 `DeviceOperationShell` chunk |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 47 |
| `git diff --check` | 通过 | exit code 0；仅有 Git 对 Web 构建产物 LF/CRLF 的工作区换行提示 |

本阶段 Headless Chrome/CDP smoke 使用本地 mock 采集服务验证以下页面和交互：`/device`、`/device/workbench?deviceId=dev-1`、`/control?deviceId=dev-1`、`/shadow?deviceId=dev-1`、`/diagnostic?deviceId=dev-1`、`/alarm?deviceId=dev-1`、`/dashboard`、`/realtime`、`/history`、`/collect`、`/cloud`、`/network`、`/log`。其中 Control 覆盖 STRING / BOOLEAN / INT / FLOAT / DOUBLE 单点写入、批量模板、非法 JSON 防崩溃、批量写入、命令模板和协议命令；Shadow 覆盖读取全部、读取影子、读取 delta、history limit、读取历史、提交 desired、清理 desired；三路导航覆盖 Workbench -> Control -> Shadow -> Workbench -> Shadow -> Control -> Device List，`deviceId=dev-1` 未丢失。

## Phase 15 文件变化

新增：

- `collector-desktop/src/views/control/ControlView.vue`
- `collector-desktop/src/views/shadow/ShadowView.vue`
- `collector-desktop/src/features/device/components/DeviceOperationShell.vue`
- `collector-desktop/src/features/control/components/ControlPanel.vue`
- `collector-desktop/src/features/control/utils/control-utils.ts`
- `collector-desktop/src/features/control/utils/control-utils.test.ts`
- `collector-desktop/src/features/shadow/components/ShadowPanel.vue`

移动：

- `collector-desktop/src/views/legacy/shadow-utils.ts` -> `collector-desktop/src/features/shadow/utils/shadow-utils.ts`
- `collector-desktop/src/views/legacy/shadow-utils.test.ts` -> `collector-desktop/src/features/shadow/utils/shadow-utils.test.ts`

修改：

- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/scripts/workbench-layout-contract.test.mjs`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

删除：

- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/src/views/legacy/LegacyManualShadowPanels.vue`
- `collector-desktop/src/views/runtime/runtime-utils.ts`
- `collector-desktop/src/views/runtime/runtime-utils.test.ts`
- `collector-desktop/src/views/legacy/` 空目录
- `collector-desktop/src/views/runtime/` 空目录

## Phase 15 已知问题与回归风险

- 本阶段 smoke 使用本地 mock 采集服务验证前端页面和请求 payload；真实设备、真实协议命令、真实 Shadow desired/delta/history 行为仍需真实后端与设备环境联调。
- `DeviceWorkbenchView.vue` 暂未接入 `DeviceOperationShell`，避免扩大 Phase 12 已验证工作台的改动范围；目前 Shell 只服务 Control/Shadow。
- `legacy-console.css` 与 `workbench.css` 仍保留共享样式，Phase 16 需要基于实际 selector 使用情况系统化清理，不能直接按文件名删除。
- 搜索 `runtime-utils` 仍会命中 `features/diagnostic/utils/device-runtime-utils.ts`，这是诊断 feature 的独立 helper，不是已删除的 `src/views/runtime/runtime-utils.ts`。

## 下一步

等待确认后进入 Phase 16：清理 `legacy-console.css` / `workbench.css`。

Phase 16 执行前需要先统计 `legacy-console.css` / `workbench.css` 中仍被已迁页面复用的 selector，区分全局共享样式、设备操作工作台样式、已死亡 Legacy Host 样式和页面专属样式；当前不自动执行 Phase 16。

## Phase 16：CSS Migration / Cleanup 完成内容

- 完成 `legacy-console.css` / `workbench.css` selector inventory，先按当前 `src/**/*.vue`、`src/**/*.ts`、`src/**/*.mjs` 引用情况区分死亡 selector、App 全局基础、共享 primitive 和 feature/component 专属规则，再开始迁移。
- 删除 `src/styles/legacy-console.css` 与 `src/styles/workbench.css`，`main.ts` 不再导入两个历史大 CSS。
- `main.ts` 样式入口固定为：Element Plus 原始 CSS -> `tokens.css` -> `global.css` -> `base.css` -> `element-plus.css` -> `utilities.css`。
- `AppShell.vue` 根节点从 `shell legacy-console theme-anchor modao-exact app-shell` 收敛为 `shell app-shell`，删除 `onMounted/onBeforeUnmount` 中对 `theme-anchor`、`modao-exact` 的 body class 操作；`modal-active` 仅由 `LocalDeviceEditor` 管理弹层滚动锁定。
- `tokens.css` 中 `--console-*`、`--exact-*` compatibility aliases 改为挂在 `:root`，不再依赖 `body.modao-exact` 才存在；本阶段没有扩大到全量变量重命名，后续新代码优先使用 `--app-*`。
- `global.css` 只保留 `html/body/#app` 高度链路、box-sizing、body reset、字体继承和 `.dot` 状态点等真正全局 primitive。
- 新增 `base.css`，只放跨业务页面共享的 `exact-page`、`section-heading`、toolbar、surface/card、table、badge、json、empty-state、form-grid 等基础语义样式。
- 新增 `element-plus.css`，承接 Element Plus 变量、表单/表格/弹层/Dialog/MessageBox/Popper/DatePicker 等必要全局覆盖，Teleport 样式不放入普通 scoped 规则。
- 新增 `utilities.css`，只保留 `inline-actions`、`text-muted`、`hidden`、`sr-only` 等小型 utility，避免形成新的 `workbench.css`。
- `AppShell` 主布局样式迁入 `AppShell.vue` scoped style；`AppSidebar` 导航、品牌、分组、底部状态和令牌抽屉样式迁入 `AppSidebar.vue`；`AppTopbar` 仅保留自身状态条样式。
- `DeviceOperationShell.vue` 作为 Workbench / Control / Shadow 公共外壳，拥有 `device-operation-*`、外壳标题、三段 tab、左侧 rail、设备信息与外壳 body 样式。
- `DeviceWorkbenchView.vue` 改为复用 `DeviceOperationShell active-tab="config"`，不再重复旧 Device Operation Shell CSS。
- `DeviceConfigPanel.vue` 拥有运行控制、快捷导航、协议配置折叠区、运行数据区、点位详情、表格滚动和响应式布局样式。
- `LocalDeviceEditor.vue` 拥有 Teleport 编辑器 overlay/panel、四步 tab、左侧校验 rail、setup/point/cloud/json 区、footer、点位详情与相关响应式规则。
- `PointEditor.vue` 拥有 point toolbar、point workbench grid、table/detail/tabs/runtime/json/import preview 样式；`PointBatchEditDialog.vue` / `PointGenerateDialog.vue` 拥有各自小型弹窗表单样式。
- `ControlPanel.vue` 拥有 manual shadow/control 专属 pane、head card、surface grid 与 textarea 样式；`ShadowPanel.vue` 拥有 shadow summary、history、desired/delta 操作区样式。
- `AlarmTablePanel.vue`、`LogPanel.vue`、`ProtocolDynamicForm.vue` 分别迁入自身 filter/stat/form-grid scoped style，不再依赖旧 `workbench.css`。
- `RealtimeDataPanel.vue` 迁入自身表格与空状态小范围样式。
- 重构 `scripts/workbench-layout-contract.test.mjs`：测试不再读取 `workbench.css`，改为拼接真实样式所有者（`base.css`、`element-plus.css`、`DeviceOperationShell.vue`、`DeviceConfigPanel.vue`、`LocalDeviceEditor.vue`、`PointEditor.vue`、`AlarmTablePanel.vue`、`LogPanel.vue`、`ProtocolDynamicForm.vue`）验证布局契约。
- 新增 `scripts/css-architecture.test.mjs`，长期防止历史 CSS 文件、旧入口 import、AppShell legacy/modao anchor、旧高权重 selector 和 tokens `body.modao-exact` 绑定回归；同步更新根 `.gitignore` 只放行该新增长期测试脚本。

## Phase 16 Selector Inventory 与规模统计

| 文件 | 原始 bytes | 原始行数 | 规则数 | selector 数 | unique class/id | `!important` | A 死亡规则/selector | B App/Global 规则 | C 共享 primitive 规则 | D feature/component 规则 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `src/styles/legacy-console.css` | 24,622 | 1,047 | 126 | 187 | 70 | 3 | 30 / 41 | 26 | 70 | 0 |
| `src/styles/workbench.css` | 179,926 | 6,164 | 870 | 1,406 | 363 | 24 | 113 / 133 | 10 | 259 | 488 |
| 合计 | 204,548 | 7,211 | 996 | 1,593 | - | 27 | 143 / 174 | 36 | 329 | 488 |

- 第一轮直接删除的死亡样式为 A 类至少 143 条规则 / 174 个 selector；剩余仍有引用的规则没有整体搬家，而是按 AppShell、Sidebar、Topbar、DeviceOperationShell、DeviceConfigPanel、LocalDeviceEditor、PointEditor、Control、Shadow、Alarm、Log、ProtocolDynamicForm、RealtimeDataPanel 与共享 primitive 分发并压缩。
- 新共享项目 CSS 规模：`base.css` 7,767 bytes / 404 行，`element-plus.css` 6,917 bytes / 231 行，`utilities.css` 598 bytes / 44 行，合计 15,282 bytes。
- 入口 CSS 总规模（含 tokens/global）：`tokens.css` 2,917 bytes / 77 行，`global.css` 1,051 bytes / 61 行，加上 base/element/utilities 合计 19,250 bytes。
- `src/styles/` 下不存在超过 50 KB 的单一项目全局 CSS；未出现把 200 KB 历史 CSS 改名搬家的情况。
- 旧 CSS `!important` 合计 27 处；迁移后项目共享 CSS 中仅 `element-plus.css` 保留 8 处，均用于 Element Plus Teleport/组件库 DOM 覆盖。当前 `src` 全量 `!important` 为 10 处，其中 2 处为既有 `DashboardView.vue` scoped 样式。

## Phase 16 验证结果

执行时间：2026-09-01 15:11-15:15，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 38 个测试文件、211 个测试通过；包含重构后的 `workbench-layout-contract.test.mjs` 与新增 `css-architecture.test.mjs` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；baseline `@vueuse` PURE warning 与 Element Plus vendor chunk > 500 KB warning 仍存在，本阶段未处理 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 53 |
| `git diff --check` | 通过 | exit code 0；仅有 Git 对部分工作区文件 LF/CRLF 的提示 |

最终搜索：

| 搜索 | 结果 |
|---|---:|
| `rg 'legacy-console\.css' collector-desktop/src collector-desktop/scripts` | 0 命中 |
| `rg 'workbench\.css' collector-desktop/src collector-desktop/scripts` | 0 命中 |
| `rg 'legacy-console' collector-desktop/src` | 0 命中 |
| `rg 'modao-exact' collector-desktop/src` | 0 命中 |
| `rg 'body\.modao-exact' collector-desktop/src` | 0 命中 |
| `rg 'views/legacy' collector-desktop/src` | 0 命中 |
| `rg 'theme-anchor' collector-desktop/src` | 0 命中 |

本阶段 Headless Chrome/CDP smoke 使用本地 mock 采集服务验证以下内容：

- 业务 Route：`/dashboard`、`/realtime`、`/history`、`/alarm`、`/device`、`/device/workbench?deviceId=dev-1`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/control?deviceId=dev-1`、`/shadow?deviceId=dev-1`。
- 尺寸：`1920x1080`、`1440x900`、`1280x720`。
- 断言：页面可打开、`AppShell` 高度链路正常、无 `legacy-console` / `modao-exact` DOM/class、无页面级横向溢出、无严重 console error。
- LocalDeviceEditor 三入口：`/device -> 新增本地设备`、`/collect -> 配置设备`、`/dashboard -> 新增本地设备`；均验证 Teleport panel 可见、四步 tab 存在、基础输入存在、`modal-active` 正常、Escape 可关闭、弹层不超屏。
- Element Plus Popup：在设备工作台告警子页验证 `ElSelect` dropdown 与 `DatePicker`，在设备列表验证 `ElMessageBox` 与 `ElMessage`；弹层均为深色背景、文字可见、z-index 可用。

Smoke 验证后已停止临时 Headless Chrome、Vite dev server 和 mock backend 进程。

## Phase 16 文件变化

新增：

- `collector-desktop/src/styles/base.css`
- `collector-desktop/src/styles/element-plus.css`
- `collector-desktop/src/styles/utilities.css`
- `collector-desktop/scripts/css-architecture.test.mjs`

修改：

- `.gitignore`
- `collector-desktop/src/main.ts`
- `collector-desktop/src/styles/tokens.css`
- `collector-desktop/src/styles/global.css`
- `collector-desktop/src/app/AppShell.vue`
- `collector-desktop/src/app/AppSidebar.vue`
- `collector-desktop/src/app/AppTopbar.vue`
- `collector-desktop/src/views/device/DeviceWorkbenchView.vue`
- `collector-desktop/src/features/device/components/DeviceOperationShell.vue`
- `collector-desktop/src/components/device/DeviceConfigPanel.vue`
- `collector-desktop/src/features/device/components/LocalDeviceEditor.vue`
- `collector-desktop/src/features/point/components/PointEditor.vue`
- `collector-desktop/src/features/point/components/PointBatchEditDialog.vue`
- `collector-desktop/src/features/point/components/PointGenerateDialog.vue`
- `collector-desktop/src/features/control/components/ControlPanel.vue`
- `collector-desktop/src/features/shadow/components/ShadowPanel.vue`
- `collector-desktop/src/components/alarm/AlarmTablePanel.vue`
- `collector-desktop/src/components/log/LogPanel.vue`
- `collector-desktop/src/components/protocol/ProtocolDynamicForm.vue`
- `collector-desktop/src/components/realtime/RealtimeDataPanel.vue`
- `collector-desktop/scripts/workbench-layout-contract.test.mjs`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

删除：

- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/styles/workbench.css`

## Phase 16 已知问题与回归风险

- 本阶段 smoke 基于本地 mock 后端和 Headless Chrome/CDP，验证 CSS 迁移后的布局、弹层和基础页面打开；真实后端、真实设备、真实历史/告警/影子数据仍依赖后续联调环境。
- `--console-*`、`--exact-*` 作为 compatibility aliases 保留在 `:root`；本阶段未做全量 CSS variable rename，避免扩大低价值 diff。
- `exact-*`、`modao-property-*` 等已成为长期 UI primitive 的 class 名本阶段未强制重命名；已清除的是 Legacy Host anchor：`legacy-console`、`modao-exact` 与 `body.modao-exact .legacy-console`。
- 构建仍只有既有两类 baseline warning：`@vueuse/core` PURE annotation warning 与 Element Plus vendor chunk > 500 KB warning；Phase 16 未处理 vendor chunk / tree-shaking / manualChunks 优化。

## 下一步

等待确认后进入 Phase 17：最终代码质量门禁。Phase 16 完成后当前不自动执行 Phase 17。

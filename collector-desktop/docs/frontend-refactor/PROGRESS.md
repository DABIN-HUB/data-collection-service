# collector-desktop 前端架构重构进度

更新时间：2026-08-28 11:48:11 +0800

## 当前状态

- 当前目标分支：`feature_2.0`
- 最近提交：
  - `a16d805` 修改
  - `cd572d3` 前端修改
  - `c0b4cd2` 样式修改
  - `288c0d7` 样式修改
  - `ce90843` 修改
- Phase 1：已完成并通过验证。
- Phase 2：Dashboard 迁移已完成并通过验证。
- 下一阶段：Phase 3 迁移 Realtime。

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
- `/realtime`、`/history`、`/alarm`、`/device`、`/device/workbench`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/control`、`/shadow` 仍保持 `LegacyConsoleView.vue` 过渡状态。
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

## Phase 2 检查结果

- `/dashboard`：`src/router/route-definitions.ts` 直接指向 `DashboardView`。
- `/device`：仍指向 `LegacyConsoleView`。
- `/realtime`：仍指向 `LegacyConsoleView`。
- `/alarm`：仍指向 `LegacyConsoleView`。
- `/log`：仍指向 `LegacyConsoleView`。
- 搜索确认 `LegacyConsoleView.vue` 中已无 `activeModule === 'overview'`、`overviewCards`、`overview-section`、`overview-cards`、`home-dashboard`、`home-panel`、`topology-*`、`resource-gauge/resource-ring` 等 Dashboard 实现痕迹。
- 搜索确认 `legacy-console.css` / `workbench.css` 已无上述 Dashboard 专属选择器。

## Phase 2 新增文件

- `collector-desktop/src/views/dashboard/DashboardView.vue`
- `collector-boot/src/main/resources/static/desktop/assets/DashboardView-BoI9B3og.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/DashboardView-C8sHpWil.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/AppShell-eMyZRgr3.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/LegacyConsoleView-BfWQYs8U.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/LegacyConsoleView-DiMclAvv.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/LoginView-DPj_YMZ-.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/app.store-CF6Rnkgg.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/index-C62y0npa.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/index-CLDd3KQW.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/ops-utils-CJ_T2jgB.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/vendor-element-plus-HQR2MFFw.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/vendor-vue-BJCT_Pwb.js`（`build:web` 生成）

## Phase 2 修改文件

- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）

## Phase 2 删除文件

源码文件未删除。

`build:web` 同步时删除上一版 Web 静态构建 hash 文件：

- `collector-boot/src/main/resources/static/desktop/assets/AppShell-C5L_y7Y5.js`
- `collector-boot/src/main/resources/static/desktop/assets/LegacyConsoleView-BXp8pKgK.css`
- `collector-boot/src/main/resources/static/desktop/assets/LegacyConsoleView-BlnkvwUn.js`
- `collector-boot/src/main/resources/static/desktop/assets/LoginView-CHBBa-on.js`
- `collector-boot/src/main/resources/static/desktop/assets/app.store-BLooyUe_.js`
- `collector-boot/src/main/resources/static/desktop/assets/index-DLQReBaR.css`
- `collector-boot/src/main/resources/static/desktop/assets/index-SykID_IV.js`
- `collector-boot/src/main/resources/static/desktop/assets/vendor-element-plus-D0sFrN_d.js`
- `collector-boot/src/main/resources/static/desktop/assets/vendor-vue-Bj6K4sZk.js`

## 已知问题与回归风险

- `LegacyConsoleView.vue` 仍然承载 Realtime / History / Alarm / Device / Collection / Cloud / Diagnostic / Log / Network / Workbench 等旧业务页面，是后续逐页迁移的主要对象。
- 为避免破坏旧页面，Legacy 内部仍暂时保留 `devices/runtimeMap/selectedDeviceId`、告警列表、日志列表、监控指标、配置摘要、上报链路等共享状态；具体页面迁移时再收敛到 Pinia 或页面级 composable。
- `loadOverview()` 仍服务 Collection / Cloud / Diagnostic，因此本阶段没有把监控和上报数据全部从 Legacy 删除。
- `refreshAll()` 仍保留给配置导入/同步这类旧页面显式操作使用，但 Legacy 初次进入时已不再自动刷新整个控制台。
- Web 静态产物 hash 因 `build:web` 更新，属于验证命令产生的预期变更。

## 下一步

等待确认后进入 Phase 3：迁移 Realtime。

Phase 3 只迁移 Realtime，不自动进入 History、Alarm、Device 或其它业务页面。

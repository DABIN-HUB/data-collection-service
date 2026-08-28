# collector-desktop 前端架构重构计划

更新时间：2026-08-28 10:42:19 +0800

## 目标

对 `collector-desktop` Vue 3 + TypeScript + Pinia + Vue Router + Element Plus + Electron/Web 共用 renderer 前端进行长期、渐进式架构重构。重点收敛 Vue 前端架构、组件职责、Router、Pinia 使用方式、代码组织和 CSS 架构；不改变 Electron/Web 共用 renderer、后端 API/DTO 和现有业务行为。

## 不变约束

- 保留 Electron 与 Web 共用 Vue 源码。
- 保留 `createWebHashHistory()`。
- 保留 `build:renderer`、`build:web`、`sync:web`。
- 保留 Electron main / preload / IPC。
- 保留现有后端 API、DTO 和业务行为。
- 不引入新的全局 CSS 覆盖层。
- Vue Router 是当前页面的唯一状态源。
- 跨页面领域状态优先由 Pinia 统一管理，页面只保留本地 UI 状态。

## 目标目录方向

```text
src/
├─ app/
│  ├─ AppShell.vue
│  ├─ AppSidebar.vue
│  ├─ AppTopbar.vue
│  └─ navigation.ts
├─ router/
│  ├─ index.ts
│  ├─ routes.ts
│  └─ route-names.ts
├─ views/
│  ├─ dashboard/DashboardView.vue
│  ├─ realtime/RealtimeView.vue
│  ├─ history/HistoryView.vue
│  ├─ alarm/AlarmView.vue
│  ├─ device/DeviceListView.vue
│  ├─ device/DeviceWorkbenchView.vue
│  ├─ collection/CollectionView.vue
│  ├─ cloud/CloudView.vue
│  ├─ diagnostic/DiagnosticView.vue
│  ├─ log/LogView.vue
│  └─ network/NetworkView.vue
├─ features/
│  ├─ device/
│  ├─ point/
│  ├─ protocol/
│  ├─ realtime/
│  ├─ alarm/
│  ├─ log/
│  ├─ history/
│  ├─ diagnostic/
│  └─ network/
├─ components/ui/
├─ composables/
├─ stores/
├─ api/
├─ types/
└─ styles/
```

## Phase 列表

### Phase 0：建立基线

- 检查 git branch/status/最近提交。
- 阅读当前 Router、`LegacyConsoleView.vue`、Pinia stores、全局样式和构建脚本。
- 创建 `collector-desktop/AGENTS.md`。
- 创建 `docs/frontend-refactor/PLAN.md`、`PROGRESS.md`、`DECISIONS.md`。
- 执行 baseline：`npm test`、`npm run typecheck`、`npm run build`、`npm run build:web`。
- 将 baseline 结果写入 `PROGRESS.md`，如原本失败则标记为 baseline failure，不顺手修无关业务。

### Phase 1：AppShell / Sidebar / Topbar / Router 地基

- 新增 `src/router/route-names.ts`。
- 将 `LegacyConsoleView.vue` 中的 `navGroups` 提取为 `src/app/navigation.ts`。
- 新增 `src/app/AppSidebar.vue`，使用 RouterLink/router.push，禁止使用 `activeModule` 控制选中页面。
- 新增 `src/app/AppTopbar.vue`，只承载全局系统级顶部信息。
- 新增 `src/app/AppShell.vue`，结构为 Sidebar + main + Topbar + RouterView。
- 修改 Router，让根 Layout 使用 `AppShell`；业务页面本阶段仍可进入 `LegacyConsoleView.vue`。
- 从 `LegacyConsoleView.vue` 删除 Sidebar/App Shell/Topbar。
- 将临时 `activeModule` 改为 `computed(route.path -> module)` 单向推导。
- 新增 `src/styles/tokens.css`，先统一深色工业控制台 token，不大规模拆 CSS。
- 执行四项验证并更新进度。

### Phase 2：Dashboard

- 从 `LegacyConsoleView.vue` 迁移 Dashboard section 到 `views/dashboard/DashboardView.vue`。
- 梳理 Dashboard API、computed、状态和样式。
- Router `/dashboard` 指向新 View。
- 删除 Legacy 中 Dashboard 实现和已迁移 CSS。
- 验证并更新进度。

### Phase 3：Realtime

- 迁移实时数据页面到 `views/realtime/RealtimeView.vue`。
- 优先复用 `RealtimeDataPanel.vue` 和 `realtime-utils`。
- 明确设备状态来源，避免复制 Pinia 领域状态。
- 验证并更新进度。

### Phase 4：Log

- 迁移日志页面到 `views/log/LogView.vue`。
- 保留现有日志查询、筛选、导出和 `log-utils`。
- 验证并更新进度。

### Phase 5：Alarm

- 迁移告警历史与确认页面到 `views/alarm/AlarmView.vue`。
- 保留告警确认幂等语义和 `ops-utils`、`alarm-history-utils`。
- 验证并更新进度。

### Phase 6：Network

- 迁移网络检测页面到 `views/network/NetworkView.vue`。
- 保留白名单/设备 host 带入/报告导出语义。
- 验证并更新进度。

### Phase 7：Cloud

- 迁移云平台配置与上报链路页面到 `views/cloud/CloudView.vue`。
- 保留 Outbox/ACK/影子提交点展示语义。
- 验证并更新进度。

### Phase 8：Diagnostic

- 迁移系统诊断页面到 `views/diagnostic/DiagnosticView.vue`。
- 保留诊断包导出和运行状态明细组件。
- 验证并更新进度。

### Phase 9：History

- 迁移历史趋势页面到 `views/history/HistoryView.vue`。
- 保留 `LegacyHistoryPanel` 的真实查询能力，迁移后再视情况改名。
- 验证并更新进度。

### Phase 10：Collection

- 迁移采集配置页面到 `views/collection/CollectionView.vue`。
- 保留协议 Schema、配置导入导出、同步和缓存操作。
- 验证并更新进度。

### Phase 11：DeviceList

- 迁移设备列表页面到 `views/device/DeviceListView.vue`。
- 推进设备领域状态使用 `deviceStore`，页面只保留筛选、Dialog 等 UI 状态。
- 验证并更新进度。

### Phase 12：DeviceWorkbench

- 迁移设备工作台到 `views/device/DeviceWorkbenchView.vue`。
- 保留配置、控制、影子分区和设备上下文。
- 验证并更新进度。

### Phase 13：LocalDeviceEditor

- 梳理本地临时设备编辑器职责和目录位置。
- 保留动态协议 Schema 和已有本地设备创建/编辑行为。
- 验证并更新进度。

### Phase 14：PointEditor

- 梳理点位编辑器职责和目录位置。
- 保留动态点位字段、批量编辑、生成和导入导出能力。
- 验证并更新进度。

### Phase 15：彻底删除 LegacyConsoleView

- 确认所有业务页面已经由 Router 独立承载。
- 删除 `src/views/legacy/LegacyConsoleView.vue`。
- 删除旧宿主相关路由和过渡代码。
- 验证并更新进度。

### Phase 16：清理 legacy-console.css / workbench.css

- 将仍在使用的样式迁入 token/base/element-plus/utilities 或组件 scoped style。
- 删除 `legacy-console.css`。
- 删除或完全拆解后删除 `workbench.css`。
- 确认没有新全局 override 文件。
- 验证并更新进度。

### Phase 17：补齐最终质量门禁

- 评估并补充 ESLint / Prettier / Stylelint。
- 补齐最终检查脚本。
- 执行 test/typecheck/build/build:web/lint/stylelint。
- 更新最终进度和剩余风险。

## 每阶段完成标准

- 当前阶段需求全部完成。
- 旧实现中对应代码已删除，不保留第二份实现。
- Router、Pinia、CSS 决策符合 `AGENTS.md` 和 `DECISIONS.md`。
- 四项验证完成并记录；失败必须标明是否为 baseline failure 或本阶段新增失败。
- `PROGRESS.md` 已更新下一步。

# collector-desktop 前端重构长期规则

本文件是 `collector-desktop` Vue/Electron 前端长期架构重构的固定约束。后续继续本项目时，必须先读取本文件以及 `docs/frontend-refactor/PLAN.md`、`PROGRESS.md`、`DECISIONS.md`。

## 不变架构边界

- Electron 客户端与 Web 端继续共用同一套 Vue renderer 源码。
- 保留 `createWebHashHistory()`，不得切换为 history 模式。
- 保留 `build:renderer`、`build:web`、`sync:web` 的构建与同步链路。
- 保留 Electron main / preload / IPC，不因前端重构删除桌面能力边界。
- 保留现有后端 API、DTO、接口语义和业务行为。
- 不修改设备启动/停止、点位配置、实时数据、历史数据、告警确认、日志、网络诊断、云上报、设备影子等业务能力。

## 重构目标

- `LegacyConsoleView.vue` 当前承担菜单、布局、顶部栏、所有业务页面、API 调用、计时器和导航状态，最终必须删除。
- App 外壳目标结构：

```text
AppShell
├─ AppSidebar
├─ AppTopbar
└─ RouterView
   ├─ DashboardView
   ├─ RealtimeView
   ├─ HistoryView
   ├─ AlarmView
   ├─ DeviceListView
   ├─ DeviceWorkbenchView
   ├─ CollectionView
   ├─ CloudView
   ├─ DiagnosticView
   ├─ LogView
   └─ NetworkView
```

- Vue Router 是当前页面的唯一状态源。
- 禁止重新引入 `activeModule ref + router` 双向同步。
- 过渡期间如旧宿主仍需 `activeModule` 做 `v-show`，只能使用由 `route.path` 单向推导的 `computed`。

## Pinia 状态规则

- 跨页面领域状态只能有一个来源。
- 设备领域状态优先使用 `device.store.ts` 中的 `devices`、`runtimeMap`、`selectedDeviceId`。
- 页面本地状态保留在页面内，例如搜索关键字、分页、当前 Tab、Dialog 开关、临时表单、展开/折叠。
- 不把所有 UI 状态塞进 Pinia，也不在页面复制一份与 Pinia 等价的领域状态。

## CSS 规则

- 长期目标样式结构：

```text
src/styles/
├─ tokens.css
├─ base.css
├─ element-plus.css
└─ utilities.css
```

- 组件自身布局优先使用 Vue scoped style。
- `legacy-console.css` 必须最终删除。
- `workbench.css` 必须最终删除，或完全拆解后删除。
- 禁止新增 `new-console.css`、`refactor.css`、`override-v2.css` 等第三套全局覆盖文件。
- 不通过越来越高的 specificity 叠加覆盖，例如 `body.modao-exact .legacy-console #xxx ...`。

## 分阶段规则

- 每次只做当前 Phase；当前 Phase 未验证通过，不自动进入下一阶段。
- 每个 Phase 必须小范围、可回滚，不做全项目无意义格式化。
- 不覆盖未提交代码，不删除测试，不降低 TypeScript strict，不用大量 `any` 逃避类型错误。
- 修改前检查 `git branch`、`git status` 和最近提交。
- 修改后执行：

```bash
npm test
npm run typecheck
npm run build
npm run build:web
```

- 如果之后加入 lint/stylelint，还必须执行对应检查。
- 每阶段完成后更新 `docs/frontend-refactor/PROGRESS.md`，记录完成内容、文件变更、验证结果、已知问题和下一阶段。

## 页面迁移固定流程

每迁移一个页面必须按顺序处理：

1. 找到 `LegacyConsoleView.vue` 中对应 section。
2. 找到该页面用到的 ref/computed/function/API。
3. 判断哪些数据来自 Pinia。
4. 判断哪些属于 View 本地状态。
5. 判断是否有真正值得抽的 composable。
6. 创建新的 View。
7. 移动代码，不复制保留第二份实现。
8. Router 改到新 View。
9. 删除 `LegacyConsoleView.vue` 中原实现。
10. 移动对应 utils/test。
11. 移动对应 CSS。
12. 删除原 CSS。
13. 执行验证。
14. 更新 `PROGRESS.md`。

## 优先保留内容

以下现有代码若没有明确问题，优先保留并按阶段移动到合理位置：

- `src/api/` 现有拆分。
- `src/types/`。
- `app.store.ts`、`device.store.ts`、`point.store.ts`、`protocol.store.ts`、`runtime.store.ts`、`websocket.store.ts`。
- `ProtocolDynamicForm.vue` 及其动态 Schema 设计。
- 现有 utils 测试和 API 测试。
- Electron/Web 共用 renderer 的构建方式。

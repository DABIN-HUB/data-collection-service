# collector-desktop 前端重构长期规则

本文件是 `collector-desktop` Vue/Electron 前端长期架构重构的固定约束。后续继续本项目时，必须先读取本文件以及 `docs/frontend-refactor/PLAN.md`、`PROGRESS.md`、`DECISIONS.md`。

## 不变架构边界

- Electron 客户端与 Web 端继续共用同一套 Vue renderer 源码。
- 保留 `createWebHashHistory()`，不得切换为 history 模式。
- 保留 `build:renderer`、`build:web`、`sync:web` 的构建与同步链路。
- 保留 Electron main / preload / IPC，不因前端重构删除桌面能力边界。
- 保留现有后端 API、DTO、接口语义和业务行为。
- 不修改设备启动/停止、点位配置、实时数据、历史数据、告警确认、日志、网络诊断、云上报、设备影子等业务能力。

## 当前最终架构

- App 外壳最终结构：

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
   ├─ NetworkView
   ├─ ControlView
   └─ ShadowView
```

- Vue Router 是当前页面的唯一状态源。
- Legacy Host 不得重新引入，所有业务页面必须由独立 Route/View 承载。
- 禁止重新引入 `activeModule ref + router` 双向同步。
- 禁止重新引入 `switchModule` 或其它手动模块状态切换来代替 Router。

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
├─ global.css
├─ base.css
├─ element-plus.css
└─ utilities.css
```

- 组件自身布局优先使用 Vue scoped style。
- `legacy-console.css` 已删除，后续不得重新引入。
- `workbench.css` 已删除，后续不得重新引入。
- 禁止新增 `new-console.css`、`refactor.css`、`override-v2.css` 等第三套全局覆盖文件。
- 不通过越来越高的 specificity 叠加覆盖，例如 `body.modao-exact .legacy-console #xxx ...`。

## 质量门禁规则

- Phase 1 ~ Phase 17 已完成，后续不自动创建 Phase 18；新增优化必须作为独立任务重新确认范围。
- 每次修改必须小范围、可回滚，不做全项目无意义格式化。
- 不覆盖未提交代码，不删除测试，不降低 TypeScript strict，不用大量 `any` 逃避类型错误。
- 修改前检查 `git branch`、`git status` 和最近提交。
- 修改后至少执行：

```bash
npm run lint
npm run stylelint
npm test
npm run typecheck
npm run build
npm run build:web
```

- 推荐完整执行 `npm run verify`。
- `npm run lint`、`npm run stylelint`、`npm run quality`、`npm run verify` 必须保持 check-only；不得默认 `--fix` 或修改源码。
- lint/stylelint 禁止通过大量 disable 绕过，不得整文件关闭规则；确需局部例外时必须写明中文原因。
- 涉及重构文档时更新 `docs/frontend-refactor/PROGRESS.md`，记录完成内容、文件变更、验证结果和剩余风险，不写不存在的下一阶段。

## 页面与状态长期规则

- 新增业务入口必须使用独立 View 和明确 Route，不得恢复旧宿主 `v-show` 聚合页面模式。
- 页面只保留搜索、分页、Tab、Dialog、临时表单、展开/折叠等本地 UI 状态。
- 跨页面领域状态必须回到对应 Pinia 单一来源，禁止在多个页面复制一份等价领域缓存。
- 后端 API、DTO、接口语义和业务行为不随前端结构调整而改变。
- Electron main / preload / IPC 只承载桌面能力和白名单桥接，不吸收业务协议、采集、缓存或云上报逻辑。
- Electron/Web 继续共用 renderer，Web 交付继续通过 `build:web` 和 `sync:web` 同步到后端静态目录。

## 优先保留内容

以下现有代码若没有明确问题，优先保留并按阶段移动到合理位置：

- `src/api/` 现有拆分。
- `src/types/`。
- `app.store.ts`、`device.store.ts`、`point.store.ts`、`protocol.store.ts`、`runtime.store.ts`、`websocket.store.ts`。
- `ProtocolDynamicForm.vue` 及其动态 Schema 设计。
- 现有 utils 测试和 API 测试。
- Electron/Web 共用 renderer 的构建方式。

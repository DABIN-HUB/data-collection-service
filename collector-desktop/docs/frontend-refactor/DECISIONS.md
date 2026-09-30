# collector-desktop 前端架构重构决策记录

更新时间：2026-08-28 10:56:51 +0800

## ADR-001：Electron/Web 共用 renderer 保持不变

- 状态：已接受
- 决策：Electron 客户端和 Web 端继续共用同一套 Vue renderer 源码。
- 理由：现有 `build:renderer`、`build:web`、`sync:web` 方案是正确方向，可以降低功能分叉和发布风险。
- 影响：重构只调整 Vue 前端架构和代码组织，不拆分两套 renderer。

## ADR-002：Router 是当前页面唯一状态源

- 状态：已接受
- 决策：Vue Router 负责表达当前页面。禁止继续使用 `activeModule ref + router` 双向同步。
- 过渡方案：在 `LegacyConsoleView.vue` 尚未删除前，如果旧 `v-show` 仍需模块判断，只允许使用 `computed(() => route.path -> module)` 单向推导。
- 影响：新 Sidebar 必须使用 RouterLink/router.push；业务内部跳转也只能更新路由，不能手动改 `activeModule`。

## ADR-003：deviceStore 是设备领域状态长期唯一来源

- 状态：已接受
- 决策：`devices`、`runtimeMap`、`selectedDeviceId` 等跨页面设备领域状态长期收敛到 `device.store.ts`。
- 过渡方案：Phase 1 不拆业务页面时可保留 Legacy 内部状态；迁移具体页面时必须判断并消除与 Pinia 重复的领域状态。
- 影响：页面只保留搜索、分页、Tab、Dialog、临时表单、展开折叠等本地 UI 状态。

## ADR-004：不新增全局 CSS override 层

- 状态：已接受
- 决策：不新增 `new-console.css`、`refactor.css`、`override-v2.css` 等全局覆盖文件。
- 过渡方案：Phase 1 只新增 `tokens.css` 定义统一 token；后续逐步将样式迁入 `base.css`、`element-plus.css`、`utilities.css` 或组件 scoped style。
- 影响：`legacy-console.css` 和 `workbench.css` 最终必须删除，但不在 Phase 1 大规模改动。

## ADR-005：Phase 1 不拆业务页面

- 状态：已接受
- 决策：Phase 1 只建立 AppShell、Sidebar、Topbar、Router 和 token 地基，业务页面暂时仍可进入 `LegacyConsoleView.vue`。
- 理由：降低一次性改动风险，避免复制业务实现。
- 影响：Dashboard/Realtime/History/Alarm/Device/Collection/Cloud/Diagnostic/Log/Network 等页面从 Phase 2 开始逐页迁移。

## ADR-006：设备工作台使用独立过渡路由

- 状态：已接受
- 决策：过渡期为旧设备工作台新增 `/device/workbench` 路由，仍渲染 `LegacyConsoleView.vue` 中的工作台 section。
- 理由：旧实现通过 `activeModule.value = "workbench"` 但 URL 仍停留在 `/device`，导致 Router 不能唯一表达当前页面；独立路由可以先修正导航状态源，再分阶段迁移真正的 `DeviceWorkbenchView`。
- 影响：`/device` 只表达设备列表；`/device/workbench` 表达设备配置工作台；`/control` 和 `/shadow` 继续作为控制/影子分区入口，并由 route 单向同步本地 `workbenchTab`。

## ADR-007：所有业务页面由 Router 独立承载

- 状态：已接受
- 决策：Dashboard、Realtime、History、Alarm、DeviceList、DeviceWorkbench、Collection、Cloud、Diagnostic、Log、Network、Control、Shadow 全部由独立 Route/View 承载。
- 理由：Router 必须唯一表达当前页面，避免旧宿主聚合页面继续扩大。
- 影响：新增业务入口必须新增明确路由和 View，不得恢复多页面 `v-show` 宿主。

## ADR-008：Legacy Host 永久退出

- 状态：已接受
- 决策：Legacy Host 已退出，后续不得重新引入 `activeModule` / `switchModule` 与 Router 双状态。
- 理由：双状态会导致 URL、菜单选中态、页面生命周期和数据刷新语义不一致。
- 影响：历史过渡代码只保留在重构记录中，生产源码必须保持 Legacy 引用为 0。

## ADR-009：持久化 Point 与本地编辑草稿分离

- 状态：已接受
- 决策：持久化点位领域状态使用 `pointStore`；`LocalDeviceEditor` 的 draft points 只作为编辑器本地草稿，不写入 `pointStore`。
- 理由：本地临时设备编辑过程需要未保存草稿，不能污染跨页面点位领域状态。
- 影响：点位保存后再通过正式 API / store 刷新进入持久化视图。

## ADR-010：CSS ownership 使用 scoped + 小型 shared CSS

- 状态：已接受
- 决策：组件和页面布局优先放在 Vue scoped style；跨页面基础能力仅保留 `tokens.css`、`global.css`、`base.css`、`element-plus.css`、`utilities.css`。
- 理由：避免旧全局 CSS 通过高 specificity 覆盖形成不可维护级联。
- 影响：不得新增第三套全局 override 文件，也不得恢复 `legacy-console.css` / `workbench.css`。

## ADR-011：Element Plus Teleport override 统一放 element-plus.css

- 状态：已接受
- 决策：Element Plus 变量、弹层、下拉、Dialog 等跨页面覆盖统一放入 `src/styles/element-plus.css`。
- 理由：Teleport 内容脱离组件 DOM，使用 scoped style 容易失效或造成重复覆盖。
- 影响：页面组件只保留自身结构样式，不在各组件中复制全局 Element Plus 覆盖。

## ADR-012：ESLint / Stylelint 是长期硬 Gate

- 状态：已接受
- 决策：`npm run lint` 与 `npm run stylelint` 纳入长期质量门禁，并由 `npm run quality` / `npm run verify` 串联执行。
- 理由：Phase 17 后需要用工具防止未使用代码、无效 Vue 模板、renderer 误用 Node/Electron API、CSS 重复选择器等问题回归。
- 影响：禁止通过大量 disable 或整文件关闭规则绕过门禁。

## ADR-013：Prettier 本轮不作为硬 Gate

- 状态：已接受
- 决策：Prettier 已评估，但本轮不接入硬门禁，也不执行全项目 `prettier --write`。
- 理由：当前检查会触发大量既有文件纯格式改动，超出 Phase 17 最终质量收尾范围。
- 影响：如未来采用 Prettier，应单独安排格式化窗口并隔离非语义 diff。

## ADR-014：frontend architecture contract 防止架构回归

- 状态：已接受
- 决策：新增 `scripts/frontend-architecture.test.mjs`，长期检查 Legacy 引用、反向依赖、Router 页面独立性和 renderer Node/Electron 边界。
- 理由：这些约束仅靠文档容易回归，需要随 `npm test` 自动执行。
- 影响：新增页面、store、api 或 Electron 边界变更时必须先满足 architecture contract。

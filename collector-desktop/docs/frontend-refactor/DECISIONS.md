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

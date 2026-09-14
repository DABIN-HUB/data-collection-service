# Task UI-01 — Frontend UI Visual & Layout Audit

## 1. Audit Scope

本轮属于 `Frontend UI Cleanup / Task UI-01`，目标是完整审计，不执行 UI-02/UI-03 的实际整改。

审计范围：

- 前端工程：`collector-desktop/`
- 真实运行方式：`npm run dev -- --host 127.0.0.1` + packaged Electron/CDP 访问 `http://127.0.0.1:5173`
- Router 来源：`src/router/route-definitions.ts`
- 审计 route：14 个生产 route
- 审计 viewport：4 个
  - `1280x720`
  - `1366x768`
  - `1440x900`
  - `1920x1080`
- 审计内容：全局 overflow、局部 overflow、控制项主题、Element Plus 基础控件、table/toolbar/card/panel/dialog 静态结构、backend offline 降级、console error。

本轮未做：

- 未大规模修改 CSS。
- 未统一 Element Plus Theme。
- 未修改任何生产 `.vue` 页面或业务 API。
- 未引入 Playwright/Cypress/Tailwind 等新框架。
- 未修改 Task 07 dependency/security 内容。

## 2. Frontend Architecture

### 2.1 应用入口

- `src/main.ts`
  - 引入顺序：`element-plus/dist/index.css` → `tokens.css` → `global.css` → `base.css` → `element-plus.css` → `utilities.css`。
  - 注册 Element Plus、Element Plus icons、Pinia、Vue Router。
- `src/App.vue`
  - 仅挂载 `<router-view />`。
  - Electron menu navigation 通过 `window.collectorDesktop?.onNavigate()` 推进 router。

### 2.2 Router 与页面结构

- Router 使用 `createWebHashHistory()`，符合 `collector-desktop/AGENTS.md` 长期约束。
- `/login` 独立渲染 `LoginView`，不进入 `AppShell`。
- 业务页面统一位于 `/` 父 route 下，由 `AppShell` 承载。
- `AppShell` 结构：

```text
AppShell
├─ AppSidebar
└─ main.app-shell__main
   ├─ AppTopbar
   └─ RouterView
```

### 2.3 Viewport / height / overflow 责任层

- `src/styles/global.css`
  - `html, body, #app`：`width: 100%; height: 100%; margin: 0;`
  - `body`：`overflow: hidden`，因此 body 不应出现浏览器级滚动条。
- `src/app/AppShell.vue`
  - `.app-shell`：`height: 100%; overflow: hidden; display: flex;`
  - `.app-shell__main`：`flex: 1; min-width: 0; min-height: 0; overflow: hidden; flex-direction: column;`
- `src/styles/base.css`
  - `.exact-page`：`height: 100%; overflow: hidden; padding: 16px;`
  - `.exact-page-body`：`flex: 1; min-width: 0; min-height: 0; overflow: auto;`

结论：当前设计意图是 **body 不滚动，页面内容区或业务局部容器内部滚动**。这对 Electron 桌面端是正确方向，但需要避免 toolbar/table/card 再额外产生不必要横向/双重 scrollbar。

### 2.4 Theme / tokens

全局主题变量位于 `src/styles/tokens.css`：

- 背景：`--app-color-bg: #0d1b2a`、`--app-color-panel: #1a2332`
- 边框：`--app-color-border: #2d4a7a`
- 主色：`--app-color-primary: #2563eb`、`--app-color-primary-hover: #3b82f6`
- 文本：`--app-color-text-primary: #ffffff`、`--app-color-text-secondary: #e2e8f0`
- 控件：`--console-input-bg: var(--app-color-bg)`、`--console-input-border: var(--app-color-border)`

Element Plus 覆盖位于 `src/styles/element-plus.css`，已覆盖：

- `--el-bg-color`
- `--el-bg-color-overlay`
- `--el-fill-color-blank`
- `--el-text-color-*`
- `.el-input__wrapper`
- `.el-select__wrapper`
- `.el-textarea__inner`
- `.el-date-editor`
- `.el-table`
- `.el-pagination`
- Teleport popper/dialog/message-box 基础背景。

### 2.5 独立 CSS / legacy 标识

存在页面级 scoped style：

- `src/views/dashboard/DashboardView.vue`
- `src/views/device/DeviceListView.vue`
- `src/views/history/HistoryView.vue`
- `src/views/alarm/AlarmView.vue`
- `src/views/collection/CollectionView.vue`
- `src/views/cloud/CloudView.vue`
- `src/views/diagnostic/DiagnosticView.vue`
- `src/views/log/LogView.vue`
- `src/views/network/NetworkView.vue`

存在组件级 scoped style：

- `src/components/realtime/RealtimeDataPanel.vue`
- `src/components/protocol/ProtocolDynamicForm.vue`
- `src/components/log/LogPanel.vue`
- `src/components/device/DeviceConfigPanel.vue`
- `src/components/alarm/AlarmTablePanel.vue`
- `src/features/**/components/*.vue`

未发现仍可访问的 legacy route；`legacy-icons/` 仅是资产目录，不代表 legacy 页面仍在生产路由中。

## 3. Route Inventory

Router 事实来源：`src/router/route-definitions.ts`。

| Route | Name | Component/View | Shell | 参数 | 登录要求 | Device selection | Backend 数据依赖 | 二级 Tab / 子区域 | 备注 |
|---|---|---|---|---|---|---|---|---|---|
| `/login` | `LOGIN` | `views/auth/LoginView.vue` | 否 | 无 | 否，保存服务地址/令牌 | 否 | 可调用桌面配置/服务检测 | 无 | 独立登录/连接设置页 |
| `/dashboard` | `DASHBOARD` | `views/dashboard/DashboardView.vue` | 是 | 无 | Router 无 guard | 否 | 设备、告警、云上报、运行状态、资源、缓存、历史、性能 | 多个 dashboard panel | backend offline 时展示失败/不可用状态 |
| `/realtime` | `REALTIME` | `views/realtime/RealtimeView.vue` | 是 | 无 | Router 无 guard | 是 | 实时点位、设备列表、运行状态 | 单点读取/实时表格区域 | backend offline 有 console error |
| `/history` | `HISTORY` | `views/history/HistoryView.vue` | 是 | 无 | Router 无 guard | 是 | 历史查询、趋势数据、设备/点位 | 查询面板、趋势/结果区域 | 当前空状态可渲染 |
| `/alarm` | `ALARM` | `views/alarm/AlarmView.vue` | 是 | 无 | Router 无 guard | 否 | 告警列表、确认操作 | 告警表格/确认 dialog | 当前空状态可渲染 |
| `/device` | `DEVICE` | `views/device/DeviceListView.vue` | 是 | 无 | Router 无 guard | 否 | 设备列表、协议列表、导入导出 | 设备卡片/过滤 toolbar | 当前空状态可渲染 |
| `/device/workbench` | `DEVICE_WORKBENCH` | `views/device/DeviceWorkbenchView.vue` | 是 | 无 | Router 无 guard | 是 | 设备详情、点位、协议 schema、运行状态 | 设备配置/点位/协议/运行区域 | 当前无选中设备时降级显示 |
| `/collect` | `COLLECTION` | `views/collection/CollectionView.vue` | 是 | 无 | Router 无 guard | 是 | 采集配置、协议、同步、导入导出 | 配置卡片、协议能力、同步区 | 当前页面内容较高，有内部滚动 |
| `/cloud` | `CLOUD` | `views/cloud/CloudView.vue` | 是 | 无 | Router 无 guard | 否 | 云上报配置/状态 | 云端配置/状态区 | 当前空状态可渲染 |
| `/diagnostic` | `DIAGNOSTIC` | `views/diagnostic/DiagnosticView.vue` | 是 | 无 | Router 无 guard | 否 | 运行诊断、设备运行详情 | 诊断卡片/详情 panel | 当前空状态可渲染 |
| `/log` | `LOG` | `views/log/LogView.vue` | 是 | query 用于过滤 | Router 无 guard | 可选 | 日志列表、异常查询 | 过滤 toolbar/日志面板 | 1280 宽度 toolbar 局部滚动 |
| `/network` | `NETWORK` | `views/network/NetworkView.vue` | 是 | 无 | Router 无 guard | 否 | 网络检测、边缘接入 | 网络探测/边缘遥测区域 | 当前空状态可渲染 |
| `/control` | `CONTROL` | `views/control/ControlView.vue` | 是 | 无 | Router 无 guard | 是 | 控制命令、设备点位 | 控制 panel | 当前空状态可渲染 |
| `/shadow` | `SHADOW` | `views/shadow/ShadowView.vue` | 是 | 无 | Router 无 guard | 是 | 设备影子状态 | Shadow panel | 当前空状态可渲染 |

Redirect / hidden route：

| Route | 类型 | 目标 |
|---|---|---|
| `/` child `""` | redirect | `/dashboard` |
| `/:pathMatch(.*)*` | catch-all redirect | `/` → `/dashboard` |

## 4. Page / Component Audit Inventory

| 区域 | 实际源码 | Route / 使用位置 | 本轮覆盖方式 |
|---|---|---|---|
| Sidebar / navigation | `src/app/AppSidebar.vue` | 全 shell route | 真实渲染 + DOM metrics |
| Topbar | `src/app/AppTopbar.vue` | 全 shell route | 真实渲染 + DOM metrics |
| Dashboard cards/topology/resources | `src/views/dashboard/DashboardView.vue` | `/dashboard` | 真实渲染 + overflow metrics |
| Realtime table/query | `src/views/realtime/RealtimeView.vue`, `components/realtime/RealtimeDataPanel.vue` | `/realtime` | 空状态真实渲染 + table static inventory |
| History query/trend | `src/views/history/HistoryView.vue` | `/history` | 空状态真实渲染 + form/static inventory |
| Alarm table/ack dialog | `src/views/alarm/AlarmView.vue`, `components/alarm/AlarmTablePanel.vue` | `/alarm` | 空状态真实渲染 + dialog/table static inventory |
| Device list/filter/import/export | `src/views/device/DeviceListView.vue` | `/device` | 空状态真实渲染 + toolbar inventory |
| Device workbench/config panel | `src/views/device/DeviceWorkbenchView.vue`, `components/device/DeviceConfigPanel.vue` | `/device/workbench` | 无选中设备状态真实渲染 + table/dialog static inventory |
| Local device editor | `features/device/components/LocalDeviceEditor.vue` | Device flows | 静态 inventory，需 UI-03 有数据/交互复核 |
| Device operation shell | `features/device/components/DeviceOperationShell.vue` | Device flows | 静态 inventory，需 UI-03 有数据/交互复核 |
| Point editor/config | `features/point/components/PointEditor.vue` | Device workbench | 静态 inventory，需 UI-03 有数据/交互复核 |
| Point batch/generate dialogs | `features/point/components/PointBatchEditDialog.vue`, `PointGenerateDialog.vue` | Point editor | 静态 inventory |
| Protocol dynamic form | `components/protocol/ProtocolDynamicForm.vue` | Device/config flows | 静态 inventory |
| Collection config/sync | `views/collection/CollectionView.vue`, `features/collection/components/ConfigOpsPanel.vue` | `/collect` | 真实渲染 + overflow metrics |
| Cloud panel | `src/views/cloud/CloudView.vue` | `/cloud` | 真实渲染 |
| Diagnostic panels | `views/diagnostic/DiagnosticView.vue`, `features/diagnostic/*` | `/diagnostic` | 真实渲染 + static inventory |
| Log toolbar/list | `src/views/log/LogView.vue`, `components/log/LogPanel.vue` | `/log` | 真实渲染 + overflow metrics |
| Network/edge telemetry | `src/views/network/NetworkView.vue`, `features/network/components/EdgeTelemetryPanel.vue` | `/network` | 真实渲染 + controls inventory |
| Control panel | `features/control/components/ControlPanel.vue` | `/control` | 真实渲染 + controls inventory |
| Shadow panel | `features/shadow/components/ShadowPanel.vue` | `/shadow` | 真实渲染 + controls inventory |

## 5. Viewport Matrix

真实执行结果来自 `collector-desktop/.ui-audit/report.json`。

| Route | 1280×720 | 1366×768 | 1440×900 | 1920×1080 | Theme | Overflow | Layout | Notes |
|---|---|---|---|---|---|---|---|---|
| `/login` | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 无 AppShell，输入为深色 |
| `/dashboard` | WARN | WARN | WARN | PASS | PASS | WARN | WARN | 资源面板/拓扑局部 overflow，低高度需要页面内部滚动 |
| `/realtime` | WARN | WARN | WARN | WARN | PASS | PASS | PASS | backend offline 时每 viewport 产生 console.error |
| `/history` | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 空状态可渲染，无 body overflow |
| `/alarm` | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 空状态可渲染，确认 dialog 需数据触发 |
| `/device` | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 空状态可渲染 |
| `/device/workbench` | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 无选中设备状态可渲染 |
| `/collect` | WARN | WARN | WARN | WARN | PASS | WARN | WARN | 页面高内容使用内部滚动；Element alert 存在局部内容高度 overflow |
| `/cloud` | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 空状态可渲染 |
| `/diagnostic` | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 空状态可渲染 |
| `/log` | WARN | PASS | PASS | PASS | PASS | WARN | WARN | 1280×720 toolbar filters 横向内部滚动 |
| `/network` | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 17 个可见 controls 均为深色 |
| `/control` | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 空状态可渲染 |
| `/shadow` | PASS | PASS | PASS | PASS | PASS | PASS | PASS | 空状态可渲染 |

## 6. Actual Render Coverage

真实运行覆盖：

- 实际运行 route：14 / 14
- 实际检查 viewport：4 / 4
- 实际 route/viewport 检查：56 / 56
- 完整渲染 route：14 / 14
- 未访问 route：0
- document/body 横向 overflow：0 / 56
- document/body 纵向 overflow：0 / 56
- 存在局部 layout/overflow finding 的 route：3 / 14
- 存在 theme mismatch 的 route：0 / 14
- 存在 console error 的 route：1 / 14
- 可见 form control 样本：280 个 control observation
- 可见 control 白底样本：0
- runtime Teleport popup observation：0（backend offline + 当前可见控件未触发 Element popup）
- 截图：56 张，保存于 `collector-desktop/.ui-audit/screenshots/`，该目录已忽略，不纳入 Git。

Backend 当前状态：

- `http://127.0.0.1:9090/collector/...` 连接超时。
- 页面均在 backend offline 状态下真实渲染，未出现 white screen 或 crash。
- 需要真实设备/点位/告警/日志数据才能完全验证的 table row、fixed column、dialog 内容，本轮只完成空状态真实渲染 + 源码结构 inventory，并列入 UI-03 复核范围。

## 7. Global Theme Findings

结论：可见 input/select/textarea/Element Plus wrapper 在本轮 56 次渲染中未发现纯白背景。

证据：

- `report.json` 记录 280 个 control observation。
- `whiteBackgroundCount = 0`。
- 常见背景为：
  - `rgb(13, 27, 42)`
  - `rgba(0, 0, 0, 0)` 内层 input 配合深色 wrapper。
- `src/styles/element-plus.css` 已覆盖 `.el-input__wrapper`、`.el-select__wrapper`、`.el-textarea__inner`、`.el-date-editor`。

风险：Teleport popup 本轮未实际观察到展开态；虽然 `element-plus.css` 已覆盖 `.el-popper`、`.el-select-dropdown`、`.el-picker__popper`、`.el-popover`、`.el-message-box`，但 UI-02 仍应专项复核 select/date-picker/dropdown/dialog 的 hover/selected/disabled 对比度。

## 8. Form Control Findings

| 控件类型 | 本轮观察 | 结论 |
|---|---|---|
| 原生 `input/select/textarea` | 多 route 可见，包括 sidebar token、realtime/history/log/network/control/collection | 背景深色，未发现白底 |
| `el-input` | `/login`、`/history`、`/alarm`、`/log` 等 | wrapper 深色，内层透明，未发现白底 |
| `el-select` / native select | 页面空状态下部分为 native select；源码中存在大量 `el-select` | 可见样本未白底；Teleport dropdown 未实际展开 |
| `el-textarea` | `/collect` 与 dialog/source inventory | 可见样本深色 |
| `el-date-picker` | `AlarmTablePanel`、`LogPanel` 源码存在 | 需要 UI-02 打开 popper 复核 |
| `el-input-number` | `LogPanel`、Point dialogs/source inventory | 需要有数据/打开 dialog 后复核 |
| checkbox/radio/switch | `/login` checkbox 可见；其他多为 source inventory | 未发现 P1/P2 |

## 9. Overflow / Scrollbar Findings

全局 document/body overflow：

- `documentElement.scrollWidth > clientWidth`：0 / 56
- `body.scrollWidth > clientWidth`：0 / 56
- `documentElement.scrollHeight > clientHeight`：0 / 56
- 结论：当前外壳层没有浏览器级横向/纵向滚动条，也未发现 body + content 双重 scrollbar。

局部 overflow：

- 合理滚动：`.exact-page-body`、dashboard 主 section、日志/实时页面局部内容区承载内容滚动，属于当前架构预期。
- 异常/需优化：dashboard 内部资源/拓扑固定尺寸、log toolbar filters 在 1280 宽度横向内部滚动、collection/log 的 `el-alert` 行高/内容高度被隐藏。

## 10. Table Findings

源码中主要 table：

- `components/realtime/RealtimeDataPanel.vue`
  - `el-table height="360"`
  - 多列 `min-width` + 固定列宽。
- `components/alarm/AlarmTablePanel.vue`
  - `el-table height="420" border`
  - fixed right 操作列。
- `components/log/LogPanel.vue`
  - `el-table height="420" border`
  - 日志内容列 `min-width="320"`。
- `components/device/DeviceConfigPanel.vue`
  - workbench 点位表，多个 `min-width`，fixed right 操作列，pagination。
- `features/point/components/PointEditor.vue`
  - 可编辑表格，动态协议字段列 `min-width="130"`，fixed/selection/index 等列。

本轮真实运行时 backend offline，table 均未获得真实业务 rows，因此：

- 空状态没有撑破 body。
- 真实长文本、fixed column header 对齐、pagination 换行、动态字段多列撑破等必须放入 UI-03 的有数据复核。
- 源码上存在多个 `min-width` 和 fixed height，属于 UI-03 重点对象。

## 11. Dialog / Drawer Findings

源码中主要 dialog：

| Dialog | 源码 | 宽度 | 风险 |
|---|---|---:|---|
| 告警确认 | `components/alarm/AlarmTablePanel.vue` | `520px` | 1366×768 可接受；需有告警数据触发 footer/textarea 复核 |
| 配置差异 | `components/device/DeviceConfigPanel.vue` | `720px` | 需有设备/workbench 数据触发；长 diff 需 max-height/内部滚动复核 |
| 点位导入预览 | `features/point/components/PointEditor.vue` | `920px` | 1280×720 下宽度占比较高，需复核 footer 是否可见、表格是否内部滚动 |
| 批量编辑点位 | `features/point/components/PointBatchEditDialog.vue` | `520px` | 需复核 input-number/select 主题和弹层 |
| 批量生成点位 | `features/point/components/PointGenerateDialog.vue` | `520px` | 需复核 select dropdown 主题 |

未发现 `el-drawer` 生产用法。

本轮限制：backend offline + 无设备/点位/告警 seed data，主要 dialog 未能通过真实业务点击全部打开；本轮完成了源码 inventory 与空状态路由渲染，UI-03 必须用最小真实/烟雾数据复核 dialog 实际尺寸、footer 可见性和内部滚动。

## 12. Responsive Findings

- `1280×720`：最容易暴露 toolbar/低高度密度问题。
  - `/dashboard`：资源面板与拓扑局部 overflow。
  - `/log`：filter toolbar 横向内部滚动。
  - `/collect`：页面内容高，需要内部纵向滚动；`el-alert` 内容高度存在 overflow。
- `1366×768`：整体比 1280 稳定，但 `/dashboard` 资源面板仍 overflow，`/collect` alert 仍有局部 overflow。
- `1440×900`：`/dashboard` 资源面板仍轻微 overflow，`/collect` alert 仍存在。
- `1920×1080`：未发现过度留白导致的 P1/P2；`/collect` alert 仍存在局部高度 overflow。

注意：Electron main 设置 `MIN_WINDOW_HEIGHT = 760`，所以严格物理窗口 `1280×720` 可能低于 Electron 最小窗口高度；本轮通过 CDP viewport 模拟覆盖了该分辨率，用于发现 web renderer 的布局风险。

## 13. Legacy Page Findings

- Router 中没有 `/legacy` 或旧 host route。
- `collector-desktop/AGENTS.md` 明确：Legacy Host 不得重新引入，所有业务页面由独立 Route/View 承载。
- 当前仅发现 `src/assets/legacy-icons/`，属于图标资产，不是可访问 legacy 页面。
- 结论：本轮无 active legacy production page 需要纳入逐页整改；无需删除任何 legacy 资产。

## 14. Console Findings

| Finding | Route | Viewport | Console | Severity |
|---|---|---|---|---|
| `UI-CONSOLE-01` | `/realtime` | 4/4 | `ApiRequestError: Error invoking remote method 'collector:http-request': TypeError: fetch failed`，来源 `src/api/http.ts:130`、`RealtimeView.vue:94/280` | P2 |

解释：backend offline 时页面没有白屏，也能显示降级内容；但 `/realtime` 初始化每个 viewport 都输出 `console.error`。UI audit 视为可观测性/降级噪声问题，应在后续任务中改为页面状态承载或 debug/warn 策略，避免正常 offline 场景污染 console error。

## 15. Page-by-Page Matrix

| Route | Render | Global overflow | Local overflow | Form theme | Table | Dialog/Popup | Console | 结论 |
|---|---|---|---|---|---|---|---|---|
| `/login` | 4/4 | PASS | PASS | PASS | N/A | N/A | PASS | PASS |
| `/dashboard` | 4/4 | PASS | WARN | PASS | N/A | N/A | PASS | WARN |
| `/realtime` | 4/4 | PASS | PASS | PASS | 空状态 PASS / rows 未覆盖 | N/A | WARN | WARN |
| `/history` | 4/4 | PASS | PASS | PASS | 空状态 PASS | N/A | PASS | PASS |
| `/alarm` | 4/4 | PASS | PASS | PASS | 空状态 PASS | 源码 inventory / 未触发 | PASS | PASS + UI-03 复核 |
| `/device` | 4/4 | PASS | PASS | PASS | N/A | 导入/新增流程未全触发 | PASS | PASS + UI-03 复核 |
| `/device/workbench` | 4/4 | PASS | PASS | PASS | 无选中设备 PASS / rows 未覆盖 | 源码 inventory / 未触发 | PASS | PASS + UI-03 复核 |
| `/collect` | 4/4 | PASS | WARN | PASS | 协议能力空状态 PASS | N/A | PASS | WARN |
| `/cloud` | 4/4 | PASS | PASS | PASS | N/A | N/A | PASS | PASS |
| `/diagnostic` | 4/4 | PASS | PASS | PASS | N/A | N/A | PASS | PASS |
| `/log` | 4/4 | PASS | WARN at 1280 | PASS | 空状态 PASS / rows 未覆盖 | N/A | PASS | WARN |
| `/network` | 4/4 | PASS | PASS | PASS | N/A | N/A | PASS | PASS |
| `/control` | 4/4 | PASS | PASS | PASS | N/A | N/A | PASS | PASS |
| `/shadow` | 4/4 | PASS | PASS | PASS | N/A | N/A | PASS | PASS |

## 16. Finding List

### UI-LAYOUT-01 — Dashboard 资源面板在中小 viewport 内部横向 overflow

- Severity：P2
- Route：`/dashboard`
- Viewport：`1280x720`、`1366x768`、`1440x900`
- Evidence：
  - `1280x720`: `.resource-dashboard` `scrollWidth=436` / `clientWidth=354`
  - `1366x768`: `.resource-dashboard` `scrollWidth=436` / `clientWidth=389`
  - `1440x900`: `.resource-dashboard` `scrollWidth=436` / `clientWidth=418`
- 源码线索：`DashboardView.vue:1067-1078`
  - `.resource-dashboard` 固定 `height: 134px`
  - `grid-template-columns: minmax(270px, 1fr) minmax(150px, 0.55fr)`
  - 内部 `.resource-gauges` 为三列 `minmax(74px, 1fr)`
- 初步根因：资源面板 grid 最小宽度与父 panel 实际可用宽度冲突，父层 `overflow: hidden` 掩盖内容。
- 推荐归属：UI-03。

### UI-LAYOUT-02 — Dashboard 拓扑链路在 1280×720 局部内容被隐藏

- Severity：P2
- Route：`/dashboard`
- Viewport：`1280x720`
- Evidence：`.pipeline-steps` `scrollWidth=608` / `clientWidth=581`，`overflowX=hidden`
- 源码线索：`DashboardView.vue:938-948`
  - `.pipeline-steps` `overflow: hidden`
  - `.topology-flow` `min-width: 560px`
  - 多个 topology node/storage/connector 固定最小宽度叠加。
- 初步根因：拓扑图使用固定最小宽度，父容器隐藏 overflow，没有降级为可滚动、缩放或 wrap。
- 推荐归属：UI-03。

### UI-RESPONSIVE-01 — Log toolbar 在 1280×720 需要横向内部滚动

- Severity：P2
- Route：`/log`
- Viewport：`1280x720`
- Evidence：
  - `.exact-toolbar-group.exact-toolbar-filters` `scrollWidth=806` / `clientWidth=594`
  - `.exact-toolbar.log-toolbar` `scrollHeight=55` / `clientHeight=43`
- 源码线索：`LogView.vue:419-449`
  - `.log-toolbar` `flex-wrap: nowrap`
  - filters 使用 7 列 grid：`minmax(96px...) ... auto`
- 初步根因：过滤项数量超过 1280 内容宽度，当前通过内部横向滚动兜底；对桌面用户可操作性不够直观。
- 推荐归属：UI-03。

### UI-OVERFLOW-01 — Collection warning alert 存在内容高度 overflow

- Severity：P3
- Route：`/collect`
- Viewport：4/4
- Evidence：`.el-alert.el-alert--warning` `scrollHeight=28` / `clientHeight=16`，`overflowY=hidden`
- 初步根因：Element Plus alert 的默认 line-height/padding 与当前页面密度覆盖存在轻微不一致。
- 影响：未观察到 P1，但存在文字竖向裁切风险。
- 推荐归属：UI-02 或 UI-03。

### UI-OVERFLOW-02 — Log error alert 在 1280×720 存在内容高度 overflow

- Severity：P3
- Route：`/log`
- Viewport：`1280x720`
- Evidence：`.el-alert.el-alert--error` `scrollHeight=34` / `clientHeight=28`，`overflowY=hidden`
- 初步根因：同 `UI-OVERFLOW-01`，Element Plus alert 密度/line-height 需要统一。
- 推荐归属：UI-02。

### UI-CONSOLE-01 — Realtime backend offline 降级输出 console.error

- Severity：P2
- Route：`/realtime`
- Viewport：4/4
- Evidence：每个 viewport 均捕获 `ApiRequestError ... fetch failed`。
- 影响：页面未崩溃，但离线是可预期状态时不应作为未捕获/错误级别污染 console。
- 推荐归属：UI-03 或后续 page-state failure UX cleanup。

### UI-CONSISTENCY-01 — 页面级视觉 token 存在 drift

- Severity：P3
- Route：多页面
- Evidence：
  - `DashboardView.vue` 使用大量 `--exact-*` 与硬编码 `#fff`、固定 px 布局。
  - `LogView.vue:384-390` 自定义日志 panel 背景 `#08131f`、字体和密度。
  - `base.css` 使用 `.exact-*`，页面内还存在 `.modao-*`、`.home-*`、`.surface-*` 混合命名。
- 影响：当前视觉整体可用，但 UI-02 做全局一致性时需要收敛 token 与命名边界。
- 推荐归属：UI-02。

### UI-DIALOG-01 — 部分 Dialog 固定宽度缺少统一 max-width/max-height 策略

- Severity：P3
- Route：`/alarm`、`/device/workbench`、Point flows
- Evidence：
  - `AlarmTablePanel.vue` `width="520px"`
  - `DeviceConfigPanel.vue` `width="720px"`
  - `PointEditor.vue` `width="920px"`
  - `PointBatchEditDialog.vue` / `PointGenerateDialog.vue` `width="520px"`
- 影响：本轮 backend offline 未触发全部 dialog；源码显示宽度固定，UI-03 应复核 1366×768 footer 可见、内部滚动和长内容策略。
- 推荐归属：UI-02 先统一 dialog skin/max-height，UI-03 再按页面复核内容布局。

### UI-TABLE-01 — 有数据表格的长文本/fixed column/分页仍需专项复核

- Severity：P3
- Route：`/realtime`、`/history`、`/alarm`、`/device/workbench`、`/log`
- Evidence：backend offline 下只覆盖空状态；源码存在多处 fixed height、`min-width`、fixed right 操作列和动态字段列。
- 影响：无数据状态 PASS，但不能据此证明有数据表格在 1366×768 下不会撑破或 header 错位。
- 推荐归属：UI-03。

## 17. Severity

| Severity | Count | Findings |
|---|---:|---|
| P1 | 0 | 无功能不可操作、按钮不可见、dialog 无法关闭、body 横向滚动导致关键控件不可达 |
| P2 | 4 | `UI-LAYOUT-01`、`UI-LAYOUT-02`、`UI-RESPONSIVE-01`、`UI-CONSOLE-01` |
| P3 | 5 | `UI-OVERFLOW-01`、`UI-OVERFLOW-02`、`UI-CONSISTENCY-01`、`UI-DIALOG-01`、`UI-TABLE-01` |

## 18. Recommended UI-02 Scope

`Task UI-02 — Global Theme & Component Consistency` 建议只处理全局视觉和组件一致性，不做逐页面复杂布局：

1. Element Plus 全局 skin 复核
   - input/select/textarea/input-number/date-picker。
   - dropdown/date-picker/popper/dialog/message-box Teleport 背景、边框、hover、selected、disabled、placeholder。
2. Alert / Message / Notification 密度统一
   - 处理 `UI-OVERFLOW-01`、`UI-OVERFLOW-02` 的 line-height/padding 裁切风险。
3. Dialog / message-box 基础策略
   - `max-width: calc(100vw - safe margin)`。
   - `max-height: calc(100vh - safe margin)`。
   - header/body/footer 分区滚动策略。
4. Table global skin
   - header/body 背景、边框、hover、empty state、scrollbar。
5. Pagination/control height
   - 确认 button/input/select/pagination 高度一致。
6. Panel/card tokens
   - 收敛 padding、gap、radius、border、background、shadow。
7. Scrollbar skin
   - 只统一视觉，不隐藏必要业务滚动。

## 19. Recommended UI-03 Scope

`Task UI-03 — Page-by-Page Layout & Overflow Cleanup` 建议逐页整改：

1. Dashboard
   - 修复 `resource-dashboard` grid 最小宽度问题。
   - 修复 topology/pipeline 1280 下隐藏 overflow。
   - 重新评估 dashboard 低高度内容密度，避免重要 panel 被过深滚动隐藏。
2. Log
   - 1280 下 toolbar 由横向内部滚动改为可控换行、分组或压缩策略。
   - 有日志 rows 时复核长 logger/thread/message 的 ellipsis 和 tooltip。
3. Collection
   - 修复 warning alert 高度裁切。
   - 评估页面长内容的 section 分组、折叠或 sticky toolbar。
4. Device / Device Workbench / Point Editor
   - 用最小真实设备/点位数据复核 point table、dynamic protocol fields、fixed right column、pagination。
   - 打开点位导入预览、批量编辑、批量生成等 dialog。
5. Realtime / History / Alarm
   - 用真实/烟雾数据复核 table rows、long text、date range 控件、确认 dialog。
6. Network / Control / Shadow
   - 复核较长设备名、URL、错误信息、状态文本的 ellipsis/wrap。

## 20. Deferred / Non-Issue Items

- `.exact-page-body` 的纵向滚动是当前架构预期，不应简单隐藏。
- dashboard 主 section 在低高度下内部滚动是合理机制，但内部固定尺寸 panel 的横向/隐藏 overflow 需要修复。
- backend offline 下页面显示“不可用/失败”属于合理降级；只有 `/realtime` 的 console.error 需要治理。
- 无 active legacy route，不在本轮删除任何 `legacy-icons` 资产。
- 本轮未对后端、realtime protocol、dependency security 做任何变更。

## 21. Audit Script

新增审计脚本：

```text
collector-desktop/scripts/ui-layout-audit.mjs
```

输出：

```text
collector-desktop/.ui-audit/report.json
collector-desktop/.ui-audit/screenshots/<viewport>/<route>.png
```

脚本行为：

- 启动 packaged Electron exe，并通过 `VITE_DEV_SERVER_URL=http://127.0.0.1:5173` 访问 dev server。
- 开启 `--remote-debugging-port`，使用 CDP 遍历 route。
- 对每个 route/viewport 采集：
  - document/body scroll metrics。
  - 可见 DOM 局部 overflow metrics。
  - controls 背景/颜色/高度/白底判断。
  - popper/dialog/message-box 可见对象。
  - console error / exception。
  - screenshot。

`.ui-audit/` 已加入 `collector-desktop/.gitignore`，生成物不提交。

## 22. Verification

已执行：

```bash
npm run dev -- --host 127.0.0.1
node --check scripts/ui-layout-audit.mjs
node scripts/ui-layout-audit.mjs
```

审计脚本输出摘要：

```json
{
  "ok": true,
  "summary": {
    "routeCount": 14,
    "viewportCount": 4,
    "executedChecks": 56,
    "renderedRouteCount": 14,
    "routesWithOverflow": 3,
    "routesWithThemeMismatch": 0,
    "routesWithLayoutIssue": 3,
    "routesWithConsoleErrors": 1,
    "notVisited": []
  }
}
```

后端在线性检查：

```text
http://127.0.0.1:9090/collector/... -> timeout / 000
```

因此有数据 table/dialog 的视觉结论不伪造，已转入 UI-03 明确复核范围。

## 23. Task Status

`Task UI-01` 状态：`PASS / COMPLETE with backend-offline limitations documented`

PASS 依据：

- Route inventory COMPLETE：14/14。
- 主要业务页面实际渲染 COMPLETE：14/14。
- 4 个 viewport 完成检查：56/56。
- 全局 overflow audit COMPLETE：document/body 横向/纵向 overflow 为 0/56。
- 局部 overflow audit COMPLETE：已记录 3 个 route 的局部问题。
- form/theme audit COMPLETE：280 个可见 control observation，白底 0。
- console audit COMPLETE：记录 `/realtime` backend offline console.error。
- 问题已分类并标记 P1/P2/P3。
- UI-02 / UI-03 范围已拆分。

限制：

- backend offline，无法证明真实数据 rows、全部 Element Plus Teleport popup、全部业务 dialog 在有数据场景下完全无问题；这些不作为本轮伪造 PASS，而作为 UI-03 的明确输入。

## 24. Next

下一步只建议进入：

```text
Task UI-02 — Global Theme & Component Consistency
```

不要在 UI-01 后直接开始逐页重排；UI-02 应先统一全局主题、控件、Teleport popup、Dialog/Table/Pagination/Scrollbar/Card tokens，再进入 UI-03 的逐页 overflow 和响应式清理。

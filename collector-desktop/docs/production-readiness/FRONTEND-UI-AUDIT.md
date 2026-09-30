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

---

## 25. Task UI-02 — Global Theme & Component Consistency

### 25.1 Scope

本节记录 `Task UI-02` 的生产代码变更。本轮开始真正修改全局视觉样式，但仍不处理 UI-03 的页面级结构重排。

本轮处理：

- canonical theme token。
- native text-like `input` / `select` / `textarea`。
- Element Plus `Input` / `Select` / `Textarea` / `InputNumber` / `DatePicker` / `Cascader` / `TreeSelect` 基础状态。
- Teleport popup：select dropdown、date/time picker、dropdown、popover、tooltip。
- `Dialog` / `MessageBox` 全局 dark baseline 和 viewport safety。
- `Alert` 内容裁切与 success/warning/error/info 语义暗色主题。
- `Table` / `Pagination` / fixed column / empty / loading mask 基础 skin。
- 全局与 Element Plus scrollbar 视觉。
- Login 与 AppShell 范围外页面的基础控件一致性。

本轮明确不处理：

- `Dashboard resource-dashboard` 固定布局 overflow。
- `Dashboard topology-flow` 固定布局 overflow。
- `/log` toolbar 响应式重排。
- 真实有数据 table 的列宽、长文本和分页挤压。
- `/realtime` backend offline 的 console error 模型。

### 25.2 Theme Architecture

继续采用现有架构：

```text
collector-desktop/src/styles/tokens.css       canonical --app-* tokens
collector-desktop/src/styles/global.css       browser/native baseline
collector-desktop/src/styles/element-plus.css Element Plus baseline + Teleport overlay
collector-desktop/src/styles/base.css         页面/壳层基础布局与既有 alias 使用
```

策略保持不变：

- `--app-*` 是 canonical design token。
- `--exact-*` / `--console-*` 保留为兼容 alias。
- 不进行全仓 class rename。
- 不创建第二套 theme 文件。

### 25.3 Canonical Token Changes

新增或补齐的 token 类别：

```text
--app-control-bg
--app-control-bg-hover
--app-control-bg-focus
--app-control-bg-disabled
--app-control-bg-readonly
--app-control-bg-error

--app-control-border
--app-control-border-hover
--app-control-border-focus
--app-control-border-disabled
--app-control-border-readonly
--app-control-border-error

--app-control-text
--app-control-placeholder
--app-control-text-disabled

--app-overlay-bg
--app-overlay-border
--app-overlay-shadow
--app-focus-ring

--app-scrollbar-track
--app-scrollbar-thumb
--app-scrollbar-thumb-hover

--app-color-success-soft
--app-color-warning-soft
--app-color-danger-soft
--app-color-info-soft
--app-color-info
--app-color-error
--app-color-success-text
--app-color-warning-text
--app-color-danger-text
--app-color-info-text
--app-color-error-text
--app-code-bg
--app-code-border
--app-code-text
```

同时将 `--console-input-*`、`--console-overlay-*`、`--console-scrollbar-*` 指向新的 `--app-*` token，避免旧页面继续使用偏白/偏浅的输入背景。

### 25.4 Native Form Control Theme

`global.css` 已建立 native dark baseline：

- 仅覆盖 text-like `input`、`select`、`textarea`。
- 排除 `checkbox`、`radio`、`range`、`color`、`file`、`hidden`、button 类 input 以及 Element Plus 内部 input。
- 覆盖 normal、hover、focus、disabled、readonly、placeholder。
- 增加 `color-scheme: dark` 与 `:-webkit-autofill` 深色覆盖，降低 Electron/Chromium autofill 白底或黄底风险。

### 25.5 Element Plus Input Theme

`element-plus.css` 已将 Element Plus 基础控件从 `.app-shell` 局部 scope 改为全局 dark baseline，覆盖 Login 和 Teleport 场景：

```text
.el-input__wrapper
.el-select__wrapper
.el-textarea__inner
.el-input-number
.el-date-editor
.el-range-editor
.el-cascader
.el-tree-select
```

状态覆盖：

- normal：深蓝 control 背景、统一 border、文字清晰。
- hover：border 与背景轻微增强，不跳白。
- focus：蓝色 border + `--app-focus-ring`。
- disabled：低对比但可读，不使用 `opacity: 0.3`。
- readonly：与 disabled 区分，保持内容清晰。
- error：红色 border + 暗红背景和错误 focus ring。

### 25.6 Select / Dropdown Theme

Teleport 层已覆盖：

```text
.el-popper
.el-select__popper
.el-select-dropdown
.el-select-dropdown__item
.el-dropdown-menu
.el-dropdown-menu__item
.el-popper__arrow
```

状态覆盖：

- normal 深蓝 overlay 背景。
- hover 蓝色弱高亮。
- selected 明确蓝色选中态。
- disabled 使用 disabled token。
- empty 文本使用 muted text。

### 25.7 Date / Time Picker Theme

已建立 Element Plus picker baseline：

```text
.el-picker__popper
.el-picker-panel
.el-date-picker__header
.el-date-table
.el-time-panel
.el-time-spinner__item
.el-picker-panel__footer
```

覆盖 header、weekday、date cell、today、selected、range、disabled、footer 和 time panel 的深色背景/文字/选中态。

### 25.8 Checkbox / Radio / Switch

已统一：

- label text 使用 dark theme text。
- checked 状态使用 `--app-color-primary`。
- disabled 状态使用 control disabled token。
- 不破坏 Element Plus 原有可访问性 class 结构。

### 25.9 Dialog / MessageBox

已建立所有 `.el-dialog` 的 global dark baseline：

- panel 背景、border、shadow。
- header/title/close。
- body text。
- footer border 和 spacing。
- `max-width: calc(100vw - 32px)`。
- `max-height: calc(100vh - 32px)`。
- body 使用内部滚动，避免直接裁切内容。

`MessageBox` 已同步同一 overlay 体系，覆盖 title、content、buttons、close、最大宽高和内部滚动。

UI audit fixture 对 520px、720px、920px 三类 dialog 宽度策略做了 4 个 viewport 验证：

```text
themeFixtureUnsafeDialogs = 0
```

### 25.10 Alert 修复

UI-01 原始 finding：

```text
UI-OVERFLOW-01 /collect warning alert scrollHeight > clientHeight
UI-OVERFLOW-02 /log error alert scrollHeight > clientHeight
```

本轮修复：

- 移除 Alert 内容被固定高度/line-height 裁切的风险。
- `.el-alert` 使用自然高度和 `overflow: visible`。
- `.el-alert__content` / title / description 允许正常换行。
- success/warning/error/info 使用深色语义背景，而不是 Element Plus 默认浅色块。

回归结果：

```text
themeFixtureClippedAlerts = 0
/collect route layoutIssue = false
/log route layoutIssue = false
```

### 25.11 Table Theme

已统一 Element Plus table 基础 skin：

- header/background/text/border。
- row hover/current row。
- empty block/text。
- loading mask。
- fixed / fixed-right 背景与边界。
- sort/filter popper 基础深色背景。

不处理真实数据列宽、长文本、固定列错位和分页挤压，这些继续留给 UI-03 带数据复核。

### 25.12 Pagination

已统一：

- prev / next。
- pager normal / hover / active / disabled。
- page size select / jumper input 通过全局 Element Plus input/select baseline 避免白底。
- pagination 内部 control 使用 30px 紧凑高度。

### 25.13 Scrollbar

已统一：

- 全局 WebKit scrollbar。
- Firefox `scrollbar-color` / `scrollbar-width`。
- Element Plus scrollbar thumb。
- popper/dialog/message-box 内部 scrollbar。

本轮只改视觉，不改变现有滚动责任和布局结构。

### 25.14 Login Theme

因为 `/login` 不在 `.app-shell` 内，本轮将 Element Plus 和 native controls 的深色 baseline 提升为全局 selector。

回归结果：

```text
/login rendered = 4/4
/login themeMismatch = false
/login white control count = 0
```

### 25.15 Dynamic Form Theme

已重点检查：

```text
DeviceConfigPanel
ProtocolDynamicForm
PointEditor
PointBatchEditDialog
PointGenerateDialog
LocalDeviceEditor
```

未发现这些 dynamic form 组件通过 scoped CSS 重新设置纯白 input/select/textarea 背景；动态生成的 Element Plus 控件现在会命中全局 dark baseline。

另外修复了少量页面级浅色 token drift：

- `DashboardView.vue`：overview card 指示条不再使用 `#f8fafc`。
- `HistoryView.vue`：统计 pill 不再使用浅色背景和深色文字。

异步只读主题审查返回后，本轮继续关闭以下共享层风险：

- 补齐 Element Plus `primary/success/warning/danger/error/info` 的 dark light 色阶，避免 `el-tag effect="light"`、plain/disabled 按钮回落到默认浅色变量。
- 增加 `el-tag.is-light/is-plain` 语义色覆盖。
- 增加 `.el-empty` 插图填充变量，避免 empty 插图继续使用 Element Plus 默认白/浅灰填充。
- 修复 `DiagnosticDetailPanel.vue` 中未定义的 `--exact-accent` 链接色，并补充 focus-visible。
- 修复 AppShell 原生 button disabled 状态，避免 disabled 继续触发 hover 或保持 primary/danger 高亮。
- 为原生 checkbox/radio 增加统一 `accent-color`。
- 为 `/login` 自定义 surface 增加 dark panel / border / illustration 基础样式。

### 25.16 UI-01 Finding Closure

| Finding | UI-02 状态 | 说明 |
|---|---|---|
| `UI-OVERFLOW-01` | `CLOSED` | `/collect` Alert 裁切已关闭，route audit 不再报告该问题。 |
| `UI-OVERFLOW-02` | `CLOSED` | `/log` Alert 裁切已关闭，route audit 不再报告该问题。 |
| `UI-CONSISTENCY-01` | `CLOSED at shared theme layer` | canonical control/overlay/scrollbar token 已统一；页面级布局和图表色继续留 UI-03。 |
| `UI-DIALOG-01` | `GLOBAL BASELINE CLOSED` | 全局 dialog/message-box dark baseline 与 viewport safety 已建立；业务内容仍需 UI-03 带数据复核。 |
| Global white form control risk | `CLOSED at shared theme layer` | native + Element Plus + Login + dynamic form 共用 dark baseline。 |
| Teleport popup theme risk | `CLOSED at shared theme layer` | select/date/dropdown/popper fixture 检查无白底。 |
| `UI-LAYOUT-01` | `OPEN -> UI-03` | Dashboard resource-dashboard overflow 不在 UI-02 范围。 |
| `UI-LAYOUT-02` | `OPEN -> UI-03` | Dashboard topology-flow overflow 不在 UI-02 范围。 |
| `UI-RESPONSIVE-01` | `OPEN -> UI-03` | Log toolbar 响应式重排不在 UI-02 范围。 |
| `UI-CONSOLE-01` | `OPEN -> UI-03 / failure UX repair` | `/realtime` backend offline console.error 暂不在本轮处理。 |
| `UI-TABLE-01` | `OPEN -> UI-03` | 有数据 table 布局需带数据复核。 |

### 25.17 56-check Audit Result

最终 UI audit 摘要：

```json
{
  "routeCount": 14,
  "viewportCount": 4,
  "executedChecks": 56,
  "renderedRouteCount": 14,
  "routesWithOverflow": 1,
  "routesWithThemeMismatch": 0,
  "routesWithLayoutIssue": 1,
  "routesWithConsoleErrors": 1,
  "notVisited": [],
  "themeFixtureChecks": 4,
  "themeFixtureWhiteBackgrounds": 0,
  "themeFixtureClippedAlerts": 0,
  "themeFixtureUnsafeDialogs": 0,
  "themeFixtureLightEmptyFills": 0
}
```

解释：

- `routesWithOverflow = 1` 仅剩 `/dashboard` 的 `resource-dashboard` / `topology-flow`，属于 UI-03。
- `routesWithLayoutIssue = 1` 同上，仅剩 `/dashboard`。
- `routesWithConsoleErrors = 1` 为 `/realtime` backend offline console error，属于 UI-03 或单独 failure UX repair。
- `routesWithThemeMismatch = 0`。
- `themeFixtureWhiteBackgrounds = 0`。
- `themeFixtureClippedAlerts = 0`。
- `themeFixtureUnsafeDialogs = 0`。
- `themeFixtureLightEmptyFills = 0`。

### 25.18 Viewport Result

| Viewport | Route checks | Theme fixture samples | White backgrounds | Clipped alerts | Unsafe dialogs | Light empty fills |
|---|---:|---:|---:|---:|---:|---:|
| `1280×720` | 14 | 41 | 0 | 0 | 0 | 0 |
| `1366×768` | 14 | 41 | 0 | 0 | 0 | 0 |
| `1440×900` | 14 | 41 | 0 | 0 | 0 | 0 |
| `1920×1080` | 14 | 41 | 0 | 0 | 0 | 0 |

### 25.19 Verification

已执行并通过：

```bash
npm run typecheck
npm test
npm run build
npm run build:web
npm run verify
npm run pack
node scripts/ui-layout-audit.mjs
git diff --check
```

测试结果：

```text
Test Files  76 passed (76)
Tests       561 passed (561)
```

构建提示仍只有既有 vendor chunk / `@vueuse/core` PURE annotation warning，未导致失败。

### 25.20 build:web Generated Assets

`npm run build:web` 已同步新版 Web 控制台到：

```text
collector-boot/src/main/resources/static/desktop
```

同步输出：

```text
同步文件数：57
```

CSS/JS hash 变化属于本轮 theme CSS 修改后的真实 `build:web` 产物，不手工恢复。

### 25.21 Changed Files

生产代码与审计脚本变更：

```text
collector-desktop/src/styles/tokens.css
collector-desktop/src/styles/global.css
collector-desktop/src/styles/element-plus.css
collector-desktop/src/views/dashboard/DashboardView.vue
collector-desktop/src/views/auth/LoginView.vue
collector-desktop/src/app/AppShell.vue
collector-desktop/src/features/diagnostic/components/DiagnosticDetailPanel.vue
collector-desktop/src/views/history/HistoryView.vue
collector-desktop/scripts/ui-layout-audit.mjs
collector-desktop/docs/production-readiness/FRONTEND-UI-AUDIT.md
collector-boot/src/main/resources/static/desktop/**
```

未修改：

```text
backend API
Electron Main/Preload security
realtime protocol
dependency 配置
Dashboard grid / topology layout
Log toolbar responsive layout
```

### 25.22 Remaining UI-03 Findings

UI-03 继续处理：

- `Dashboard resource-dashboard` 在 `1280×720`、`1366×768`、`1440×900` 的固定宽度 overflow。
- `Dashboard topology-flow` 在 `1280×720` 的局部隐藏 overflow。
- `/log` toolbar 在 `1280×720` 的响应式布局。
- 有数据场景 table：列宽、fixed column、长文本、pagination 挤压。
- 业务 dialog 内容区：长表单、真实数据、footer 可见性。
- `/realtime` backend offline console error 降级模型。

### 25.23 Task Status

`Task UI-02` 状态：`PASS / COMPLETE`

PASS 依据：

- 全局 dark control baseline 完整。
- Input / Select / Textarea 无明显白底 fallback。
- dynamic form 命中统一 Element Plus baseline。
- Select popup / DatePicker popup / MessageBox / Dialog fixture 无白底。
- Dialog 520px / 720px / 920px viewport safety fixture 通过。
- Alert 内容裁切已关闭。
- Alert semantic dark theme 已统一。
- Table / Pagination / Scrollbar global skin 不回归。
- Login 与 AppShell 页面主题一致。
- Element Plus light tag / primary plain button / disabled button / el-empty 共享层风险已关闭。
- 56-check audit 无新增 document/body overflow。
- theme fixture 4 个 viewport 全部通过。
- typecheck/test/build/build:web/verify/pack/ui-layout-audit 通过。

### 25.24 Next

下一步：

```text
Task UI-03 — Page-by-Page Layout & Overflow Cleanup
```

UI-03 重点处理真实页面布局和有数据场景，不应再回头重新开一套全局 theme。

## 26. Task UI-03 — Page-by-Page Layout & Overflow Cleanup

### 26.1 完成情况

本轮在不改变设备、点位、采集、告警、控制、历史查询、权限、Token、Electron IPC 和实时数据协议的前提下，完成页面级布局与滚动责任整改。修改范围限定在页面/业务组件布局、长文本呈现、审计脚本和预期离线日志处理；没有重新设计 UI-02 的全局主题。

### 26.2 Dashboard Resource Dashboard

- `.resource-dashboard` 改为 `min-width: 0` 与流式 `grid`，移除 `minmax(270px, ...)` 的硬性第一列约束。
- gauge 列使用 `repeat(3, minmax(0, 1fr))`，圆环尺寸使用 `clamp()`，在窄面板中保持可读性而不是依赖父层裁切。
- 运行摘要、间距、进度条宽度使用可收缩规则。
- `.home-resource-list` 不再用 `overflow: hidden` 掩盖内容。

结果：`1280×720`、`1366×768`、`1440×900`、`1920×1080` 均未发现 resource dashboard 横向溢出。

### 26.3 Dashboard Topology

- `.pipeline-steps` 和 `.topology-flow` 增加 `min-width: 0`，拓扑节点、存储块、连接线允许在可用宽度内收缩。
- 节点、连接线、存储块使用 `clamp()` 与 flex 收缩，保留 source/device、gateway、pipeline、storage、cloud 的语义可见性。
- 移除 `.pipeline-steps` 的 `overflow: hidden`，不再静默裁切最右侧节点。
- 网关/云平台状态文本保留 `title`，节点说明使用 ellipsis 时仍可查看完整内容。

结果：全部四个 viewport 的 Dashboard topology 未再出现局部 hidden clip；1920 宽度下也未出现无限拉伸。

### 26.4 Log Toolbar

- `/log` 工具栏改为可换行布局，`1280×720` 和 `1366×768` 下过滤区使用四列网格，操作区独立换行。
- 更窄宽度继续降为三列，控件使用 `min-width: 0`，不依赖横向滚动。
- 移除页面级 `overflow-x: hidden` 掩盖方式，改为 `overflow: visible`，让布局问题真实暴露。
- 日志消息保留单行 ellipsis，并增加 `title` 完整文本提示。

结果：四个 viewport 的 `toolbarHorizontalOverflowCount = 0`。

### 26.5 Alarm Layout

- `AlarmTablePanel` 过滤区允许换行，日期范围控件由固定 `360px` 改为 `clamp(280px, 34vw, 360px)`。
- 告警设备、点位、告警内容使用带 `title` 的 cell ellipsis，长文本不会撑破表格。
- Element Plus 表格继续由自身内部滚动容器负责多列数据，fixed-right 操作列保持原有结构。

### 26.6 Realtime Layout

- `RealtimeDataPanel` 的 panel toolbar/table actions 移除内部横向滚动，改为可换行布局，避免设备工作台嵌入时形成双层横向滚动。
- 点位名称、编码、地址、当前值增加 cell ellipsis 与 title。
- 未改变实时表格分页、刷新周期、HTTP fallback、WebSocket 状态和 latest-response-wins 逻辑。

### 26.7 History Layout

- 历史数据表增加 `.table-wrap`，表格采用有限的最小宽度和 `table-layout: fixed`；大量列或长原始记录只在表格容器内部处理。
- `JSON.stringify(row)` 使用 `white-space: pre-wrap` 与 `overflow-wrap: anywhere`，长 JSON 不再撑破页面。
- 相关告警表使用固定布局；设备/点位列 ellipsis，告警内容列保留换行。
- 查询条件在当前 1280+ 桌面矩阵中未产生页面级横向溢出。

### 26.8 Device Workbench

- `DeviceConfigPanel` 在窄宽断点去除运行控制/快捷导航卡片的固定 `max-height: 105px`，运行状态与操作按钮允许占用多行。
- `DeviceOperationShell` 标题内容增加 `min-width: 0` 和 ellipsis；窄宽下标题操作区允许换行，并避免固定 viewport 高度把标题内容裁掉。
- 点位编辑工具栏左右分组允许换行，搜索框由固定宽度改为 `clamp()`，移除嵌套横向滚动。
- 点位导入预览已有独立的表格内部 `overflow: auto`；本轮保留该合理滚动责任，未改变导入业务逻辑。

### 26.9 Table Scroll Ownership

当前滚动责任明确分层：

- `body` / `documentElement`：无横向滚动。
- `exact-page-body`：页面内容需要时承担纵向滚动。
- Element Plus `el-table`：多列真实数据由表格自身内部滚动处理。
- 历史原始数据、点位导入预览等原生表格：由 `.table-wrap` / `.point-import-preview-table` 承担内部滚动。
- 设备树、JSON、日志列表等长内容区域：保留已有合理内部滚动。
- 常规 toolbar：不再使用横向滚动作为 1280+ 的布局方案。

离线后端下未能填充真实设备/告警/实时/历史行，因此 fixed column 与真实字段宽度属于 `CODE/CONTRACT CLOSED`；真实数据字段密度仍需后端在线环境做现场复核，不将空数据渲染误报为真实数据验证。

### 26.10 Long Text Handling

- 日志 message、告警设备/点位/内容、实时点位字段使用 ellipsis + `title`。
- Dashboard 告警标题、设备名称使用 `title`；异常描述和告警标题允许安全换行。
- 历史原始 JSON 使用换行与 `overflow-wrap:anywhere`。
- URL、JSON、错误信息等不可预测长文本不使用全局 `word-break: break-all`，避免破坏可读性。

### 26.11 Dialog Verification

已复核实际业务 Dialog：告警确认 `520px`、设备配置差异 `720px`、点位导入预览 `920px`、点位批量编辑/批量生成 `520px`。UI-02 的全局 dialog max-width/max-height/body scroll safety 继续生效；导入预览表格保留内部滚动，业务 footer 不被表格内容推出 viewport。

本次后端不可达，未声称已完成所有业务 Dialog 的真实长数据交互验证。实际长设备名、长 JSON、多条导入预览需在后端在线或受控 audit fixture 中继续做数据态复核。

### 26.12 Realtime Backend-Offline Console

`RealtimeView` 现在只对明确可识别的网络失败（`fetch failed`、连接拒绝、请求服务超时等）更新页面已有的 unavailable/degraded 状态，不再向 `console.error` 或高频 `console.warn` 重复写入预期离线异常；非预期编程错误仍保留 `console.error` 可观测性。未改变 API exception contract、降级状态和 latest-response-wins。

### 26.13 Audit Script Changes

`scripts/ui-layout-audit.mjs` 增加/保留以下页面级诊断：

- toolbar/filter horizontal overflow 选择器扫描。
- `overflow:hidden/clip` 下真实 scrollWidth/scrollHeight 超出检测。
- intentional ellipsis 只有在同时存在 `title` 或 `aria-label` 时才会被分类为合理裁切，避免通过泛化豁免让指标变绿。
- theme fixture 继续检查 popup、dialog、alert、tag、empty、table、pagination。
- 仍生成 `.ui-audit/screenshots/` 与 `report.json`；`.ui-audit/` 不进入版本控制。

### 26.14 14 Route Matrix

真实 Router 清单全部执行：

```text
/login
/dashboard
/realtime
/history
/alarm
/device
/device/workbench
/collect
/cloud
/diagnostic
/log
/network
/control
/shadow
```

每个 route 均执行四个 viewport，共 `14 × 4 = 56` 次检查；`renderedRouteCount = 14`，`notVisited = []`。

### 26.15 Viewport Results

| Viewport | Route checks | Rendered | Document/body X overflow | Toolbar X overflow | Hidden clips |
|---|---:|---:|---:|---:|---:|
| `1280×720` | 14 | 14 | 0 | 0 | 0 |
| `1366×768` | 14 | 14 | 0 | 0 | 0 |
| `1440×900` | 14 | 14 | 0 | 0 | 0 |
| `1920×1080` | 14 | 14 | 0 | 0 | 0 |

最终 `.ui-audit/report.json` 摘要：

```json
{
  "routeCount": 14,
  "viewportCount": 4,
  "executedChecks": 56,
  "renderedRouteCount": 14,
  "routesWithOverflow": 0,
  "routesWithThemeMismatch": 0,
  "routesWithLayoutIssue": 0,
  "routesWithConsoleErrors": 0,
  "toolbarHorizontalOverflows": 0,
  "hiddenClips": 0,
  "notVisited": []
}
```

### 26.16 Intentional Scroll

本轮审计记录的 intentional overflow 共 50 项，主要来自页面内容纵向滚动、Element Plus 表格内部滚动、JSON/日志/设备树/导入预览等有明确内容边界的区域。它们不计入 `routesWithOverflow`；没有把常规 toolbar 横向滚动标记为 intentional。

### 26.17 Remaining Unintentional Overflow

最终矩阵中：

```text
routesWithOverflow = 0
routesWithLayoutIssue = 0
toolbarHorizontalOverflows = 0
hiddenClips = 0
unintentionalOverflowCount = 0
```

没有遗留已知的页面级 unintentional overflow。真实数据表格和业务 Dialog 的字段密度仍受 backend offline 限制，已按 `CODE/CONTRACT CLOSED` 记录，不冒充真实数据闭环。

### 26.18 UI-01 Finding Closure

| Finding | UI-03 状态 | 说明 |
|---|---|---|
| `UI-LAYOUT-01` | `CLOSED` | Resource dashboard 改为 fluid grid，四个 viewport 通过。 |
| `UI-LAYOUT-02` | `CLOSED` | Topology 不再由 `overflow:hidden` 裁切，节点/连接线可收缩。 |
| `UI-RESPONSIVE-01` | `CLOSED` | Log toolbar 在 1280/1366 下换行，无横向滚动。 |
| `UI-TABLE-01` | `CODE/CONTRACT CLOSED; REAL-DATA VERIFY DEFERRED` | 表格内部滚动、fixed-right、长文本和分页责任已处理；真实数据字段密度待在线环境复核。 |
| `UI-CONSOLE-01` | `CLOSED` | 预期离线网络失败不再刷 console.error/warn，非预期错误仍可观测。 |

### 26.19 Full Verification

本轮生产代码验证命令：

```bash
npm run stylelint
npm run lint
node --check scripts/ui-layout-audit.mjs
npm run typecheck
npm test
npm run build
npm run build:web
npm run verify
npm run pack
node scripts/ui-layout-audit.mjs
git diff --check
```

不得创建或修改测试代码；`npm test` 仅执行仓库已有测试套件，未增加测试依赖。

### 26.20 build:web Generated Assets

CSS/Vue 修改后的 `npm run build:web` 最新输出应保留在：

```text
collector-boot/src/main/resources/static/desktop/**
```

其中 hashed CSS/JS 与 `index.html` 属于有效生成物；本轮不手工回退有效 build 输出。生成物不纳入 UI audit 临时截图/报告。

### 26.21 Changed Files

本轮页面级生产代码、审计脚本和文档变更涉及：

```text
collector-desktop/src/views/dashboard/DashboardView.vue
collector-desktop/src/views/log/LogView.vue
collector-desktop/src/views/history/HistoryView.vue
collector-desktop/src/views/realtime/RealtimeView.vue
collector-desktop/src/components/alarm/AlarmTablePanel.vue
collector-desktop/src/components/log/LogPanel.vue
collector-desktop/src/components/realtime/RealtimeDataPanel.vue
collector-desktop/src/components/device/DeviceConfigPanel.vue
collector-desktop/src/features/device/components/DeviceOperationShell.vue
collector-desktop/src/features/point/components/PointEditor.vue
collector-desktop/src/styles/base.css
collector-desktop/scripts/ui-layout-audit.mjs
collector-desktop/docs/production-readiness/FRONTEND-UI-AUDIT.md
```

`.hermes.md` 未修改。未执行 `git add`、`git commit`、`git push`、`git checkout`、`git reset`、`git stash`、`git rebase`。

### 26.22 Remaining Limitations

- 当前后端 `http://127.0.0.1:9090/collector` 不可达，因此本轮没有伪造在线设备、实时行、告警行或历史行。
- `UI-TABLE-01` 的布局源代码/滚动责任/长文本策略/fixed-right/pagination 已关闭；真实数据列密度、极长设备/点位字段和后端实际返回字段仍需在线环境复核。
- 业务 Dialog 的 viewport safety 已由全局规则、实际 width 复核和 audit fixture 覆盖；包含真实长 JSON/多条导入数据的交互仍需在线或受控 fixture 复核。

### 26.23 Task Status

`Task UI-03: PASS / COMPLETE`

依据：Dashboard 两项已知 overflow 已关闭，Log toolbar 已完成响应式整改，14 route × 4 viewport 真实矩阵通过，无 document/body 横向溢出、无 toolbar 横向溢出、无 hidden clip、无新增 theme mismatch，Realtime 预期离线错误不再刷屏；代码质量、构建、打包和审计命令均通过。

### 26.24 Next

```text
Task UI-04 — Full Frontend Visual Regression / Final Audit
```

UI-04 再次从真实页面验证 Theme、Layout、Overflow、Toolbar、Table、Dialog、Long Text、Viewport 和 Console；在 UI-04 开始前不回到 Task 07，也不扩大本轮页面整改范围。

## 27. Task UI-04 — Full Frontend Visual Regression & Final Audit

### 27.1 完成情况

UI-04 是 Frontend UI Cleanup 的最终回归审计。本轮以审计和受控数据回归为主，没有重新设计页面、没有重新做 UI-02 Theme，也没有重新做 UI-03 页面布局整改。实际代码改动集中在 `scripts/ui-layout-audit.mjs`，用于补齐表格区域 computed-style / effective background 审计、受控 table fixture、loading/empty/fixed/pagination/native table 指标；未修改业务 API、设备控制、采集协议、Electron Main/Preload 或后端逻辑。

### 27.2 14 Route Coverage

生产 Router 全量访问完成：

```text
/login
/dashboard
/realtime
/history
/alarm
/device
/device/workbench
/collect
/cloud
/diagnostic
/log
/network
/control
/shadow
```

结果：`visited = 14 / 14`，`notVisited = []`。

### 27.3 4 Viewport Coverage

继续使用 UI-01 至 UI-03 的四个 desktop viewport：

| Viewport | Route checks | Rendered | X overflow | Toolbar X overflow | Hidden clips | Console errors | Table white/light |
|---|---:|---:|---:|---:|---:|---:|---:|
| `1280×720` | 14 | 14 | 0 | 0 | 0 | 0 | 0 / 0 |
| `1366×768` | 14 | 14 | 0 | 0 | 0 | 0 | 0 / 0 |
| `1440×900` | 14 | 14 | 0 | 0 | 0 | 0 | 0 / 0 |
| `1920×1080` | 14 | 14 | 0 | 0 | 0 | 0 | 0 / 0 |

总计：`14 × 4 = 56`，未抽样替代。

### 27.4 Theme Final Result

最终 Theme fixture 覆盖 native control、Element Plus input/select/textarea/input-number、checkbox/radio/switch、Select popper、DatePicker、MessageBox、Dialog、Alert、Tag、Empty、Table、Pagination。结果：

```text
themeFixtureChecks = 4
themeFixtureWhiteBackgrounds = 0
themeFixtureClippedAlerts = 0
themeFixtureUnsafeDialogs = 0
themeFixtureLightEmptyFills = 0
```

### 27.5 Input / Select Final Result

生产 route 与 fixture 中继续检查 native `input` / `select` / `textarea`、Element Plus `.el-input__wrapper`、`.el-select__wrapper`、`.el-textarea__inner`、`.el-date-editor`、`.el-input-number`，覆盖 normal / focus / disabled / readonly / error 状态。

最终：`whiteBackgrounds = 0`。没有发现白色 Input / Select / Textarea 回归。

### 27.6 Final Table Theme Verification

本轮重点增强表格审计，不再只检查 `.el-table` 根节点，也不把 `transparent` 机械当作 PASS。审计逻辑使用 `getComputedStyle(element).backgroundColor`，并沿父链计算 effective visible background：

```text
cell → row → table → wrapper → panel → body
```

白色/浅色判断包括：

- `rgb(255,255,255)` / `rgba(255,255,255,...)`
- RGB >= 245 的近白背景
- RGB >= 235 的明显浅灰/浅色 surface 背景
- 透明背景继续向上查找实际可见背景

### 27.7 Element Plus Table Final Result

Element Plus table 审计至少覆盖：

```text
.el-table
.el-table__header-wrapper
.el-table__header
thead
th.el-table__cell
.el-table__body-wrapper
.el-table__body
tbody
tr
td.el-table__cell
.el-table__expanded-cell
.el-table__fixed
.el-table__fixed-right
.el-table__fixed-header-wrapper
.el-table__fixed-body-wrapper
.el-table__empty-block
.el-table__empty-text
.el-table__loading-mask
.el-table-filter
.el-table-filter__list
.el-table__border-left-patch
```

受控 fixture 增加了 `3+` 条真实数据行，包含短文本、超长设备名、超长点位编码、较长中文告警内容和较长 endpoint URL，用于验证 cell 背景、ellipsis/wrap、固定列和内部滚动。

结果：

```text
tableChecks = 236
tableWhiteBackgrounds = 0
tableLightBackgrounds = 0
```

表格白色背景问题：`CLOSED`。

### 27.8 Native Table Final Result

生产 route 与 fixture 同时扫描 native table：

```text
.runtime-table
.table-wrap table
.point-import-preview-table table
thead / th
tbody / tr / td
```

History、Collection、Diagnostic、Point import preview 等普通 table 不再只依赖 Element Plus 规则。最终 native table effective background 未发现白色/浅色 surface 泄漏。

### 27.9 Fixed Column Result

fixture 专门构造并检查：

```text
table-fixed-left
table-fixed-right
fixed header / fixed body / fixed patch
```

结果：

```text
tableFixedWhiteBackgrounds = 0
```

未发现 fixed-right 操作列白块或固定列白色条。

### 27.10 Empty / Loading Table Result

fixture 明确覆盖：

```text
.el-table__empty-block
.el-table__empty-text
.el-empty
.el-table__loading-mask
.el-loading-spinner
.el-loading-text
```

结果：

```text
tableEmptyWhiteBackgrounds = 0
tableLoadingWhiteBackgrounds = 0
themeFixtureLightEmptyFills = 0
```

未发现白色 empty block、浅色 loading 遮罩或 `el-empty` 浅色插图回归。

### 27.11 Pagination Result

fixture 覆盖 pagination root、prev / next、pager normal / active / disabled、jumper input、page-size select。

结果：

```text
paginationWhiteBackgrounds = 0
```

Pagination 内部 Select/Input 未出现白框。

### 27.12 Popup Result

Popup 回归覆盖 Select dropdown、DatePicker、MessageBox、Dialog、Popover/Dropdown selector 范围。结果：生产 route 与 fixture 中未发现 popup 白底或浅色 fallback。

### 27.13 Dialog Result

fixture 保留并验证 `520px`、`720px`、`920px`，并加入长内容 Dialog fixture：30+ 行导入预览、long JSON、long URL、long Chinese message、long device/point name。

结果：

```text
themeFixtureUnsafeDialogs = 0
```

1280×720 与 1366×768 下 header 可见、body 可滚动、footer 存在且未被长内容推出 dialog 边界。

### 27.14 Alert Result

生产 route 与 fixture 继续检查 `/collect`、`/log`、`/realtime`、`/alarm`、`/login` 中的 Alert/状态提示。结果：

```text
themeFixtureClippedAlerts = 0
```

未发现 Alert 文本或图标被 line-height / overflow 裁切。

### 27.15 Dashboard Result

Dashboard 最终回归再次覆盖 `.resource-dashboard` 和 `.topology-flow`。四个 viewport 下均无 document/body 横向 overflow、无 hidden clip、无 toolbar overflow。UI-03 对 Resource Dashboard 和 Topology 的整改没有在 UI-04 中回归。

### 27.16 Log Result

`/log` 在 `1280×720` 和 `1366×768` 下 filter toolbar 无横向 scrollbar，操作按钮可见，字段无异常挤压，日志长文本不撑破页面。结果：`toolbarHorizontalOverflows = 0`。

### 27.17 Alarm Result

`/alarm` 最终检查覆盖 level、keyword、datetime range、refresh、batch ack、fixed-right 操作列。结果：允许 wrap，未发现 toolbar horizontal drag，fixed-right 操作列为 dark theme。

### 27.18 Realtime Result

`/realtime` 最终检查覆盖 table、tag、toolbar、error state。当前 backend offline 条件下：

```text
页面稳定
error state 可见
routesWithConsoleErrors = 0
```

未通过 `console.error = noop` 隐藏未知异常；非预期异常仍保留可观测性。

### 27.19 History Result

`/history` 最终检查覆盖 query controls、chart、alarm table、history table、raw record、pagination/limit controls。结果：raw JSON 使用表格内部换行/滚动责任，不撑破 page。

### 27.20 Device Workbench Result

`/device/workbench` 最终检查覆盖 DeviceConfigPanel、ProtocolDynamicForm、PointEditor、PointBatchEdit、PointGenerate、运行控制区和 Quick Nav。结果：1280 / 1366 下无横向 page overflow，form 可操作，table 自己负责滚动。

### 27.21 Long Text Result

最终长文本策略仍为：单行表格 cell 使用 ellipsis + `title`，JSON / raw record 使用 `pre-wrap` + `overflow-wrap:anywhere`，长 URL / 长编码不撑破页面，Dialog 长内容由 body 内部 scroll 且 footer 保持可操作。未发现长文本撑破 page。

### 27.22 Overflow Result

最终 Layout Gate：

```text
document horizontal overflow = 0
body horizontal overflow = 0
routesWithOverflow = 0
routesWithLayoutIssue = 0
toolbarHorizontalOverflows = 0
hiddenClips = 0
```

合理内部 scroll 继续单独分类，不算 failure：table horizontal scroll、exact-page-body vertical scroll、device tree vertical scroll、long list vertical scroll、dialog body vertical scroll。

### 27.23 Console Result

最终 Console Gate：

```text
routesWithConsoleErrors = 0
```

backend offline 场景稳定降级，未发现新的 route console error 或 exception。

### 27.24 Screenshot / DOM Evidence

`.ui-audit/screenshots/` 已重新生成。重点截图文件存在且非空：

```text
1280x720/dashboard.png
1366x768/dashboard.png
1280x720/log.png
1366x768/log.png
1366x768/alarm.png
1366x768/realtime.png
1366x768/history.png
1366x768/device_workbench.png
1920x1080/dashboard.png
```

当前工作流主要依赖 DOM/computed-style metrics 做机器可复现审计；未把截图加入 Git。

### 27.25 Audit Metrics

最终 `.ui-audit/report.json` 摘要：

```json
{
  "routeCount": 14,
  "viewportCount": 4,
  "executedChecks": 56,
  "renderedRouteCount": 14,
  "routesWithOverflow": 0,
  "routesWithThemeMismatch": 0,
  "routesWithLayoutIssue": 0,
  "routesWithConsoleErrors": 0,
  "toolbarHorizontalOverflows": 0,
  "hiddenClips": 0,
  "notVisited": [],
  "themeFixtureChecks": 4,
  "themeFixtureWhiteBackgrounds": 0,
  "themeFixtureClippedAlerts": 0,
  "themeFixtureUnsafeDialogs": 0,
  "themeFixtureLightEmptyFills": 0,
  "tableChecks": 236,
  "tableWhiteBackgrounds": 0,
  "tableLightBackgrounds": 0,
  "tableFixedWhiteBackgrounds": 0,
  "tableLoadingWhiteBackgrounds": 0,
  "tableEmptyWhiteBackgrounds": 0,
  "paginationWhiteBackgrounds": 0
}
```

### 27.26 Finding Final Matrix

| Finding | Final Status | 说明 |
|---|---|---|
| `UI-LAYOUT-01` | `CLOSED` | Dashboard resource dashboard 全 viewport 无横向 overflow。 |
| `UI-LAYOUT-02` | `CLOSED` | Dashboard topology 无 hidden clip。 |
| `UI-RESPONSIVE-01` | `CLOSED` | Log toolbar 1280/1366 无横向拖动。 |
| `UI-OVERFLOW-01` | `CLOSED` | document/body x-overflow 为 0。 |
| `UI-OVERFLOW-02` | `CLOSED` | 重要 panel hidden clip 为 0。 |
| `UI-CONSISTENCY-01` | `CLOSED` | controls/popups/table dark theme 无白底回归。 |
| `UI-DIALOG-01` | `CLOSED` | 520/720/920 与长内容 fixture 均安全。 |
| `UI-CONSOLE-01` | `CLOSED` | backend offline 降级下 console error 为 0。 |
| `UI-TABLE-01` | `CLOSED BY CODE + CONTROLLED-DATA VISUAL CONTRACT` | 生产 route table 与受控 populated/fixed/loading/empty/pagination/native table fixture 均无白色/浅色背景。真实后端字段联调变为 optional。 |

### 27.27 Verification

UI-04 验证命令：

```bash
npm --prefix collector-desktop run stylelint
npm --prefix collector-desktop run lint
npm --prefix collector-desktop run typecheck
npm --prefix collector-desktop test
npm --prefix collector-desktop run build
npm --prefix collector-desktop run build:web
npm --prefix collector-desktop run verify
npm --prefix collector-desktop run pack
node --check collector-desktop/scripts/ui-layout-audit.mjs
node collector-desktop/scripts/ui-layout-audit.mjs
git diff --check
```

本轮未创建测试文件、未修改测试代码、未新增测试依赖。

### 27.28 Changed Files

UI-04 生产代码未改业务逻辑；当前新增/修改集中在：

```text
collector-desktop/scripts/ui-layout-audit.mjs
collector-desktop/docs/production-readiness/FRONTEND-UI-AUDIT.md
```

完整验证链执行 `build:web` 后，若 CSS/JS hash 变化，应保留：

```text
collector-boot/src/main/resources/static/desktop/**
```

`.hermes.md` 保持不动。未执行 `git add`、`git commit`、`git push`、`git checkout`、`git reset`、`git stash`、`git rebase`。

### 27.29 Remaining Limitations

- 当前后端仍不可达，因此没有声称已经完成真实后端字段值、真实设备数据、真实告警行、真实历史行的现场联调。
- UI-04 通过受控 audit fixture 覆盖 populated rows、long text、fixed columns、loading、empty、pagination 与 native table，将 UI-03 的 `REAL-DATA FIELD VERIFY DEFERRED` 升级为 `CONTROLLED-DATA VISUAL VERIFY COMPLETE; REAL FIELD DATA VERIFY OPTIONAL`。
- 截图保存在 `.ui-audit/screenshots/`，未提交 Git；最终判断以 DOM/computed-style metrics 和真实 route matrix 为准。

### 27.30 Task Status

```text
Task UI-04: PASS / COMPLETE
```

表格白色背景问题：`CLOSED`。

### 27.31 Frontend UI Cleanup Status

```text
Frontend UI Cleanup: PASS / COMPLETE

Theme: PASS
Layout: PASS
Overflow: PASS
Table dark-theme: PASS
Responsive desktop: PASS
Controlled UI regression: PASS
```

下一步如需继续，应由用户确认是否回到：

```text
Task 07 — Dependency Security
```

UI-04 内未开始 Task 07。

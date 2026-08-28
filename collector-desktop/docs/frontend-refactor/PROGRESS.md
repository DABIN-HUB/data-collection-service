# collector-desktop 前端架构重构进度

更新时间：2026-08-28 10:56:51 +0800

## 当前状态

- 当前目标分支：`feature_2.0`
- 最近提交：
  - `c0b4cd2` 样式修改
  - `288c0d7` 样式修改
  - `ce90843` 修改
  - `759d1c2` 采集功能整理
  - `4021949` xg
- 启动时 git 状态：`## feature_2.0...github/feature_2.0`，未见已有未提交代码。
- 当前阶段：Phase 1 已完成并通过验证。
- 下一阶段：Phase 2 迁移 Dashboard。

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

## 本阶段新增文件

- `collector-desktop/AGENTS.md`
- `collector-desktop/docs/frontend-refactor/PLAN.md`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-desktop/docs/frontend-refactor/DECISIONS.md`
- `collector-desktop/src/app/AppShell.vue`
- `collector-desktop/src/app/AppSidebar.vue`
- `collector-desktop/src/app/AppTopbar.vue`
- `collector-desktop/src/app/navigation.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/styles/tokens.css`
- `collector-boot/src/main/resources/static/desktop/assets/AppShell-C5L_y7Y5.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/AppShell-D4lXCEpo.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/LegacyConsoleView-BXp8pKgK.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/LegacyConsoleView-BlnkvwUn.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/LoginView-CHBBa-on.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/_plugin-vue_export-helper-DlAUqK2U.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/app.store-BLooyUe_.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/index-DLQReBaR.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/index-SykID_IV.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/vendor-element-plus-D0sFrN_d.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/vendor-vue-Bj6K4sZk.js`（`build:web` 生成）

## 本阶段修改文件

- `.gitignore`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-desktop/src/main.ts`
- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`

## 本阶段删除文件

源码文件未删除。

`build:web` 同步时删除了上一版 Web 静态构建 hash 文件：

- `collector-boot/src/main/resources/static/desktop/assets/LoginView-BwZ1OBgG.js`
- `collector-boot/src/main/resources/static/desktop/assets/index-Dghfuwmz.css`
- `collector-boot/src/main/resources/static/desktop/assets/index-DzhMd1DY.js`
- `collector-boot/src/main/resources/static/desktop/assets/vendor-element-plus-BWF7_VwE.js`
- `collector-boot/src/main/resources/static/desktop/assets/vendor-vue-CnrZo9C0.js`

## 已知问题与回归风险

- `LegacyConsoleView.vue` 仍然承载全部业务页面，只是已经退出 Shell/Sidebar/Topbar 职责；这是 Phase 2 之后逐页迁移的主要对象。
- Phase 1 新增了 `/device/workbench` 过渡路由，原先“设备列表 URL 下显示工作台”的隐式行为被改为显式 URL；这是为了保证 Router 成为唯一页面状态源。
- `legacy-console.css` 与 `workbench.css` 仍未拆解，Phase 1 只新增 token，不处理大规模样式迁移。
- `device.store.ts` 已经有设备领域状态，但 Legacy 宿主仍保留 `devices/runtimeMap/selectedDeviceId` 页面内旧状态；具体页面迁移时必须逐步收敛到 Pinia。
- Web 静态产物 hash 因 `build:web` 更新，属于验证命令产生的预期变更。

## 下一步

等待确认后进入 Phase 2：迁移 Dashboard。

Phase 2 开始前必须先读取：

1. `collector-desktop/AGENTS.md`
2. `collector-desktop/docs/frontend-refactor/PLAN.md`
3. `collector-desktop/docs/frontend-refactor/PROGRESS.md`
4. `collector-desktop/docs/frontend-refactor/DECISIONS.md`

Phase 2 只迁移 Dashboard，不自动进入 Realtime 或其他业务页面。

# 数据采集工作台桌面客户端

`collector-desktop` 是 `data-collection-service` 的 Vue 3 + Electron 桌面工作台，用于配置、控制、监控、告警、历史数据、设备影子、日志和诊断。

> 当前阶段：桌面端**只连接用户手动启动的 Spring Boot 后端服务**，不会自动拉起后端 jar，不内置 JRE，也不管理后端进程生命周期。

## 技术栈

- Vue 3
- TypeScript
- Vite
- Pinia
- Vue Router
- Element Plus
- ECharts
- Vitest
- Electron 33
- electron-builder

## 后端服务要求

默认连接地址：

```text
http://127.0.0.1:9090/collector
```

访问规则：

- `/health` 可直接访问；
- `/api/**`、`/monitor/**` 需要请求头 `X-Collector-Token`；
- 令牌在登录页输入，文档和源码中不要保存真实凭据。

## 开发启动

安装依赖：

```bash
npm install --legacy-peer-deps --registry=https://registry.npmmirror.com
```

启动 Vite 渲染进程：

```bash
npm run dev
```

启动 Electron 开发模式：

```bash
npm run electron:dev
```

## 构建验证

```bash
npm test
npm run typecheck
npm run build
```

真实后端 smoke 联调：

```bash
npm run smoke:backend
```

无 token 模式会验证：

- `/health` 可访问；
- `/api/protocols` 未带 token 返回 401，证明鉴权链路生效。

如需联调管理接口，可通过环境变量临时传入令牌，不要写入源码或文档：

```bash
COLLECTOR_TOKEN=[REDACTED] npm run smoke:backend
```

也可指定服务地址：

```bash
COLLECTOR_BASE_URL=http://127.0.0.1:9090/collector COLLECTOR_TOKEN=[REDACTED] npm run smoke:backend
```

脚本会生成：

```text
docs/backend-smoke-latest.json
docs/backend-smoke-latest.md
```

报告会脱敏 token，不保存真实凭据。

设备级 smoke 联调会创建并删除一个本地临时测试设备：

```bash
COLLECTOR_TOKEN=[REDACTED] npm run smoke:device
```

默认测试设备：

```text
desktop_smoke_p11
```

默认测试连接：

```text
MODBUS_TCP 127.0.0.1:65020
```

如果本机没有 Modbus 模拟器，`start-local` 可能返回启动失败，脚本会标记为 `EXPECTED_FAIL`，但仍会验证配置保存、读取、状态查询、停止和删除清理。

设备级报告：

```text
docs/device-smoke-latest.json
docs/device-smoke-latest.md
```

带本地 Modbus TCP 模拟器的闭环 smoke：

```bash
COLLECTOR_TOKEN=[REDACTED] npm run smoke:modbus
```

该脚本会在本机启动一个临时 Modbus TCP 模拟器：

```text
127.0.0.1:65020
```

然后创建 `desktop_smoke_p12_modbus` 本地临时设备，验证 `start-local`、运行状态、实时数据、停止和删除。脚本结束后会关闭模拟器并删除测试设备。

报告：

```text
docs/modbus-smoke-latest.json
docs/modbus-smoke-latest.md
```

扩展闭环 smoke 会在 Modbus 成功采集基础上继续验证历史、告警确认、设备影子和网络检测：

```bash
COLLECTOR_TOKEN=[REDACTED] npm run smoke:extended
```

该脚本会创建并删除 `desktop_smoke_p13_extended` 本地临时设备，验证：

- `start-local`；
- 实时数据；
- 历史接口 disabled 语义；
- 告警确认和确认状态查询；
- 设备影子 desired/delta/history/clear；
- TCP 网络检测 `127.0.0.1:65020` reachable。

报告：

```text
docs/extended-smoke-latest.json
docs/extended-smoke-latest.md
```

本地 Electron 预览：

```bash
npm run electron:preview
```

仅生成未安装目录包：

```bash
npm run pack
```

生成安装包：

```bash
npm run dist
```

当前本地开发配置关闭了 Windows 可执行文件签名/资源编辑：

```json
"signAndEditExecutable": false
```

原因是普通 Windows 用户可能没有创建符号链接权限，electron-builder 解压 `winCodeSign` 时会失败。正式发布如需签名和完整 Windows 资源信息，可在具备权限的 CI/签名环境中重新开启。

构建产物目录：

```text
collector-desktop/release/
```

## 当前功能范围

已覆盖原静态控制台主要功能入口：

- 控制台总览；
- 实时数据；
- 历史数据；
- 告警管理；
- 设备管理；
- 采集配置；
- 云平台链路；
- 系统诊断；
- 运行日志；
- 网络检测；
- 手动控制；
- 设备影子。

关键能力：

- 协议 Schema 动态表单；
- 点位通用字段 + 协议动态字段；
- Excel 导入/导出；
- 本地临时设备四步编辑器；
- 设备启动/停止/状态/diff；
- 实时数据 HTTP 兜底与 WebSocket-ready；
- 历史趋势图；
- 告警确认和批量确认；
- 日志导出；
- 云上报 Outbox/ACK 摘要；
- 系统诊断建议；
- 网络诊断。

## Electron 桌面能力

主进程负责：

- 桌面窗口创建；
- 窗口尺寸持久化；
- 中文菜单；
- 外部链接安全打开；
- 本地客户端配置文件；
- 受限 IPC。

preload 暴露白名单能力：

- `getAppInfo()`；
- `getServerConfig()`；
- `setServerConfig()`；
- `openExternal()`；
- `onNavigate()`。

不会暴露 Node.js 文件系统能力给渲染进程。

## 本地配置

Electron 模式下，本地客户端配置保存到应用 `userData` 目录中的：

```text
collector-desktop-config.json
```

配置内容只包含服务地址和窗口状态等桌面客户端配置。接口令牌是否记住由登录页选项控制，生产环境请按安全要求管理令牌。

## 安全说明

- 不提交真实 token、密码、云密钥、设备密钥或连接字符串；
- 示例凭据统一使用占位符；
- 后端鉴权失败时页面展示真实错误，不伪造连接成功；
- 控制/写点操作必须由用户点击触发；
- 设备停止、后端不可用或未登录时页面显示未连接或错误状态。

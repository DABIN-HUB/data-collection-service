# HTTP `/api/v1` 实测记录

- 来源：ProtoForge HTTP 仿真服务 `GET http://127.0.0.1:8080/api/v1`，匿名请求 HTTP 200；**非** `:8000` 管理 API。
- 原始结构在同目录 `http-api-v1-response-redacted.json`：仅 `device_id` 替换为 `[REDACTED_DEVICE_ID]`；点位名称、实际数值、单位、数据类型与采集时间均来自本次真实响应。
- 响应顶层 `points` 是数组；元素的 `name` 是点位键，`value` 是数值或字符串。`RAW` 只处理顶层 `values`、顶层键、`pointId/value` 数组，不会自动解析 `{points:[{name,value}]}`。
- 通用配置契约：建议连接 `url=http://127.0.0.1:8080`，请求 `requestMode=DIRECT`、`method=GET`、`path=/api/v1`，默认 `healthCheckPath=/health` 可用；响应 `responseMode=POINT_ARRAY`、`responseArrayPath=$.points`、`responseKeyField=name`、`responseValueField=value`，点位 `pointCode` 对应响应 `name`。另一种配置是完整连接 URL `http://127.0.0.1:8080/api/v1` + `path` 留空，但此时必须另行配置健康检查：默认 `/health` 会拼为 `/api/v1/health`（实测 404）；例如把 `healthCheckPath` 和 `heartbeatEndpoint` 都设为空字符串，使其请求完整 URL 本身。`AUTO_COMPAT` 的默认兼容策略不因 URL 含路径而自动推断为直接请求。
- 链路追踪：`HttpProtocolDescriptorProvider` 暴露请求与提取字段；控制台配置导入经 `ConfigImportExportApplicationService.appendImportContext` 保留 `DeviceConnection.extJson` 并交给配置管理器；`HttpCollector.requestRead` 分别选择 DIRECT 直接请求和响应提取器；`HttpConnectionAdapter.buildFullUrl` 负责 URL；`PointArrayHttpResponseExtractor` 根据 `name` 匹配 `pointCode` 回填 `pointId`。此次修复完整 URL + 空 endpoint 时额外补 `/` 的通用问题；未对设备名或厂商做特判。
- 验证边界：真实仿真 HTTP 响应抓取成功，适配器路径与点位提取分别有自动化测试；未进行采集平台 UI 配置/实时数据端到端验收。

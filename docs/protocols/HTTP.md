# HTTP

## 实现类

- `core/collector/protocol/http/HttpCollector`

## 实现方式

- 已接入 HTTP 连接适配器。
- 采集读写采用请求-响应模型：
  - `read` / `batchRead` 发送 JSON 请求并解析响应中的点位值。
  - `write` 发送写入请求，支持解析 `success/status` ACK。
  - `command` 发送命令请求并解析回包。

## 地址与点位配置

- 设备连接可配置 `url`，或由 `host+port` 自动组装。
- 点位地址含义需按业务协议自行扩展。

## 使用方式

1. 设备 `protocolType` 设为 `HTTP`。
2. 在服务端约定以下请求格式字段：`action/deviceId/points(pointId,pointCode,address)`。
3. 响应建议使用以下任一格式：
   - `{\"values\": {\"pointId\": value}}`
   - `{\"pointId\": \"xxx\", \"value\": ...}`
   - `[{\"pointId\": \"xxx\", \"value\": ...}]`

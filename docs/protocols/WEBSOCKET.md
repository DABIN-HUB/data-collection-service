# WEBSOCKET

## 实现类

- `core/collector/protocol/websocket/WebSocketCollector`

## 实现方式

- 基于 WebSocket 连接适配器。
- 通过发送 JSON 字符串实现 `subscribe/unsubscribe/read/batchRead/write/command`。
- 已实现收包解析并回填点位缓存（支持多种消息结构）。

## 地址与点位配置

- 设备可配置 `url`，未配置时按 `ws://host:port` 或 `wss://host:port` 组装。
- 点位配置以 `pointId` 为核心，订阅消息模板在代码中固定。

## 使用方式

1. 设备 `protocolType` 设为 `WEBSOCKET`。
2. 推荐上游消息结构包含 `pointId + value` 或 `values` map。
3. 若使用点位编码回包，键名可使用 `pointCode`，采集器会做映射回填。

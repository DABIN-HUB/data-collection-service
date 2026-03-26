# WEBSOCKET

## 实现类

- `core/collector/protocol/websocket/WebSocketCollector`

## 实现方式

- 基于 WebSocket 连接适配器。
- 通过发送 JSON 字符串实现 subscribe/unsubscribe/write/command。
- 收包解析函数 `handleWebSocketMessage` 当前为占位实现。

## 地址与点位配置

- 设备可配置 `url`，未配置时按 `ws://host:port` 或 `wss://host:port` 组装。
- 点位配置以 `pointId` 为核心，订阅消息模板在代码中固定。

## 使用方式

1. 设备 `protocolType` 设为 `WEBSOCKET`。
2. 先用于链路联调。
3. 若上生产，需补齐消息协议解析与回填点位逻辑。

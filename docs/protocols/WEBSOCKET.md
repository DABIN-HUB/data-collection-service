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

## 连接字段整理（createFieldConfig 写法）

说明：
- `url` 与 `host + port + path` 二选一即可。
- 下列字段覆盖 `WebSocketCollector` 与 `WebSocketConnectionAdapter` 的真实读取项。

```java
fields.add(createFieldConfig("url", "string", "WebSocket地址", false, "ws://127.0.0.1:8080/ws", null));
fields.add(createFieldConfig("host", "string", "主机", false, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "端口", false, "8080", null));
fields.add(createFieldConfig("sslEnabled", "boolean", "是否启用WSS", false, "false", new String[]{"true", "false"}));
fields.add(createFieldConfig("path", "string", "连接路径", false, "/ws", null));
fields.add(createFieldConfig("queryParams", "object", "查询参数", false, "{}", null));
fields.add(createFieldConfig("headers", "object", "自定义请求头", false, "{}", null));
fields.add(createFieldConfig("connectTimeout", "number", "连接超时(ms)", false, "10000", null));
fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
fields.add(createFieldConfig("writeTimeout", "number", "写入超时(ms)", false, "5000", null));
fields.add(createFieldConfig("username", "string", "用户名", false, "", null));
fields.add(createFieldConfig("password", "string", "密码", false, "", null));
fields.add(createFieldConfig("authToken", "string", "Bearer令牌", false, "", null));
fields.add(createFieldConfig("subprotocol", "string", "子协议", false, "collector-v1", null));
fields.add(createFieldConfig("binaryMode", "boolean", "是否二进制收发", false, "false", new String[]{"true", "false"}));
fields.add(createFieldConfig("heartbeatInterval", "number", "心跳间隔(ms)", false, "60000", null));
fields.add(createFieldConfig("heartbeatMessage", "string", "心跳消息", false, "ping", null));
fields.add(createFieldConfig("heartbeatUsePing", "boolean", "是否使用Ping帧", false, "true", new String[]{"true", "false"}));
fields.add(createFieldConfig("authWaitResponse", "boolean", "认证后是否等待响应", false, "true", new String[]{"true", "false"}));
fields.add(createFieldConfig("productKey", "string", "产品标识", false, "", null));
fields.add(createFieldConfig("deviceSecret", "string", "设备密钥", false, "", null));
fields.add(createFieldConfig("authParams", "object", "扩展认证参数", false, "{}", null));
```

## 使用方式

1. 设备 `protocolType` 设为 `WEBSOCKET`。
2. 推荐上游消息结构包含 `pointId + value` 或 `values` map。
3. 若使用点位编码回包，键名可使用 `pointCode`，采集器会做映射回填。

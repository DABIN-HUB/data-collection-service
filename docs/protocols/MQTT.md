# MQTT

## 实现类

- `core/collector/protocol/mqtt/MqttCollector`
- 点位选项：`core/collector/protocol/mqtt/MqttPointOptions`

## 实现方式

- 订阅 topic 接收消息并更新点位最新值。
- 写入通过 publish 到 `writeTopic`。
- 支持命令：`publish/subscribe/unsubscribe/status`。

## 地址与点位配置

- `address` 或 `additionalConfig.topic` 作为订阅 topic。
- 常用扩展：`writeTopic`、`qos`、`retain`、`jsonPath`、`payloadEncoding`、`publishTemplate`、`charset`。

## 连接字段整理（createFieldConfig 写法）

说明：
- `url`、`brokerUrl`、`host + port` 三种方式都可能被代码使用。
- `subscribeTopics/publishTopic/willTopic` 等字段来自连接配置。

```java
fields.add(createFieldConfig("url", "string", "Broker完整地址", false, "tcp://127.0.0.1:1883", null));
fields.add(createFieldConfig("brokerUrl", "string", "Broker地址兼容字段", false, "tcp://127.0.0.1:1883", null));
fields.add(createFieldConfig("host", "string", "Broker主机", false, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "Broker端口", false, "1883", null));
fields.add(createFieldConfig("clientId", "string", "客户端ID", true, "device_mqtt", null));
fields.add(createFieldConfig("version", "string", "MQTT版本", true, "v5", new String[]{"v5", "v3"}));
fields.add(createFieldConfig("username", "string", "用户名", false, "", null));
fields.add(createFieldConfig("password", "string", "密码", false, "", null));
fields.add(createFieldConfig("sslEnabled", "boolean", "是否启用SSL", false, "false", new String[]{"true", "false"}));
fields.add(createFieldConfig("subscribeTopics", "string", "默认订阅主题列表", false, "devices/${deviceId}/#", null));
fields.add(createFieldConfig("subscribeQos", "number", "默认订阅QoS", false, "1", new String[]{"0", "1", "2"}));
fields.add(createFieldConfig("publishTopic", "string", "默认发布主题", false, "devices/${deviceId}/data", null));
fields.add(createFieldConfig("publishQos", "number", "发布QoS", false, "1", new String[]{"0", "1", "2"}));
fields.add(createFieldConfig("retained", "boolean", "发布保留标记", false, "false", new String[]{"true", "false"}));
fields.add(createFieldConfig("cleanSession", "boolean", "是否清理会话", false, "true", new String[]{"true", "false"}));
fields.add(createFieldConfig("autoReconnect", "boolean", "是否自动重连", false, "true", new String[]{"true", "false"}));
fields.add(createFieldConfig("connectTimeout", "number", "连接超时(ms)", false, "10000", null));
fields.add(createFieldConfig("heartbeatInterval", "number", "保活间隔(ms)", false, "60000", null));
fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
fields.add(createFieldConfig("sessionExpiryInterval", "number", "会话过期间隔(s)", false, "86400", null));
fields.add(createFieldConfig("receiveMaximum", "number", "接收窗口大小", false, "65535", null));
fields.add(createFieldConfig("willTopic", "string", "遗嘱主题", false, "", null));
fields.add(createFieldConfig("willMessage", "string", "遗嘱消息", false, "", null));
fields.add(createFieldConfig("willQos", "number", "遗嘱QoS", false, "0", new String[]{"0", "1", "2"}));
fields.add(createFieldConfig("willRetained", "boolean", "遗嘱保留标记", false, "false", new String[]{"true", "false"}));
fields.add(createFieldConfig("authTopic", "string", "额外认证主题", false, "", null));
fields.add(createFieldConfig("messageProperties", "object", "MQTT v5消息属性", false, "{}", null));
fields.add(createFieldConfig("maxPendingMessages", "number", "最大缓存消息数", false, "5000", null));
fields.add(createFieldConfig("dispatchBatchSize", "number", "批量分发大小", false, "1", null));
fields.add(createFieldConfig("dispatchFlushInterval", "number", "批量分发刷新间隔(ms)", false, "0", null));
fields.add(createFieldConfig("overflowStrategy", "string", "消息溢出策略", false, "BLOCK", new String[]{"BLOCK", "DROP_LATEST", "DROP_OLDEST"}));
fields.add(createFieldConfig("productKey", "string", "产品标识", false, "", null));
fields.add(createFieldConfig("deviceSecret", "string", "设备密钥", false, "", null));
fields.add(createFieldConfig("authParams", "object", "扩展认证参数", false, "{}", null));
```

## 使用方式

1. 设备 `protocolType` 设为 `MQTT`。
2. 连接配置中提供 broker、clientId、鉴权参数。
3. 点位配置 topic 和数据类型映射。

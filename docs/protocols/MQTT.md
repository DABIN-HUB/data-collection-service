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

## 使用方式

1. 设备 `protocolType` 设为 `MQTT`。
2. 连接配置中提供 broker、clientId、鉴权参数。
3. 点位配置 topic 和数据类型映射。

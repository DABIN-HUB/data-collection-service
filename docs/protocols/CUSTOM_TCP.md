# CUSTOM_TCP

## 实现类

- `core/collector/protocol/custom/CustomProtocolCollector`

## 实现方式

- 当前是协议占位实现。
- 仅 connect/disconnect 有基础行为。
- 读写订阅命令均抛 `UnsupportedOperationException`。

## 连接字段整理（createFieldConfig 写法）

说明：
- 当前 `CustomProtocolCollector` 仅做生命周期占位。
- 现有实现里没有通过 `connection.getXxx()` 或 `connection.getString("...")` 读取任何 `CUSTOM_TCP` 专属协议字段。
- 因此当前文档不生成 `fields.add(createFieldConfig(...))` 列表，避免文档先于实现虚构字段。

## 使用方式

1. 设备 `protocolType` 设为 `CUSTOM_TCP`。
2. 仅可验证采集器生命周期，不可用于真实采集。
3. 需按目标私有协议补齐：报文编解码、请求响应、异常重试、批量读取。

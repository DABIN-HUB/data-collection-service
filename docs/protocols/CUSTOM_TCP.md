# CUSTOM_TCP

## 实现类

- `core/collector/protocol/custom/CustomProtocolCollector`

## 实现方式

- 当前是协议占位实现。
- 仅 connect/disconnect 有基础行为。
- 读写订阅命令均抛 `UnsupportedOperationException`。

## 使用方式

1. 设备 `protocolType` 设为 `CUSTOM_TCP`。
2. 仅可验证采集器生命周期，不可用于真实采集。
3. 需按目标私有协议补齐：报文编解码、请求响应、异常重试、批量读取。

# OPC UA

## 实现类

- `core/collector/protocol/opc/OpcUaCollector`
- 基类：`core/collector/protocol/opc/ua/base/AbstractOpcUaCollector`

## 实现方式

- 基于 Milo 客户端。
- 支持批量读、写、订阅（Subscription + MonitoredItem）。
- 支持命令：`read`、`write`、`browse`。

## 地址与点位配置

- `DataPoint.address` 可直接填 `nodeId`。
- 或在 `additionalConfig` 配：`nodeId/namespace/identifier/identifierType`。
- 可配订阅参数：`samplingInterval`、`queueSize`、`deadband`、`subscribe`。

## 使用方式

1. 设备 `protocolType` 设置 `OPC_UA`。
2. 连接配置提供 `url`（opc.tcp）、安全策略等。
3. 点位配置 `nodeId` 或可解析的命名空间+标识。

# OPC DA

## 实现类

- `core/collector/protocol/opc/OpcDaCollector`
- 桥接接口：`core/collector/protocol/opc/da/OpcDaBridge`

## 实现方式

- 通过 bridge 抽象读写订阅。
- 当前默认实现是 `InMemoryOpcDaBridge`（内存模拟）。

## 地址与点位配置

- `DataPoint.address` 默认作为 OPC DA `itemId`。
- 也可由 `pointCode/pointName` 回退构建 itemId。

## 连接扩展参数（extJson）

- `serverProgId/progId/clsid`
- `requestTimeout`
- `updateRate`

## 使用方式

1. 设备 `protocolType` 设置 `OPC_DA`。
2. 配置服务器信息与 itemId。
3. 注意当前为桥接占位实现，需替换真实 DA 驱动才能用于生产采集。

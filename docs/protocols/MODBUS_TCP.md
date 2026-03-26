# MODBUS TCP

## 实现类

- `core/collector/protocol/modbus/ModbusTcpCollector`
- 基类：`core/collector/protocol/modbus/base/AbstractModbusCollector`

## 实现方式

- 通过 `ModbusTcpConnectionAdapter` 建立连接。
- 读取使用 ReadPlan 聚合（连续地址分段读取），减少请求次数。
- 批量写入会按地址连续性和协议上限自动分块。

## 地址与点位配置

- `DataPoint.address` 支持：`3x40001`、`3:40001`、`440001`。
- `unitId` 优先取点位 `unitId`，否则取连接配置 `slaveId`。

## 连接扩展参数（extJson）

- `slaveId`
- `byteOrder`（如 `BIG_ENDIAN`）
- `parity`

## 使用方式

1. 设备 `protocolType` 设置为 `MODBUS_TCP`。
2. 在连接配置提供 `host/port`。
3. 点位配置 `address + dataType + readWrite`。

# MODBUS RTU

## 实现类

- `core/collector/protocol/modbus/ModbusRtuCollector`
- 基类：`core/collector/protocol/modbus/base/AbstractModbusCollector`

## 实现方式

- 基于串口 RTU 连接适配器。
- 读写逻辑与 TCP 类似，也支持 ReadPlan 聚合读与批量分块写。
- 支持帧间隔控制（`interFrameDelay`）。

## 地址与点位配置

- `DataPoint.address` 与 TCP 一致（`3x40001`/`3:40001`/`440001`）。

## 连接扩展参数（extJson）

- `serialPort`、`baudRate`、`dataBits`、`stopBits`
- `parity`、`slaveId`、`byteOrder`
- `interFrameDelay`

## 使用方式

1. 设备 `protocolType` 设置为 `MODBUS_RTU`。
2. 配置串口参数和站号。
3. 配置点位地址与数据类型。

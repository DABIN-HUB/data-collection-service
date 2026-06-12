# MODBUS TCP

## 实现类

- `core/collector/protocol/modbus/Plc4xModbusTcpCollector`
- 基类：`core/collector/protocol/modbus/base/AbstractModbusCollector`

## 实现方式

- 通过 `Plc4xModbusTcpConnectionAdapter` 建立 PLC4X Modbus TCP 连接。
- 保留原有 ReadPlan 聚合读取、批量写入分块和后处理链路。
- 仅替换协议边缘的 Modbus wire client，不改调度、缓存、上报和实时流主链路。

## 地址与点位配置

- `DataPoint.address` 支持：`3x40001`、`3:40001`、`440001`。
- `unitId` 优先取点位 `unitId`，否则取连接配置 `slaveId`。

## 连接扩展参数（extJson）

- `slaveId`
- `byteOrder`（如 `BIG_ENDIAN`）
- `parity`
- `plc4xConnectionString`
- `pingAddress`
- `maxRegistersPerRequest`
- `maxCoilsPerRequest`

## 连接字段整理（createFieldConfig 写法）

说明：
- 以下字段按代码实际读取来源整理。
- `url` 不参与当前 `Plc4xModbusTcpCollector` 的字段读取，当前实现以 `host + port` 为主。

```java
fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "端口", true, "502", null));
fields.add(createFieldConfig("slaveId", "number", "从站ID", true, "1", null));
fields.add(createFieldConfig("byteOrder", "string", "字节顺序", true, "BIG_ENDIAN", new String[]{"BIG_ENDIAN", "LITTLE_ENDIAN"}));
fields.add(createFieldConfig("parity", "string", "兼容校验位字段", false, "none", new String[]{"none", "odd", "even"}));
fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X连接串覆盖", false, "", null));
fields.add(createFieldConfig("pingAddress", "string", "PLC4X Ping地址", false, "", null));
fields.add(createFieldConfig("maxRegistersPerRequest", "number", "单次最大寄存器数", false, "125", null));
fields.add(createFieldConfig("maxCoilsPerRequest", "number", "单次最大线圈数", false, "2000", null));
fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "3000", null));
fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "3000", null));
```

## 使用方式

1. 设备 `protocolType` 设置为 `MODBUS_TCP`。
2. 在连接配置提供 `host/port`。
3. 点位配置 `address + dataType + readWrite`。

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

## 连接字段整理（createFieldConfig 写法）

说明：
- 以下字段按代码实际读取来源整理。
- `url` 不参与当前 `ModbusTcpCollector` 的字段读取，当前实现以 `host + port` 为主。

```java
fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "端口", true, "502", null));
fields.add(createFieldConfig("slaveId", "number", "从站ID", true, "1", null));
fields.add(createFieldConfig("byteOrder", "string", "字节顺序", true, "BIG_ENDIAN", new String[]{"BIG_ENDIAN", "LITTLE_ENDIAN"}));
fields.add(createFieldConfig("parity", "string", "兼容校验位字段", false, "none", new String[]{"none", "odd", "even"}));
fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "3000", null));
fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "3000", null));
```

## 使用方式

1. 设备 `protocolType` 设置为 `MODBUS_TCP`。
2. 在连接配置提供 `host/port`。
3. 点位配置 `address + dataType + readWrite`。

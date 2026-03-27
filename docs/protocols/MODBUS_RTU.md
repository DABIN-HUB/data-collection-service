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

## 连接字段整理（createFieldConfig 写法）

```java
fields.add(createFieldConfig("slaveId", "number", "从站ID", true, "1", null));
fields.add(createFieldConfig("serialPort", "string", "串口", true, "COM1", null));
fields.add(createFieldConfig("baudRate", "number", "波特率", true, "9600", null));
fields.add(createFieldConfig("dataBits", "number", "数据位", true, "8", null));
fields.add(createFieldConfig("stopBits", "number", "停止位", true, "1", null));
fields.add(createFieldConfig("byteOrder", "string", "字节顺序", true, "BIG_ENDIAN", new String[]{"BIG_ENDIAN", "LITTLE_ENDIAN"}));
fields.add(createFieldConfig("interFrameDelay", "number", "帧间延时(ms)", true, "5", null));
fields.add(createFieldConfig("parity", "string", "校验位", true, "none", new String[]{"none", "odd", "even"}));
fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "3000", null));
fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "3000", null));
```

## 使用方式

1. 设备 `protocolType` 设置为 `MODBUS_RTU`。
2. 配置串口参数和站号。
3. 配置点位地址与数据类型。

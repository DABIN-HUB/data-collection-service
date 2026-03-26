# SNMP

## 实现类

- `core/collector/protocol/snmp/SnmpCollector`
- 基类：`core/collector/protocol/snmp/base/AbstractSnmpCollector`

## 实现方式

- 基于 SNMP4J。
- 支持 GET/SET/WALK。
- 订阅接口当前主要做点位登记（Trap/Inform 需扩展）。

## 地址与点位配置

- OID 可从 `DataPoint.address` 获取。
- 若 address 为空，可从 `additionalConfig.oid` 获取。
- 数据类型可来自 `additionalConfig.snmpType` 或 `dataType`。

## 连接扩展参数

- `community`
- `snmpVersion`
- `snmpRetries`

## 使用方式

1. 设备 `protocolType` 设为 `SNMP`。
2. 配置 host/port/community/version。
3. 点位配置 OID。

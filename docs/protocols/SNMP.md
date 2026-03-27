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
- `snmpSecurityLevel`（SNMPv3）
- `snmpSecurityName`
- `snmpAuthProtocol` / `snmpAuthPassword`
- `snmpPrivProtocol` / `snmpPrivPassword`
- `snmpContextName` / `snmpContextEngineId`

## 使用方式

1. 设备 `protocolType` 设为 `SNMP`。
2. 配置 host/port/community/version。
3. 点位配置 OID。

## SNMPv3 安全参数

- `snmpSecurityLevel`：`noAuthNoPriv`、`authNoPriv`、`authPriv`，默认 `authPriv`。
- `snmpSecurityName`：USM 用户名（必填）。
- `snmpAuthProtocol`：`MD5`、`SHA`、`SHA256`...；设置为 `NONE` 可关闭鉴权。
- `snmpAuthPassword`：鉴权口令（当 `securityLevel` 含 auth 时必填）。
- `snmpPrivProtocol`：`DES`、`AES128`、`AES192`、`AES256` 等；`NONE` 表示不加密。
- `snmpPrivPassword`：加密口令（`authPriv` 时必填）。
- `snmpContextName`：可选上下文名（PDU Scoped Context）。
- `snmpContextEngineId`：可选上下文 Engine ID，支持十六进制写法 `80:00:00:01:02:03:04`。

### 连接示例（application.yml）

```yaml
devices:
  pump-snmp:
    host: 10.0.0.10
    port: 161
    protocolType: SNMP
    connection:
      extJson:
        snmpVersion: "3"
        snmpSecurityLevel: authPriv
        snmpSecurityName: collectorUser
        snmpAuthProtocol: SHA256
        snmpAuthPassword: ${SNMP_AUTH_PASS}
        snmpPrivProtocol: AES256
        snmpPrivPassword: ${SNMP_PRIV_PASS}
        snmpContextName: pumpCtx
        snmpContextEngineId: 80:00:00:01:02:03:04
```

> 提示：SNMPv3 鉴权/加密口令应通过环境变量或远端配置中心加密下发，避免写入明文。

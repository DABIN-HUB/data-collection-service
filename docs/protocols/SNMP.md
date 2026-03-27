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

## 连接字段整理（createFieldConfig 写法）

```java
fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "端口", true, "161", null));
fields.add(createFieldConfig("community", "string", "团体字", true, "public", null));
fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "5000", null));
fields.add(createFieldConfig("snmpRetries", "number", "重试次数", false, "1", null));
fields.add(createFieldConfig("snmpVersion", "string", "SNMP版本", true, "2c", new String[]{"1", "2c", "3"}));
fields.add(createFieldConfig("snmpSecurityName", "string", "SNMPv3安全用户名", false, "", null));
fields.add(createFieldConfig("snmpSecurityLevel", "string", "SNMPv3安全级别", false, "authPriv", new String[]{"noAuthNoPriv", "authNoPriv", "authPriv"}));
fields.add(createFieldConfig("snmpAuthProtocol", "string", "SNMPv3认证协议", false, "SHA", new String[]{"MD5", "SHA", "SHA224", "SHA256", "SHA384", "SHA512", "NONE"}));
fields.add(createFieldConfig("snmpAuthPassword", "string", "SNMPv3认证密码", false, "", null));
fields.add(createFieldConfig("snmpPrivProtocol", "string", "SNMPv3加密协议", false, "AES128", new String[]{"DES", "AES128", "AES192", "AES256", "NONE"}));
fields.add(createFieldConfig("snmpPrivPassword", "string", "SNMPv3加密密码", false, "", null));
fields.add(createFieldConfig("snmpContextName", "string", "上下文名称", false, "", null));
fields.add(createFieldConfig("snmpContextEngineId", "string", "上下文引擎ID", false, "", null));
```

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

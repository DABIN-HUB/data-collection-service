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

## 连接字段整理（createFieldConfig 写法）

说明：
- `url`、`endpointUrl`、`endpoint`、`host + port` 都会参与端点解析，通常任选一种即可。
- 若启用安全通道或证书认证，还需要补充证书相关字段。

```java
fields.add(createFieldConfig("url", "string", "OPC UA端点地址", false, "opc.tcp://127.0.0.1:4840", null));
fields.add(createFieldConfig("endpointUrl", "string", "端点地址兼容字段", false, "opc.tcp://127.0.0.1:4840", null));
fields.add(createFieldConfig("endpoint", "string", "端点地址别名", false, "opc.tcp://127.0.0.1:4840", null));
fields.add(createFieldConfig("host", "string", "主机", false, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "端口", false, "4840", null));
fields.add(createFieldConfig("securityPolicy", "string", "安全策略", true, "None", new String[]{"None", "Basic128Rsa15", "Basic256", "Basic256Sha256", "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"}));
fields.add(createFieldConfig("securityMode", "string", "安全模式", true, "None", new String[]{"None", "Sign", "SignAndEncrypt"}));
fields.add(createFieldConfig("authType", "string", "认证方式", true, "ANONYMOUS", new String[]{"ANONYMOUS", "USERNAME", "CERT"}));
fields.add(createFieldConfig("username", "string", "用户名", false, "", null));
fields.add(createFieldConfig("password", "string", "密码", false, "", null));
fields.add(createFieldConfig("authParams", "object", "兼容认证参数", false, "{}", null));
fields.add(createFieldConfig("requestTimeoutMs", "number", "请求超时(ms)", false, "5000", null));
fields.add(createFieldConfig("requestTimeout", "number", "请求超时兼容字段(ms)", false, "5000", null));
fields.add(createFieldConfig("connectTimeoutMs", "number", "连接超时(ms)", false, "5000", null));
fields.add(createFieldConfig("connectTimeout", "number", "连接超时兼容字段(ms)", false, "5000", null));
fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "5000", null));
fields.add(createFieldConfig("subscriptionInterval", "number", "订阅发布间隔(ms)", false, "1000", null));
fields.add(createFieldConfig("namespaceUri", "string", "命名空间URI", false, "", null));
fields.add(createFieldConfig("clientCertPath", "string", "客户端证书路径", false, "", null));
fields.add(createFieldConfig("clientCertPassword", "string", "客户端证书密码", false, "", null));
fields.add(createFieldConfig("trustAllServerCert", "boolean", "是否信任所有服务端证书", false, "false", new String[]{"true", "false"}));
```

## 使用方式

1. 设备 `protocolType` 设置 `OPC_UA`。
2. 连接配置提供 `url`（opc.tcp）、安全策略等。
3. 点位配置 `nodeId` 或可解析的命名空间+标识。

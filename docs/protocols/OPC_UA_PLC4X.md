# OPC UA PLC4X

## 实现类

- `core/collector/protocol/opc/Plc4xOpcUaCollector`
- `core/connection/adapter/Plc4xOpcUaConnectionAdapter`
- `core/collector/protocol/opc/plc4x/domain/Plc4xOpcUaAddress`
- `core/collector/protocol/opc/plc4x/util/Plc4xOpcUaAddressParser`

## 实现方式

- 这是 `OPC_UA` 的并行验证路径，不替换当前基于 Milo 的生产实现。
- 基于 PLC4X `plc4j-driver-opcua`。
- 支持批量读、写；订阅入口和 `browse` 命令是否可用取决于 PLC4X 运行时 metadata 与真实服务器行为。
- 当前只面向标量点位；数组点位仍明确不支持。

## 地址与点位配置

- `DataPoint.address` 直接使用 OPC UA `NodeId`，例如 `ns=2;s=Channel1.Device1.Tag1`、`ns=3;i=1001`。
- 如果地址里没有显式数据类型，解析器会尝试根据 `DataPoint.dataType` 追加 PLC4X 数据类型后缀，例如 `;REAL`、`;BOOL`。
- 也可以继续用 `additionalConfig` 提供 `namespace`、`identifier`、`identifierType`。
- 订阅相关兼容字段仍支持：`samplingInterval`、`queueSize`、`deadband`、`subscribe`。

## 连接字段整理（createFieldConfig 写法）

说明：
- `url`、`endpointUrl`、`endpoint`、`host + port` 都可以用于解析目标端点。
- 若需要更细粒度控制，可直接使用 `plc4xConnectionString` 覆盖生成逻辑。
- `securityPolicy` 支持当前系统里常见的完整 URI 写法，适配层会在生成 PLC4X 连接串时归一化成 PLC4X 需要的枚举值。
- `securityMode`、`clientCertPath`、`clientCertPassword`、`authParams`、`requestTimeoutMs`、`connectTimeoutMs` 仍作为迁移兼容字段保留，最终都会映射到 PLC4X 原生参数。
- 证书相关字段优先走 PLC4X 原生 `keyStore/trustStore` 模型；`clientCertPath` / `clientCertPassword` 只作为兼容别名参与映射。
- 自动生成的 PLC4X 配置不支持 `trustAllServerCert=true`；如需放宽校验，必须显式提供 `trustStoreFile`，或者直接使用 `plc4xConnectionString`。

```java
fields.add(createFieldConfig("url", "string", "OPC UA端点地址", false, "opc.tcp://127.0.0.1:4840", null));
fields.add(createFieldConfig("endpointUrl", "string", "端点地址兼容字段", false, "opc.tcp://127.0.0.1:4840", null));
fields.add(createFieldConfig("endpoint", "string", "端点地址别名", false, "opc.tcp://127.0.0.1:4840", null));
fields.add(createFieldConfig("host", "string", "主机", false, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "端口", false, "4840", null));
fields.add(createFieldConfig("discovery", "boolean", "是否启用 discovery", false, "true", new String[]{"true", "false"}));
fields.add(createFieldConfig("authType", "string", "认证方式", false, "ANONYMOUS", new String[]{"ANONYMOUS", "USERNAME", "CERT"}));
fields.add(createFieldConfig("securityPolicy", "string", "安全策略", false, "NONE", new String[]{"NONE", "Basic128Rsa15", "Basic256", "Basic256Sha256", "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"}));
fields.add(createFieldConfig("messageSecurity", "string", "消息安全模式", false, "NONE", new String[]{"NONE", "SIGN", "SIGN_ENCRYPT"}));
fields.add(createFieldConfig("securityMode", "string", "消息安全模式兼容字段", false, "NONE", new String[]{"NONE", "Sign", "SignAndEncrypt"}));
fields.add(createFieldConfig("username", "string", "用户名", false, "", null));
fields.add(createFieldConfig("password", "string", "密码", false, "", null));
fields.add(createFieldConfig("authParams", "object", "认证参数兼容字段", false, "{}", null));
fields.add(createFieldConfig("keyStoreFile", "string", "客户端密钥库文件", false, "", null));
fields.add(createFieldConfig("keyStoreType", "string", "客户端密钥库类型", false, "pkcs12", null));
fields.add(createFieldConfig("keyStorePassword", "string", "客户端密钥库密码", false, "", null));
fields.add(createFieldConfig("clientCertPath", "string", "客户端证书兼容字段", false, "", null));
fields.add(createFieldConfig("clientCertPassword", "string", "客户端证书密码兼容字段", false, "", null));
fields.add(createFieldConfig("trustStoreFile", "string", "信任库文件", false, "", null));
fields.add(createFieldConfig("trustStoreType", "string", "信任库类型", false, "pkcs12", null));
fields.add(createFieldConfig("trustStorePassword", "string", "信任库密码", false, "", null));
fields.add(createFieldConfig("serverCertificateFile", "string", "服务端证书文件", false, "", null));
fields.add(createFieldConfig("endpointHost", "string", "端点主机覆盖", false, "", null));
fields.add(createFieldConfig("endpointPort", "number", "端点端口覆盖", false, "", null));
fields.add(createFieldConfig("channelLifetime", "number", "安全通道生命周期(ms)", false, "3600000", null));
fields.add(createFieldConfig("sessionTimeout", "number", "会话超时(ms)", false, "120000", null));
fields.add(createFieldConfig("negotiationTimeout", "number", "握手超时(ms)", false, "60000", null));
fields.add(createFieldConfig("connectTimeoutMs", "number", "连接超时兼容字段(ms)", false, "60000", null));
fields.add(createFieldConfig("connectTimeout", "number", "连接超时兼容字段(ms)", false, "60000", null));
fields.add(createFieldConfig("requestTimeout", "number", "请求超时(ms)", false, "30000", null));
fields.add(createFieldConfig("requestTimeoutMs", "number", "请求超时兼容字段(ms)", false, "30000", null));
fields.add(createFieldConfig("subscriptionInterval", "number", "订阅周期(ms)", false, "1000", null));
fields.add(createFieldConfig("maxFieldsPerRequest", "number", "单次最大点位数", false, "100", null));
fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X连接串覆盖", false, "", null));
```

## 使用方式

1. 设备 `protocolType` 设置为 `OPC_UA_PLC4X`。
2. 连接配置使用 PLC4X 对应字段，特别是安全通道场景下的 `keyStoreFile` / `trustStoreFile`。
3. 点位地址保持 OPC UA `NodeId` 风格。
4. 仓库内提供了联调模板 [opcuaPlc4xDevice.json](../../src/main/resources/mock/opcuaPlc4xDevice.json)，可直接替换 `endpointUrl` 和安全参数后接真实服务器验证。

## 当前边界

1. 当前生产路由仍然是 `OPC_UA` -> Milo；`OPC_UA_PLC4X` 只是并行验证入口。
2. 仓库内的本地嵌入式 Milo 联调已经验证匿名连接读写，以及 `securityMode` / `requestTimeoutMs` / `connectTimeoutMs` 等兼容别名的建连映射。
3. `browse` 入口受 PLC4X runtime metadata 限制；本地嵌入式联调里当前返回 unsupported，不能直接视为可替代 Milo browse。
4. 周期订阅目前只确认了 collector 侧注册链路；仓库内尚未在嵌入式服务器上稳定复现端到端订阅值回推。
5. 当前 validator 会拒绝 `trustAllServerCert=true` 的自动生成配置，避免把 Milo 时代的“全信任”语义误透传到 PLC4X。
6. 本地嵌入式联调模板的 endpoint path 统一使用 `_`；当前 PLC4X OPC UA 驱动对包含 `-` 的 path 仍需要继续谨慎验证。
7. 真实服务器端到端验证仍未在仓库里落地，尤其是 `browse`、订阅值回推和 X509 场景。

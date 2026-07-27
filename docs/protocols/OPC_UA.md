# OPC UA

## 当前实现

- 生产 `OPC_UA` 路由已经切到 PLC4X。
- 兼容协议别名 `OPCUA`。
- Milo 实现已通过独立实验协议 `OPC_UA_MILO` 接入，默认 `OPC_UA` 路由仍不改变。

核心类：

- `core/collector/protocol/opc/Plc4xOpcUaCollector`
- `core/connection/adapter/Plc4xOpcUaConnectionAdapter`
- `core/collector/protocol/opc/plc4x/domain/Plc4xOpcUaAddress`
- `core/collector/protocol/opc/plc4x/util/Plc4xOpcUaAddressParser`

## 能力边界

- 支持 connect / read / write。
- 支持 collector 侧订阅注册，但不同服务器上的值回推能力仍需逐台验证。
- `browse` 是否可用取决于 PLC4X runtime metadata 和目标服务器行为，不能默认视为可用。
- 支持标量和一维同构数组轮询读写；数组订阅仍然拒绝。
- `OPC_UA_MILO` 尚未完成与 PLC4X 的同服契约测试，不能替换生产默认驱动。

## 点位配置

- `DataPoint.address` 直接使用 OPC UA `NodeId`，例如 `ns=2;s=Channel1.Device1.Tag1`、`ns=3;i=1001`。
- 若地址里未显式带 PLC4X 数据类型后缀，解析器会结合 `DataPoint.dataType` 尝试补齐，例如 `;REAL`、`;BOOL`。
- 兼容 `additionalConfig` 中的 `namespace`、`identifier`、`identifierType`。
- 订阅兼容字段仍可使用：`samplingInterval`、`queueSize`、`deadband`、`subscribe`。
- 数组节点通过 `additionalConfig.arraySize` 声明元素数量，读写数量不一致时直接失败。

## 连接字段

主字段分组如下，完整列表以 `ProtocolSchemaService` 和 `docs/protocols/FIELD_CONFIG_SUMMARY.md` 为准。

- 端点：`url`、`endpointUrl`、`endpoint`、`host`、`port`
- 连接策略：`discovery`、`plc4xConnectionString`
- 认证：`authType`、`username`、`password`、`authParams`
- 安全：`securityPolicy`、`messageSecurity`、`securityMode`
- 证书：`keyStoreFile`、`keyStorePassword`、`trustStoreFile`、`trustStorePassword`
- 兼容别名：`clientCertPath`、`clientCertPassword`、`requestTimeoutMs`、`connectTimeoutMs`

注意：

- 自动生成的 PLC4X 配置不支持 `trustAllServerCert=true`。
- 如果需要放宽服务端证书校验，使用 `trustStoreFile`，或者直接提供 `plc4xConnectionString`。

## 使用方式

1. 设备 `protocolType` 设置为 `OPC_UA`。
2. 连接配置提供端点和认证参数。
3. 点位地址保持 OPC UA `NodeId` 风格。
4. 只有明确联调 Milo 时才把 `protocolType` 设置为 `OPC_UA_MILO`；同一设备不能混用两种驱动。

## 当前验证结论

- 本地嵌入式服务器已验证匿名 connect/read/write。
- 本机 Prosys Simulation Server 已验证 `opc.tcp://DESKTOP-IKHU04D:53530/OPCUA/SimulationServer` 可读。
- `ns=3;i=1030` 已实测可写。
- 当前实服上 `browse` 仍返回 unsupported。
- 当前实服上订阅注册成功，但值回推尚未证实。

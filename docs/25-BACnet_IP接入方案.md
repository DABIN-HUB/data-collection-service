# BACnet/IP 接入方案

## 1. 目标与约束

本方案用于把 `BACnet/IP` 接入当前 `data-collection-service`，并且必须完全遵循现有统一采集框架：

1. 设备启动仍由 `CollectionService -> CollectionScheduler -> CollectionManager` 驱动。
2. 协议实例仍由 `CollectorFactory` 按 `protocolType` 创建。
3. 协议连接仍由 `ConnectionFactory` 和 `AbstractConnectionAdapter` 体系统一管理。
4. 采集结果仍由 `BaseCollector` 完成数据转换、质量处理、`ProcessResult` 生成。
5. 缓存、上报、Redis Stream、历史存储仍复用 `CollectorDataPostProcessor` / `TelemetryIngressService` 现有链路。
6. 不允许为 `BACnet/IP` 单独分叉一套采集、处理、上报逻辑。

本方案只讨论“如何按当前框架正确接入”，不建议在 P0 阶段做脱离框架的快捷实现。

## 2. 范围分期

### 2.1 P0 范围

P0 只做“可工程化上线的基础 BACnet/IP 采集”：

1. 单设备 `BACnet/IP` 连接与重连。
2. 单点读：`ReadProperty`。
3. 批量读：`ReadPropertyMultiple`，必要时回退到逐点读。
4. 单点写：`WriteProperty`。
5. 批量写：框架接口支持，但 P0 可以先按逐点串行写实现，保证行为正确。
6. 支持常见对象：
   - `analogInput`
   - `analogOutput`
   - `analogValue`
   - `binaryInput`
   - `binaryOutput`
   - `binaryValue`
   - `multiStateInput`
   - `multiStateOutput`
   - `multiStateValue`
   - `device`
7. 支持常见属性：
   - `presentValue`
   - `objectName`
   - `description`
   - `units`
   - `statusFlags`
   - `outOfService`
   - `reliability`
8. 支持常见平台数据类型：
   - `BOOLEAN`
   - `INT` / `INT16` / `INT32`
   - `LONG` / `INT64`
   - `FLOAT` / `FLOAT32`
   - `DOUBLE` / `FLOAT64`
   - `STRING`

### 2.2 P1 范围

P1 再补：

1. `SubscribeCOV` / `SubscribeCOVProperty` 订阅。
2. `Who-Is / I-Am` 设备发现和诊断命令。
3. `WritePropertyMultiple`。
4. `BBMD / Foreign Device Registration`。
5. 更完整的枚举、位串、日期时间、数组值转换。

### 2.3 不建议在 P0 承诺的内容

1. 复杂楼宇系统跨子网广播发现。
2. `BBMD` 联动和外部路由链路稳定性。
3. 厂家私有对象 / 私有属性的完整兼容。
4. 大规模 `COV` 订阅风暴和广播网络治理。
5. `BACnet/SC`。

## 3. ProtocolType

### 3.1 需要新增的协议类型

在 [ProtocolType.java](/F:/ideaWorkSpace/data-collection-service/src/main/java/com/wangbin/collector/common/domain/enums/ProtocolType.java:6) 新增：

```java
BACNET_IP("BACNET_IP", "BACnet/IP", 47808)
```

同时补齐：

1. `isTcpProtocol()` 不要把 `BACNET_IP` 归到 TCP。
2. 如有 `isUdpProtocol()` 或类似判断，需把 `BACNET_IP` 纳入。
3. `getDefaultTimeout()` 为 `BACNET_IP` 指定默认超时，建议 `5000ms` 起步。

### 3.2 建议的别名

在协议 descriptor 层允许以下别名映射到主协议：

1. `BACNET`
2. `BACNETIP`
3. `BACNET/IP`

统一规范名仍是 `BACNET_IP`。

## 4. Descriptor

### 4.1 注册入口

在 [ProtocolDescriptorRegistry.java](/F:/ideaWorkSpace/data-collection-service/src/main/java/com/wangbin/collector/core/config/protocol/ProtocolDescriptorRegistry.java:35) 注册主 descriptor。

推荐定义：

1. `code`: `BACNET_IP`
2. `name`: `BACnet/IP`
3. `collectorClass`: `BacnetIpCollector.class`
4. `connectionType`: `BACNET_IP`
5. `defaultPort`: `47808`
6. `addressingMode`: `SYMBOLIC` 或 `MIXED`

建议使用 `MIXED`，因为地址主体是符号结构，但可兼容数字属性 ID、数组索引和实例号。

### 4.2 descriptor 示例字段

协议连接字段必须通过 `/api/protocols/BACNET_IP/fields` 暴露给前端，不允许页面硬编码一套单独表单。

建议字段如下：

#### connection

1. `host`
   目标设备 IP。
2. `port`
   默认 `47808`。
3. `localBindHost`
   本地监听地址，默认 `0.0.0.0`。
4. `localBindPort`
   本地 UDP 端口；若启用订阅，建议显式配置。

#### protocol

1. `remoteDeviceInstance`
   目标 BACnet 设备实例号。
2. `localDeviceInstance`
   本地 collector 作为 BACnet client 的实例号。
3. `useWhoIsDiscovery`
   是否在连接阶段先做 `Who-Is / I-Am` 发现。
4. `networkNumber`
   预留给 routed network / BBMD 场景。
5. `macAddress`
   预留给非直连寻址场景。

#### advanced

1. `apduTimeout`
2. `segmentTimeout`
3. `retries`
4. `maxPropertiesPerRequest`
5. `readPropertyMultipleEnabled`
6. `writePropertyMultipleEnabled`
7. `bbmdHost`
8. `bbmdPort`
9. `foreignDeviceTtlSeconds`
10. `readTimeout`
11. `timeout`

#### subscription

1. `covEnabled`
2. `defaultCovLifetimeSeconds`
3. `defaultCovIncrement`
4. `resubscribeOnReconnect`

### 4.3 文档与字段总表

接入时同步更新：

1. `docs/protocols/FIELD_CONFIG_SUMMARY.md`
2. `docs/02-采集协议支持与实现方式.md`
3. 新增协议文档 `docs/protocols/BACNET_IP.md`

## 5. Collector

### 5.1 类设计

新增：

1. `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`
2. `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/base/AbstractBacnetCollector.java`
3. `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/domain/*`
4. `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/util/*`
5. `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/plan/*`

建议 `BacnetIpCollector` 继承 `BaseCollector`，不要绕过现有基类。

### 5.2 collector 职责边界

`BacnetIpCollector` 只负责：

1. 地址解析。
2. 协议读写命令组装。
3. 协议返回值转换为“原始 Java 值”。
4. 订阅事件接入 `ingestPushedValue(...)`。
5. 协议专项状态、诊断、统计。

`BacnetIpCollector` 不负责：

1. 缓存写入。
2. Redis Stream 写入。
3. 上报聚合。
4. 历史存储。
5. 通用数据质量规则。

这些必须继续交给 `BaseCollector` 和后置链路处理。

### 5.3 关键方法实现要求

需要实现：

1. `doConnect()`
2. `doDisconnect()`
3. `doReadPoint(DataPoint point)`
4. `doReadPoints(List<DataPoint> points)`
5. `doWritePoint(DataPoint point, Object value)`
6. `doWritePoints(Map<DataPoint, Object> points)`
7. `doSubscribe(List<DataPoint> points)`
8. `doUnsubscribe(List<DataPoint> points)`
9. `doGetDeviceStatus()`
10. `doExecuteCommand(...)`
11. `buildReadPlans(String deviceId, List<DataPoint> points)`

### 5.4 读写处理原则

1. `doReadPoint` 返回协议原始值，如 `Boolean`、`Long`、`Double`、`String`。
2. 不在 `doReadPoint` 里直接做平台质量处理。
3. 不在 `doReadPoints` 里直接写缓存。
4. `BaseCollector.readPoint/readPoints` 继续统一调用：
   - `convertData(...)`
   - `dataQualityProcessor.process(...)`
   - `lastProcessResults.put(...)`
5. 推送型数据必须走 `ingestPushedValue(point, rawValue)`，这样才能进入：
   - `TelemetryIngressService`
   - `CollectorDataPostProcessor`
   - 缓存
   - 上报
   - Redis Stream
   - 历史存储

## 6. Connection

### 6.1 连接类

新增：

1. `src/main/java/com/wangbin/collector/core/connection/adapter/BacnetIpConnectionAdapter.java`

并在 [ConnectionFactory.java](/F:/ideaWorkSpace/data-collection-service/src/main/java/com/wangbin/collector/core/connection/factory/ConnectionFactory.java:1) 新增：

```java
case "BACNET_IP" -> createBacnetIpConnection(deviceInfo, cfg);
```

### 6.2 连接模型要求

`BacnetIpConnectionAdapter` 必须继承 `AbstractConnectionAdapter`，复用：

1. 连接状态管理
2. 重连退避
3. 统计指标
4. 错误计数
5. `reconnect()` 生命周期

不要在 collector 内自己手写一套与框架脱节的重连逻辑。

### 6.3 adapter 职责

adapter 负责：

1. 初始化底层 BACnet client。
2. 本地 UDP 绑定。
3. 远端设备对象缓存。
4. 请求串行化或限流分发。
5. 订阅通知监听注册。
6. `Who-Is / I-Am`、`ReadProperty`、`ReadPropertyMultiple`、`WriteProperty` 的统一发送入口。

### 6.4 validator

在 `ProtocolConnectionValidator` 新增 `validateBacnetIp(...)`，至少校验：

1. `host` 非空。
2. `port` 合法，默认 `47808`。
3. `remoteDeviceInstance` 为正整数。
4. 若启用 `covEnabled=true`，则 `localBindPort` 不能缺失或冲突风险需给出提示。
5. 若配置 `bbmdHost`，必须同时校验 `bbmdPort` 和 `foreignDeviceTtlSeconds`。

### 6.5 状态与诊断

`connection_info` / `status` / `diagnostic` 至少输出：

1. 目标 `host:port`
2. `remoteDeviceInstance`
3. 本地绑定地址
4. 是否启用 `Who-Is` 发现
5. 最近 APDU 错误
6. 最近重连错误
7. 当前活动订阅数
8. 最近一次成功读写时间

## 7. 地址模型

### 7.1 地址表达式

建议统一地址格式：

```text
<objectType>:<instance>.<property>
```

示例：

1. `analogInput:1.presentValue`
2. `analogOutput:5.presentValue`
3. `binaryInput:3.presentValue`
4. `multiStateValue:7.presentValue`
5. `device:1001.objectName`
6. `analogInput:1.units`

带数组索引时：

```text
<objectType>:<instance>.<property>[<index>]
```

示例：

1. `trendLog:1.logBuffer[0]`
2. `device:1001.objectList[12]`

### 7.2 domain 对象

新增：

1. `BacnetAddress`
   - `objectType`
   - `instanceNumber`
   - `propertyIdentifier`
   - `arrayIndex`
   - `driverDataType`
2. `BacnetDriverType`
3. `BacnetObjectTypeSupport`

### 7.3 解析器

新增：

1. `BacnetAddressParser`

解析规则：

1. 先读 `DataPoint.address`。
2. 若 `additionalConfig.driverDataType` 存在，则优先作为协议原生类型提示。
3. 允许属性名和属性 ID 两种方式。
4. 非法地址在启动前或第一次读写时给出显式错误，不允许 silent fallback。

### 7.4 点位 additionalConfig 建议

1. `driverDataType`
2. `writePriority`
3. `covMode`
4. `covIncrement`
5. `arrayIndex`
6. `enumMapping`
7. `stringEncoding`

## 8. 读写能力

### 8.1 单点读

使用 `ReadProperty`。

处理流程：

1. `BacnetAddressParser.parse(point)`
2. `connectionAdapter.readProperty(...)`
3. `BacnetValueCodec` 转为原始 Java 值
4. 返回给 `BaseCollector.readPoint(...)`

### 8.2 批量读

P0 推荐优先支持 `ReadPropertyMultiple`。

实现要求：

1. `buildReadPlans(...)` 将点位按兼容性分组。
2. 同一设备内按 `maxPropertiesPerRequest` 切分。
3. `ReadPropertyMultiple` 失败时允许自动回退到逐点 `ReadProperty`。
4. 部分点失败不能拖垮整批，结果必须按点位回填。

建议新增：

1. `BacnetReadPlan`
2. `BacnetReadPlanItem`
3. `BacnetReadPlanBuilder`

### 8.3 单点写

使用 `WriteProperty`。

要求：

1. 写前仍走 `BaseCollector.writePoint(...)` 的质量校验。
2. 支持 `presentValue` 常见写入。
3. 若配置 `additionalConfig.writePriority`，写请求应带优先级。
4. 对只读属性必须返回显式错误。

### 8.4 批量写

P0 可先实现为逐点串行写，保证行为正确。

P1 再评估 `WritePropertyMultiple`。

### 8.5 订阅能力

P1 才建议正式承诺 `COV` 订阅。

设计要求现在就要预留好：

1. `doSubscribe(...)` 负责建立 `SubscribeCOV` / `SubscribeCOVProperty`。
2. 收到通知后调用 `ingestPushedValue(point, rawValue)`。
3. `doUnsubscribe(...)` 负责取消订阅并清理本地映射。
4. 重连后若 `resubscribeOnReconnect=true`，应自动补订阅。

### 8.6 命令能力

建议 `doExecuteCommand(...)` 支持：

1. `who_is`
2. `read_property`
3. `read_property_multiple`
4. `write_property`
5. `discover_objects`
6. `diagnostic`

## 9. 前端字段

### 9.1 协议连接页

控制台前端必须继续走：

1. `GET /api/protocols`
2. `GET /api/protocols/BACNET_IP`
3. `GET /api/protocols/BACNET_IP/fields`

也就是说，前端不能单独为 `BACnet/IP` 手写一套脱离 descriptor 的字段。

### 9.2 连接字段建议分组

#### connection

1. `host`
2. `port`
3. `localBindHost`
4. `localBindPort`

#### protocol

1. `remoteDeviceInstance`
2. `localDeviceInstance`
3. `useWhoIsDiscovery`
4. `networkNumber`
5. `macAddress`

#### subscription

1. `covEnabled`
2. `defaultCovLifetimeSeconds`
3. `defaultCovIncrement`
4. `resubscribeOnReconnect`

#### advanced

1. `apduTimeout`
2. `segmentTimeout`
3. `retries`
4. `maxPropertiesPerRequest`
5. `readPropertyMultipleEnabled`
6. `writePropertyMultipleEnabled`
7. `bbmdHost`
8. `bbmdPort`
9. `foreignDeviceTtlSeconds`
10. `readTimeout`
11. `timeout`

### 9.3 点位配置页

点位侧继续使用平台通用字段：

1. `address`
2. `dataType`
3. `readWrite`
4. `enabled`

并补 BACnet 相关 `additionalConfig`：

1. `driverDataType`
2. `writePriority`
3. `covMode`
4. `covIncrement`
5. `arrayIndex`

前端如果已有动态点位字段机制，则应复用该机制。
如果当前只支持固定点位表单，也必须至少把这些 BACnet 参数纳入可视化编辑，而不是要求用户手工改 JSON。

## 10. 测试清单

## 10.1 单元测试

至少新增：

1. `ProtocolConnectionValidatorTest`
   - `validateBacnetIp()` 正常与异常分支。
2. `BacnetAddressParserTest`
   - 对象类型、实例、属性、数组索引、非法地址。
3. `BacnetValueCodecTest`
   - `BOOLEAN` / `FLOAT64` / `ENUM` / `STRING` 转换。
4. `BacnetIpConnectionAdapterTest`
   - 连接、断开、重连、超时、订阅重建。
5. `BacnetIpCollectorTest`
   - 单点读、批量读、写入、逐点 fallback、状态输出。
6. `ProtocolSchemaService` 或 descriptor 相关测试
   - `BACNET_IP` 字段对外暴露正确。

## 10.2 集成测试

至少新增：

1. 假设备或模拟 server 的单点读集成测试。
2. `ReadPropertyMultiple` 批量读集成测试。
3. 单点 `WriteProperty` 集成测试。
4. 批量写逐点 fallback 集成测试。
5. 连接断开后的 `reconnect()` 最小复现场景测试。
6. `COV` 推送进入 `ingestPushedValue(...)` 的集成测试。

建议新增：

1. `FakeBacnetServer`
2. `BacnetIpCollectorIntegrationTest`

## 10.3 框架链路验证

必须验证 `BACnet/IP` 没有绕开框架：

1. `readPoint/readPoints` 后，`lastProcessResults` 正常落值。
2. 推送型数据经 `TelemetryIngressService` 进入缓存/上报/实时流。
3. `CollectionScheduler -> CollectionManager -> BacnetIpCollector` 主链路可跑通。
4. `status / statistics / diagnostic` 能从监控接口读到。

## 10.4 实机验证清单

现场联调至少覆盖：

1. 常见楼控控制器单点读。
2. `ReadPropertyMultiple` 批量读。
3. `analogOutput.presentValue` 写入。
4. `binaryOutput.presentValue` 写入。
5. 断网重连后继续采集。
6. 同网段广播发现。
7. 如承诺 `COV`，则验证订阅、续租、断线恢复。

## 11. 推荐实施顺序

1. 新增 `ProtocolType`、descriptor、`ConnectionFactory`、validator。
2. 完成 `BacnetIpConnectionAdapter` 基础连接能力。
3. 完成 `BacnetAddress` / `BacnetAddressParser` / `BacnetValueCodec`。
4. 完成 `BacnetIpCollector` 的单点读写。
5. 完成 `ReadPropertyMultiple` 批量读和读计划。
6. 接前端字段 schema。
7. 补单元测试和模拟集成测试。
8. 最后再补 `COV`、`Who-Is`、`BBMD` 等增强项。

## 12. 交付口径建议

当前建议的对外口径：

1. `BACnet/IP` 可以作为“下一阶段重点新增协议”推进。
2. P0 先承诺“固定设备实例、固定点表、轮询读写”。
3. 不要在第一版就对外承诺“全网自动发现 + COV + BBMD + 私有对象全兼容”。
4. 任何 `BACnet/IP` 的缓存、上报、实时流行为都必须沿用现有统一链路，不做协议特例。

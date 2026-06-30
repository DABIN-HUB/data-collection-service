# BACNET_IP

## 当前状态

`BACNET_IP` 已完成框架接入，并且已经不是空壳协议。

当前已经落地：

- `ProtocolType`、协议别名归一化、descriptor、collector factory、connection factory、validator 已接入。
- 控制台前端可通过 `/api/protocols/BACNET_IP` 和 `/api/protocols/BACNET_IP/fields` 获取 BACnet/IP 配置字段。
- 已完成真实 `UDP` 连接适配器，不再是占位对象。
- 已完成真实读链路：
  - `ReadProperty`
  - `ReadPropertyMultiple`
  - 单点读 `readPoint`
  - 批量读 `readPoints`
  - `ReadPropertyMultiple` 失败自动回退逐点 `ReadProperty`
- 已完成基础 primitive 类型支持：
  - `REAL`
  - `DOUBLE`
  - `BOOLEAN`
  - `STRING`
  - `ENUMERATED`
  - `UNSIGNED / SIGNED`
- 已完成 `useWhoIsDiscovery=true` 时的连接阶段 `Who-Is / I-Am` 发现。
- 已完成假设备集成测试，不再只有 schema/骨架级测试。

当前仍未完成：

- `WriteProperty`
- `WritePropertyMultiple`
- `SubscribeCOV / SubscribeCOVProperty`
- `executeCommand`
- `BBMD / Foreign Device Registration`
- 分段报文
- constructed / array / sequence 类型的通用 `ANY` 解码

当前对外交付口径：

- `BACnet/IP P0` 已可用于 `UDP` 轮询型 `ReadProperty / ReadPropertyMultiple` 采集。
- 不建议现场承诺写入、`COV`、`BBMD`、跨子网自动发现、复杂数组/对象属性。

## 已完成能力

### 1. 真实连接

- `BacnetIpConnectionAdapter` 已完成真实 `UDP socket` 建连。
- 当前生效的连接字段：
  - `host`
  - `port`
  - `localBindHost`
  - `localBindPort`
  - `remoteDeviceInstance`
  - `useWhoIsDiscovery`
  - `readTimeout`
  - `timeout`
- `useWhoIsDiscovery=true` 时，连接阶段会先发 `Who-Is`，要求收到目标 `remoteDeviceInstance` 的 `I-Am` 后才认为发现成功。

### 2. 真实读链路

- `BacnetIpCollector` 已接入：
  - `readPoint`
  - `readPoints`
- 当前已支持：
  - `Confirmed ReadProperty`
  - `Confirmed ReadPropertyMultiple`
  - `ComplexACK(ReadPropertyAck)`
  - `ComplexACK(ReadPropertyMultipleAck)`
- `ReadPropertyMultiple` 当前按“同对象多属性合并 + `maxPropertiesPerRequest` 切分”执行。
- `ReadPropertyMultiple` 被设备拒绝或失败时，会自动回退到逐点 `ReadProperty`，避免整批点位不可用。
- 超时、socket 异常、协议头异常会触发连接失效判定，collector 状态会打成 `DISCONNECTED`。

### 3. 数据处理边界

- BACnet 字符串属性不会再误走数值转换。
- 当前已对以下类型做安全处理：
  - 数值型走 `convertData(...)`
  - `STRING` 透传
  - `BOOLEAN` 返回布尔
  - `ENUMERATED` 返回整数
- 结果仍统一进入：
  - `ProcessResult`
  - `DataQualityProcessor`
  - `lastProcessResults`

## 未完成功能清单

### 高优先级

1. `WriteProperty`
   - 前端字段里已有 `writePriority` 等预留，但底层未兑现。
   - 不建议现场承诺可控写回。
2. `executeCommand`
   - 还未实现 `who_is / read_property / read_property_multiple / discover_objects / diagnostic` 这类诊断命令入口。

### 中优先级

3. `SubscribeCOV / SubscribeCOVProperty`
   - 当前 `covEnabled` 只是 schema 和 validator 已接入。
   - 还没有真实订阅、通知接收、断线补订阅。
4. constructed / array / sequence 类型解码
   - 当前只支持 primitive `ANY`。
   - 像 `objectList`、`priorityArray`、复杂对象属性还不能承诺。
5. `ReadPropertyMultiple` 读计划优化
   - 当前已做同对象聚合、批量切分、失败回退。
   - 还没有做更强的跨对象聚合和动态性能优化。

### 低优先级但高现场风险

6. `BBMD / Foreign Device Registration`
   - 字段已预留：
     - `bbmdHost`
     - `bbmdPort`
     - `foreignDeviceTtlSeconds`
   - 代码未实现，不建议现场承诺跨子网 BACnet/IP。
7. 分段报文
   - 当前 `ComplexACK` 仅支持非分段。
   - 大对象、大数组、大响应场景可能失败。
8. 更完整的发现能力
   - 当前 `Who-Is / I-Am` 只做了“按目标实例号发现单设备”。
   - 还没有全网扫描、对象枚举、发现缓存治理。

## 当前可交付范围

可以交付：

- `BACnet/IP UDP`
- 指定 `host:port + remoteDeviceInstance`
- 可选 `Who-Is / I-Am` 发现
- `ReadProperty`
- `ReadPropertyMultiple`
- 单点读
- 批量读
- `ReadPropertyMultiple` 失败自动回退
- 基础 primitive 值类型读取

需实机验证后再对外说稳：

- 不同厂商设备的 `I-Am` 兼容性
- `objectName / presentValue / units / reliability` 等跨厂商属性差异
- 高频轮询下的响应时间和丢包容忍度
- 单个设备对 `ReadPropertyMultiple` 的兼容程度和单报文属性数上限

不建议现场承诺：

- `WriteProperty`
- `WritePropertyMultiple`
- `COV`
- `BBMD`
- `Foreign Device`
- 跨网段自动发现
- 复杂数组 / sequence / proprietary object

## 支持的地址格式

当前解析器支持：

1. `analogInput:1.presentValue`
2. `analogValue:12.presentValue`
3. `binaryOutput:3.presentValue`
4. `device:1001.objectName`
5. `analogInput:7.priorityArray[5]`

规则：

- 标准格式：`<objectType>:<instance>.<property>[<index>]`
- `instance >= 0`
- `[index]` 可选
- 若地址中未写 `[index]`，可由 `additionalConfig.arrayIndex` 提供
- `driverDataType` 可由以下字段提示：
  - `additionalConfig.driverDataType`
  - `additionalConfig.bacnetType`
  - `additionalConfig.propertyType`

## 连接字段

```java
fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "UDP端口", false, "47808", null));
fields.add(createFieldConfig("localBindHost", "string", "本地绑定IP", false, "", null));
fields.add(createFieldConfig("localBindPort", "number", "本地绑定端口", false, "", null));
fields.add(createFieldConfig("remoteDeviceInstance", "number", "目标设备实例号", true, "", null));
fields.add(createFieldConfig("localDeviceInstance", "number", "本地客户端实例号", false, "", null));
fields.add(createFieldConfig("useWhoIsDiscovery", "boolean", "启用 Who-Is/I-Am 发现", false, "false", new String[]{"true", "false"}));
fields.add(createFieldConfig("networkNumber", "number", "BACnet 网络号", false, "", null));
fields.add(createFieldConfig("macAddress", "string", "远端 MAC 地址", false, "", null));
fields.add(createFieldConfig("covEnabled", "boolean", "启用 COV 订阅", false, "false", new String[]{"true", "false"}));
fields.add(createFieldConfig("defaultCovLifetimeSeconds", "number", "默认 COV 生命周期(s)", false, "300", null));
fields.add(createFieldConfig("defaultCovIncrement", "number", "默认 COV 增量阈值", false, "", null));
fields.add(createFieldConfig("resubscribeOnReconnect", "boolean", "重连后自动补订阅", false, "true", new String[]{"true", "false"}));
fields.add(createFieldConfig("apduTimeout", "number", "APDU 超时(ms)", false, "5000", null));
fields.add(createFieldConfig("segmentTimeout", "number", "分段超时(ms)", false, "3000", null));
fields.add(createFieldConfig("retries", "number", "重试次数", false, "1", null));
fields.add(createFieldConfig("maxPropertiesPerRequest", "number", "单次最大属性数", false, "32", null));
fields.add(createFieldConfig("readPropertyMultipleEnabled", "boolean", "启用 ReadPropertyMultiple", false, "true", new String[]{"true", "false"}));
fields.add(createFieldConfig("writePropertyMultipleEnabled", "boolean", "启用 WritePropertyMultiple", false, "false", new String[]{"true", "false"}));
fields.add(createFieldConfig("bbmdHost", "string", "BBMD 地址", false, "", null));
fields.add(createFieldConfig("bbmdPort", "number", "BBMD 端口", false, "47808", null));
fields.add(createFieldConfig("foreignDeviceTtlSeconds", "number", "Foreign Device TTL(s)", false, "", null));
fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "5000", null));
```

说明：

- 这些字段并不代表当前都已经实现。
- 当前真正已生效的重点字段是：
  - `host`
  - `port`
  - `localBindHost`
  - `localBindPort`
  - `remoteDeviceInstance`
  - `useWhoIsDiscovery`
  - `maxPropertiesPerRequest`
  - `readPropertyMultipleEnabled`
  - `readTimeout`
  - `timeout`

## 点位 AdditionalConfig

```java
fields.add(createFieldConfig("additionalConfig.driverDataType", "string", "驱动原生类型", false, "AUTO",
        new String[]{"AUTO", "BOOLEAN", "UNSIGNED", "SIGNED", "REAL", "DOUBLE", "ENUM", "STRING", "BIT_STRING"}));
fields.add(createFieldConfig("additionalConfig.arrayIndex", "number", "属性数组下标", false, "", null));
fields.add(createFieldConfig("additionalConfig.writePriority", "number", "写优先级", false, "", null));
fields.add(createFieldConfig("additionalConfig.covMode", "string", "COV 模式", false, "OBJECT",
        new String[]{"OBJECT", "PROPERTY"}));
fields.add(createFieldConfig("additionalConfig.covIncrement", "number", "点位级 COV 增量阈值", false, "", null));
```

当前真正已生效的是：

- `additionalConfig.driverDataType`
- `additionalConfig.arrayIndex`

## 测试覆盖

当前仓库已覆盖：

- 协议 alias 和 validator 检查
- 协议 schema 暴露
- collector 状态输出
- 地址解析
- `ReadProperty` 编解码
- `ReadPropertyMultiple` 编解码
- 假设备 `UDP` 集成测试
  - `ReadProperty REAL`
  - `ReadProperty STRING`
  - `ReadPropertyMultiple` 成功批量读
  - `ReadPropertyMultiple Reject -> ReadProperty fallback`
  - `Reject`
  - 超时断链
  - `Who-Is / I-Am` 发现

## 代码入口

- `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`
- `src/main/java/com/wangbin/collector/core/connection/adapter/BacnetIpConnectionAdapter.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/domain/BacnetAddress.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/util/BacnetAddressParser.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/*`
- `src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/*`
- 总体方案：`../25-BACnet_IP接入方案.md`

# BACNET_IP

# 当前状态

`BACNET_IP` 已完成框架接入，并且已经不是空壳协议。

当前已经落地：

- `ProtocolType`、协议别名归一化、`Descriptor`、`CollectorFactory`、`ConnectionFactory`、`Validator` 已接入。
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
- 已完成假设备集成测试，不再只有 schema/框架级测试。

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

---

# 已完成能力

## 1. 真实连接

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

## 2. 真实读链路

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

## 3. 数据处理边界

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

---

# 未完成功能清单

## 高优先级

1. `WriteProperty`
    - 前端字段里已有 `writePriority` 等预留，但底层未兑现。
    - 不建议现场承诺可控写回。
2. `executeCommand`
    - 还未实现 `who_is / read_property / read_property_multiple / discover_objects / diagnostic` 这类诊断命令入口。

## 中优先级

3. `SubscribeCOV / SubscribeCOVProperty`
    - 当前 `covEnabled` 只是 schema 和 validator 已接入。
    - 还没有真正订阅、通知接收、断线补订阅。
4. constructed / array / sequence 类型解码
    - 当前只支持 primitive `ANY`。
    - 像 `objectList`、`priorityArray`、复杂对象属性还不能承诺。
5. `ReadPropertyMultiple` 读计划优化
    - 当前已做同对象聚合、批量切分、失败回退。
    - 还没有做更强的跨对象聚合和动态性能优化。

## 低优先级但高现场风险

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
    - 还没有全网扫描、对象枚举、发现缓存管理。

---

# 当前可交付范围

**可以交付：**

- `BACnet/IP UDP`
- 指定 `host:port + remoteDeviceInstance`
- 可选 `Who-Is / I-Am` 发现
- `ReadProperty`
- `ReadPropertyMultiple`
- 单点读
- 批量读
- `ReadPropertyMultiple` 失败自动回退
- 基础 primitive 值类型读取

**需实机验证后再对外说稳：**

- 不同厂商设备的 `I-Am` 兼容性
- `objectName / presentValue / units / reliability` 等跨厂商属性差异
- 高频轮询下的响应时间和丢包容忍度
- 单个设备对 `ReadPropertyMultiple` 的兼容程度和单报文属性数上限

**不建议现场承诺：**

- `WriteProperty`
- `WritePropertyMultiple`
- `COV`
- `BBMD`
- `Foreign Device`
- 跨网段自动发现
- 复杂数组 / sequence / proprietary object

---

# 支持的地址格式

当前解析器支持：

1. `analogInput:1.presentValue`
2. `analogValue:12.presentValue`
3. `binaryOutput:3.presentValue`
4. `device:1001.objectName`
5. `analogInput:7.priorityArray[5]`

**规则：**

- 标准格式：`<objectType>:<instance>.<property>[<index>]`
- `instance >= 0`
- `[index]` 可选
- 若地址中未写 `[index]`，可用 `additionalConfig.arrayIndex` 提供
- `driverDataType` 可由以下字段提示：
    - `additionalConfig.driverDataType`
    - `additionalConfig.bacnetType`
    - `additionalConfig.propertyType`

---

# 连接字段

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

**说明：**

- 这些字段并不代表当前都已实现。
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

---

# 点位 AdditionalConfig

```java
fields.add(createFieldConfig("additionalConfig.driverDataType", "string", "驱动原始类型", false, "AUTO",
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

---

# 测试覆盖

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

---

# 代码入口

- `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`
- `src/main/java/com/wangbin/collector/core/connection/adapter/BacnetIpConnectionAdapter.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/domain/BacnetAddress.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/util/BacnetAddressParser.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/*`
- `src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/*`
- 总体方案：`../25-BACnet_IP接入方案.md`

---

# BACnet 实现补齐路线图

下面的路线图按 `P1 / P2 / P3` 切分，目标是把当前 `BACnet/IP P0` 从“最小可用轮询读”逐步补齐到“可现场交付的 BACnet 接入能力”。

## P1：同网段可交付能力补齐

**目标：**

1. 把当前只支持 `ReadProperty / ReadPropertyMultiple` 的实现，补到“同网段可读、可写、可订阅、可诊断”。
2. 让当前 schema 里已经暴露的关键字段，和运行时能力对齐，避免前端可配但底层不生效。

**建议范围：**

1. `WriteProperty`
2. 可选 `WritePropertyMultiple`
3. `SubscribeCOV / SubscribeCOVProperty`
4. 基础诊断命令
5. 配置字段收口

**具体改造点：**

1. **写入链路**
    - 在 `BacnetIpCollector.java` 实现 `doWritePoint(...)`、`doWritePoints(...)`。
    - 在 `BacnetIpConnectionAdapter.java` 新增 `writeProperty(...)`，后续若启用聚合写，再新增 `writePropertyMultiple(...)`。
    - 在 `domain/` 新增 `BacnetWritePropertyRequest`、`BacnetWritePropertyResponse`；如需聚合写，再补 `BacnetWritePropertyMultipleRequest`。
    - 在 `codec/` 新增 `BacnetWritePropertyCodec`；如需聚合写，再补 `BacnetWritePropertyMultipleCodec`，并视 ACK 形式补充统一响应解码。
    - 写入时接通点位扩展字段：`additionalConfig.writePriority`；必要时将 `ENUM / BOOLEAN / REAL / UNSIGNED / SIGNED / STRING` 的反向编码做完整。
    - 统一沿用 `BaseCollector.writePoint/writePoints` 的质量校验与反向转换链路，不单独分叉写入流程。

2. **订阅链路**
    - 在 `BacnetIpCollector.java` 实现 `doSubscribe(...)`、`doUnsubscribe(...)`。
    - 在 `BacnetIpConnectionAdapter.java` 补一个持续接收推送报文的监听与分发机制，不再只做“发请求后阻塞等响应”。
    - 在 `codec/` 新增 `BacnetSubscribeCovCodec`、`BacnetSubscribeCovPropertyCodec`、`BacnetCovNotificationDecoder`。
    - 收到推送值后，统一走 `BaseCollector.ingestPushedValue(...)`，不要绕过现有缓存、上报、实时流链路。
    - 将以下连接字段真正接入运行时：`covEnabled`、`defaultCovLifetimeSeconds`、`defaultCovIncrement`、`resubscribeOnReconnect`、`localBindPort`。
    - 将以下点位扩展字段真正接入运行时：`additionalConfig.covMode`、`additionalConfig.covIncrement`。

3. **基础诊断命令**
    - 在 `BacnetIpCollector.java` 实现 `doExecuteCommand(...)`，先补最实用的一组命令：`who_is`、`read_property`、`read_property_multiple`、`device_info`、`discover_objects`。
    - 上层入口已存在，可直接沿用：`CollectionManager.java`、`ControlController.java`。
    - 目标不是做全功能调试台，而是先具备“现场排障不必改代码”的最小能力。

4. **配置字段收口**
    - 在 `ProtocolDescriptorRegistry.java` 标清哪些字段已生效、哪些字段仍是预留。
    - 在 `ProtocolConnectionValidator.java` 增加“功能开启即要求配套字段齐全”的校验，例如：`covEnabled=true` 时校验本地监听参数，`writePropertyMultipleEnabled=true` 时校验设备端兼容策略与批量大小。
    - 如果某些字段在 P1 仍不打算支持，应在协议文档和 schema 描述里明确写成 `reserved / not active yet`。

5. **P1 建议新增测试**
    - `WriteProperty` 集成测试
    - `SubscribeCOV` 推送接收测试
    - 重连后自动补订阅测试
    - `executeCommand` 命令集成测试
    - 写入失败、拒绝、超时、取消订阅的异常路径测试

**P1 完成标准：**

1. 同网段 BACnet/IP 设备可稳定完成读、写、COV 订阅。
2. 文档、schema、validator、运行时生效字段保持一致。
3. 现场最常见的“改值、订阅、诊断”不再依赖临时改代码。

---

## P2：网络兼容性与复杂对象能力补齐

**目标：**

1. 解决跨子网、复杂属性、大报文、厂商差异导致的现场兼容性问题。
2. 把“能接少量简单点位”提升到“能接楼控项目里的典型复杂对象”。

**建议范围：**

1. `BBMD / Foreign Device Registration`
2. 路由与跨子网发现
3. 分段报文
4. constructed / array / sequence 解码
5. 私有对象 / 私有属性访问

**具体改造点：**

1. **跨子网与路由**
    - 在 `BacnetIpConnectionAdapter.java` 接入：`bbmdHost`、`bbmdPort`、`foreignDeviceTtlSeconds`、`networkNumber`、`macAddress`。
    - 新增 BBMD/FD 注册、续租、失效重试逻辑。
    - `Who-Is / I-Am` 不再只做“按实例号找单设备”，补广播发现、指定 network 范围发现、路由相关诊断命令。

2. **超时、重试、会话参数**
    - 在 `BacnetIpUdpClient.java` 和 `BacnetIpConnectionAdapter.java` 真正接入：`apduTimeout`、`segmentTimeout`、`retries`。
    - 当前 `invokeId` 只是简单自增，P2 应补更稳健的请求上下文管理，避免并发、重试、迟到响应相互污染。

3. **分段响应支持**
    - 在 `BacnetReadPropertyResponseDecoder.java`、`BacnetReadPropertyMultipleResponseDecoder.java` 补分段 `ComplexACK` 重组。
    - `BacnetIpUdpClient.java` 需要从“一发一收”升级为“同一请求上下文下的多段收包与组装”。
    - 没有这一步，大对象、长属性列表、复杂对象读取仍会大量失败。

4. **复杂类型解码**
    - 当前 `ANY` 只支持 primitive，P2 应在 `codec/` 下补通用值模型和递归解码器。
    - 建议新增：`BacnetAnyValue`、`BacnetConstructedValue`、`BacnetArrayValue`、`BacnetSequenceValue`。
    - 先优先打通这些现场高频属性：`objectList`、`priorityArray`、`stateText`、`statusFlags`、`reliability`、`units`。
    - 需要同步评估 `ProcessResult` 里如何承载结构化值；若最终仍按标量上报，应提供可选的打平策略。

5. **私有对象 / 私有属性**
    - 当前 `BacnetObjectType.java`、`BacnetPropertyIdentifier.java` 对未知 id 会直接报错。
    - P2 应允许“标准枚举优先，未知数值透传”，否则厂商私有对象很难接。
    - `BacnetAddressParser.java` 也要扩展地址语法，至少支持数值型对象类型和属性 id，而不仅依赖当前内置枚举名。

6. **P2 建议新增测试**
    - BBMD / Foreign Device 注册与续租测试
    - 分段 `ReadProperty` / `ReadPropertyMultiple` 集成测试
    - `priorityArray`、`objectList`、`stateText` 解码测试
    - 私有对象 / 私有属性地址解析与读取测试
    - 跨子网发现与路由诊断测试

**P2 完成标准：**

1. BACnet/IP 不再局限于同子网简单读点。
2. 对典型楼控项目中的复杂属性和大响应具备稳定兼容性。
3. 厂商私有点表不再因为枚举表缺失而直接不可接入。

---

## P3：平台级 BACnet 能力建设

**目标：**

1. 从“协议驱动”升级到“平台级 BACnet 子系统”。
2. 解决大规模设备接入、对象建模、历史对象、告警与互操作验证问题。

**建议范围：**

1. 传输层扩展
2. 对象发现与建模
3. 历史与事件能力
4. 互操作与观测体系

**具体改造点：**

1. **传输层扩展**
    - 评估是否继续完全自研，还是引入成熟库承接更复杂 transport。
    - 若继续自研，建议把 `protocol/bacnet` 再拆层：`transport/`、`session/`、`service/`、`model/`。
    - P3 可评估支持：`IPv6`、`MS/TP`、`BACnet/SC`。
    - 这一阶段不建议继续把所有协议语义都堆在 `BacnetIpCollector` 一个类里。

2. **对象发现与建模**
    - 新增“设备快照 / 对象目录 / 属性缓存”能力。
    - 通过命令或后台任务建立：device 基本信息、object list、常用属性快照。
    - 便于前端点位辅助配置、差异比对、设备巡检和自动生成候选点表。
    - 相关能力建议沉到单独服务，而不是继续塞进 collector 主读链路。

3. **历史与事件能力**
    - 补 `ReadRange`、`TrendLog`、事件/告警对象读取。
    - 评估是否需要：alarm acknowledge、event enrollment / notification class 相关诊断、time sync / schedule 相关命令。
    - 这一阶段才适合把 BACnet 从“采集协议”扩展成“楼控系统接入协议”。

4. **观测与互操作**
    - 在监控里新增 BACnet 专项指标：`rpmFallbackCount`、`covNotificationCount`、`covResubscribeFailureCount`、`segmentedResponseCount`、`bbmdRenewFailureCount`、`invokeIdMismatchCount`。
    - 构建厂商兼容矩阵，至少覆盖：Siemens、Johnson Controls、Honeywell、Trane、Delta、国产常见楼控网关。
    - 若后续要对外承诺“大规模 BACnet 交付”，应引入更接近 BTL 场景的回归测试集。

5. **P3 建议新增测试**
    - 多设备并发与大点表压测
    - 复杂对象全量扫描回归
    - 历史对象 / TrendLog 读取测试
    - 多厂商互操作测试
    - 长稳测试：重连、续租、广播风暴、分段报文、迟到响应

**P3 完成标准：**

1. BACnet 能力不再只是“协议插件”，而是独立可演进的子系统。
2. 支持大规模项目的发现、建模、采集、写入、订阅、诊断和观测。
3. 能够更稳妥地对外承诺“BACnet 现场交付能力”。

---

## 阶段排序建议

建议严格按下面顺序推进：

1. `P1`：先补写入、COV、诊断、字段收口，把同网段现场最常见需求打通。
2. `P2`：再补 BBMD、分段、复杂属性、私有对象，解决兼容性和跨网段问题。
3. `P3`：最后再做 BACnet/SC、MS/TP、TrendLog、对象建模、兼容矩阵等平台级建设。

如果资源有限，最小闭环应至少完成：

1. `WriteProperty`
2. `SubscribeCOV`
3. `BBMD / Foreign Device`
4. 分段响应支持
5. `priorityArray / objectList` 这类复杂属性解码

### 已落地：BACnet MS/TP 传输层

1. 传输帧与 CRC 已补齐，代码落点：
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetMstpFrame.java`
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetMstpFrameCodec.java`
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetMstpCrc.java`
2. token passing / poll-for-master 已补齐，代码落点：
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/transport/BacnetMstpTokenManager.java`
3. 串口收发抽象与默认实现已补齐，代码落点：
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/transport/BacnetSerialChannel.java`
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/transport/JSerialCommBacnetSerialChannel.java`
   - `src/main/java/com/wangbin/collector/core/connection/adapter/BacnetMstpConnectionAdapter.java`
4. 采集器与工厂接入已补齐，代码落点：
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetMstpCollector.java`
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/client/BacnetMstpClient.java`
   - `src/main/java/com/wangbin/collector/core/connection/factory/ConnectionFactory.java`
   - `src/main/java/com/wangbin/collector/core/config/protocol/ProtocolDescriptorRegistry.java`
   - `src/main/java/com/wangbin/collector/core/config/validator/ProtocolConnectionValidator.java`
5. 当前支持范围：
   - 面向采集场景的主站侧 token 接收、空闲 claim、Poll For Master、Reply To Poll、Token 传递。
   - 复用现有 BACnet APDU/NPDU 编解码能力，已打通 `ReadProperty` 读点闭环。
   - 支持 `serialPort`、`baudRate`、`dataBits`、`stopBits`、`parity`、`localMacAddress`、`remoteMacAddress`、`maxMaster`、`maxInfoFrames` 等关键连接参数。
6. 当前限制：
   - 当前是为采集框架服务的精简 MS/TP master 实现，不是完整的 BACnet MS/TP 状态机与互操作认证实现。
   - 暂未覆盖从站代理、复杂多主竞争调优、链路层诊断对象、MS/TP 路由器场景。
   - 当前测试以内存串口通道模拟为主，真实串口兼容性仍需现场设备回归。

### 已落地：BACnet/SC（实验性）

1. 代码落点：
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/client/BacnetScClient.java`
   - `src/main/java/com/wangbin/collector/core/connection/adapter/BacnetScConnectionAdapter.java`
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetScCollector.java`
2. 当前实现方式：
   - 使用 secure WebSocket 二进制通道承载 BACnet 请求/响应报文。
   - 复用现有 `BacnetIpCollector` 的读点、写点、订阅、分段响应重组、未知对象/私有属性动态透传能力。
3. 当前接入字段：
   - `url`、`host`、`port`、`path`、`subprotocol`、`remoteDeviceInstance`、`timeout`、`connectTimeout`、`apduTimeout`、`segmentTimeout`、`retries`。
4. 当前限制：
   - 当前定位是实验性 secure tunnel 接入，并未完整实现标准 BACnet/SC hub / node 会话模型。
   - 证书信任链治理、节点发现、邻居/路由分发、标准化连接管理仍需后续继续补齐。
   - 在项目对外宣称 BACnet/SC 交付能力之前，必须先做目标平台互操作回归。

### 本轮新增测试

1. `src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetMstpFrameCodecTest.java`
2. `src/test/java/com/wangbin/collector/core/connection/adapter/BacnetMstpConnectionAdapterTest.java`
3. `src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetMstpCollectorTest.java`
4. `src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetScCollectorTest.java`

以上补齐后，BACnet 这条线已经从“仅 BACnet/IP 基础读点”推进到“BACnet/IP 增强 + MS/TP 传输闭环 + BACnet/SC 实验接入”的状态，但 BACnet/SC 标准化互操作与 MS/TP 大规模现场验证仍应继续放在后续阶段推进。

## 待完成与待优化清单（2026-06-30 核对追加）

说明：

- 状态约定：`[ ]` 未完成，`[~]` 部分完成，`[x]` 已完成。
- 后续完成某项时，直接修改对应状态，并在条目后补充完成日期、代码入口、测试入口。
- 本区仅记录“当前代码已确认仍未闭环”或“架构上建议继续补齐”的事项。

### A. 功能未完全实现

- `[x]` `WritePropertyMultiple`
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/domain/BacnetWritePropertyMultipleRequest.java`、`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetWritePropertyMultipleCodec.java`、`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`、`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetReadPropertyCodecTest.java`
  - 说明：已补齐 `domain + codec + client + connection adapter + collector`，支持 `writePropertyMultipleEnabled=true` 时聚合写，失败后自动逐点回退 `WriteProperty`。

- `[x]` confirmed `COV Notification` 接收闭环
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetCovNotificationDecoder.java`、`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetConfirmedCovNotificationCodec.java`、`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/client/BacnetIpUdpClient.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`
  - 说明：已补齐 confirmed COV 解码、SimpleACK 确认和 collector 推送入链。
  - 当前情况：当前入站仅实现 unconfirmed `COV Notification` 解码；订阅请求虽然可携带 `issueConfirmedNotifications=true`，但服务端若真的回 confirmed COV，当前没有对应接收与确认处理。
  - 完成标记建议：补 confirmed COV APDU 解码、ACK/处理链路、超时/重发策略、模拟服务端测试。

- `[x]` `resubscribeOnReconnect` 重连后自动恢复订阅
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`、`src/main/java/com/wangbin/collector/core/connection/adapter/BacnetIpConnectionAdapter.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`
  - 说明：已补齐适配器重连回调、collector 自动补订阅与失败计数。
  - 当前情况：配置字段已暴露，但当前 `BacnetIpCollector` / 调度层未实现“连接失效后重连并恢复 COV 订阅”闭环。
  - 完成标记建议：补连接恢复后的订阅重建逻辑，并覆盖断线、重连、重复订阅去重、失败重试测试。

- `[x]` `defaultCovIncrement` 连接级默认增量阈值生效
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`
  - 说明：属性级 COV 订阅已支持点位 `covIncrement` 优先、连接级 `defaultCovIncrement` 回退。

- `[x]` `covEnabled` 配置到订阅行为的统一闭环
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`、`src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`、`src/test/java/com/wangbin/collector/core/collector/scheduler/CollectionSchedulerTest.java`
  - 说明：`covEnabled` 已统一控制自动订阅、轮询绕过和推送点调度语义。

- `[x]` constructed / array / sequence / complex `ANY` 通用解码
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetValueDecoder.java`、`BacnetReadPropertyResponseDecoder.java`、`BacnetReadPropertyMultipleResponseDecoder.java`、`BacnetCovNotificationDecoder.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetReadPropertyCodecTest.java`
  - 说明：已补齐 BACnet application primitive、constructed、array/sequence 统一解码路径，并覆盖 `objectList`、`priorityArray`、`statusFlags` 等典型复杂属性。
  - 当前情况：当前读值解码以 primitive 为主，支持 `NULL / BOOLEAN / UNSIGNED / SIGNED / REAL / DOUBLE / CHARACTER_STRING / BIT_STRING / ENUMERATED / OBJECT_IDENTIFIER`，支持 `arrayIndex` 定位，但不支持通用 constructed/sequence/复杂数组属性展开。
  - 直接影响：`priorityArray`、复杂对象属性、厂商扩展 constructed 值、嵌套 sequence 返回仍不能稳定接入。
  - 完成标记建议：补通用 tag walker、constructed value model、序列化策略，并补真实复杂属性模拟测试。

- `[x]` 复杂 BACnet 属性的结果建模策略
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/domain/BacnetValue.java`、`BacnetValueKind.java`、`BacnetIpCollector.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`
  - 说明：已定义统一 `BacnetValue`/`BacnetValueKind` 建模，并在 `ProcessResult.metadata` 中固化 `bacnetValueType`、`bacnetComplexValue`、`bacnetValueMetadata`，复杂值按统一 passthrough 模式进入缓存/告警/上报链。
  - 当前情况：当前 `ProcessResult` 路径对 scalar 友好，但对复杂数组/对象结果缺统一约束，尚未定义“原样透传 JSON / typed model / flattened map”的平台标准。
  - 完成标记建议：先定平台层结果模型，再补复杂属性读链路，避免后续接口反复变更。

- `[ ]` `BACnet/SC` 标准 hub / node 会话模型
  - 当前情况：当前是 secure WebSocket binary tunnel 实验接入，不是完整标准 `BACnet/SC` 实现。
  - 完成标记建议：补标准会话治理、证书/信任链、节点发现、邻居/路由控制，并做目标平台互操作回归。

- `[ ]` `MS/TP` 大规模现场兼容性验证
  - 当前情况：当前 `MS/TP` 已有 transport/read 闭环与内存串口模拟测试，但真实串口、多主竞争、复杂现场兼容性仍未完成。
  - 完成标记建议：补真实串口回归、不同波特率/校验位/多主设备验证和长期稳定性测试。

### B. 流程与框架接入待补充

- `[x]` 调度层自动订阅 BACnet 订阅点的策略
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/scheduler/CollectionSchedulerTest.java`
  - 说明：调度器启动阶段已自动识别 `SUBSCRIPTION/EVENT` 点并调用订阅，同时从轮询计划剔除。

- `[x]` BACnet 推送链路与轮询链路的统一配置语义
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`、`src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`、`src/test/java/com/wangbin/collector/core/collector/scheduler/CollectionSchedulerTest.java`
  - 说明：已明确 `covEnabled + collectionMode=SUBSCRIPTION/EVENT` 的推送点模型，并统一到轮询绕过和调度编排。

- `[x]` BACnet 专项监控指标的系统化暴露
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`
  - 说明：已在 `getDeviceStatus()/protocolMetrics` 暴露 COV、重连、分段、Foreign Device、fallback 等 BACnet 专项指标。

### C. 架构优化与代码组织建议

- `[x]` 拆分 `BacnetIpCollector`
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/service/BacnetValueMapper.java`、`BacnetSubscriptionService.java`、`BacnetDeviceSnapshotService.java`、`BacnetWriteRequestBuilder.java`
  - 说明：已将复杂值映射、订阅构造与匹配、设备快照、写请求构造从 collector 中拆出，`BacnetIpCollector` 主类收敛为连接调度、读写编排和统一框架接入职责。

- `[x]` 拆分 BACnet 协议栈层次
  - 完成日期：`2026-07-01`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/service/BacnetRequestSession.java`、`BacnetSegmentAssembler.java`、`BacnetClientSupport.java`、`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/client/BacnetIpUdpClient.java`、`BacnetScClient.java`、`BacnetMstpClient.java`
  - 测试入口：`mvn "-Dtest=BacnetIpCollectorFeatureTest,BacnetIpCollectorIntegrationTest,BacnetReadPropertyCodecTest,BacnetMstpConnectionAdapterTest,BacnetScCollectorTest" test`
  - 说明：已把 `invokeId` 校验、confirmed request 会话、分段组装、COV 通知处理下沉到共享 `service/session` 层，`IP/SC/MS/TP` 三种 client 复用统一栈能力。

- `[x]` 建立复杂类型独立解码层
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetValueDecoder.java`
  - 说明：已建立独立 BACnet value decoder，`ReadProperty / ReadPropertyMultiple / COV` 统一复用，处理 primitive、constructed、array、sequence 和复杂 `ANY` 值。

- `[x]` 建立 BACnet 设备快照 / 对象目录 / 属性缓存服务
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/service/BacnetDeviceSnapshotService.java`、`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/domain/BacnetDeviceSnapshot.java`、`src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollector.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorIntegrationTest.java`
  - 说明：已抽出设备快照、对象目录和属性缓存服务，`device_info` / `discover_objects` 命令已切换到该服务。

- `[x]` 明确复杂值在缓存/上报/实时流中的标准表示
  - 完成日期：`2026-06-30`
  - 代码入口：`src/main/java/com/wangbin/collector/core/report/shadow/ShadowManager.java`、`src/main/java/com/wangbin/collector/core/report/model/ReportData.java`、`src/main/java/com/wangbin/collector/core/report/service/IoTProtocolService.java`、`src/main/java/com/wangbin/collector/core/report/adapter/JsonProtocolAdapter.java`
  - 测试入口：`src/test/java/com/wangbin/collector/core/report/shadow/ShadowManagerTest.java`、`src/test/java/com/wangbin/collector/core/report/service/CacheReportServiceTest.java`
  - 说明：已统一复杂 BACnet 值通过 `ProcessResult.metadata` 固化为 `bacnetValueType`、`bacnetComplexValue`、`bacnetValueMetadata`，并贯通影子、上报、协议消息和实时流。

### D. 回归测试与交付验证待补充

- `[x]` `WritePropertyMultiple` 集成测试
  - 完成日期：`2026-06-30`
  - 代码入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/FeatureBacnetTestServer.java`
  - 测试入口：`mvn "-Dtest=BacnetReadPropertyCodecTest,BacnetIpCollectorFeatureTest,BacnetIpCollectorIntegrationTest" test`
  - 说明：已覆盖 WPM 编码、聚合写成功、Reject 后逐点 fallback 与 BACnet 主链回归。
- `[x]` confirmed `COV Notification` 集成测试
  - 完成日期：`2026-06-30`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorIntegrationTest.java#shouldHandleConfirmedCovNotificationInIntegrationPath`
  - 说明：已覆盖 confirmed COV 入站通知、collector 接收处理与 ACK 回包集成闭环。
- `[x]` 断线重连 + 自动恢复订阅集成测试
  - 完成日期：`2026-06-30`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorIntegrationTest.java#shouldRecoverConnectionAndResubscribeAfterTimeoutWhenCovEnabled`
  - 说明：已覆盖读超时导致连接失效、自动恢复连接、恢复订阅并继续读取的集成路径。
- `[x]` 复杂数组 / sequence / `priorityArray` / `objectList` 解码测试
  - 完成日期：`2026-06-30`
  - 代码入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/codec/BacnetReadPropertyCodecTest.java`、`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorFeatureTest.java`
  - 说明：已补 decoder 级和 collector 级回归，验证复杂属性读值、复杂值透传与元数据保留。

### 本轮完成记录（2026-06-30）

- `[x]` 第一批：复杂值解码与结果建模闭环
  - 完成日期：`2026-06-30`
  - 代码入口：`BacnetValueDecoder`、`BacnetIpCollector`、`BacnetReadPropertyResponseDecoder`、`BacnetReadPropertyMultipleResponseDecoder`、`BacnetCovNotificationDecoder`
  - 测试入口：`mvn "-Dtest=BacnetReadPropertyCodecTest,BacnetIpCollectorFeatureTest,BacnetIpCollectorIntegrationTest" test`
  - 说明：已完成从 BACnet 读值/COV 值解码到 `ProcessResult`、缓存/上报链的复杂值闭环，后续继续推进 COV 自动订阅/重连恢复和 `WritePropertyMultiple`。
- `[x]` 第二批：COV 自动订阅、confirmed 通知与重连恢复闭环
  - 完成日期：`2026-06-30`
  - 代码入口：`BacnetIpCollector`、`CollectionScheduler`、`BacnetConnectionAdapter`、`BacnetIpConnectionAdapter`、`BacnetScConnectionAdapter`、`BacnetMstpConnectionAdapter`、`BacnetCovNotificationDecoder`、`BacnetConfirmedCovNotificationCodec`
  - 测试入口：`mvn "-Dtest=BacnetIpCollectorFeatureTest,CollectionSchedulerTest" test`
  - 说明：已完成 `covEnabled`/`defaultCovIncrement`/`resubscribeOnReconnect` 运行时闭环，confirmed COV ACK、自动订阅、轮询绕过与 BACnet 专项指标一并落地。
- `[x]` 第三批：`WritePropertyMultiple` 批量写闭环
  - 完成日期：`2026-06-30`
  - 代码入口：`BacnetWritePropertyMultipleRequest`、`BacnetWritePropertyMultipleCodec`、`BacnetIpCollector`、`BacnetIpUdpClient`、`BacnetIpConnectionAdapter`、`BacnetScClient`、`BacnetMstpClient`
  - 测试入口：`mvn "-Dtest=BacnetReadPropertyCodecTest,BacnetIpCollectorFeatureTest,BacnetIpCollectorIntegrationTest" test`
  - 说明：已完成 BACnet 聚合写请求编码、三种传输适配、collector 写聚合策略与失败自动回退。
- `[x]` 第四批：设备快照 / 对象目录 / 属性缓存服务
  - 完成日期：`2026-06-30`
  - 代码入口：`BacnetDeviceSnapshotService`、`BacnetDeviceSnapshot`、`BacnetIpCollector`
  - 测试入口：`mvn "-Dtest=BacnetIpCollectorIntegrationTest#shouldBuildDeviceSnapshotThroughDeviceInfoAndDiscoverObjectsCommands" test`
  - 说明：已将 `device_info`、`discover_objects`、属性缓存从 collector 内部流程中抽出为独立服务。
- `[x]` 第五批：collector 架构拆分、复杂值链路标准化与 COV 集成回归补齐
  - 完成日期：`2026-06-30`
  - 代码入口：`BacnetValueMapper`、`BacnetSubscriptionService`、`ShadowManager`、`ReportData`、`IoTProtocolService`、`JsonProtocolAdapter`
  - 测试入口：`mvn "-Dtest=BacnetIpCollectorIntegrationTest,BacnetIpCollectorFeatureTest,BacnetReadPropertyCodecTest,ShadowManagerTest,CacheReportServiceTest,TelemetryStreamServiceImplTest,CollectorDataPostProcessorTest" test`
  - 说明：已完成 `BacnetIpCollector` 进一步拆分、复杂值在缓存/影子/上报/实时流中的统一表示，并补齐 confirmed COV 与断线恢复订阅集成测试。
- `[x]` 跨子网 `BBMD / Foreign Device` 长稳测试
  - 完成日期：`2026-07-01`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorIntegrationTest.java#shouldSustainForeignDeviceRenewalAcrossMultipleLeaseCycles`
  - 说明：已补多租期自动续租回归，验证 `Foreign Device Registration`、续租次数、续租失败计数和跨 BBMD 读点链路。
- `[x]` 多设备并发、大点表、长时间运行稳定性测试
  - 完成日期：`2026-07-01`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetIpCollectorScalabilityTest.java`
  - 说明：已覆盖 180 点 `ReadPropertyMultiple` 大点表读取、3 设备并发读取，以及 120 轮持续轮询稳定性回归。
- `[x]` 多厂商兼容矩阵回归测试
  - 完成日期：`2026-07-01`
  - 测试入口：`src/test/java/com/wangbin/collector/core/collector/protocol/bacnet/BacnetCompatibilityMatrixTest.java`
  - 说明：已建立基于 `FakeBacnetIpServer` 的厂商画像矩阵回归，覆盖不同 `vendorId/maxApdu` 与属性组合读取路径。

- `[x]` `MS/TP` 多主站共享总线仿真回归
  - 完成日期：`2026-07-01`
  - 测试入口：`src/test/java/com/wangbin/collector/core/connection/adapter/BacnetMstpMultiMasterIntegrationTest.java`
  - 说明：已补内存共享串口总线与双主站双远端设备回归，验证 token 传递、多主共线读点与远端主站响应路径。

### 本轮完成记录（2026-07-01）

- `[x]` 第六批：协议栈分层、长稳回归与兼容矩阵补齐
  - 完成日期：`2026-07-01`
  - 代码入口：`BacnetRequestSession`、`BacnetSegmentAssembler`、`BacnetClientSupport`、`BacnetIpUdpClient`、`BacnetScClient`、`BacnetMstpClient`、`InMemoryBacnetSerialBus`
  - 测试入口：`mvn "-Dtest=BacnetIpCollectorIntegrationTest#shouldSustainForeignDeviceRenewalAcrossMultipleLeaseCycles,BacnetIpCollectorScalabilityTest,BacnetCompatibilityMatrixTest,BacnetMstpMultiMasterIntegrationTest" test`
  - 说明：已补齐 BACnet `session/service` 共享层、`BBMD/Foreign Device` 多租期长稳测试、多设备并发与大点表稳定性测试、厂商画像矩阵回归，以及 `MS/TP` 多主站共享总线仿真。

### E. 完成记录模板

后续某项完成时，建议按下面格式直接更新对应条目：

- `[x]` 条目名称
  - 完成日期：`YYYY-MM-DD`
  - 代码入口：`类 / 方法 / 文件`
  - 测试入口：`测试类 / 用例`
  - 说明：一句话说明完成范围与边界

### F. 我们当前实现 vs `BACnet4J` 详细对照

#### 对比前提

1. 本节对比时间点为 `2026-07-01`。
2. `BACnet4J` 结论主要基于其公开 README 与公开源码入口，不基于本项目内实际嵌入运行结果。
3. 这两个东西不完全是同一类产品：
   - 我们当前实现：是“BACnet 协议采集实现 + 调度/缓存/告警/上报/实时流”的完整采集服务能力。
   - `BACnet4J`：首先是“通用 BACnet Java 协议栈 / SDK / 对象模型库”。
4. 所以必须拆成两张表看：
   - 协议栈深度谁更强
   - 采集框架闭环谁更完整

#### 1. 协议栈能力对照

| 维度 | 我们当前实现 | `BACnet4J` | 结论 |
| --- | --- | --- | --- |
| 产品定位 | 面向采集服务的 BACnet client/collector 实现 | 通用 BACnet Java 协议栈与对象模型库 | 定位不同，不能只按“功能点个数”判断 |
| BACnet/IP | 已实现，支持轮询、`ReadProperty`、`ReadPropertyMultiple`、`WriteProperty`、`WritePropertyMultiple`、COV、分段响应、`BBMD/Foreign Device` | README 明确是纯 Java BACnet 实现，支持 `IPv4` | 两边都具备，`BACnet4J` 更偏标准协议库 |
| IPv6 | 当前未实现，文档中仍作为后续方向 | README 明确支持 `IPv6` | `BACnet4J` 更强 |
| MS/TP | 已实现面向采集的 master 侧传输、token 管理、读写/COV/分段；已补共享总线多主站仿真 | README 明确支持 `MS/TP`，且 2.x 起网络层为支持 `MS/TP` 重写 | 协议成熟度上 `BACnet4J` 更强；我们当前更偏“够用采集版” |
| BACnet/SC | 当前是实验性 secure WebSocket binary tunnel 接入，未完成标准 hub/node 会话模型 | 截至 `2026-07-01` 查到的公开 README/源码入口中未看到明确 `BACnet/SC` 说明，按“未公开确认”处理 | 我们有实验入口，但两边都不应视为已完成标准化交付 |
| `Who-Is / I-Am` 与远端发现 | 已支持，且已补跨 BBMD 发现与远端信息快照链路 | README 6.2.0 明确提到远端设备缓存、手工加入 remote device、发现后扩展信息抓取 | `BACnet4J` 更成熟，远端设备缓存/发现工具链更完整 |
| `BBMD / Foreign Device` | 已实现注册、续租、失败计数、跨子网测试 | README 3.2 起已支持 `BBMD`，6.1.0 又增强 `Foreign Device` 失败容忍与重试控制 | `BACnet4J` 更成熟；我们当前已把采集主路径打通 |
| 分段报文 | 已支持 segmented `ComplexACK` 组装，`IP/SC/MS/TP` client 共用分段组装能力 | README 5.0.0 明确支持最多 `255 segments` 收发 | `BACnet4J` 更强、更成熟 |
| `ReadPropertyMultiple` | 已实现，支持读计划聚合和失败回退 | README 未逐项枚举，但作为完整 BACnet 协议栈与 BTL certifiable 版本，合理推断核心标准服务能力完整 | 标准成熟度上倾向 `BACnet4J`；采集聚合策略上我们更贴近业务 |
| `WritePropertyMultiple` | 已实现，支持聚合写和逐点 fallback | README 未逐项枚举，公开入口未单列说明 | 我们当前实现可直接用于采集服务；`BACnet4J` 公开资料层面这里不做绝对结论 |
| COV | 已支持 `SubscribeCOV/SubscribeCOVProperty`、confirmed/unconfirmed notification、ACK、重连恢复订阅 | README 较早版本就明确有 `COV reporting`，`LocalDevice` 源码中也有 COV context/persistence 相关结构 | `BACnet4J` 更偏完整对象模型；我们当前更偏“订阅并消费通知” |
| 复杂数组 / sequence / complex value 解码 | 已支持复杂值解码，并纳入统一 `BacnetValue` 模型与 `ProcessResult.metadata` | README 多次提到新增大量 structures/properties，并修复 `priority array` 等问题 | 协议覆盖面上 `BACnet4J` 大概率更广；我们当前已覆盖采集主场景 |
| 本地设备 / 对象模型 | 当前以 remote collector 为主，不是通用 BACnet server 对象栈 | `LocalDevice`、`DeviceObject`、本地对象列表、事件处理、私有服务处理器都较完整 | `BACnet4J` 明显更强 |
| 私有服务 / 私有对象扩展 | 当前以采集与标准服务为主，厂商私有扩展主要体现在值透传与兼容处理 | `LocalDevice` 源码中有 `PrivateTransferHandler`、`VendorServiceKey` 等扩展点 | `BACnet4J` 更强 |
| 标准符合度与协议栈成熟度 | 当前已能打通采集必需链路，但仍保留 `BACnet/SC` 标准会话、真实 `MS/TP` 大现场验证等未完成项 | README 5.0.0 明确写到 `Fully BTL Certifiable` | 如果只比“BACnet 协议栈成熟度”，`BACnet4J` 更强 |

#### 2. 采集框架能力对照

| 维度 | 我们当前实现 | `BACnet4J` | 结论 |
| --- | --- | --- | --- |
| 设备调度 | 有统一调度器、时间片、批计划、并发执行 | 不属于其核心职责 | 我们更强 |
| 多协议统一接入 | BACnet 只是统一采集框架里的一个协议 | 是单 BACnet 协议栈 | 我们更强 |
| 点位配置治理 | 有设备/连接/点位配置、在线刷新与治理接口 | 不是其核心职责 | 我们更强 |
| 采集后处理 | 统一 `ProcessResult`、数据质量、转换、复杂值标准化 | 主要提供协议对象与服务，不负责平台数据处理链 | 我们更强 |
| 缓存 | 本地缓存、Redis 缓存、多级缓存 | 不属于其核心职责 | 我们更强 |
| 告警/影子/上报 | 已打通影子聚合、告警、MQTT/HTTP/TCP 上报 | 不属于其核心职责 | 我们更强 |
| 实时流 | 已打通 Redis Stream 实时流 | 不属于其核心职责 | 我们更强 |
| 历史存储接入 | 可接 TDengine 等存储 | 不属于其核心职责 | 我们更强 |
| 监控指标 | 已暴露 BACnet 专项指标与平台运行指标 | 更偏协议库内部能力，不是完整运维监控产品 | 我们更强 |
| 开箱即用交付 | 当前仓库就是完整服务形态 | 需要二次封装为服务 | 我们更强 |

#### 3. 架构层面的核心判断

1. 如果比较“BACnet 协议库本身谁更成熟”，`BACnet4J` 明显更强。
   - 原因：它是长期维护的通用 BACnet 栈，公开资料里明确支持 `IPv4/IPv6/MS/TP`、`BBMD`、更成熟的 `Foreign Device`、更高的标准符合度，以及本地对象模型与事件处理体系。

2. 如果比较“工业采集服务闭环谁更完整”，当前工程明显更强。
   - 原因：我们已经把 BACnet 能力嵌进了统一采集框架，打通了“调度 -> 采集 -> 处理 -> 缓存 -> 告警/上报 -> 实时流 -> 配置治理 -> 监控”整条业务链。

3. 如果比较“二次开发成本”，两边优劣取决于目标。
   - 目标是做一个更标准、更通用的 BACnet Java SDK：`BACnet4J` 更合适。
   - 目标是交付一个可运营的工业采集服务：当前工程更合适。

4. 如果比较“后续架构演进空间”，最优路线不一定是二选一。
   - 一种务实路线是：继续保留当前采集框架外壳，但评估是否把更底层、标准化程度要求更高的 BACnet transport/session/service 能力逐步替换或对接成熟库。
   - 这样可以同时保留我们已经做好的调度、缓存、告警、上报、实时流和配置治理能力。

#### 4. 结论

一句话结论：

- 只看 BACnet 协议栈深度：`BACnet4J` 更强。
- 只看采集平台闭环交付：我们当前实现更强。
- 只看当前项目目标是否匹配：当前工程更贴合“工业采集服务”目标，`BACnet4J` 更像可借鉴或可承接底层协议栈能力的成熟库。

#### 5. 对比依据

1. `BACnet4J` README
   - https://github.com/RadixIoT/BACnet4J
   - 原始内容入口：
     https://raw.githubusercontent.com/RadixIoT/BACnet4J/master/README.md
2. `BACnet4J` `LocalDevice` 源码入口
   - https://raw.githubusercontent.com/RadixIoT/BACnet4J/master/src/main/java/com/serotonin/bacnet4j/LocalDevice.java
3. `BACnet4J` `IpNetwork` 源码入口
   - https://raw.githubusercontent.com/RadixIoT/BACnet4J/master/src/main/java/com/serotonin/bacnet4j/npdu/ip/IpNetwork.java
4. 我们当前工程代码入口
   - `src/main/java/com/wangbin/collector/core/collector/protocol/bacnet/**`
   - `src/main/java/com/wangbin/collector/core/connection/adapter/Bacnet*ConnectionAdapter.java`

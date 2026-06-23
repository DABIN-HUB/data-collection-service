# 点位配置可视化字段梳理与前端字段 Schema 草案

## 1. 背景与目标

当前管理页 `static/admin/index.html` 的点位配置仍是一个 JSON 文本框：

- 页面输入入口：`src/main/resources/static/admin/index.html`
- 提交逻辑：`src/main/resources/static/admin/app.js`
- 后端保存接口：`PUT /api/config/device/{deviceId}/points`
- 后端契约模型：`com.wangbin.collector.common.domain.entity.DataPoint`

本文件的目标不是设计最终页面样式，而是先把“点位配置到底有哪些字段、每个字段应该按单字段还是列表处理、哪些字段适合做主表列、哪些字段只能放详情表单、哪些字段只读”一次梳理清楚，作为后续点位可视化改造的输入。

## 2. 现状确认

### 2.1 当前前端现状

当前本地临时设备编辑面板里，点位配置只有一个 JSON 文本域：

- `#localPointsJson`
- `buildLocalDeviceRequest()` 直接把它解析成 `points[]`

这意味着前端没有真正理解 `DataPoint` 的字段结构，也没有按协议拆分点位扩展配置。

### 2.2 当前后端真实契约

点位接口接收的是 `List<DataPoint>`，不是任意 JSON。

代码入口：

- `src/main/java/com/wangbin/collector/api/controller/ConfigController.java`
- `src/main/java/com/wangbin/collector/common/domain/entity/DataPoint.java`

文件配置加载也明确按 `DataPoint.class` 反序列化，并开启了 `FAIL_ON_UNKNOWN_PROPERTIES=false`：

- `src/main/java/com/wangbin/collector/core/config/loader/FileConfigLoader.java`

这意味着：

1. 稳定的顶层字段，应以 `DataPoint` 类为准。
2. 样例 JSON 里出现但 `DataPoint` 没有的顶层字段，不应直接当成正式表单字段。
3. 协议扩展字段的稳定入口，主要是 `additionalConfig`。

## 3. 总结论

先给结论，后面再展开：

1. 真正应该按“列表”建模的字段只有三类：
   - `points[]`
   - `alarmRule[]`
   - `additionalConfig.reportBindings[]`
2. 其余大部分字段都应按“单字段”处理。
3. `additionalConfig` 不应该继续做成一个大 JSON 文本框，而应拆成：
   - 通用功能区
   - 协议区
   - 极少数兼容字段保留为高级设置
4. `DataPoint` 中有一批运行态字段，不应该进入编辑表单：
   - `currentCollectionInterval`
   - `stableCount`
   - `lastValue`
   - `changeRate`
   - `lastAdjustTime`
   - `reportFieldConflict`
5. 仓库里的部分 mock JSON 出现了 `registerType`、`defaultValue`、`stringLength` 等字段，但它们不是稳定的 `DataPoint` 顶层契约，不能直接照着样例做顶层输入框。

## 4. DataPoint 顶层字段总表

下表按“字段性质 + 前端处理方式”梳理 `DataPoint` 的顶层字段。

| 字段 | 字段类型 | 结构结论 | 前端归类 | 是否建议编辑 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `id` | `Long` | 单字段 | 只读项 | 否 | 持久化/历史遗留 ID，不适合手填 |
| `unitId` | `Integer` | 单字段 | 表单项/协议区 | 有条件 | Modbus、IEC104 等会用到 |
| `commonAddress` | `Integer` | 单字段 | 表单项/协议区 | 有条件 | IEC104 常用 |
| `pointId` | `String` | 单字段 | 表单项 | 是 | 推荐必填，系统内部主标识 |
| `pointCode` | `String` | 单字段 | 表单项 | 是 | 推荐必填，业务主编码 |
| `pointName` | `String` | 单字段 | 表单项 | 是 | 推荐必填，列表主显示名 |
| `pointAlias` | `String` | 单字段 | 表单项 | 是 | 可选，但手动控制/上报会使用 |
| `deviceId` | `String` | 单字段 | 只读项 | 否 | 应从当前设备上下文带出，不建议手填 |
| `deviceName` | `String` | 单字段 | 只读项 | 否 | 应从当前设备上下文带出 |
| `groupId` | `String` | 单字段 | 表单项/高级区 | 有条件 | 使用面较窄，不放主表 |
| `address` | `String` | 单字段 | 表单项 | 是 | 点位协议地址核心字段 |
| `dataType` | `String` | 单字段 | 表单项 | 是 | 应做下拉或受控枚举 |
| `readWrite` | `String` | 单字段 | 表单项 | 是 | `R/W/RW` |
| `scalingFactor` | `Double` | 单字段 | 表单项 | 是 | 数值转换规则 |
| `offset` | `Double` | 单字段 | 表单项 | 是 | 数值转换规则 |
| `deadband` | `Double` | 单字段 | 表单项 | 是 | 通用死区 |
| `unit` | `String` | 单字段 | 表单项 | 是 | 显示/转换单位 |
| `minValue` | `Double` | 单字段 | 表单项 | 是 | 最小值校验 |
| `maxValue` | `Double` | 单字段 | 表单项 | 是 | 最大值校验 |
| `collectionMode` | `String` | 单字段 | 表单项 | 是 | `POLLING/SUBSCRIPTION/EVENT` |
| `priority` | `Integer` | 单字段 | 表单项/高级区 | 是 | 影响缓存时长等策略 |
| `cacheEnabled` | `Integer` | 单字段 | 表单项 | 是 | 建议 switch/单选 |
| `cacheDuration` | `Integer` | 单字段 | 表单项 | 是 | 秒级缓存时长 |
| `alarmEnabled` | `Integer` | 单字段 | 表单项 | 是 | 建议 switch/单选 |
| `alarmRule` | `String` | 列表字段的序列化载体 | 子表项 | 是 | 前端应编辑为 `alarmRule[]`，提交时序列化成 JSON 字符串 |
| `status` | `Integer` | 单字段 | 表单项 | 是 | `0/1/2/3`，建议映射成状态枚举 |
| `createTime` | `Date` | 单字段 | 只读项 | 否 | 创建时间 |
| `updateTime` | `Date` | 单字段 | 只读项 | 否 | 更新时间 |
| `precision` | `Integer` | 单字段 | 表单项 | 是 | 小数精度 |
| `remark` | `String` | 单字段 | 表单项 | 是 | 备注 |
| `additionalConfig` | `Map<String,Object>` | 对象字段 | 协议区/功能区 | 是 | 不能再整块做主编辑器，应拆开 |
| `baseCollectionInterval` | `Long` | 单字段 | 表单项 | 是 | 自适应采集基础间隔 |
| `currentCollectionInterval` | `long` | 单字段 | 只读项 | 否 | 运行态当前间隔 |
| `minCollectionInterval` | `Long` | 单字段 | 表单项 | 是 | 自适应采集最小间隔 |
| `maxCollectionInterval` | `Long` | 单字段 | 表单项 | 是 | 自适应采集最大间隔 |
| `pointChangeThreshold` | `Double` | 单字段 | 表单项 | 是 | 自适应变化阈值 |
| `stableCount` | `int` | 单字段 | 只读项 | 否 | 运行态稳定计数 |
| `lastValue` | `Object` | 单字段 | 只读项 | 否 | 运行态上次值 |
| `changeRate` | `double` | 单字段 | 只读项 | 否 | 运行态变化率 |
| `lastAdjustTime` | `long` | 单字段 | 只读项 | 否 | 运行态上次调整时间 |
| `reportFieldConflict` | `boolean` | 单字段 | 只读项 | 否 | 上报字段冲突后被降级为 raw-only 的标志 |

说明：

1. `configuredReportEnabled`、`configuredReportField`、`configuredChangeThreshold`、`reportConfigParsed` 等 `transient` 字段是解析缓存，不属于前端契约。
2. `currentCollectionInterval` 等运行态字段虽然会通过接口返回，但不应被编辑器当成配置源。

## 5. 复杂字段拆解结论

### 5.1 `alarmRule`

`alarmRule` 在模型里是 `String`，但实际业务语义是“规则列表”：

- 字段定义：`private String alarmRule`
- 业务读取：`getAlarmRule()` 会把它解析成 `List<AlarmRule>`

因此前端不应把它做成一个普通字符串输入框，而应做成子表。

#### 建议结构

`alarmRule[]`

每项字段：

| 字段 | 结构结论 | 说明 |
| --- | --- | --- |
| `ruleId` | 单字段 | 规则 ID |
| `ruleName` | 单字段 | 规则名称 |
| `operator` | 单字段 | `>`, `>=`, `<`, `<=`, `==`, `!=` |
| `threshold` | 单字段 | 阈值 |
| `duration` | 单字段 | 持续时间 |
| `level` | 单字段 | `INFO/WARNING/ERROR/CRITICAL` |
| `description` | 单字段 | 规则描述 |
| `enabled` | 单字段 | 是否启用 |
| `additionalConfig` | 对象字段 | 暂可做高级 JSON |

注意：

1. 当前代码实际使用的是 `operator`，不是注释中的 `comparison`。
2. 前端提交时应把 `alarmRule[]` 序列化为 JSON 字符串后回写到 `alarmRule` 顶层字段。

### 5.2 `additionalConfig`

`additionalConfig` 是一个对象字段，但不建议继续整体编辑。

推荐拆成三层：

1. 通用功能区
2. 协议区
3. 高级兼容字段

### 5.3 `additionalConfig.reportBindings`

这是另一个明确的列表字段。

来源：

- `ReportIdentityResolver.resolveBindings(...)`

建议建模为：

`additionalConfig.reportBindings[]`

每项字段：

| 字段 | 结构结论 | 说明 |
| --- | --- | --- |
| `deviceName` | 单字段 | 目标设备名 |
| `productKey` | 单字段 | 目标产品标识 |
| `reportProductKey` | 单字段 | 兼容字段，可内部归并 |

建议：

1. 新 UI 统一只展示 `deviceName` + `productKey`。
2. `reportProductKey` 作为兼容读入，不作为主输入。

## 6. `additionalConfig` 通用功能字段

以下字段不是协议专属，而是通用能力开关或行为参数。

| 字段 | 结构结论 | 前端归类 | 说明 |
| --- | --- | --- | --- |
| `reportEnabled` | 单字段 | 表单项 | 是否参与设备影子/属性上报 |
| `reportField` | 单字段 | 表单项 | 上报字段名；未配置时会回退到 `pointAlias` |
| `changeThreshold` | 单字段 | 表单项 | 属性变化上报阈值 |
| `changeMinIntervalMs` | 单字段 | 表单项 | 变化上报最小间隔 |
| `eventEnabled` | 单字段 | 表单项 | 是否允许事件上报 |
| `eventMinIntervalMs` | 单字段 | 表单项 | 事件上报最小间隔 |
| `reportBindings` | 列表字段 | 子表项 | 多目标上报绑定 |
| `reportDeviceName` | 兼容字段，可单值/数组/逗号串 | 高级区 | 老配置兼容，不建议作为主模型 |
| `productKey` | 兼容字段，可单值/数组 | 高级区 | 老配置兼容 |
| `reportProductKey` | 兼容字段，可单值/数组 | 高级区 | 老配置兼容 |
| `historyEnabled` | 单字段 | 表单项 | 是否写历史存储 |
| `streamEnabled` | 单字段 | 表单项 | 是否写 Redis Stream |
| `sourceUnit` | 单字段 | 表单项/高级区 | 单位转换源单位 |
| `configSource` | 单字段 | 只读项 | 本地临时配置内部标记 |
| `temporaryConfig` | 单字段 | 只读项 | 本地临时配置内部标记 |

## 7. 协议扩展字段梳理

本节只列“代码真实读取到的点位级扩展字段”。

### 7.1 MQTT

来源：`MqttPointOptions`

| 字段 | 结构结论 | 说明 |
| --- | --- | --- |
| `topic` | 单字段 | 订阅 topic，缺省回退到 `address` |
| `writeTopic` | 单字段 | 写入 topic |
| `qos` | 单字段 | QoS |
| `retain` | 单字段 | 是否 retained |
| `jsonPath` | 单字段 | JSON 载荷提取路径 |
| `payloadEncoding` | 单字段 | 载荷编码方式 |
| `publishTemplate` | 单字段 | 发布模板 |
| `charset` | 单字段 | 字符集 |

### 7.2 SNMP

来源：`SnmpAddressParser`

| 字段 | 结构结论 | 说明 |
| --- | --- | --- |
| `oid` | 单字段 | OID，缺省回退到 `address` |
| `snmpType` | 单字段 | SNMP 数据类型 |
| `dataType` | 单字段 | 兼容覆盖键，不建议与顶层 `dataType` 并存编辑 |

### 7.3 COAP

来源：`CoapAddressParser`

| 字段 | 结构结论 | 说明 |
| --- | --- | --- |
| `path` | 单字段 | 路径，缺省回退到 `address` |
| `method` | 单字段 | `GET/POST/PUT/DELETE` |
| `query` | 单字段 | query 参数 |
| `mediaType` | 单字段 | 媒体类型 |
| `observe` | 单字段 | 是否 Observe |
| `binary` | 单字段 | 是否按二进制处理 |

### 7.4 OPC UA / OPC_UA_PLC4X

来源：`Plc4xOpcUaAddressParser`、`OpcUaAddressParser`

| 字段 | 结构结论 | 说明 |
| --- | --- | --- |
| `nodeId` | 单字段 | 显式 NodeId |
| `id` | 单字段 | `nodeId` 兼容别名 |
| `namespace` / `ns` | 单字段 | namespace |
| `identifier` | 单字段 | 节点标识 |
| `identifierType` / `idType` | 单字段 | `s/i/g/b` |
| `opcUaType` / `opcType` / `nodeType` | 单字段 | OPC UA 驱动类型 |
| `dataType` | 单字段 | 兼容覆盖键，不建议与顶层重复编辑 |
| `samplingInterval` | 单字段 | 订阅采样周期 |
| `publishingInterval` | 单字段 | 兼容周期字段 |
| `queueSize` | 单字段 | 订阅队列长度 |
| `deadband` | 单字段 | 订阅死区 |
| `subscribe` / `monitor` | 单字段 | 是否订阅 |

说明：

1. `address` 仍应是 OPC UA 点位主字段。
2. `namespace + identifier + identifierType` 更适合做“替代型高级输入”，不是和 `address` 并排强制全填。

### 7.5 IEC104

来源：`Iec104Collector`、`docs/protocols/IEC104.md`

| 字段 | 结构结论 | 说明 |
| --- | --- | --- |
| `commonAddress` | 单字段 | 读公共地址，也可直接用顶层 `commonAddress` |
| `typeId` | 单字段 | 读类型 ID |
| `iecTypeId` | 单字段 | `typeId` 兼容别名 |
| `registerType` | 单字段 | 旧式类型兼容键，建议只做高级区 |
| `writeAddress` | 单字段 | 写入地址，推荐强类型地址 |
| `writeCommonAddress` | 单字段 | 写公共地址 |
| `writeQl` | 单字段 | 写命令限定词 |
| `writeSelect` | 单字段 | 选择/执行位 |
| `writeTimeTag` | 单字段 | 当前明确不支持，应禁止配置 |

说明：

1. `writeTimeTag` 当前代码遇到会直接报错，不应放进可编辑字段。
2. `commonAddress` 既有顶层字段也有 `additionalConfig.commonAddress`，新 UI 应统一只保留一个入口，优先顶层。

### 7.6 IEC61850

来源：`Iec61850AddressParser`

| 字段 | 结构结论 | 说明 |
| --- | --- | --- |
| `logicalDevice` | 单字段 | 逻辑设备 |
| `logicalNode` | 单字段 | 逻辑节点 |
| `dataObject` | 单字段 | 数据对象 |
| `dataAttribute` | 单字段 | 数据属性 |
| `subAttribute` | 单字段 | 子属性 |
| `fc` | 单字段 | Functional Constraint |
| `functionalConstraint` | 单字段 | `fc` 兼容别名 |
| `FC` | 单字段 | `fc` 兼容别名 |

### 7.7 SIEMENS_S7

来源：`S7AddressParser`

| 字段 | 结构结论 | 说明 |
| --- | --- | --- |
| `s7Type` | 单字段 | PLC4X 类型覆盖 |
| `plc4xType` | 单字段 | 类型兼容别名 |
| `plcType` | 单字段 | 类型兼容别名 |
| `stringLength` | 单字段 | `STRING/WSTRING` 长度 |
| `s7StringLength` | 单字段 | 长度兼容别名 |
| `plc4xAddress` | 单字段 | 地址兼容别名 |
| `s7Address` | 单字段 | 地址兼容别名 |

说明：

1. 新 UI 不建议把 `plc4xAddress`、`s7Address` 做成主输入。
2. 主输入仍应是顶层 `address`。

### 7.8 ADS

来源：`AdsAddressParser`

| 字段 | 结构结论 | 说明 |
| --- | --- | --- |
| `adsType` | 单字段 | PLC4X 类型覆盖 |
| `plc4xType` | 单字段 | 类型兼容别名 |
| `plcType` | 单字段 | 类型兼容别名 |
| `stringLength` | 单字段 | `STRING/WSTRING` 长度 |
| `adsStringLength` | 单字段 | 长度兼容别名 |
| `arraySize` | 单字段 | 数组长度 |
| `numberOfElements` | 单字段 | 数组长度兼容别名 |
| `plc4xAddress` | 单字段 | 地址兼容别名 |
| `adsAddress` | 单字段 | 地址兼容别名 |
| `amsAddress` | 单字段 | 地址兼容别名 |

说明：

1. 当前采集器仍以 scalar point 为主，数组相关字段先放高级区。

### 7.9 ETHERNET_IP

来源：`EtherNetIpAddressParser`

| 字段 | 结构结论 | 说明 |
| --- | --- | --- |
| `eipType` | 单字段 | 类型覆盖 |
| `logixType` | 单字段 | 类型兼容别名 |
| `plc4xType` | 单字段 | 类型兼容别名 |
| `plcType` | 单字段 | 类型兼容别名 |
| `plc4xAddress` | 单字段 | 地址兼容别名 |
| `etherNetIpAddress` | 单字段 | 地址兼容别名 |
| `logixAddress` | 单字段 | 地址兼容别名 |
| `tagName` | 单字段 | 地址兼容别名 |

### 7.10 KNXNET_IP

来源：`KnxAddressParser`

| 字段 | 结构结论 | 说明 |
| --- | --- | --- |
| `dpt` | 单字段 | KNX DPT |
| `dptId` | 单字段 | DPT 兼容别名 |
| `knxDpt` | 单字段 | DPT 兼容别名 |
| `groupAddress` | 单字段 | 地址兼容别名 |
| `plc4xAddress` | 单字段 | 地址兼容别名 |
| `knxAddress` | 单字段 | 地址兼容别名 |

### 7.11 MODBUS / HTTP / WEBSOCKET / CUSTOM_TCP

当前代码里没有稳定的点位级 `additionalConfig` schema 可以作为第一版正式界面字段。

建议：

1. 第一版只展示通用字段。
2. 协议私有扩展先保留一个“高级 JSON”兜底区。
3. 等协议字段真正沉淀后，再把它们转成受控表单。

## 8. 非稳定字段与样例残留字段

仓库里的部分 mock JSON 出现了以下字段：

- `registerType`
- `defaultValue`
- `stringLength`

需要分开看：

### 8.1 `registerType`

它不是 `DataPoint` 顶层正式字段。

现状：

1. 顶层 `registerType` 不在 `DataPoint` 类中。
2. 文件配置加载开启了 `FAIL_ON_UNKNOWN_PROPERTIES=false`，因此顶层 `registerType` 可能被静默忽略。
3. IEC104 当前真正识别的是 `additionalConfig.registerType`。

结论：

- 不要把顶层 `registerType` 做成正式表单字段。
- 若后续确需兼容老 JSON，可在导入阶段做迁移映射。

### 8.2 `defaultValue`

它也不是 `DataPoint` 顶层正式字段。

结论：

- 当前不建议进入正式点位编辑器。
- 如果后续业务真的需要默认值，应该先在后端模型中正式化。

### 8.3 `stringLength`

它不是顶层字段，但在 S7/ADS 里是协议扩展字段。

结论：

- 不能做成通用顶层字段。
- 只能出现在对应协议区里。

## 9. 前端字段 Schema 草案

本节直接给出后续页面建模的输出结构，按“列表列 / 表单项 / 子表项 / 只读项 / 协议区”五类整理。

### 9.1 列表列

列表页建议只展示高频识别字段，避免把复杂配置挤进主表。

| 列名 | 来源字段 | 说明 |
| --- | --- | --- |
| 点位名称 | `pointName` | 主显示字段 |
| 点位编码 | `pointCode` | 业务识别字段 |
| 点位标识 | `pointId` | 系统标识 |
| 协议地址 | `address` | 点位主地址 |
| 数据类型 | `dataType` | 类型 |
| 读写权限 | `readWrite` | `R/W/RW` |
| 采集模式 | `collectionMode` | `POLLING/SUBSCRIPTION/EVENT` |
| 状态 | `status` | 启用/禁用/维护/异常 |
| 上报字段 | `additionalConfig.reportField` 或 `getReportField()` | 用于识别影子字段 |
| 缓存 | `cacheEnabled` | 开关态 |
| 告警 | `alarmEnabled` | 开关态 |
| 协议扩展摘要 | 协议区派生 | 例如 MQTT topic、IEC104 commonAddress、KNX DPT |

说明：

1. `pointAlias` 可以放在次级列或 tooltip，不建议占主表固定列。
2. `unit`、`precision`、`priority` 更适合详情侧边栏，不建议默认列出。

### 9.2 表单项

表单项建议按业务分组，而不是机械按 Java 字段顺序平铺。

#### A. 基础信息

| 字段 | 说明 |
| --- | --- |
| `pointId` | 必填 |
| `pointCode` | 必填 |
| `pointName` | 必填 |
| `pointAlias` | 可选 |
| `address` | 必填 |
| `dataType` | 必填 |
| `readWrite` | 必填 |
| `collectionMode` | 必填 |
| `status` | 必填 |
| `remark` | 可选 |

#### B. 数值处理

| 字段 | 说明 |
| --- | --- |
| `unit` | 单位 |
| `scalingFactor` | 缩放因子 |
| `offset` | 偏移量 |
| `deadband` | 通用死区 |
| `minValue` | 最小值 |
| `maxValue` | 最大值 |
| `precision` | 小数精度 |
| `additionalConfig.sourceUnit` | 源单位 |

#### C. 采集策略

| 字段 | 说明 |
| --- | --- |
| `baseCollectionInterval` | 基础采集间隔 |
| `minCollectionInterval` | 最小采集间隔 |
| `maxCollectionInterval` | 最大采集间隔 |
| `pointChangeThreshold` | 自适应变化阈值 |
| `priority` | 优先级 |

#### D. 缓存与落地

| 字段 | 说明 |
| --- | --- |
| `cacheEnabled` | 是否缓存 |
| `cacheDuration` | 缓存时长 |
| `additionalConfig.historyEnabled` | 是否写历史 |
| `additionalConfig.streamEnabled` | 是否写实时流 |

#### E. 上报与影子

| 字段 | 说明 |
| --- | --- |
| `additionalConfig.reportEnabled` | 是否参与属性上报 |
| `additionalConfig.reportField` | 上报字段名 |
| `additionalConfig.changeThreshold` | 变化上报阈值 |
| `additionalConfig.changeMinIntervalMs` | 变化上报最小间隔 |
| `additionalConfig.eventEnabled` | 是否启用事件 |
| `additionalConfig.eventMinIntervalMs` | 事件最小间隔 |

#### F. 高级字段

| 字段 | 说明 |
| --- | --- |
| `unitId` | 协议相关 |
| `commonAddress` | IEC104 常用 |
| `groupId` | 分组标识 |

### 9.3 子表项

子表项是本次可视化改造里必须单独处理的部分。

#### A. `alarmRule[]`

| 字段 | 说明 |
| --- | --- |
| `ruleId` | 规则 ID |
| `ruleName` | 规则名称 |
| `operator` | 比较操作符 |
| `threshold` | 阈值 |
| `duration` | 持续时间 |
| `level` | 告警级别 |
| `description` | 描述 |
| `enabled` | 是否启用 |

#### B. `additionalConfig.reportBindings[]`

| 字段 | 说明 |
| --- | --- |
| `deviceName` | 目标设备名 |
| `productKey` | 目标产品标识 |

### 9.4 只读项

以下字段可以在详情页展示，但不应进入编辑表单：

| 字段 | 说明 |
| --- | --- |
| `id` | 历史/持久化标识 |
| `deviceId` | 当前设备上下文派生 |
| `deviceName` | 当前设备上下文派生 |
| `createTime` | 创建时间 |
| `updateTime` | 更新时间 |
| `currentCollectionInterval` | 当前运行态采集间隔 |
| `stableCount` | 稳定计数 |
| `lastValue` | 上次值 |
| `changeRate` | 当前变化率 |
| `lastAdjustTime` | 上次调整时间 |
| `reportFieldConflict` | 上报字段冲突标志 |
| `additionalConfig.configSource` | 内部配置来源 |
| `additionalConfig.temporaryConfig` | 本地临时配置标志 |

### 9.5 协议区

协议区按当前设备协议动态展示，只显示对应协议的点位扩展字段。

#### MQTT 协议区

- `topic`
- `writeTopic`
- `qos`
- `retain`
- `jsonPath`
- `payloadEncoding`
- `publishTemplate`
- `charset`

#### SNMP 协议区

- `oid`
- `snmpType`

#### COAP 协议区

- `path`
- `method`
- `query`
- `mediaType`
- `observe`
- `binary`

#### OPC UA 协议区

- `nodeId`
- `namespace/ns`
- `identifier`
- `identifierType/idType`
- `opcUaType/opcType/nodeType`
- `samplingInterval/publishingInterval`
- `queueSize`
- `deadband`
- `subscribe/monitor`

#### IEC104 协议区

- `commonAddress`
- `typeId`
- `iecTypeId`
- `registerType`
- `writeAddress`
- `writeCommonAddress`
- `writeQl`
- `writeSelect`

#### IEC61850 协议区

- `logicalDevice`
- `logicalNode`
- `dataObject`
- `dataAttribute`
- `subAttribute`
- `fc`

#### S7 协议区

- `s7Type`
- `stringLength`

#### ADS 协议区

- `adsType`
- `stringLength`
- `arraySize`

#### ETHERNET_IP 协议区

- `eipType`
- `logixType`

#### KNXNET_IP 协议区

- `dpt`
- `dptId`
- `knxDpt`

#### 其他协议

- 第一版先不单独拆字段
- 仅保留“高级 JSON 扩展”兜底

## 10. 推荐的前端实现原则

1. 点位配置的主入口必须从 JSON 文本框升级为“点位列表 + 点位详情表单”。
2. 顶层字段以 `DataPoint` 为准，不以 mock JSON 为准。
3. `additionalConfig` 要拆开，不要继续把全部协议配置塞进一个文本框。
4. `alarmRule` 和 `reportBindings` 必须做子表，不要做字符串输入。
5. `deviceId/deviceName` 等上下文字段只读，避免前端和当前设备选择状态打架。
6. 协议区只按当前协议显示，避免一个点位表单同时出现 MQTT topic、KNX DPT、IEC104 writeAddress 这种互斥字段。
7. 对于兼容别名字段，应采用“读兼容、写收敛”的策略：
   - 读取时兼容老字段
   - 保存时统一写新字段

## 11. 最小可落地范围建议

如果只做第一版，可先收敛到以下范围：

1. 列表页支持新增、编辑、删除、排序。
2. 详情页先完成通用字段。
3. 子表先完成：
   - `alarmRule[]`
   - `reportBindings[]`
4. 协议区先完成高频协议：
   - MQTT
   - OPC UA
   - IEC104
   - KNXNET_IP
5. 其余协议先保留高级 JSON 兜底。

这样可以先摆脱“只能配 JSON”的状态，同时不需要一口气做完所有协议细枝末节。

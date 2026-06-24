# 点位配置可视化字段梳理与前端字段 Schema 草案

## 1. 文档定位

本文档负责回答两个问题：

1. `DataPoint` 的稳定前端编辑字段到底有哪些。
2. 这些字段在页面里应该按列表列、详情表单、子表、只读项、协议扩展区中的哪一种方式呈现。

关于协议类型字段如何拆成 `dataType`、`driverDataType`、`dptId` 以及如何通过 schema 驱动前端，最终规则见：

- [20-点位类型与协议原生类型最终规则](./20-点位类型与协议原生类型最终规则.md)

## 2. 当前落地现状

当前本地临时设备编辑页已经不再只是一个点位 JSON 文本框，而是开始按 `ProtocolSchema` 输出的元数据驱动：

- `dataTypes`
- `typeMode`
- `primaryTypeField`
- `platformDataTypeMode`
- `driverTypeEnabled`
- `driverTypeLabel`
- `driverTypeField`
- `driverDataTypes`
- `pointFields`

相关代码：

- `src/main/resources/static/admin/app.js`
- `src/main/resources/static/admin/local-point-editor.js`
- `src/main/java/com/wangbin/collector/core/config/protocol/ProtocolSchema.java`
- `src/main/java/com/wangbin/collector/core/config/protocol/ProtocolDescriptorRegistry.java`

## 3. `DataPoint` 稳定字段分组

### 3.1 基础信息区

| 字段 | 说明 |
| --- | --- |
| `pointCode` | 点位编码，建议必填 |
| `pointName` | 点位名称，建议必填 |
| `pointAlias` | 点位别名 |
| `address` | 协议地址核心字段 |
| `readWrite` | `R/W/RW` |
| `collectionMode` | `POLLING/SUBSCRIPTION/EVENT` |
| `status` | 启用/禁用/维护/异常 |
| `remark` | 备注 |

说明：

- 类型字段不再在这里固定写死为 `dataType`，而是由 `ProtocolSchema.typeMode` 决定主展示字段是谁。

### 3.2 数据处理区

| 字段 | 说明 |
| --- | --- |
| `dataType` | 平台统一类型；展示方式由 `platformDataTypeMode` 决定 |
| `unit` | 单位 |
| `additionalConfig.sourceUnit` | 源单位 |
| `scalingFactor` | 缩放系数 |
| `offset` | 偏移量 |
| `deadband` | 通用死区 |
| `minValue` | 最小值 |
| `maxValue` | 最大值 |
| `precision` | 小数位 |
| `unitId` | 协议相关高级字段 |
| `commonAddress` | IEC104 常用高级字段 |

### 3.3 上报 / 缓存区

| 字段 | 说明 |
| --- | --- |
| `priority` | 优先级 |
| `cacheEnabled` | 是否缓存 |
| `cacheDuration` | 缓存时长 |
| `additionalConfig.reportEnabled` | 是否参与设备上报 |
| `additionalConfig.reportField` | 上报字段名 |
| `additionalConfig.changeThreshold` | 变化上报阈值 |
| `additionalConfig.changeMinIntervalMs` | 变化上报最小间隔 |
| `additionalConfig.eventEnabled` | 是否启用事件上报 |
| `additionalConfig.eventMinIntervalMs` | 事件最小间隔 |
| `additionalConfig.historyEnabled` | 是否写历史存储 |
| `additionalConfig.streamEnabled` | 是否写 Redis Stream |

### 3.4 采集策略区

| 字段 | 说明 |
| --- | --- |
| `baseCollectionInterval` | 基础采集间隔 |
| `minCollectionInterval` | 最小采集间隔 |
| `maxCollectionInterval` | 最大采集间隔 |
| `pointChangeThreshold` | 自适应变化阈值 |

### 3.5 只读项

| 字段 | 说明 |
| --- | --- |
| `id` | 历史/持久化标识 |
| `pointId` | 系统标识 |
| `deviceId` | 当前设备上下文带出 |
| `deviceName` | 当前设备上下文带出 |
| `createTime` / `updateTime` | 时间戳 |
| `currentCollectionInterval` | 当前运行态采集间隔 |
| `stableCount` | 稳定计数 |
| `lastValue` | 上次值 |
| `changeRate` | 当前变化率 |
| `lastAdjustTime` | 上次调整时间 |
| `reportFieldConflict` | 上报字段冲突标志 |
| `additionalConfig.configSource` | 内部配置来源 |
| `additionalConfig.temporaryConfig` | 本地临时配置标志 |

## 4. 必须做成子表的字段

### 4.1 `alarmRule[]`

后端 `DataPoint.alarmRule` 存的是 JSON 字符串，但实际业务语义是规则列表，因此前端必须做成子表。

建议子项字段：

- `ruleId`
- `ruleName`
- `operator`
- `threshold`
- `duration`
- `level`
- `description`
- `enabled`

### 4.2 `additionalConfig.reportBindings[]`

这是另一个明确的列表字段。

建议子项字段：

- `deviceName`
- `productKey`

说明：

- `reportProductKey` 只作为兼容读入字段，不作为主输入字段。

## 5. 协议区的最终设计方式

本轮之后，协议区不再直接按各协议旧别名字段硬编码，而是优先走 `ProtocolSchema.pointFields`。

### 5.1 协议区的展示来源

| schema 字段 | 作用 |
| --- | --- |
| `typeMode` | 决定主类型字段模式 |
| `primaryTypeField` | 指定前端主展示的类型字段路径 |
| `platformDataTypeMode` | 决定 `dataType` 是必填、可推导还是高级区展示 |
| `driverTypeEnabled` | 是否启用统一协议原生类型输入区 |
| `driverTypeLabel` | 协议原生类型字段名 |
| `driverTypeField` | 协议原生类型写回路径 |
| `driverDataTypes` | 协议原生类型候选列表 |
| `pointFields` | 协议点位扩展字段 |

### 5.2 已经收敛掉的旧思路

以下内容不再作为正式前端字段设计方向：

1. 每个协议都在前端单独发明一个不同的 top-level datatype 字段。
2. 把 `s7Type`、`adsType`、`eipType`、`opcUaType` 之类兼容键直接暴露成主编辑字段。
3. 把 `additionalConfig` 整块作为一个 JSON 文本框长期保留为主编辑方式。

这些兼容键仍然可能存在于驱动解析逻辑中，但前端主配置已经改为：

- 平台统一 `dataType`
- 统一 `driverDataType` 入口
- `KNXNET_IP` 这类协议专属主类型字段
- `pointFields` 协议扩展字段

## 6. 第一版前端页面建模建议

### 6.1 列表列

建议主表列只放高频识别信息：

- 点位名称
- 点位编码
- 协议地址
- 类型摘要
- 读写权限
- 状态

说明：

- 类型摘要建议按 schema 输出：
  - `PLATFORM_ONLY`：显示 `dataType`
  - `DRIVER_PRIMARY`：显示 `driverDataType / dataType`
  - `PROTOCOL_FIELD_PRIMARY`：显示协议主类型字段 / `dataType`

### 6.2 详情表单

建议详情页按以下顺序组织：

1. 基础信息
2. 数据处理
3. 上报 / 缓存
4. 告警规则
5. 协议扩展
6. 只读项

### 6.3 协议扩展区

协议扩展区的职责是：

- 展示 `pointFields`
- 展示协议地址示例
- 说明当前协议主类型字段是谁
- 不重复展示已经提升到基础信息区的主类型字段

## 7. 仍然保留的兼容事实

虽然前端主配置已经切到 schema 驱动，但后端驱动层仍然会兼容读取一些历史键，例如：

- S7：`s7Type` / `plc4xType` / `plcType`
- ADS：`adsType` / `plc4xType` / `plcType`
- EtherNet/IP：`eipType` / `logixType` / `plc4xType` / `plcType`
- OPC UA：`opcUaType` / `opcType` / `nodeType`
- KNX：`dpt` / `dptId`

这些兼容键的定位是：

- 后端读兼容
- schema 写收敛
- 前端不再把它们逐个暴露成第一优先级表单字段

## 8. 当前结论

可以把这轮改造后的前端字段策略概括成一句话：

- 顶层字段以 `DataPoint` 为准
- 类型展示以 `ProtocolSchema` 为准
- 协议差异以 `pointFields` 为准
- 历史兼容键只保留在后端解析层，不再继续扩散成新的前端主字段
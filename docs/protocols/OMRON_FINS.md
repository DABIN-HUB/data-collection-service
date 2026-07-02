# OMRON FINS 第一版协议设计草案

## 1. 文档定位

本文档不是“已实现说明”，而是 `Omron FINS` 接入当前采集框架的第一版设计草案。

目标：

1. 明确 `FINS` 第一版在本项目中的能力边界。
2. 明确如何融合到当前统一采集框架。
3. 明确配置字段、点位地址语法、数据类型和类结构。
4. 为后续编码实现提供直接落地的设计输入。

当前判断：

1. `Omron FINS` 非常适合作为下一个协议接入。
2. 当前未确认存在成熟、主流、可直接依赖的 Java 协议栈 JAR。
3. 第一版建议按“轻量自研协议实现”推进，而不是等待现成库。

## 2. 第一版目标范围

### 2.1 第一版必须实现

第一版目标先聚焦“可交付采集闭环”，不追求覆盖所有 `FINS` 命令。

必须实现：

1. `FINS/UDP` 连接与请求应答通信。
2. PLC 内存区的单点/连续批量读取。
3. PLC 内存区的单点/连续批量写入。
4. 常见数值类型和位类型解码。
5. 统一接入当前框架的调度、缓存、告警、上报、实时流链路。
6. 统一接入当前框架的配置治理、监控、状态查询链路。

### 2.2 第一版暂不实现

第一版不做以下内容：

1. `FINS/TCP`
2. 时间同步等扩展命令
3. 文件传输类命令
4. 程序上传下载类命令
5. 路由表维护和复杂跨网段网关场景
6. PLC 编程态/监控态的完整控制能力

理由：

1. 第一版必须优先打通“读写点 -> 处理 -> 上报”的主闭环。
2. `FINS/UDP` 更简单，更适合作为先落地的版本。
3. 先稳定地址模型和数据模型，再扩展 `TCP` 与高级命令。

## 3. 协议定位与框架适配判断

`Omron FINS` 比 `PROFINET / EtherCAT` 更像传统 PLC 请求应答协议，和当前框架天然匹配。

匹配点：

1. 有明确的连接目标。
2. 有明确的读写命令。
3. 点位可以抽象成“内存区 + 地址 + 数据类型”。
4. 非实时硬循环控制协议，不要求我们引入新的控制器状态机架构。

所以第一版实现不需要改造主框架，只需要作为新的协议适配器接入：

1. `CollectionScheduler`
2. `CollectionManager`
3. `CollectorFactory`
4. `ConnectionFactory`
5. `BaseCollector`
6. `CollectorDataCacheAspect`

## 4. 第一版功能边界

### 4.1 读能力

第一版建议支持：

1. bit 读
2. word 读
3. 连续 word 批量读
4. 连续 bit 批量读

面向采集点的典型能力：

1. 读取 `BOOL`
2. 读取 `INT16 / UINT16`
3. 读取 `INT32 / UINT32`
4. 读取 `FLOAT32`
5. 读取 `STRING`

### 4.2 写能力

第一版建议支持：

1. 单 bit 写
2. 单 word 写
3. 连续 word 批量写

第一版不强求复杂结构写入。

### 4.3 设备信息与状态

第一版建议支持以下状态查询能力：

1. 连接状态
2. 请求成功数
3. 请求失败数
4. 超时数
5. 重试数
6. 最后一次活动时间
7. 最后错误码
8. PLC 目标地址信息

## 5. 当前框架中的实现位置

### 5.1 协议代码目录建议

建议新增：

- `src/main/java/com/wangbin/collector/core/collector/protocol/fins`
- `src/main/java/com/wangbin/collector/core/collector/protocol/fins/codec`
- `src/main/java/com/wangbin/collector/core/collector/protocol/fins/domain`
- `src/main/java/com/wangbin/collector/core/collector/protocol/fins/service`
- `src/main/java/com/wangbin/collector/core/collector/protocol/fins/support`

### 5.2 连接层建议

建议新增：

- `src/main/java/com/wangbin/collector/core/connection/adapter/OmronFinsUdpConnectionAdapter.java`

第一版只做 `UDP`，不立即增加 `TCP` adapter。

### 5.3 Collector 建议

建议新增：

- `src/main/java/com/wangbin/collector/core/collector/protocol/fins/OmronFinsCollector.java`

职责：

1. 点位地址解析
2. 批量读计划生成
3. 读写请求编排
4. 结果映射为统一 `ProcessResult`
5. 暴露协议状态与专项指标

### 5.4 Factory / Registry 接入点

必须接入：

1. `CollectorFactory`
2. `ConnectionFactory`
3. `ProtocolDescriptorRegistry`
4. `ProtocolConnectionValidator`
5. 协议字段文档汇总

## 6. 第一版连接模型设计

### 6.1 连接类型

第一版协议名建议：

- `OMRON_FINS`

第一版传输层：

- `FINS/UDP`

### 6.2 连接配置字段建议

建议字段如下：

1. `host`
   - PLC IP 地址
2. `port`
   - 默认 `9600`
3. `connectTimeout`
4. `readTimeout`
5. `timeout`
6. `retries`

扩展字段建议放在 `extJson`：

1. `transport`
   - 固定为 `UDP`
2. `plcNode`
   - 目标 PLC 节点号
3. `plcUnit`
   - 目标单元号，默认 `0`
4. `plcNetwork`
   - 目标网络号，默认 `0`
5. `localNode`
   - 本机节点号
6. `localUnit`
   - 本机单元号，默认 `0`
7. `localNetwork`
   - 本机网络号，默认 `0`
8. `serviceIdSeed`
   - 请求 `SID` 起始值
9. `batchReadMaxWords`
   - 批量读最大 word 数
10. `batchWriteMaxWords`
   - 批量写最大 word 数

### 6.3 连接校验规则

校验器建议至少校验：

1. `host` 非空
2. `port` 合法，默认 `9600`
3. `plcNode` 合法
4. `localNode` 合法
5. `timeout/retries` 合法

## 7. 点位地址模型设计

### 7.1 地址设计原则

地址必须做到：

1. 人类可读
2. 能唯一映射协议原生地址
3. 能支持 bit/word/连续块
4. 能支持批量规划

### 7.2 第一版建议地址语法

推荐统一格式：

```text
<AREA>:<WORD>[.<BIT>][#<LENGTH>]
```

示例：

```text
DM:100
DM:100#4
DM:100.FLOAT
CIO:20.3
WR:10
HR:200#2
AR:50
EM0:100
```

但为了和当前框架的数据类型分离，最终建议地址只负责原生位置，不把类型直接塞进地址里。

最终建议格式：

```text
<AREA>:<WORD>[.<BIT>][#<LENGTH>]
```

数据类型继续放在 `DataPoint.dataType`。

例如：

1. `DM:100`
   - 一个 word 起始地址
2. `DM:100#2`
   - 连续 2 个 word
3. `CIO:20.3`
   - `CIO` 区第 20 word 第 3 bit
4. `WR:10#8`
   - `WR` 区连续 8 word
5. `EM0:100#4`
   - 扩展内存区

### 7.3 第一版建议支持的内存区

建议第一版支持：

1. `CIO`
2. `WR`
3. `HR`
4. `AR`
5. `DM`
6. `EM0` ~ `EMF`

第一版先不做全部历史兼容区，按常用区落地。

## 8. 点位数据类型设计

### 8.1 第一版支持的数据类型

建议支持：

1. `BOOL`
2. `INT16`
3. `UINT16`
4. `INT32`
5. `UINT32`
6. `FLOAT`
7. `DOUBLE`
8. `STRING`

### 8.2 解码规则建议

建议统一规则：

1. bit 地址只允许映射 `BOOL`
2. `INT16 / UINT16` 占 1 word
3. `INT32 / UINT32 / FLOAT` 占 2 word
4. `DOUBLE` 占 4 word
5. `STRING` 通过 `additionalConfig.stringLength` 或地址 `#length` 共同决定长度

### 8.3 `additionalConfig` 建议

建议支持：

1. `wordOrder`
   - `HIGH_LOW` / `LOW_HIGH`
2. `byteOrder`
   - `BIG_ENDIAN` / `LITTLE_ENDIAN`
3. `stringLength`
4. `stringEncoding`
   - 默认 `ASCII`
5. `bcd`
   - 是否按 BCD 解码
6. `signed`
   - 某些数值型兼容控制

## 9. 批量读写与调度接入策略

### 9.1 批量读原则

`FINS` 非常适合按连续地址合并批量读取。

建议策略：

1. 按内存区分组
2. 按 word 地址排序
3. 尽量合并连续 word
4. bit 点优先按所属 word 合并
5. 大跨度地址不强行合并

### 9.2 与当前调度器的融合方式

沿用当前框架：

1. `CollectionScheduler` 负责时间片调度
2. `DeviceBatchPlanner` 负责点位切批
3. `OmronFinsCollector.rebuildReadPlans(...)` 负责生成协议级读计划

建议增加：

1. FINS 连续块规划器
2. 读取块到点位的结果回填器

### 9.3 批量写原则

第一版只做：

1. 同内存区连续地址合并写
2. 非连续地址回退为单点写

## 10. 与当前数据处理链的融合

### 10.1 采集主链不改

`Omron FINS` 必须继续走现有统一主链：

1. `readPoint/readPoints`
2. `convertData`
3. `DataQualityProcessor`
4. `ProcessResult`
5. `CollectorDataCacheAspect`

### 10.2 推送链路

第一版 `FINS` 按轮询协议处理，不设计推送模型。

所以：

1. 不需要类似 `COV` 的订阅模型
2. 不需要新增 `TelemetryIngressService` 推送入口

## 11. 协议类设计草图

### 11.1 domain 层建议

建议新增：

1. `FinsAddress`
2. `FinsMemoryArea`
3. `FinsRequest`
4. `FinsResponse`
5. `FinsReadRequest`
6. `FinsReadResponse`
7. `FinsWriteRequest`
8. `FinsStatusResponse`

### 11.2 codec 层建议

建议新增：

1. `FinsFrameCodec`
2. `FinsCommandCodec`
3. `FinsAddressParser`
4. `FinsDataDecoder`
5. `FinsDataEncoder`

### 11.3 service 层建议

建议新增：

1. `FinsReadPlanBuilder`
2. `FinsBatchMergeService`
3. `FinsValueMapper`
4. `FinsRequestSession`

### 11.4 connection 层建议

建议新增：

1. `OmronFinsUdpConnectionAdapter`
2. `FinsUdpClient`

### 11.5 collector 层建议

建议新增：

1. `OmronFinsCollector`

Collector 主要职责：

1. 地址解析与校验
2. 批量读写编排
3. 调用 adapter
4. 统一结果转换
5. 状态暴露

## 12. 第一版监控指标建议

建议在 `getDeviceStatus()/protocolMetrics` 中暴露：

1. `requestCount`
2. `requestSuccessCount`
3. `requestErrorCount`
4. `requestTimeoutCount`
5. `requestRetryCount`
6. `batchReadCount`
7. `batchWriteCount`
8. `mergedPointCount`
9. `singlePointFallbackCount`
10. `lastFinsResponseCode`

## 13. 控制台前端与配置治理改造范围

### 13.1 当前判断

`Omron FINS` 不是只做后端协议类就算完成，控制台前端也必须同步纳入第一版范围。

原因：

1. 当前项目的协议配置页是 schema 驱动，不是后端写完 collector 就自动可用。
2. `FINS` 有自己的连接参数、地址语法和点位数据类型边界，前端必须能正确渲染和校验。
3. 如果前端不配套，现场配置、联调、导入导出和问题排查都会卡住。

### 13.2 第一版必须覆盖的前端范围

第一版必须同步覆盖以下前端能力：

1. 协议下拉中可选择 `OMRON_FINS`
2. 连接配置页可动态渲染 `OMRON_FINS` schema
3. 点位编辑页能正确录入 `FINS` 地址
4. 点位编辑页能正确选择 `dataType`
5. 状态页能展示 `FINS` 协议专项指标
6. 导入导出模板能承载 `FINS` 连接字段和点位地址
7. 页面显式提示第一版能力边界

### 13.3 后端 schema 对前端的要求

后端必须通过 `ProtocolDescriptorRegistry` / `ProtocolSchemaService` 暴露完整 schema，前端不能单独硬编码一套 `FINS` 表单。

第一版必须补齐的 schema 元数据包括：

1. 协议标题和说明
2. 是否支持写入
3. 连接字段列表
4. 点位地址提示
5. 点位类型策略
6. `driverTypeEnabled / driverDataTypes / primaryTypeField`
7. 示例地址与中文说明
8. 第一版不支持项的页面提示文案

### 13.4 连接配置页需要支持的字段

控制台连接配置页应至少支持：

1. `host`
2. `port`
3. `connectTimeout`
4. `readTimeout`
5. `timeout`
6. `retries`
7. `plcNode`
8. `plcUnit`
9. `plcNetwork`
10. `localNode`
11. `localUnit`
12. `localNetwork`
13. `batchReadMaxWords`
14. `batchWriteMaxWords`

前端要求：

1. 顶层字段与 `extJson` 保存落点必须继续由 schema 驱动。
2. 不允许前端自己维护 `FINS` 顶层字段白名单。
3. 端口默认值应自动带出 `9600`。

### 13.5 点位编辑页需要支持的内容

点位编辑页必须支持：

1. 地址输入框
2. 地址格式帮助说明
3. `dataType` 下拉
4. 协议原生扩展配置区
5. 地址示例
6. 非法地址即时校验提示

建议展示的地址示例：

```text
DM:100
DM:100#2
CIO:20.3
WR:10#8
EM0:100#4
```

### 13.6 前端校验建议

前端至少应做基础校验：

1. 地址非空
2. 内存区格式合法
3. bit 地址只能搭配 `BOOL`
4. `STRING` 必须要求长度配置
5. `INT32/FLOAT/DOUBLE` 对应长度不足时给出提示

注意：

1. 前端校验只是辅助，最终合法性仍必须由后端 collector 和 validator 校验。
2. 不允许只靠前端校验决定协议语义。

### 13.7 设备状态页需要展示的内容

控制台设备状态页建议增加 `FINS` 协议专项指标：

1. `requestCount`
2. `requestSuccessCount`
3. `requestErrorCount`
4. `requestTimeoutCount`
5. `requestRetryCount`
6. `batchReadCount`
7. `batchWriteCount`
8. `mergedPointCount`
9. `singlePointFallbackCount`
10. `lastFinsResponseCode`

### 13.8 导入导出与模板

第一版必须考虑导入导出：

1. 设备连接导入模板要包含 `FINS` 连接字段
2. 点位模板要支持 `FINS` 地址语法
3. 示例模板要至少提供一份 `DM/CIO/WR` 区示例

### 13.9 前端风险提示文案建议

第一版页面建议显式提示：

1. 当前仅支持 `FINS/UDP`
2. 当前不支持 `FINS/TCP`
3. 当前仅覆盖常用内存区读写
4. 复杂命令、文件传输、程序操作不在第一版范围内

### 13.10 与当前框架的融合结论

前端层的正确融合方式是：

1. 后端新增 `OMRON_FINS` descriptor
2. schema 接口自动暴露连接字段和点位字段元数据
3. 前端复用当前动态协议表单能力
4. 前端只补 `FINS` 特有说明、示例和状态展示

也就是说：

1. 不需要为 `FINS` 单独发明一套配置页面架构
2. 但必须补协议专属的 schema、帮助文案和状态展示

## 14. 第一版测试策略

### 13.1 必须有的测试

1. 地址解析测试
2. codec 编解码测试
3. 连续地址合并测试
4. 数据类型解码测试
5. collector 读写集成测试
6. mock UDP server 集成测试

### 13.2 建议增加的测试

1. 不同字节序/字序测试
2. bit/word 混合块读取测试
3. 批量写回退测试
4. 超时重试测试
5. 非法响应码测试

## 15. 第一版实施顺序建议

建议分四批实施：

### 第一批

1. 协议字段注册
2. 地址解析器
3. `FINS/UDP` 基础连接
4. 单点读

### 第二批

1. 连续块批量读
2. 常见类型解码
3. collector 接入框架
4. 状态与监控指标

### 第三批

1. 单点写
2. 连续块写
3. 写失败回退

### 第四批

1. 测试补齐
2. 文档补齐
3. 真机联调
4. 评估 `FINS/TCP`

## 16. 当前设计结论

一句话总结：

`Omron FINS` 第一版最合理的落地方式，是作为“标准轮询型 PLC 协议”接入当前框架，先做 `FINS/UDP + 常用内存区读写 + 批量连续块优化`，不改变当前调度/缓存/告警/上报主链路。

这条路线的优点：

1. 风险可控
2. 和当前框架匹配度高
3. 可以较快形成真实可交付协议
4. 后续再扩展 `FINS/TCP` 也不会推翻第一版架构

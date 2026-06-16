# 架构优化执行计划

本文档用于把当前架构扫描中识别出的优化点拆成可执行计划。原则是先做收口型优化，避免一次性大拆架构；优先解决会影响采集稳定性、上报完整性和后续协议扩展的问题。

## 1. 优化目标

1. 降低采集后处理链路的耦合度，避免缓存、实时流、历史存储、上报互相拖累。
2. 修正 `point.needCache()` 作为总入口导致的功能耦合，保证缓存、Stream、历史、上报可以独立开关。
3. 统一协议描述能力，减少协议新增或迁移时在多个工厂、文档、字段配置中重复维护。
4. 强化设备生命周期、连接生命周期、告警事件和背压治理，提升实服稳定性。
5. 保持现有主链路兼容，优先小步提交、可回滚、可验证。

## 2. 总体执行顺序

```text
P0 后处理链路收口
  -> 拆 CollectorDataPostProcessor pipeline
  -> 拆分 cache / stream / history / report 独立开关
  -> 补充单元测试和失败隔离测试

P1 协议扩展治理
  -> 抽 ProtocolDescriptor
  -> 下沉协议批次策略
  -> 统一协议能力和字段 schema 来源

P2 运行稳定性增强
  -> 设备/连接状态机
  -> 告警事件出口收口
  -> 历史写入和上报背压
  -> CUSTOM_TCP 插件式协议解析
```

## 3. P0 后处理链路收口

### 3.1 拆分后处理 Pipeline

现状：

- `CollectorDataPostProcessor` 同时负责缓存、Redis Stream、历史库和上报。
- 任一环节变慢或异常，都可能影响同一个后处理方法。

目标结构：

```text
CollectorDataPostProcessor
  -> TelemetryPostProcessPipeline
      -> CacheStage
      -> StreamStage
      -> HistoryStage
      -> ReportStage
```

执行项：

- [ ] 新增 `TelemetryPostProcessContext`，统一承载 `deviceId`、`DataPoint`、`ProcessResult`、时间戳、来源类型。
- [ ] 新增 `TelemetryPostProcessStage` 接口，定义 `name()`、`enabled(context)`、`process(context)`。
- [ ] 新增 `TelemetryPostProcessPipeline`，按顺序执行 stage，并捕获单个 stage 异常。
- [ ] 将多级缓存写入迁移到 `CacheStage`。
- [ ] 将 Redis Stream 写入迁移到 `StreamStage`。
- [ ] 将 TDengine 历史写入迁移到 `HistoryStage`。
- [ ] 将 `CacheReportService.reportPoint(...)` 迁移到 `ReportStage`。
- [ ] `CollectorDataPostProcessor` 只负责标准化输入和调用 pipeline。

验收标准：

- [ ] 单个 stage 抛异常不会阻断其他 stage。
- [ ] 拉取型协议和推送型协议仍共用同一条后处理链。
- [ ] 原有缓存、Stream、历史、上报行为保持兼容。
- [ ] 新增或更新单元测试覆盖 stage 成功、失败、跳过三类场景。

### 3.2 拆掉 `needCache()` 总入口耦合

现状：

- `CollectorDataPostProcessor` 使用 `point.needCache()` 判断是否继续处理。
- 点位不开缓存时，可能也不会进入 Redis Stream、历史库和属性上报。

目标：

- 缓存、实时流、历史、上报分别由独立条件控制。
- `cacheEnabled` 只影响缓存，不影响其他后处理能力。

建议规则：

| 能力 | 判断条件 |
| --- | --- |
| 本地/Redis 缓存 | `point.needCache()` |
| Redis Stream | 全局 `spring.data.redis.stream.enabled` + 点位启用 |
| 历史存储 | 历史存储全局启用 + 点位历史配置启用 |
| 属性上报 | `point.isReportEnabled()` |
| 事件上报 | `point.isEventReportingEnabled()` 或 `ProcessResult.metadata.eventTriggered` |

执行项：

- [ ] 在各 stage 内部实现自己的 `enabled(context)`。
- [ ] 删除 `CollectorDataPostProcessor` 顶层 `shouldCache(point)` 作为总入口的判断。
- [ ] 保留 `CacheStage` 内部的 `point.needCache()` 判断。
- [ ] 增加“不开缓存但开启上报/Stream”的测试。
- [ ] 增加“开启缓存但关闭上报”的测试。

验收标准：

- [ ] 点位不开缓存但开启上报时，属性仍可进入设备影子和上报链路。
- [ ] 点位不开缓存但 Redis Stream 开启时，实时流仍可写入。
- [ ] 点位关闭上报时，不再进入属性影子上报。

## 4. P1 协议扩展治理

### 4.1 抽取 `ProtocolDescriptor`

现状：

- 协议别名、采集器、连接类型、默认端口、协议能力散落在 `CollectorFactory`、`ConnectionFactory`、文档和字段 schema 中。

目标：

```java
ProtocolDescriptor {
    String code;
    Set<String> aliases;
    Class<? extends ProtocolCollector> collectorClass;
    String connectionType;
    Integer defaultPort;
    ProtocolCapability capability;
    ProtocolFieldSchema fieldSchema;
}
```

执行项：

- [ ] 新增 `ProtocolDescriptor` 和 `ProtocolCapability`。
- [ ] 新增 `ProtocolDescriptorRegistry`，集中注册协议、别名、默认端口和能力。
- [ ] `CollectorFactory` 改为从 registry 查采集器。
- [ ] `ConnectionFactory` 改为从 registry 查标准连接类型和默认端口。
- [ ] 协议能力接口和文档生成入口改为读取 registry。
- [ ] 保留原协议名和别名兼容。

验收标准：

- [ ] 所有现有协议仍可创建采集器。
- [ ] `OPC_UA`、`OPCUA`、`OPC_UA_PLC4X`、`OPCUA_PLC4X` 都路由到 PLC4X OPC UA。
- [ ] 新增协议时不再需要同时修改多个 switch/map。

### 4.2 协议批次策略下沉

现状：

- `DeviceBatchPlanner` 的通用地址排序更适合 Modbus，不完全适合 OPC UA、S7、EtherNet/IP 等符号地址。

目标：

```text
ProtocolBatchStrategy
  -> ModbusBatchStrategy
  -> OpcUaBatchStrategy
  -> S7BatchStrategy
  -> SymbolAddressBatchStrategy
  -> DefaultBatchStrategy
```

执行项：

- [ ] 明确 `ProtocolBatchStrategy` 输入输出模型。
- [ ] Modbus 按 unitId、寄存器区、连续地址聚合。
- [ ] OPC UA 按 namespace、endpoint、采样周期或节点路径分组。
- [ ] S7 按 DB 区、地址区和字段数量分组。
- [ ] EtherNet/IP、ADS 等符号地址协议使用符号批次策略。
- [ ] 为每种策略补最小单元测试。

验收标准：

- [ ] Modbus 连续地址批次不退化。
- [ ] 符号地址协议不再依赖“提取第一个数字排序”。
- [ ] 批次策略可通过协议描述符选择。

## 5. P2 运行稳定性增强

### 5.1 设备和连接状态机

目标状态：

```text
INIT
  -> CONNECTING
  -> RUNNING
  -> RECONNECTING
  -> STOPPING
  -> STOPPED
  -> ERROR
```

执行项：

- [ ] 新增设备运行状态枚举和连接状态枚举。
- [ ] 在 `CollectionScheduler`、`CollectionManager`、`BaseCollector` 中统一状态迁移。
- [ ] 明确重连、停止、配置刷新、订阅恢复的合法状态转换。
- [ ] 监控接口输出状态机状态和最后一次状态变更原因。

验收标准：

- [ ] 重连中不会重复启动同一设备采集任务。
- [ ] 停止中不会继续提交新的采集批次。
- [ ] 配置刷新失败可回到旧配置或明确进入 ERROR。

### 5.2 告警事件出口收口

现状：

- 告警可能通过 `DataQualityProcessor -> AlertManager -> CacheReportService.reportAlert(...)` 上报。
- 也可能通过 `ShadowManager.evaluateEvent(...) -> dispatchEvent(...)` 上报。

执行项：

- [ ] 明确告警事件、质量事件、属性变化事件三类事件模型。
- [ ] 增加事件去重 key：`deviceId + pointId + eventType + ruleId + timeWindow`。
- [ ] 决定 `AlertManager` 和 `ShadowManager` 的职责边界。
- [ ] 统一事件上报 payload 字段。
- [ ] 增加重复告警抑制测试。

验收标准：

- [ ] 同一次点位越限不会重复发两条等价告警。
- [ ] 质量异常和业务告警可以区分事件类型。
- [ ] 历史告警查询和实时事件上报字段一致。

### 5.3 历史写入和上报背压

执行项：

- [ ] 为历史写入增加有界队列。
- [ ] 为上报增加有界队列或按设备维度的积压限制。
- [ ] 增加重试退避、最大重试次数和丢弃策略。
- [ ] 增加积压数量、丢弃数量、重试数量、最终失败数量指标。
- [ ] 慢 TDengine、慢 MQTT/HTTP/TCP 目标下做压测验证。

验收标准：

- [ ] 历史库慢不会拖慢采集线程。
- [ ] 上报目标慢不会无限堆积内存。
- [ ] 监控接口能看到积压和丢弃情况。

### 5.4 `CUSTOM_TCP` 插件式协议解析

目标结构：

```text
CustomProtocolCollector
  -> FrameDecoder
  -> FrameEncoder
  -> PointValueMapper
  -> CommandCodec
```

执行项：

- [ ] 定义自定义协议帧配置模型。
- [ ] 支持定长帧、分隔符帧、长度字段帧三种基础拆包方式。
- [ ] 支持 CRC 或校验和扩展点。
- [ ] 支持点位字段映射和字节序配置。
- [ ] 支持读命令、写命令和被动上报。
- [ ] 补模拟 TCP 服务端联调测试。

验收标准：

- [ ] `CUSTOM_TCP` 不再只是占位实现。
- [ ] 至少支持一个模拟设备的读、写、上报闭环。
- [ ] 自定义解析失败不会影响其他设备。

## 6. 推荐落地节奏

### 第 1 阶段：P0 后处理 Pipeline

预计变更范围：

- `core/cache/aspect`
- `core/cache/service`
- `core/report/service`
- `storage/service`
- 对应单元测试

完成后收益：

- 解决 `needCache()` 总入口耦合。
- 后处理各能力独立失败隔离。
- 为后续背压、指标、重试打基础。

### 第 2 阶段：P1 协议描述符

预计变更范围：

- `core/collector/factory`
- `core/connection/factory`
- `core/config/validator`
- `api` 协议能力接口
- `docs/protocols`

完成后收益：

- 协议新增和迁移成本下降。
- OPC UA PLC4X 迁移状态更清晰。
- 协议能力、字段和连接默认值来源统一。

### 第 3 阶段：P2 稳定性治理

预计变更范围：

- `core/collector/scheduler`
- `core/collector/manager`
- `core/report`
- `monitor`
- `storage`

完成后收益：

- 更容易定位设备运行状态。
- 慢存储、慢上报目标不会拖垮采集。
- 告警事件语义更清晰。

## 7. 每次改造的通用检查清单

- [ ] 先补或更新单元测试，再改主逻辑。
- [ ] 保持原配置兼容，不直接删除旧配置字段。
- [ ] 关键链路异常只降级，不中断采集主流程。
- [ ] 所有异步队列必须有容量上限。
- [ ] 所有新增开关必须有默认值。
- [ ] 所有新增状态和失败原因必须能通过监控接口观察。
- [ ] 修改协议路由时必须验证 `CollectorFactory` 和 `ConnectionFactory` 同步。
- [ ] 修改后处理链路时必须验证拉取型和推送型协议各一条样例。

## 8. 建议最先开始的代码任务

建议先做 P0：

1. 新建 `TelemetryPostProcessContext`、`TelemetryPostProcessStage`、`TelemetryPostProcessPipeline`。
2. 把 `CollectorDataPostProcessor.savePointAsync(...)` 和 `saveBatchAsync(...)` 改成只构造 context 并调用 pipeline。
3. 先落 `CacheStage`、`StreamStage`、`HistoryStage`、`ReportStage`。
4. 删除顶层 `shouldCache(point)` 总入口判断，改为每个 stage 独立判断。
5. 补“不开缓存但仍写 Stream/上报”的测试。

这一步收益最大，且不需要改协议采集器主体逻辑，回归范围可控。

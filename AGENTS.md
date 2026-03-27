# data-collection-service 项目速记

本文档用于给后续进入该仓库的智能体快速建立上下文。目标不是覆盖所有细节，而是尽快回答这几个问题：

1. 这是个什么系统。
2. 主链路怎么跑。
3. 核心代码入口在哪里。
4. 协议采集、调度、缓存、上报、实时流各自在哪。
5. 当前有哪些已经落地的重要实现。

## 1. 项目定位

这是一个面向工业/设备场景的数据采集服务，负责：

1. 从多种采集协议读取设备点位。
2. 对原始值做统一转换、质量判定和结果封装。
3. 将处理结果写入本地缓存和 Redis 缓存。
4. 聚合后通过 MQTT / HTTP / TCP 等链路上报。
5. 将处理结果实时写入 Redis Stream。
6. 可选写入 TDengine 历史存储。

当前工程重点是“统一采集框架 + 多协议实现 + 调度与治理能力 + 缓存/上报/实时流闭环”。

## 2. 分层结构

主要代码目录：

- `src/main/java/com/wangbin/collector/api`
  说明：HTTP 管理接口，负责启动/停止采集、查询数据、配置治理、监控查询。
- `src/main/java/com/wangbin/collector/core/collector`
  说明：协议采集器、调度器、连接管理、设备生命周期。
- `src/main/java/com/wangbin/collector/core/processor`
  说明：数据转换、数据质量、`ProcessResult` 组织。
- `src/main/java/com/wangbin/collector/core/cache`
  说明：多级缓存、缓存切面、Redis Stream 实时流。
- `src/main/java/com/wangbin/collector/core/report`
  说明：影子聚合、上报处理、告警上报。
- `src/main/java/com/wangbin/collector/monitor`
  说明：健康检查、性能指标、异常指标。
- `src/main/java/com/wangbin/collector/storage`
  说明：历史存储与时序持久化。

## 3. 采集主流程

主链路按这个顺序理解：

1. `CollectionService` 接收启动请求。
2. `CollectionScheduler.startDevice(deviceId)` 启动单设备调度。
3. `ConfigManager` 读取设备、连接、点位配置。
4. `CollectionManager.registerDevice(...)` 通过 `CollectorFactory` 创建对应协议采集器。
5. 采集器建立连接。
6. 调度器把点位切批并分配到时间片。
7. 每个时间片调用 `collectionManager.readPoints(...)` 进行协议读取。
8. `BaseCollector.readPoint/readPoints` 中统一做类型转换和质量处理，产出 `ProcessResult`。
9. `CollectorDataCacheAspect` 拦截读结果，执行：
   - 多级缓存写入
   - 上报聚合
   - Redis Stream 写入
   - 历史存储写入（启用时）

## 4. 最关键的类

如果只看少量文件，优先看这些：

- `src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java`
  说明：采集调度总控。
- `src/main/java/com/wangbin/collector/core/collector/manager/CollectionManager.java`
  说明：设备采集器注册、连接、读写分发。
- `src/main/java/com/wangbin/collector/core/collector/factory/CollectorFactory.java`
  说明：协议采集器工厂。
- `src/main/java/com/wangbin/collector/core/collector/protocol/base/BaseCollector.java`
  说明：协议采集统一基类，读写处理入口。
- `src/main/java/com/wangbin/collector/core/cache/aspect/CollectorDataCacheAspect.java`
  说明：采集结果后处理总入口。
- `src/main/java/com/wangbin/collector/core/processor/ProcessResult.java`
  说明：处理结果核心对象。
- `src/main/java/com/wangbin/collector/core/report/CacheReportService.java`
  说明：缓存后的上报聚合入口。

## 5. 调度模型

调度核心是时间片模型。

### 5.1 调度器职责

`CollectionScheduler` 负责：

1. 设备启动/停止/重载。
2. 点位批次切分与时间片分配。
3. 并发采集与异步处理。
4. 性能统计和动态调优。

### 5.2 线程池分工

由 `ThreadPoolConfig` 提供：

- `timeSliceScheduler`
- `batchDispatcherExecutor`
- `asyncCollectorExecutor`
- `dataProcessorExecutor`

### 5.3 启动设备时关键步骤

1. 读取设备、连接、点位配置。
2. 初始化点位自适应采集参数。
3. 注册采集器并建立连接。
4. `scheduleDevicePoints(...)` 生成批次并分配时间片。
5. `collectionManager.rebuildReadPlans(...)` 构建协议级读计划。

### 5.4 批次规划特点

`DeviceBatchPlanner` 会：

1. 按 `dataType` 分组。
2. 按地址近似排序。
3. 结合历史性能估算批大小。
4. 地址跨度大时切分。
5. 小批次合并，超大批次切块。
6. 尽量把任务均匀打散到时间片。

### 5.5 默认协议批次上限

- `MODBUS_TCP` / `MODBUS_RTU`：125
- `OPC_UA`：100
- `MQTT`：30
- `SNMP`：20

## 6. 协议支持范围

当前协议文档入口：

- `docs/protocols/README.md`
- `docs/protocols/FIELD_CONFIG_SUMMARY.md`

当前已整理的协议：

- `MODBUS_TCP`
- `MODBUS_RTU`
- `OPC_UA`
- `OPC_DA`
- `IEC104`
- `IEC61850`
- `MQTT`
- `SNMP`
- `COAP`
- `HTTP`
- `WEBSOCKET`
- `CUSTOM_TCP`

### 6.1 协议实现位置

- Modbus：`core/collector/protocol/modbus`
- MQTT：`core/collector/protocol/mqtt`
- SNMP：`core/collector/protocol/snmp`
- OPC：`core/collector/protocol/opc`
- IEC：`core/collector/protocol/iec`
- HTTP：`core/collector/protocol/http`
- WebSocket：`core/collector/protocol/websocket`
- CoAP：`core/collector/protocol/coap`
- Custom：`core/collector/protocol/custom`

### 6.2 协议字段文档

如果后续任务涉及“某协议实际读取哪些配置字段”，直接优先看：

- `docs/protocols/FIELD_CONFIG_SUMMARY.md`

该文档已经按如下格式整理完毕：

```java
case "MODBUS_RTU":
    fields.add(createFieldConfig(...));
    break;
```

它的内容来自代码真实读取字段，不是凭协议常识猜测。

## 7. 数据处理链

统一处理发生在 `BaseCollector.readPoint/readPoints`。

主要步骤：

1. 原始值转目标类型。
2. 缩放、偏移、布尔/数值转换。
3. 调用 `DataQualityProcessor` 做质量判定。
4. 生成 `ProcessResult`。
5. 结果会缓存到采集器的 `lastProcessResults`。

## 8. 缓存、上报、实时流链路

### 8.1 缓存

核心类：

- `MultiLevelCacheManager`
- `LocalCacheManager`
- `RedisCacheManager`

入口：

- `core/cache/aspect/CollectorDataCacheAspect`

### 8.2 上报

核心类：

- `core/report/CacheReportService`
- `ShadowManager`
- `ReportManager`

机制：

1. 点位变化进入设备影子。
2. 只 flush 脏设备。
3. 通过 `ReportHandler` 分发到 MQTT / HTTP / TCP。

### 8.3 Redis Stream 实时流

这是当前工程中已经明确落地的重要能力。

核心类：

- `TelemetryStreamProperties`
- `StreamRetentionMode`
- `TelemetryStreamService`
- `TelemetryStreamServiceImpl`
- `TelemetryStreamRecordBuilder`

入口：

- `CollectorDataCacheAspect` 中新增 `telemetryStreamService.append(...)`

写入内容：

- `eventTs`
- `deviceId`
- `pointId`
- `pointCode`
- `pointName`
- `processResult`

保留策略：

1. `COUNT`
   说明：写入时使用 `XADD MAXLEN`。
2. `TIME`
   说明：写入 `eventTs`，定时执行 `XTRIM MINID`。

配置入口：

- `spring.data.redis.stream.*`

示例字段：

```yaml
spring:
  data:
    redis:
      stream:
        enabled: true
        key: collector:telemetry:stream
        retention-mode: COUNT
        max-length: 200
        max-seconds: 60
        approximate-trim: true
        trim-task-enabled: true
        trim-interval-ms: 5000
```

注意：

1. 该功能不改变原缓存和上报主流程。
2. Stream 写入失败不会中断采集主链路。
3. 项目内已有测试：
   `src/test/java/com/wangbin/collector/core/cache/service/TelemetryStreamServiceImplTest.java`

## 9. 配置治理能力

如果后续任务涉及“在线修改设备/点位/连接配置”，直接看：

- `api/controller/ConfigController`
- `docs/07-配置治理接口.md`

能力包括：

1. 查询配置摘要。
2. 查询单设备详情、点位、连接、差异。
3. 在线更新设备、点位、连接。
4. 刷新单设备配置。
5. 导入导出配置。
6. 触发全量或局部配置同步。

重要接口前缀：

- `/api/config/**`

## 10. 监控与接口

### 10.1 控制器

- `DeviceController`
  说明：设备启动/停止/重载、运行状态、采集统计。
- `DataController`
  说明：缓存查询、单点查询、历史查询。
- `MonitorController`
  说明：缓存指标、设备运行指标、性能指标、系统资源、异常指标。
- `HealthController`
  说明：健康检查。
- `CacheController`
  说明：缓存统计与健康。

### 10.2 常用观测点

1. 采集抖动：看批次耗时和时间片超时趋势。
2. 连接不稳：看连接状态与异常计数。
3. 缓存效果：看命中率与 Redis 可用性。
4. 上报积压：看影子脏数据、上报队列与重试次数。

## 11. 后续做任务时的建议阅读顺序

### 11.1 如果要改采集协议

优先看：

1. `CollectorFactory`
2. 对应 `protocol/*Collector`
3. 对应 `connection/adapter/*ConnectionAdapter`
4. `docs/protocols/*.md`
5. `docs/protocols/FIELD_CONFIG_SUMMARY.md`

### 11.2 如果要改调度

优先看：

1. `CollectionScheduler`
2. `DeviceBatchPlanner`
3. `ThreadPoolConfig`
4. `docs/03-采集调度逻辑详解.md`

### 11.3 如果要改缓存/上报/实时流

优先看：

1. `CollectorDataCacheAspect`
2. `MultiLevelCacheManager`
3. `CacheReportService`
4. `TelemetryStreamServiceImpl`
5. `docs/04-处理-缓存-上报-实时流链路.md`

### 11.4 如果要改配置治理

优先看：

1. `ConfigController`
2. `ConfigManager`
3. `ConfigUpdateEvent` 相关监听
4. `docs/07-配置治理接口.md`

## 12. 当前已知事实

1. Redis Stream 实时采集功能已经完成落地。
2. 协议字段已经整理成独立文档和总表文档。
3. `CUSTOM_TCP` 目前还是占位实现，不支持真实采集。
4. 文档体系已经比较完整，优先使用 `docs` 建立上下文，不要凭印象猜。

## 13. 文档入口

建议先看：

1. `docs/00-文档导航.md`
2. `docs/01-系统架构与主流程.md`
3. `docs/02-采集协议支持与实现方式.md`
4. `docs/03-采集调度逻辑详解.md`
5. `docs/04-处理-缓存-上报-实时流链路.md`
6. `docs/protocols/FIELD_CONFIG_SUMMARY.md`

# Redis Stream 实时采集功能落地说明（已完成）

本文档用于记录当前工程中“采集数据实时写入 Redis Stream”功能的最终实现结果与使用方式。

## 1. 实现目标达成情况

已完成以下目标：

1. 保持原有采集、缓存、上报逻辑不变，仅新增 Redis Stream 实时分支。
2. 采集到的数据（处理后结果）实时写入 Redis Stream。
3. 同时支持两种保留策略：
   - 最近 N 条（COUNT）
   - 最近 N 秒（TIME）
4. 两种策略均保留在代码中，并支持通过 yml 配置切换。
5. 方案基于 Redis Stream（未改为 List）。

## 2. 代码改动清单

### 2.1 新增配置类

- `src/main/java/com/wangbin/collector/core/cache/config/TelemetryStreamProperties.java`

作用：
- 绑定 `spring.data.redis.stream` 配置。
- 提供开关、key、保留模式、长度/秒数、近似裁剪、定时裁剪开关与周期。

### 2.2 新增保留策略枚举

- `src/main/java/com/wangbin/collector/core/cache/enums/StreamRetentionMode.java`

作用：
- 定义 `COUNT` / `TIME` 两种策略。

### 2.3 新增 Stream 记录构建工具

- `src/main/java/com/wangbin/collector/core/cache/util/TelemetryStreamRecordBuilder.java`

作用：
- 统一构建写入 Stream 的字段。
- 写入字段包括：`eventTs`、`deviceId`、`pointId`、`pointCode`、`pointName`、`processResult`。
- `processResult` 为完整 `ProcessResult` JSON 字符串，便于后续扩展。

### 2.4 新增 Stream 服务接口与实现

- `src/main/java/com/wangbin/collector/core/cache/service/TelemetryStreamService.java`
- `src/main/java/com/wangbin/collector/core/cache/service/TelemetryStreamServiceImpl.java`

作用：
- 封装 Redis Stream 写入与裁剪逻辑。
- COUNT 模式：
  - 使用 `XADD MAXLEN`（支持 `~` 近似裁剪）。
- TIME 模式：
  - 写入时带 `eventTs`。
  - 定时任务执行 `XTRIM MINID`，按时间阈值裁剪。

### 2.5 采集入口接入（最小侵入）

- `src/main/java/com/wangbin/collector/core/cache/aspect/CollectorDataCacheAspect.java`

作用：
- 在原有异步缓存保存流程中新增 `telemetryStreamService.append(...)` 调用。
- 不影响原有 `multiLevelCacheManager.put(...)` 与 `cacheReportService.reportPoint(...)`。
- 增加 `toProcessResult(...)` 兜底，确保写入结构始终为 `ProcessResult` 形态。

### 2.6 配置示例落地

- `src/main/resources/application.yml`

已新增 `telemetry.stream` 配置块。

### 2.7 单元测试

- `src/test/java/com/wangbin/collector/core/cache/service/TelemetryStreamServiceImplTest.java`

覆盖内容：
- COUNT 模式是否按 `XADD MAXLEN` 写入。
- TIME 模式是否写入 `eventTs` 字段。
- TIME 模式定时裁剪是否执行 `XTRIM MINID`。

## 3. yml 配置示例

```yaml
spring:
  data:
    redis:
     stream:
       enabled: true
       key: collector:telemetry:stream
       retention-mode: COUNT   # COUNT 或 TIME
       max-length: 200
       max-seconds: 60
       approximate-trim: true
       trim-task-enabled: true
       trim-interval-ms: 5000
```

## 4. 两种模式验证方法

### 4.1 COUNT 模式验证

1. 配置：
   - `retention-mode: COUNT`
   - `max-length: 200`
2. 启动服务并触发采集。
3. 执行：
   - `redis-cli XLEN collector:telemetry:stream`
4. 预期：
   - 长度保持在 200 附近（近似裁剪时可能有轻微浮动）。

### 4.2 TIME 模式验证

1. 配置：
   - `retention-mode: TIME`
   - `max-seconds: 60`
   - `trim-task-enabled: true`
2. 启动服务并触发采集。
3. 查看数据字段：
   - `redis-cli XRANGE collector:telemetry:stream - + COUNT 5`
   - 应包含 `eventTs` 和 `processResult`。
4. 持续运行并等待超过 60 秒。
5. 预期：
   - 旧数据在定时裁剪后被移除，仅保留最近时间窗口数据。

## 5. TIME 模式实现说明（为何这样做）

Redis Stream 不支持按“秒”直接做单条 TTL 过期，因此采用：

- 写入时记录 `eventTs`；
- 定时执行 `XTRIM MINID`，以 `now - maxSeconds` 计算阈值 ID 并裁剪。

该方案是可运行、可配置、可维护的工程化实现。

## 6. 复杂度与风险

1. 复杂度：
   - 写入：O(1)（单条 XADD）
   - 裁剪：与待裁剪数据量相关
2. 风险：
   - 高吞吐且裁剪周期过长时可能短时堆积。
3. 缓解：
   - 降低 `trim-interval-ms`；
   - 使用 `approximate-trim: true` 减少裁剪开销。

## 7. 兼容性说明

1. 保留原有缓存和上报主流程。
2. Stream 写入失败不会中断原有采集链路。
3. 使用项目已有 Spring Boot / RedisTemplate / Lettuce / 序列化风格，无额外框架引入。

## 8. 已执行测试

执行命令：

- `mvn -Dtest=TelemetryStreamServiceImplTest test`

结果：

- 3 个测试全部通过。

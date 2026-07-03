# 架构与代码结构清理执行计划

## 目标

本轮先做低风险、可验证的结构清理，避免一次性大规模搬包影响采集主链路。

目标遵循：

1. 开发规范参考《阿里巴巴 Java 开发手册（嵩山版）》。
2. 代码组织逐步向 RuoYi 风格靠拢。
3. 删除已确认无引用、无业务价值的占位/样例代码。
4. 不改变采集、缓存、上报、告警、TDengine 主流程行为。

## 当前边界

本轮不做大规模包路径迁移，先保留当前顶层结构：

- `api`
- `common`
- `core`
- `monitor`
- `storage`

原因：当前 `core.collector` 协议实现较多，直接迁移包路径会造成大面积 import 变化，风险和回归成本较高。

## 第一阶段：低风险清理

### 删除无用占位代码

已确认无业务引用，可删除：

1. `src/main/java/com/wangbin/collector/core/collector/strategy/CollectionStrategy.java`
2. `src/main/java/com/wangbin/collector/core/collector/strategy/PollingStrategy.java`
3. `src/main/java/com/wangbin/collector/core/collector/strategy/SubscriptionStrategy.java`
4. `src/main/java/com/wangbin/collector/core/collector/strategy/EventStrategy.java`
5. `src/main/java/com/wangbin/collector/core/collector/scheduler/BatchTask.java`
6. `src/main/java/com/wangbin/collector/core/cache/manager/Test.java`
7. `src/main/resources/mapper/placeholder.mapper.xml`

### 清理启动类

处理 `src/main/java/com/wangbin/collector/Application.java`：

1. 删除未使用 import。
2. 删除已注释的大段历史启动逻辑。
3. 移除 `System.out/System.err/printStackTrace`。
4. 改为统一日志输出。

### 小范围依赖注入规范化

优先处理入口类和简单类：

1. `CollectionService`
2. `CollectorDataCacheAspect`
3. `CollectorFactory`

本轮不批量修改协议采集器里的字段注入，避免影响 `BeanFactory.createBean(...)` 创建协议采集器的注入行为。

## 第二阶段：RuoYi 风格结构收敛（后续执行）

建议目标结构：

```text
com.wangbin.collector
├── common
│   ├── constant
│   ├── core.domain
│   ├── enums
│   ├── exception
│   └── utils
├── framework
│   ├── aspect
│   ├── config
│   ├── filter
│   └── web
├── collector
│   ├── controller
│   ├── domain
│   ├── service
│   ├── scheduler
│   └── protocol
├── monitor
│   ├── controller
│   ├── domain
│   └── service
└── storage
    ├── domain
    ├── mapper
    └── service
```

## 暂不删除的内容

以下内容虽然存在占位或未完成功能，但对外配置、文档或前端已有引用，不能直接删：

1. `CUSTOM_TCP` 相关代码。
2. `CustomProtocolCollector`。
3. `ProtocolDescriptorRegistry` 中的 `CUSTOM_TCP` schema。
4. `monitor.metrics.ReportScheduler/ReportTask/ReportResult`。

其中报表骨架需要单独决策：如果确认短期不用报表功能，应连同文档描述一起删除；如果保留，应补真实执行逻辑。

## 验证命令

本轮每批修改后执行：

```bash
mvn -DskipTests compile
mvn "-Dtest=BaseCollectorReadPointsTest,CollectionSchedulerTest,CollectorDataPostProcessorTest" test
```

如果涉及 TDengine 或告警链路，追加：

```bash
mvn "-Dtest=TimeSeriesServiceTdengineIT,AlarmHistoryServiceTdengineIT" test
```

## 风险控制

1. 不修改当前已有未提交的前端静态文件。
2. 不删除对外可见协议能力，除非同步修改配置、文档和前端。
3. 不做跨包大迁移，先以删除无用代码和局部规范化为主。
4. 每个阶段必须编译通过后再继续。

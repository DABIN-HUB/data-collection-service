# Code Review And Load Test Plan

## 1. Review scope

本次 review 重点看了采集主链路里最影响稳定性和容量的部分：

- `CollectionScheduler`
- `CollectionManager`
- `ThreadPoolConfig`
- `DeviceBatchPlanner` / `ProtocolBatchStrategy`
- `BaseCollector`
- `ConnectionManager`
- `CollectorDataCacheAspect` / `CollectorDataPostProcessor`

结论先说在前面：当前框架已经有比较清晰的“调度 -> 协议读取 -> AOP 后处理 -> 缓存/上报/实时流”分层，但如果直接拿它去做容量结论，结果会被调度重连路径、线程池拒绝策略和自动调优口径放大失真。

## 2. Code Review Findings

### 2.1 [高] 断线重连发生在批调度工作线程里，而且包含固定 `sleep(1000)`，设备抖动时会直接吃掉调度吞吐

位置：

- `src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java:258`
- `src/main/java/com/wangbin/collector/core/collector/manager/CollectionManager.java:165`
- `src/main/java/com/wangbin/collector/core/collector/manager/CollectionManager.java:176`

现象：

- `processDeviceBatch(...)` 在真正采集前，如果发现设备未连接，会同步调用 `reconnectDevice(...)`。
- `CollectionManager.reconnectDevice(...)` 里先断开，再 `Thread.sleep(1000)`，然后重连。
- 这条路径跑在 `batchDispatcher` 工作线程里，不是独立的重连线程。

影响：

- 一批设备同时抖动时，`batchDispatcher` 的核心线程会被“睡住”。
- 时间片超时、批次堆积、误判系统过载都会一起出现。
- 压测时看到的“CPU 不高但吞吐上不去”，很可能不是协议极限，而是这里先把并发吃掉了。

建议：

- 重连改成异步状态机，不要在采集批线程里直接阻塞等待。
- 给设备维护 `nextReconnectAt` / `reconnectBackoff`，当前时间未到时直接跳过本批。
- 重连应有独立限流，避免全设备同时断线时形成重连风暴。

### 2.2 [高] 核心线程池全部是有界队列 + `AbortPolicy`，但调度层没有形成稳定的背压策略，过载时会直接丢调度机会

位置：

- `src/main/java/com/wangbin/collector/common/config/ThreadPoolConfig.java:42`
- `src/main/java/com/wangbin/collector/common/config/ThreadPoolConfig.java:58`
- `src/main/java/com/wangbin/collector/common/config/ThreadPoolConfig.java:74`
- `src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java:219`
- `src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java:263`
- `src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java:297`

现象：

- `batchDispatcherExecutor`、`asyncCollectorExecutor`、`dataProcessorExecutor` 都使用 `AbortPolicy`。
- 时间片执行时，任务直接 `CompletableFuture.runAsync(..., batchDispatcher)` 提交。
- 批采集时，真实读取又继续往 `asyncCollectorPool.submit(...)` 提交。
- 后处理时，再往 `dataProcessorPool` 提交。

影响：

- 队列一满会抛 `RejectedExecutionException`，系统没有稳定的“降级/排队/跳过低优先级设备”机制。
- 对 `batchDispatcher` 来说，某个提交点被拒绝时，当前时间片剩余任务可能根本没机会发出去。
- 对 `asyncCollectorPool` / `dataProcessorPool` 来说，表现成批次失败，但这并不是设备或协议失败，而是本机资源调度失败。
- 这种失败会污染压测结论，让你误以为协议吞吐差。

建议：

- 关键链路要明确区分三种失败：协议失败、连接失败、本机资源拒绝。
- `batchDispatcher` 建议做显式背压，至少要把“本片跳过多少任务、拒绝多少任务”打成指标。
- 对低优先级设备可以做跳片或降频，而不是和高优先级设备一起被 `AbortPolicy` 打爆。

### 2.3 [中] 动态时间片调优用的不是系统 CPU，而是线程池活跃线程占比，自动扩缩容依据不可靠

位置：

- `src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java:582`
- `src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java:663`

现象：

- `adjustTimeSlicesDynamically()` 里把 `getSystemCpuLoad()` 的结果当成 CPU 负载。
- 但 `getSystemCpuLoad()` 实际算法是：
  `activeThreads / maximumPoolSize`。
- 这只是两个线程池的“线程占用率”，不是操作系统 CPU，也不是 JVM CPU。

影响：

- IO 阻塞很多时，线程可能很忙，但 CPU 其实很低。
- 反过来，GC、序列化、Redis/MQTT 压力大时，系统 CPU 可能很高，但线程池活跃数未必同步上涨。
- 这会让时间片自动调优朝错误方向走，也会让“4C8G 能跑多少点”这种结论没有可信基线。

建议：

- 用真实系统指标替换：`OperatingSystemMXBean` / Micrometer 的进程 CPU、系统 CPU、堆使用率、GC 停顿。
- 时间片调优至少同时看：`CPU + 队列长度 + 时间片超时率 + 批次 p95 延迟`。

### 2.4 [中] 自适应批大小被硬编码封顶到 100，协议级上限配置无法真正跑满

位置：

- `src/main/java/com/wangbin/collector/core/collector/scheduler/DevicePerformance.java:123`
- `src/main/java/com/wangbin/collector/core/collector/scheduler/ProtocolBatchStrategy.java:17`
- `src/main/java/com/wangbin/collector/core/collector/scheduler/ProtocolBatchStrategy.java:39`
- `src/main/java/com/wangbin/collector/core/collector/scheduler/ProtocolBatchStrategy.java:49`

现象：

- `DevicePerformance.adjustBatchSize(...)` 里把 `currentBatchSize` 上限写死成了 `100`。
- 但协议配置里已经声明了更高上限：
  - `MODBUS_*` = `125`
  - `OMRON_FINS` = `120`
  - `SIEMENS_S7` = `300`

影响：

- 调优逻辑永远不可能把这些协议推到自己声明的最大批量。
- 压测出来的吞吐会被框架上层提前卡住。
- 这属于“系统限制了自己”，不是现场协议本身的极限。

建议：

- `currentBatchSize` 的上限改成按协议动态取值，而不是固定 `100`。
- 初始批大小也建议从协议默认值而不是常量 `30` 起步。

### 2.5 [中] 连接状态字段跨线程读写没有可见性保障，调度线程可能读到过期连接状态

位置：

- `src/main/java/com/wangbin/collector/core/collector/protocol/base/BaseCollector.java:61`
- `src/main/java/com/wangbin/collector/core/collector/protocol/base/BaseCollector.java:107`
- `src/main/java/com/wangbin/collector/core/collector/protocol/base/BaseCollector.java:150`
- `src/main/java/com/wangbin/collector/core/collector/protocol/base/BaseCollector.java:528`
- `src/main/java/com/wangbin/collector/core/collector/manager/CollectionManager.java:284`

现象：

- `connected`、`connectionStatus`、`lastActivityTime` 都是普通字段。
- 连接线程更新这些字段，调度线程和管理线程并发读取。
- 这里没有 `volatile`、原子类或统一锁保护。

影响：

- 极端情况下会出现状态可见性延迟。
- 表现上可能是：刚连上仍被判未连接、刚断开仍被判已连接，进而触发多余重连或无效读写。
- 这种问题通常在并发压测和断线恢复场景里最容易暴露。

建议：

- 最少把连接状态相关字段改成 `volatile` 或原子对象。
- 更稳妥的是统一用状态对象封装，避免散落字段各自更新。

### 2.6 [中/低] 设备启动连接线程池是 `newCachedThreadPool`，批量启动/批量重载时线程数不受控

位置：

- `src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java:91`
- `src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java:453`

现象：

- 设备启动连接使用 `Executors.newCachedThreadPool(...)`。
- 单次 `startAllDevices()` 现在是顺序调用，平时不一定立刻出问题。
- 但一旦有外部并发启动、配置批量重载、批量恢复等场景，这个线程池没有上限。

影响：

- 容易在启动风暴时创建过多线程，进一步放大连接竞争和 GC 压力。
- 这类问题一般不在日常小规模运行时出现，但在压测和批量恢复时很容易出现。

建议：

- 启动连接线程池改成有界线程池。
- 配合设备启动队列、分批启动和并发上限更稳。

## 3. Review Summary

整体结构是可以继续扩展的，尤其协议层、AOP 后处理和调度层已经分开了；但当前最先要处理的不是“再加协议功能”，而是把调度过载时的行为收紧，否则容量结论会不可信。

如果只允许先改 3 件事，我建议顺序是：

1. 重连从采集线程里剥离出去。
2. 给调度/采集/处理线程池补上明确的背压与拒绝指标。
3. 把自动调优的“伪 CPU”换成真实系统指标。

## 4. 为什么现在不能直接给出“多少 CPU / 内存能采多少点”

这个问题不能凭代码静态阅读直接给出准确值，原因很简单：容量高度依赖下面这些变量。

- 协议类型：Modbus/FINS 这类连续块读取和 OPC/SNMP/MQTT 的成本完全不同。
- 点位分布：连续地址、离散地址、bit/word 混合，吞吐差异很大。
- 采集周期：1 秒、2 秒、5 秒不是线性关系。
- 现场 RTT 和设备响应时间：同样 1 万点，本地模拟器和跨网关现场差别可能数倍。
- 后处理链路是否开启：本地缓存、Redis、Redis Stream、MQTT/HTTP 上报都会改变瓶颈。
- 数据质量处理和转换复杂度：布尔、缩放、精度、规则判断都会占 CPU。

所以现在能给出的不是“拍脑袋容量值”，而是一套可复现的压测方法，以及压测前必须先盯住的观测点。

## 5. 压测思路

### 5.1 目标不要只看“能跑起来”，要看“稳定容量”

建议把稳定容量定义成：

- 点位成功率 `>= 99.9%`
- 时间片 p95 执行时长 `< 0.7 * 采集周期`
- 关键线程池队列不持续增长
- JVM 堆使用率长期 `< 75%`
- Full GC = `0`，或极低且不影响周期
- Redis / MQTT 打开后，整体吞吐退化在可接受范围内

只要其中一条持续破线，就不算“稳定容量”。

### 5.2 压测要分层，不要一上来混在一起测

建议按 4 个阶段做：

#### 阶段 A：协议读取上限

目的：先看“协议 + 调度”本身的上限。

环境：

- 关闭上报
- 关闭 Redis Stream
- 最好本地缓存保留，Redis 可先关闭
- 用协议模拟器，不要一开始接真实现场设备

输出：

- 单协议稳定点位数
- 单协议批次 p95 / p99
- 每秒处理点位数

#### 阶段 B：采集 + 缓存链路

目的：加入本地缓存 / Redis / Redis Stream，确认后处理成本。

重点看：

- `cacheAsyncExecutor` 是否进入 `CallerRunsPolicy`
- Redis RTT、写入 TPS
- 时间片是否因为后处理开始抖动

#### 阶段 C：采集 + 缓存 + 上报链路

目的：测完整链路。

重点看：

- `reportExecutor` 队列长度
- MQTT / HTTP 发送耗时
- 影子 flush 周期是否积压
- 采集线程是否被上报反压间接拖慢

#### 阶段 D：故障注入

目的：测最接近真实现场的稳定性。

场景：

- 10% 设备超时
- 5% 设备断线重连
- Redis 短暂抖动
- 上报端限速或 ACK 变慢

这一步特别重要，因为当前代码里最明显的问题正是在“异常场景下吞吐骤降”。

### 5.3 建议的资源矩阵

先从 3 档资源开始：

- `2C4G`
- `4C8G`
- `8C16G`

每档资源都跑 3 组采集周期：

- `1s`
- `2s`
- `5s`

每组周期下，跑 4 档总点位数：

- `5,000`
- `20,000`
- `50,000`
- `100,000`

如果是分协议压测，建议至少覆盖：

- `MODBUS_TCP` 连续地址场景
- `OMRON_FINS` 连续字区 + bit 混合场景
- `SNMP` 离散地址场景
- `OPC_UA` 节点读场景

## 6. 每轮压测必须采集的指标

### 6.1 主机 / JVM 指标

- 进程 CPU
- 系统 CPU
- RSS / 堆已使用 / 堆上限
- Young GC 次数与停顿
- Full GC 次数与停顿
- 线程总数

### 6.2 调度指标

- `timeSliceCount`
- `timeSliceIntervalMs`
- 每个时间片执行时长
- 时间片超时次数
- 慢设备 TopN
- 每设备成功批次数 / 失败批次数

### 6.3 线程池指标

至少暴露下面 4 个线程池的：

- `activeCount`
- `poolSize`
- `queueSize`
- `completedTaskCount`
- `rejectCount`

线程池：

- `batchDispatcherExecutor`
- `asyncCollectorExecutor`
- `dataProcessorExecutor`
- `cacheAsyncExecutor`

当前代码里最缺的是 `rejectCount`，压测前建议补上，不然你只能看到“失败了”，却不知道是协议慢还是线程池拒绝。

### 6.4 业务链路指标

- 每秒采集点位数
- 每秒成功点位数
- 每秒失败点位数
- 单批读取 p50 / p95 / p99
- 断线次数 / 重连次数 / 重连耗时
- Redis 写 TPS
- Stream 写 TPS
- 上报 TPS / ACK 延迟

## 7. 建议的压测工作负载设计

### 7.1 点位分布

以 `10,000` 点为例，建议至少有两种分布：

- 连续型：70% 连续地址，30% 离散地址
- 离散型：20% 连续地址，80% 离散地址

因为同样 1 万点，连续块协议和离散协议的结果差别会非常大。

### 7.2 功能开关组合

每组资源 / 周期下建议跑这 4 种组合：

1. 仅采集
2. 采集 + 本地缓存
3. 采集 + 本地缓存 + Redis Stream
4. 采集 + 本地缓存 + Redis Stream + 上报

这样才能看清瓶颈到底落在协议、缓存还是上报。

## 8. 建议的判定方式

不要直接问“最多能跑多少点”，而要问：

- 在 `4C8G`、`1s` 周期、`仅采集` 下，哪一档点位数第一次让时间片 p95 超线？
- 在同样资源下，打开 Redis Stream 后，稳定点位数下降了多少？
- 打开 MQTT 上报后，瓶颈是 CPU、网络、线程池队列，还是 ACK 等待？
- 故障注入后，吞吐下降曲线和恢复时间是多少？

最后得到的应该是一张容量表，而不是一句话。

示意格式：

| 资源 | 协议场景 | 周期 | 功能开关 | 稳定总点位 | p95 批时延 | CPU | Heap | 主要瓶颈 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4C8G | MODBUS 连续块 | 1s | 仅采集 | TBD | TBD | TBD | TBD | TBD |
| 4C8G | FINS 连续字区+bit | 1s | 采集+Stream | TBD | TBD | TBD | TBD | TBD |
| 8C16G | 混合协议 | 2s | 全链路 | TBD | TBD | TBD | TBD | TBD |

## 9. 我建议的落地顺序

先不要直接做大规模压测，顺序建议这样：

1. 先修复本次 review 里前 4 条问题，至少把压测结果里最明显的失真因子去掉。
2. 给线程池补 reject / queue 指标。
3. 用协议模拟器先做单协议基准压测。
4. 再打开 Redis Stream 和上报链路做全链路压测。
5. 最后做断线、超时、慢响应故障注入。

## 10. 当前结论

我现在能负责任给出的结论是：

- 这个项目已经具备做容量测试的基本框架，但当前代码会让“异常场景下的调度资源争抢”过早成为瓶颈。
- 如果不先修复重连阻塞、线程池拒绝策略和自动调优指标口径，压测结果很容易低估真实协议能力。
- 所以现阶段最合理的动作不是直接报一个容量数字，而是先修正容量测试口径，再做分层压测。

## 11. 问题影响评估与修改方案

### 11.1 影响级别判断

- 很大：重连阻塞采集线程、线程池拒绝无背压。这两类问题会直接降低稳定吞吐，不是单纯的“优化项”。
- 大：动态时间片调优使用伪 CPU 指标、自适应批大小被固定上限卡住。这会让压测结论失真，并提前压低协议吞吐。
- 中等：连接状态字段可见性不足、启动线程池无上限。这些问题更容易在批量启动、批量重载、断线风暴时暴露。

### 11.2 修改顺序建议

1. 先把重连从批调度线程里剥离，改成异步退避重连。
2. 给核心线程池补拒绝计数和可观测性，再补调度层显式背压。
3. 把自适应批大小上限改成按协议动态取值。
4. 把动态时间片调优的 CPU 口径切到真实 JVM/系统指标。
5. 最后补连接状态可见性和启动线程池限流。

### 11.3 代码落地方式

- 重连逻辑：在 `CollectionScheduler` 内维护每设备重连状态、退避时间和独立重连线程池。采集线程只负责“发现断线并调度重连”，不直接阻塞等待重连完成。
- 线程池指标：为 `batchDispatcherExecutor`、`asyncCollectorExecutor`、`dataProcessorExecutor`、`cacheAsyncExecutor` 增加拒绝计数封装，并在系统监控快照中输出 `rejectedCount`。
- 动态调优口径：优先使用 `SystemResourceMonitorService` 已有的 `processCpuLoad/systemCpuLoad`，避免继续用线程池活跃数冒充 CPU。
- 批大小调优：让 `DevicePerformance` 的批大小上限按协议初始化，不再固定写死 `100`。
- 连接状态：`BaseCollector` 的连接状态相关字段先收口为 `volatile`，避免跨线程状态判断漂移。

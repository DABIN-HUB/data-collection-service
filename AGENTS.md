# 项目开发代理规范
## Language / 语言规范

- 所有面向用户的回复必须使用简体中文。
- 分析、解释、方案设计、执行结果、错误说明、总结均使用中文。
- Java 类名、方法名、变量名、文件路径、Shell 命令、SQL、配置项、日志原文等技术内容保持原始英文，不要强制翻译。
- 如果工具返回英文内容，应使用中文解释其含义。
- 除非用户明确要求英文，否则禁止整段使用英文回答。

## 1. 文档定位

本文件用于约束本仓库中的代码开发、协议扩展、配置变更、前端控制台、测试验证和文档维护工作。

本文件重点解决以下问题：

1. 明确当前系统主链路和架构边界。
2. 防止新增功能绕过采集、处理、缓存、告警、存储、上报和 ACK 主链路。
3. 统一编码、注释、命名、异常、日志、线程池、安全和测试规范。
4. 明确新协议扩展方式，避免后端协议 Schema、前端表单和文档出现多套定义。
5. 固化当前已经调整后的工程规则，避免继续使用历史实现方式。

本仓库内新增和修改代码时，必须优先遵守本文件。发现本文件与当前可编译、可运行代码事实冲突时，应先确认真实架构，再同步修正本文件，不能继续复制过期规则。

## 2. 事实依据与优先级

判断协议状态、接口语义、配置来源和页面数据来源时，按以下顺序确认事实：

1. 当前可编译、可运行的代码和实际运行行为。
2. 自动化测试、真实设备联调记录和本地可复现实验结果。
3. `ProtocolDescriptorProvider`、`ProtocolDescriptorRegistry`、`CollectorFactory`、`ConnectionAdapterProvider`、`ConnectionFactory` 和 `ProtocolConnectionValidator` 的实际实现。
4. 后端控制器、应用服务、前端 `api-client.js` 和控制台页面的真实调用链。
5. `docs/`、`README.md`、架构计划文档和历史问题清单。

历史文档只能作为设计背景。历史文档如果仍将注册表描述为新协议元数据入口，以本文件为准：新增协议必须通过独立的 `ProtocolDescriptorProvider` 提供元数据，`ProtocolDescriptorRegistry` 只负责聚合、归一化和查询。

## 3. 当前系统主链路

修改任何业务代码前，必须先确认本次修改位于以下哪一个阶段，以及是否会改变后续阶段的输入、执行顺序、设备身份或可靠性语义。

```text
设备配置来源
  -> FileConfigLoader / RemoteConfigLoader
  -> ConfigManager 配置归一化、校验、缓存和设备上下文构建
  -> CollectionScheduler 启停设备、连接、分片调度、批量计划和运行代次管理
  -> CollectionManager 按本地 deviceId 管理协议采集器
  -> CollectorFactory / ConnectionFactory / ConnectionManager
  -> 协议采集器与连接适配器

拉取型数据
  -> BaseCollector 统一读取模板
  -> 协议原始值读取
  -> 通用值转换
  -> DataQualityProcessor 数据质量与告警状态处理
  -> CollectorDataCacheAspect / CollectorDataPostProcessor

推送型、订阅型和边缘接入数据
  -> BaseCollector.ingestPushedValue 或 EdgeTelemetryIngressService
  -> TelemetryIngressService
  -> 通用值转换、数据质量和统一遥测上下文

统一遥测后处理
  -> TelemetryPostProcessPipeline
  -> CacheTelemetryPostProcessStage 本地缓存和 Redis 缓存
  -> StreamTelemetryPostProcessStage Redis 实时流
  -> HistoryTelemetryPostProcessStage 历史写缓冲和 TDengine
  -> ReportTelemetryPostProcessStage 云端上报入口

云端可靠上报
  -> CacheReportService 更新设备影子、节流和批次聚合
  -> 本地 deviceId 映射为 CloudDeviceIdentity
  -> CloudOutboxService / CloudOutboxCoordinator
  -> ReportManager
  -> MQTT / HTTP / TCP 上报处理器
  -> 云平台
  -> ACK 或业务响应
  -> CloudOutboxCoordinator 提交、重试、隔离或完成消息
  -> ShadowManager 按提交策略确认设备影子

应用停止
  -> GracefulShutdown 停止接收请求
  -> CollectionScheduler 停止全部设备和调度任务
  -> 上报服务排空待处理任务
  -> ConnectionManager 释放连接、会话、订阅和网络资源
```

主链路必须遵守以下事实：

1. `ConfigManager` 管理配置事实和本地设备上下文，协议实现不得自行创建第二套设备配置。
2. `CollectionScheduler` 管理设备运行代次、调度和在途任务，停止设备后旧代次结果不得继续进入后处理链路。
3. 拉取型数据必须经过 `BaseCollector` 的转换和质量处理。
4. 推送型、订阅型和边缘接入数据必须进入 `TelemetryIngressService`。
5. `TelemetryPostProcessPipeline` 中的阶段顺序是任务提交顺序；不同执行器之间不天然保证完成顺序。
6. 本地缓存、Redis、历史、告警和运行状态始终使用本地 `deviceId`。
7. 云平台身份转换只能发生在上报边界，不能反向污染本地设备主键。
8. 上报发送成功、云端 ACK 成功和设备影子提交完成是不同状态，必须按提交策略推进。
9. 设备停止、应用关闭、连接断开、订阅取消和上报排空必须支持重复执行。

## 4. 技术基线

1. Java 版本：Java 17。
2. 核心框架：Spring Boot 3.2。
3. 构建工具：Maven。
4. 测试框架：JUnit 5、Spring Boot Test 和项目已有协议测试设施。
5. 前端形态：当前生产控制台为 `collector-desktop/` 下的 Vue 3 工作台，支持 Electron 客户端和后端内置 `/desktop/index.html` 网页入口；旧 `src/main/resources/static/admin/` 静态 Web 控制台已经删除，不再作为开发或发布入口。
6. 主要存储和中间件：本地缓存、Redis、TDengine，以及配置启用的云端上报通道。
7. 协议驱动和网络库以 `pom.xml` 当前依赖为准，不得只根据历史文档猜测版本或能力。

引入新依赖前，必须先确认现有依赖不能满足要求。禁止为简单功能重复引入功能重叠的网络、JSON、工具或并发库。

## 5. 编码、注释与界面文字

1. 本项目以《阿里巴巴 Java 开发手册》为基础规范，结合 Spring Boot 官方最佳实践及 RuoYi-Vue-Plus 现有工程结构制定项目规范。历史代码保持兼容，新建和修改代码优先遵循项目规范。规范冲突时，优先级为：项目明确约定 > 框架现有约定 > Spring Boot 最佳实践 > 阿里 Java 开发手册。
2. 所有新增和修改文件必须统一使用 UTF-8 编码，包括源码、配置、脚本、文档和前端资源。
3. 禁止使用 GBK、GB2312、ANSI 或依赖操作系统默认编码的实现。
4. 缩进、换行和尾随空格遵循根目录 `.editorconfig`。
5. Java 使用 4 个空格缩进；YAML、JSON、XML、HTML、CSS、JavaScript 和 TypeScript 使用 2 个空格缩进。
6. 文件末尾保留一个换行符，不得引入无意义尾随空格。
7. 所有新增和修改的代码注释必须使用中文，禁止新增纯英文注释。
8. 新增和修改的 JavaDoc 必须使用中文说明职责、关键约束、参数边界或异常语义。
9. 协议名称、类名、方法名、字段名、标准缩写和第三方 API 名称保持规范英文，但注释说明必须使用中文。
10. 不为简单赋值、显而易见的条件或普通 getter/setter 添加无意义注释。
11. 复杂协议帧、地址换算、字节序、位运算、重试、降级和可靠上报状态迁移必须补充简洁中文注释。
12. 新增和修改的前端可见文字使用中文；协议标准术语可保留英文并提供中文含义。
13. 不显示无业务价值的 `Required field` 等默认英文提示，但不得因此取消字段必填校验。

## 6. 命名、常量、枚举与 DTO

1. 包名全小写；类名使用大驼峰；方法、参数和局部变量使用小驼峰；常量使用全大写下划线分隔。
2. 协议编码必须有唯一规范值，例如 `MODBUS_TCP`；兼容名称只能作为别名，不得形成第二个业务主键。
3. 禁止在采集器、连接工厂、校验器、控制器和前端分别散落同一个协议字符串。
4. 固定字符串、默认端口、超时时间、帧头、功能码、状态码和配置键应提取为语义清晰的常量。
5. 有限且稳定的业务状态、能力状态、传输模式和策略类型应使用枚举表达。
6. 结构稳定的 API 请求、API 响应和内部业务对象优先使用明确 DTO。
7. 协议扩展字段、第三方动态数据、用户自定义字段和无法预先确定结构的数据可以继续使用 `Map<String, Object>`。
8. 不得为了形式化而创建只使用一次且没有业务语义的常量、枚举或 DTO。

## 7. Map 字段常量来源

1. 跨模块通用且结构稳定的 `Map<String, Object>` 字段名统一从 `com.wangbin.collector.common.constant.CommonMapKeys` 获取。
2. 云平台消息、物模型消息、ACK、MQTT/TCP 物联网报文字段统一从 `MessageConstant` 获取。
3. 协议编码、默认端口、上报模式、协议参数和连接参数优先从 `ProtocolConstant`、协议枚举或协议描述获取。
4. 采集处理结果元数据统一从 `ProcessResultMetadataKeys` 获取。
5. 告警元数据统一从 `AlarmMetadataKeys` 获取。
6. Outbox 元数据统一从 `CloudOutboxMetadataKeys` 获取。
7. 云端上报指标字段统一从 `CloudReportMetricKeys` 获取。
8. 缓存指标字段统一从 `CacheMetricKeys` 获取。
9. 历史存储 JSON 字段统一从 `TelemetryStorageJsonKeys` 获取。
10. 设备影子文档字段统一从 `ShadowDocumentKeys` 获取。
11. 配置扩展字段、协议扩展字段和点位 `additionalConfig` 中已有领域常量时必须复用领域常量。
12. 没有领域常量但属于稳定字段时，先评估是否应新增到对应领域常量类，禁止直接散落字符串。
13. 协议动态字段、用户自定义字段、Schema 读取字段和第三方透传字段可以继续使用变量，不得强行抽取为公共常量。
14. 新增常量必须写中文 JavaDoc，说明字段业务含义、使用边界和是否属于对外契约。
15. 替换硬编码字段时不得改变对外 JSON 字段值、Redis key 内容、数据库列名、云端报文字段、前端字段名和协议语义。

## 8. 设备身份与数据一致性

1. `deviceId` 是本地设备唯一标识，是控制台查询、采集上下文、缓存、Redis、数据库、历史数据和运行状态关联的主键。
2. 云平台的 `productKey`、`projectPK`、`deviceName` 和设备密钥属于云平台身份，不得替换本地 `deviceId`。
3. 本地身份到云平台身份的转换只能发生在云平台映射或上报边界。
4. 云端身份必须使用 `CloudDeviceIdentity`、`CloudTargetConfig` 或职责等价的明确类型表达。
5. 设备影子、缓存键、运行状态、告警、历史记录和采集结果必须继续使用本地 `deviceId` 关联。
6. 新代码不得使用同一个含义不明确的 `String deviceId` 同时表达本地设备身份和云平台设备身份。
7. 新协议不得根据协议地址、主机、端口或云平台设备名重新生成本地设备 ID。
8. 点位结果应使用稳定的 `pointId` 关联；`pointCode`、别名、上报字段和点位名称只作为明确场景下的引用或映射字段。
9. 本地身份、云端身份和原始上报身份需要同时存在时，必须使用不同字段并明确命名。

## 9. 分层与架构边界

1. `common` 不得依赖 `core`、`api`、`storage` 或 `monitor`。
2. `core` 不得引用控制器 DTO，HTTP 请求对象属于 `api`。
3. `api` 只能调用应用服务和稳定契约，不得直接依赖具体协议采集器、连接适配器或协议编解码器。
4. 协议实现不得直接依赖缓存、Redis、历史存储、设备影子或云端上报实现。
5. 存储实现不得反向驱动采集调度或协议连接生命周期。
6. 新增模块、包或跨层引用时，必须检查依赖方向，不得形成新的包循环依赖。
7. 需要跨层复用的输入契约应下沉到独立的公共契约包，不能复用 Controller 请求类。
8. 修改公共抽象前必须评估 Modbus、S7、OPC UA、IEC101、DL/T645 等已有协议影响。

## 10. Spring Bean 与配置治理

1. 生产 Bean 的必需依赖统一使用构造器注入，禁止新增字段注入。
2. 优先使用 Lombok `@RequiredArgsConstructor` 简化构造器注入。
3. 一个生产 Bean 原则上只保留一个用于 Spring 注入的构造器。
4. 测试专用兜底构造器必须限制可见性并明确说明用途，不得进入生产装配路径。
5. 禁止在 Spring 管理类中通过 `new` 绕过容器创建带依赖、生命周期或代理语义的组件。
6. 分组配置优先使用 `@ConfigurationProperties` 和 `@Validated`，禁止在多个类中通过 `@Value` 重复声明相同配置和默认值。
7. YAML 配置键统一使用短横线命名，Java 属性名使用小驼峰，环境变量使用全大写下划线。
8. 每个新增配置项必须明确默认值、取值范围、是否必填以及立即生效、重启设备生效或重启应用生效。
9. 修改 Bean 构造器、条件装配、配置属性或执行器注入后，必须执行应用上下文相关测试。

## 11. 方法、对象与编译治理

1. 一个类只承担明确职责，协议地址解析、帧编解码、连接管理和采集业务不得全部堆积在一个类中。
2. 方法只完成一个主要目标，复杂分支应拆分为可测试的私有方法或独立组件。
3. 优先使用不可变对象表达协议地址、解析结果、请求描述、运行状态快照和可靠上报状态。
4. 返回集合时明确可变性，不向外暴露内部可变集合。
5. 禁止使用 `null` 表示多个不同业务状态；存在明确状态集合时使用枚举或结果对象。
6. 禁止使用固定返回 `true`、空集合或默认值伪装尚未实现的协议能力。
7. 工厂、策略、模板方法、适配器和状态机只在能降低分支复杂度或固化业务语义时使用，禁止为了形式化强行套设计模式。
8. 协议差异优先使用 Provider、策略和适配器隔离，不得在调度器、控制器或前端堆叠协议 `if/else`。
9. 编译警告必须逐步治理，禁止通过无解释的 `@SuppressWarnings` 掩盖真实问题。
10. Lombok `@Builder.Default`、`@EqualsAndHashCode(callSuper = ...)`、已过期 API 和测试 classpath 依赖冲突属于治理项，修改相关代码时应顺带消除。
11. 编译警告治理优先级按运行行为风险、依赖冲突风险、后续版本升级风险和代码可维护性排序。
12. 不在本轮任务中顺带修改无关业务逻辑，确需调整公共行为时必须有测试保护。

## 12. 新协议开发前置分析

开始编码前必须明确以下内容：

1. 协议规范名称、项目规范编码和历史别名。
2. 传输方式是 TCP、UDP、串口、HTTP、消息订阅还是第三方 SDK。
3. 协议属于轮询、主动推送、原生订阅还是混合模式。
4. 是否支持单点读取、批量读取、单点写入、批量写入、订阅、浏览和命令。
5. 地址格式、地址基准、站号、区域、对象类型、属性、索引和字节序规则。
6. 协议原生数据类型与平台 `dataType` 的映射关系。
7. 连接级配置、点位级配置及其存储位置。
8. 批量请求的最大数量、连续地址合并规则和协议帧大小限制。
9. 超时、重试、断线重连、心跳、取消订阅和资源释放语义。
10. 可用于验证的模拟端、真实设备、测试服务器或抓包证据。

上述内容未明确时，协议能力只能标记为实验性或未支持，不能直接声明为完整支持。

## 13. 新协议标准实现顺序

新增协议必须按以下顺序实现，不能只补一个采集器就声明完成：

1. 新增独立的 `ProtocolDescriptorProvider`，声明协议元数据、别名、字段 Schema 和真实能力状态。
2. 实现协议地址模型和地址解析器。
3. 实现帧编解码器或第三方驱动地址转换层。
4. 实现连接适配器，并通过独立 `ConnectionAdapterProvider` 暴露规范 `connectionType` 到适配器创建逻辑，由 `ConnectionFactory` 和 `ConnectionManager` 管理生命周期。
5. 实现协议采集器，优先继承 `ConnectionBackedCollector`，确实不适用时才直接继承 `BaseCollector`。
6. 在 `CollectorFactory` 注册规范编码和必要别名。
7. 在 `ProtocolConnectionValidator` 接入连接参数校验。
8. 接入 `DeviceBatchPlanner` 和 `ProtocolBatchStrategy`，不得在调度器中散落协议判断。
9. 通过协议 Schema 驱动前端动态配置，不得复制第二套字段定义。
10. 补充工厂、Schema、校验器、地址解析、编解码、连接、采集器和应用上下文测试。
11. 同步更新协议总览、协议详情、未完成功能和 README 中受影响内容。

## 14. 协议元数据 Provider 规则

1. 新协议元数据必须通过独立 `ProtocolDescriptorProvider` 提供，类名按 `{协议名}ProtocolDescriptorProvider` 命名。
2. `ProtocolDescriptorRegistry` 只负责聚合 Provider、协议归一化、别名索引、公共查询和兼容入口。
3. 禁止把新协议的大段字段、默认值、能力说明直接堆到 `ProtocolDescriptorRegistry`。
4. 现有尚未迁移的历史协议可以分批迁移为 Provider，迁移时不得改变对外协议编码、字段名、默认值和能力状态。
5. Provider 中必须声明规范协议编码、历史别名、中文标题、中文说明、采集器类型、连接类型、默认端口、寻址模式和能力状态。
6. Provider 中的连接字段和点位字段必须明确字段类型、必填性、默认值、分组、存储位置、枚举值和中文说明。
7. Provider 暴露的字段约束必须与 `ProtocolConnectionValidator` 的后端校验一致。
8. Provider 中不能声明尚未真实实现的能力为已支持，运行依赖型能力应标记为运行时依赖或实验性。
9. 前端 `/api/protocols` 和 `/api/protocols/{protocol}/fields` 必须以 Provider 汇聚后的 Schema 为事实来源。
10. 新增或迁移 Provider 后必须补充协议 Schema 输出测试，确保前端表单不退化。

## 15. 协议地址、编解码与连接

1. 复杂地址必须创建独立不可变地址对象和解析器。
2. 地址解析器负责格式校验、默认值、范围校验和规范化，不负责网络通信。
3. 地址解析失败时必须返回包含字段和原因的明确异常，不能静默使用地址 `0` 等危险默认值。
4. 明确协议地址是从 0 还是从 1 开始，界面地址与报文地址之间的换算只能集中实现一次。
5. 帧编解码与连接读写分离，尽量实现为无状态组件。
6. 解码前必须验证最小长度、声明长度、功能码和错误响应，避免越界读取。
7. 批量响应必须能识别单项缺失、部分失败和整体失败。
8. 连接创建统一交给 `ConnectionFactory` 汇聚的 `ConnectionAdapterProvider` 和 `ConnectionManager` 管理。
9. 连接键继续使用本地 `deviceId`，不得使用云平台身份或临时地址替代。
10. 连接、断开、健康检查、重连和关闭必须幂等。
11. 建连失败时必须释放已经创建的通道、客户端、会话、定时任务和订阅句柄。
12. 请求必须有明确超时；超时后应取消未完成任务并清理响应等待结构。

## 16. 协议采集、写入、订阅与批量

1. 拉取型协议的 `doReadPoint` 返回协议原始值或清晰定义的驱动值，通用转换和质量处理继续交给基类。
2. 批量读取结果必须按稳定 `pointId` 回填，不得按易变点位名称关联。
3. 单个点位失败不应无条件拖垮整批结果；协议整体失败时必须保留明确异常语义。
4. 不支持的能力必须明确抛出“不支持”异常或声明为 `UNSUPPORTED`，不能伪造成功。
5. 原生订阅必须由协议或驱动真实提供，不能把定时轮询标记为原生订阅。
6. 订阅降级到轮询时必须遵循项目的 `SubscriptionFallbackStrategy`，并在运行状态中暴露实际模式和降级原因。
7. 推送数据必须调用统一遥测入口，使质量、告警、缓存、历史和上报行为与轮询数据一致。
8. 取消订阅必须释放监听器、句柄、定时任务和响应等待对象。
9. 重连后是否恢复订阅必须有明确策略和测试。
10. 批量策略必须尊重协议请求上限、报文大小、站号、区域、对象类型和连续地址边界。
11. 修改公共批量策略时必须回归 Modbus 及其他已有批量协议。

## 17. 数据类型和点位字段

1. 平台统一类型使用 `DataPoint.dataType`，用于缓存、展示、上报、历史存储和通用值转换。
2. 协议原生类型优先使用 `DataPoint.additionalConfig.driverDataType`。
3. 只有协议标准确实需要专属主类型字段时，才允许使用类似 KNX `dptId` 的协议字段。
4. `MODBUS_TCP` 和 `MODBUS_RTU` 继续以 `dataType` 决定读取长度和寄存器解码。
5. 不能明确映射到平台类型时，要求用户显式配置 `dataType`，不得猜测。
6. 新协议必须在 Schema 中明确 `typeMode`、`primaryTypeField` 和 `platformDataTypeMode`。
7. 点位差异通过 `DataPoint.address` 和 `additionalConfig` 表达，禁止为单一协议持续扩张公共实体顶层字段。
8. 类型模型发生变化时必须同步更新 `docs/20-点位类型与协议原生类型最终规则.md`。

## 18. 前端控制台规则

1. 控制台生产页面必须通过 `collector-desktop/src/api/` 下的统一 API 封装访问后端接口。
2. 禁止在 Vue 生产页面中绕过统一 API 封装直接散落 `fetch` 调用；Electron 主进程受控 HTTP 代理除外。
3. 禁止在生产控制台页面保留模拟业务数据、静态风险设备、静态拓扑状态或失败默认正常状态。
4. 概览页、实时数据、告警、设备管理、协议配置、采集配置、云平台配置、手动控制、设备影子、系统诊断、日志和网络检测必须接真实接口或明确显示未启用状态。
5. 协议表单字段必须由 `/api/protocols` 和 `/api/protocols/{protocol}/fields` 的 Schema 驱动。
6. 新增本地临时设备弹窗不得硬编码协议字段，特殊交互应优先扩展通用 Schema 表达能力。
7. 页面指标失败时不得默认展示健康、在线或 0 风险，应展示未启用、未知、加载失败或接口返回的真实状态。
8. 页面中文文案、状态含义和字段说明必须与后端实际字段一致。
9. 修改桌面控制台后必须至少执行 `npm --prefix collector-desktop run typecheck`、`npm --prefix collector-desktop test` 和 `npm --prefix collector-desktop run build`；涉及网页交付时必须执行 `npm --prefix collector-desktop run build:web` 并确认后端 jar 包含 `/desktop/index.html`；涉及客户端打包交付时还必须执行 `ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/ npm --prefix collector-desktop run pack`。

## 19. 运行状态与可观测性

1. 运行状态统一模型以 `ConsoleRuntimeStatusSnapshot` 及相关应用服务为准。
2. 控制台运行指标优先通过 `/monitor/runtime` 获取，不得新增第二套互相矛盾的运行状态模型。
3. 指标字段必须能追溯到真实采集、连接、缓存、线程池、上报、存储或告警状态。
4. 上报线程池、批量分发线程池、数据处理线程池等指标必须来自实际 Spring 管理执行器。
5. 不能因为执行器未启用、指标缺失或采集失败就展示为健康状态。
6. 高频点位读取日志使用合理级别，禁止在默认信息日志中逐点输出大量数据。
7. 建连、断连、重连、订阅降级、批量失败、上报重试和 ACK 异常应提供可检索的中文日志。
8. 异常信息必须保留协议、设备和点位上下文，但不得包含密码、令牌、设备密钥或完整敏感报文。

## 20. 可靠上报、ACK 与设备影子

1. 上报入口保持在 `CacheReportService`、`CloudOutboxService`、`CloudOutboxCoordinator` 和 `ReportManager` 组成的可靠链路内。
2. 协议采集器不得直接调用云端上报处理器、设备影子或 Outbox 存储。
3. 同一条业务消息重试时必须保持稳定 `messageId`，不得因为重试生成新的业务身份。
4. 上报处理器返回成功、云端 ACK 成功和影子提交完成是不同状态，必须按照配置的提交策略分别处理。
5. 只有达到配置的提交点后才能清除影子脏数据，不得在仅发送成功时默认视为云端已经接收。
6. ACK 重复、乱序、超时后到达和应用重启后到达时，不得造成重复提交或错误回滚。
7. Outbox、ACK、重试、隔离和设备影子状态迁移必须幂等。
8. 禁止通过直接删除 Outbox、待处理消息或影子脏数据键修复线上问题；必须提供可审计的重放、迁移或隔离处理方式。

## 21. 缓存、Redis 与历史存储

1. 本地缓存、Redis、历史存储和设备影子键必须使用本地 `deviceId`。
2. Redis key、字段名和 TTL 规则必须集中定义或通过现有常量类复用，不得散落拼接。
3. 缓存指标字段统一使用 `CacheMetricKeys`。
4. 历史存储 JSON 字段统一使用 `TelemetryStorageJsonKeys`。
5. 设备影子文档字段统一使用 `ShadowDocumentKeys`。
6. 修改 Redis、TDengine 或影子文档结构时必须评估历史兼容、迁移策略和控制台查询影响。
7. 高频写入路径不得引入无界集合、全量扫描或阻塞式长事务。
8. 不得在协议层直接操作 Redis、TDengine 或设备影子。

## 22. 异步线程池与资源管理

1. 使用 Spring 管理的命名线程池和调度器，线程池名称集中定义。
2. 禁止每个设备、点位、请求或批次创建独立线程池。
3. 禁止主采集链路无约束使用 `ForkJoinPool.commonPool()` 或默认异步执行器。
4. 每次异步切换必须保留本地 `deviceId`、稳定 `pointId`、设备运行代次、采集任务 ID 和消息 ID。
5. `@Order` 只表示阶段排序或任务提交顺序，不表示不同线程池中的任务完成顺序。
6. 业务要求严格先后顺序时，必须使用同一执行器、显式 `CompletableFuture` 串联或可靠状态机。
7. 核心遥测任务被拒绝时不得只记录日志后静默丢弃，必须计数、告警、降级或进入可靠补偿机制。
8. 第三方库要求回调线程时，应尽快把结果转交到受控执行器，避免在网络事件线程执行阻塞存储或上报。
9. 队列容量、拒绝策略、超时和关闭等待时间必须可解释；生产主链禁止默认使用无界队列。
10. 所有定时任务、连接、会话、客户端和订阅句柄必须能在设备停止和应用优雅停机时释放。

## 23. 安全规范

1. 密码、令牌、设备密钥、证书口令等必须通过环境变量或受控配置注入。
2. 禁止在源码、测试固定配置、文档示例和日志中提交真实秘密。
3. 示例秘密使用明显占位符，生产配置不得提供可直接使用的默认密码。
4. TLS 证书校验默认开启；忽略证书或信任全部证书只能用于明确隔离的调试场景。
5. 自定义报文、脚本、命令和写入接口必须限制长度、范围和允许操作。
6. 修改配置文件后必须执行 `node scripts/scan-config-secrets.mjs`。

## 24. 测试验证规则

新增协议最低测试矩阵：

1. 协议编码、规范名称和别名归一化测试。
2. `ProtocolSchemaService` 输出测试，包括能力、连接字段、点位字段和类型规则。
3. `ProtocolDescriptorProvider` 到 `ProtocolDescriptorRegistry` 的聚合测试。
4. `CollectorFactory` 采集器映射测试。
5. `ConnectionAdapterProvider` 自动发现和 `ConnectionFactory` 连接适配器映射测试。
6. `ProtocolConnectionValidator` 正常、缺失、越界和非法枚举测试。
7. 地址解析正常、边界、非法和兼容格式测试。
8. 帧编码、成功响应、协议错误响应、短帧、长度不一致和异常数据测试。
9. 连接、断开、超时、重连、重复关闭和失败清理测试。
10. 单点读取、批量读取、部分失败、写入和不支持能力测试。
11. 订阅协议的订阅、取消、降级、重连恢复和资源释放测试。
12. 统一数据处理链测试，确认质量、设备 ID、点位 ID 和处理元数据没有丢失。
13. 应用上下文测试，防止构造器、Bean 注入或条件配置导致应用无法启动。

常用提交前验证命令：

```powershell
mvn -B -ntp test
mvn -B -ntp -Pp0-regression test
npm --prefix collector-desktop run typecheck
npm --prefix collector-desktop test
npm --prefix collector-desktop run build
npm --prefix collector-desktop run build:web
node scripts/audit-source-language.mjs
node scripts/scan-config-secrets.mjs
git diff --check
```

如果由于本地缺少真实设备、数据库、Redis、Node.js 或其他外部环境而无法执行某项检查，交付说明中必须明确列出未执行项和剩余风险，不能写成“全部通过”。

## 25. 文档同步要求

新增或修改协议时，必须同步检查以下文档：

1. `docs/02-采集协议支持与实现方式.md`：更新协议支持状态和能力边界。
2. `docs/protocols/README.md`：更新协议文档索引和状态摘要。
3. `docs/protocols/{协议编码}.md`：记录配置、地址、类型、能力、示例、测试和限制。
4. `docs/20-点位类型与协议原生类型最终规则.md`：类型模型发生变化时同步更新。
5. `docs/99-问题与未完成功能清单.md`：记录仍未完成或只完成实验验证的能力。
6. `README.md`：用户可见配置、启动方式或部署要求发生变化时同步更新。
7. `AGENTS.md`：架构规则或开发流程发生变化时同步更新，避免新旧规则冲突。

每个协议文档至少包含以下内容：

1. 实现状态和能力状态。
2. 主要实现类和依赖库。
3. 最小连接配置示例。
4. 点位地址和数据类型示例。
5. 读取、批量读取、写入、订阅和浏览能力说明。
6. 超时、重连和限制说明。
7. 自动化测试命令。
8. 模拟验证和实机验证结论。
9. 已知兼容性边界和未完成功能。

## 26. 禁止事项

1. 禁止绕过 `ProtocolDescriptorProvider`，在控制器、前端或注册表中硬编码新协议元数据。
2. 禁止只实现采集器而遗漏 `ConnectionAdapterProvider`、连接工厂校验链路、Schema、测试和文档。
3. 禁止把云平台身份作为本地设备主键使用。
4. 禁止协议实现直接操作 Redis、历史库、设备影子或云上报通道。
5. 禁止复制一套数据质量、告警或后处理逻辑。
6. 禁止用假成功、固定值或空实现声明协议已经支持。
7. 禁止吞掉连接、解析、写入、订阅、上报和 ACK 异常。
8. 禁止创建不受控线程、线程池、定时器或无法关闭的客户端。
9. 禁止在生产代码中默认关闭 TLS 校验或写入明文秘密。
10. 禁止为了新协议无测试地修改已有采集调度和公共数据链路。
11. 禁止把仿真测试结果描述为真实设备全面验证。
12. 禁止提交代码状态与协议文档不一致的变更。
13. 禁止在生产控制台保留模拟业务数据、静态健康状态或失败默认正常逻辑。
14. 禁止新增 `@Autowired` 字段注入、重复 `@Value` 配置和无边界异步任务。
15. 禁止新增散落的 `Map<String, Object>` 固定字段字符串。

## 27. 交付检查清单

提交新协议或核心链路改动前，逐项确认：

- [ ] 已确定唯一规范协议编码和必要别名。
- [ ] 已通过独立 `ProtocolDescriptorProvider` 注册协议元数据和真实能力状态。
- [ ] 已确认 `ProtocolDescriptorRegistry` 只做聚合、归一化和查询，不承载新协议大段定义。
- [ ] 已实现并测试地址解析与边界校验。
- [ ] 已实现并测试帧编解码或第三方驱动地址转换。
- [ ] 已通过独立 `ConnectionAdapterProvider` 接入连接适配器，并由 `ConnectionFactory` 和 `ConnectionManager` 管理连接。
- [ ] 已在 `CollectorFactory` 注册采集器。
- [ ] 已在 `ProtocolConnectionValidator` 增加后端校验。
- [ ] 已明确连接字段、点位字段和配置存储位置。
- [ ] 已明确平台类型与协议原生类型关系。
- [ ] 前端表单由协议 Schema 驱动，没有新增重复硬编码。
- [ ] 拉取或推送数据进入统一数据质量与遥测后处理链。
- [ ] 本地 `deviceId` 在缓存、Redis、数据库、运行状态和上报映射中保持一致。
- [ ] 已验证读取、批量读取、部分失败和超时行为。
- [ ] 已验证协议声明支持的写入、订阅、浏览或命令能力。
- [ ] 已验证断开、重连、重复关闭和资源释放。
- [ ] 已补充 Provider、工厂、Schema、校验器、采集器和应用上下文测试。
- [ ] 已执行相关 Maven 验证和必要的 P0 回归。
- [ ] 已执行前端语法检查、控制台契约检查、秘密扫描、UTF-8 和 Git 差异检查。
- [ ] 已更新协议总览、协议详情和未完成功能文档。
- [ ] 已区分模拟验证、实机验证和未验证能力。

只有上述适用项全部完成，且不存在影响原有采集流程的回归问题，协议才可以声明为交付完成。

## 28. SQLite / Redis 精简改造固定阶段与权限

1. 本次 SQLite / Redis 改造只在 `feature_2.0` 分支进行，严格按阶段 01～08 顺序执行；每次只完成当前获授权阶段。
2. 每阶段修改后执行关联检查及测试，通过后只暂存本阶段文件并本地提交；提交后停止，等待用户手动 push，再等待 ChatGPT 对 GitHub 实际提交明确判定 PASS，才可进入下一阶段。
3. 严禁自动 push、创建分支、worktree、merge、rebase、`reset --hard` 或 `clean`；不得清理、覆盖未提交的用户改动。
4. 遇到超范围问题，先说明必要性、涉及文件及影响并等待批准；未经批准不做额外修复、重构、文档扩张、性能优化或模块迁移。

### 28.1 SQLite 配置权威与分层

1. SQLite 是设备、协议、连接、点位及相关网关配置的持久化权威数据源，不能另建配置权威缓存或将 SQLite 配置复制到 Redis 作为第二权威源。
2. 所有正式 SQLite 配置业务读写只能通过唯一的 `SqliteConfigService`。复用 `ConfigurationStore`、`ConfigurationReader` 契约，不建立业务等价的第二服务或第二个 `ConfigManager`；旧接口仅可作为无 SQL 的薄委托适配器。
3. Service 接口只声明业务方法，不编写 SQL；ServiceImpl 负责事务、校验、CAS、保存编排及错误处理；Mapper 负责设备配置业务 SQL、参数绑定和 ResultSet 映射。
4. Controller、ApplicationService、ConfigManager、采集模块和前端接口不得直接使用 SQLite JDBC、Connection、PreparedStatement 或 Mapper；业务数据库连接仅由统一 ServiceImpl 内部使用。`SqliteDatabase`、`SqliteFileSecurity`、`SqlitePaths` 属于物理基础设施，已有安装入口可继续执行目录认领和安装守卫，不得借此增加配置业务读写入口。
5. 数据库建表 SQL 保留在现有 migration 资源；不引入 MyBatis、MyBatis-Plus、Hibernate 或其他 ORM；不因改造清空、删除、重建已有数据库或未经授权修改表结构。
6. 保留稳定 `deviceId`、全局唯一 `pointId`、外键、唯一性、事务原子性及必要的修订校验；不得在提交失败时返回伪成功，也不得因编排失败重复执行已成功提交的 SQL。
7. 不将实时数据或历史采集值写入 SQLite；SQLite CRUD 不依赖 Redis 或 TDengine 的可用性。
8. 阶段 01 保持现有启动恢复行为；阶段 05 才取消应用启动时将全部 SQLite 设备配置载入长期内存。阶段 04 才实现用户手动启动时按 `deviceId` 读取完整配置、停止时释放任务和运行配置且不删除 SQLite 数据。

### 28.2 Redis、采集与设备生命周期

1. Redis 只用于实时数据缓存、必要消息流和运行时数据；继续复用现有协议采集及公共数据处理链路。
2. 连接成功不等于点位有效采集成功；启动单设备不能意外启动其他设备；设备必须手动启动，应用启动后不自动采集。
3. 本轮不重做 TDengine 历史 ACK、死信队列或 exactly-once 体系。

### 28.3 固定阶段及范围

1. 阶段 01：统一 SQLite Service / ServiceImpl / Mapper，建立本规范；不得提前实现阶段 02～08。
2. 阶段 02：设备列表、详情、连接、点位按需查询，去掉单设备保存时的全库扫描。
3. 阶段 03：SQLite CAS、持久修订版本、提交回执及 CRUD 一致性。
4. 阶段 04：手动启动按 `deviceId` 加载 SQLite 配置，停止释放运行资源。
5. 阶段 05：取消启动时 SQLite 全量恢复，调整运行态查询与前端显示。
6. 阶段 06：Redis 配置、实时数据写入、读回及异常状态验证。
7. 阶段 07：Redis Windows 服务及进程管理。
8. 阶段 08：端到端回归、必要遗留清理及最终验收。

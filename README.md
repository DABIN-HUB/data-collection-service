# data-collection-service

面向工业物联网场景的多协议采集网关，基于 `Spring Boot 3.x + Java 17` 构建，覆盖从设备接入、采集调度、数据处理、缓存、实时流、历史存储、云端上报到运行监控的完整链路。

这个仓库不是单一协议 Demo，而是一个可私有化部署、可持续扩展的采集底座。它的重点不是“把一个点读出来”，而是把工业现场采集做成一个稳定、可治理、可观察的系统。

## 项目定位

本项目适合两类场景：

1. 作为工业现场私有化部署的数据采集网关。
2. 作为工业物联网平台的采集底座进行二次开发。

系统目标：

- 统一多协议采集模型
- 提供高性能、可扩展的采集调度能力
- 将设备点位数据统一收敛为标准处理结果
- 支持缓存、实时流、历史存储、云端上报的全链路闭环
- 提供在线配置治理、运行态监控和问题可追踪能力

## 控制台访问

项目内置了静态管理控制台页面，服务启动后可直接访问：

- 本地默认地址：`http://127.0.0.1:9090/collector/admin/index.html`
- 如果你修改了 `server.port` 或 `server.servlet.context-path`，请按实际配置调整访问地址

控制台前端资源位置：

- `src/main/resources/static/admin/index.html`
- `src/main/resources/static/admin/app.js`
- `src/main/resources/static/admin/styles.css`

## 项目优势

这个工程的优势不在“读一个寄存器”，而在于同时解决以下问题：

- 多协议、多厂商设备并存
- 点位规模大、刷新周期不一致
- 协议层返回结构不统一
- 采集后还要做统一处理、统一缓存、统一上报
- 配置需要在线变更，并且要能安全重载
- 现场问题需要通过指标、日志和健康检查快速定位

项目当前体现出来的工程化能力包括：

- 多协议统一工厂与生命周期管理
- 时间片调度、批次规划、协议读计划构建
- `ProcessResult` 统一结果对象
- 本地缓存、Redis 缓存、Redis Stream、历史存储、云端上报的闭环链路
- 配置治理、差异诊断、导入导出、手动同步
- 健康检查、性能监控、访问日志治理

从架构上看，它是“采集协议框架 + 调度系统 + 数据处理总线 + 运维治理层”的组合。

## 核心能力

### 1. 多协议采集

当前已纳入统一工厂与生命周期管理的协议：

- `MODBUS_TCP`
- `MODBUS_RTU`
- `MODBUS_ASCII`（作为 `MODBUS_RTU` 兼容接入）
- `SIEMENS_S7`
- `MITSUBISHI_MC`
- `OMRON_FINS`
- `ETHERNET_IP`
- `ADS`
- `KNXNET_IP`
- `OPC_UA`
- `OPC_UA_PLC4X`
- `OPC_DA`
- `BACNET_IP`
- `BACNET_MSTP`
- `BACNET_SC`
- `IEC104`
- `IEC61850`
- `MQTT`
- `SNMP`
- `COAP`
- `HTTP`
- `WEBSOCKET`
- `CUSTOM_TCP`（当前为占位实现）
- `CUSTOM_UDP`（当前沿用占位实现别名）

另外，统一协议工厂还兼容以下别名或安全变体：

- `HTTPS`
- `MQTT_SSL`
- `COAP_SSL`
- `WEBSOCKET_SSL`
- `SNMP_V1` / `SNMP_V2C` / `SNMP_V3`
- `S7` / `MC` / `FINS` / `BACNET` 等别名

协议能力特征：

- `Modbus`：支持 TCP / RTU / ASCII，支持读计划、连续地址聚合读取、批量分块写
- `Siemens S7`：支持 `DB` / `I` / `Q` / `M` 等地址形式，支持读、写和订阅
- `Mitsubishi MC`：当前已落地自研 `MC 3E Binary over TCP` 采集链路，支持轮询读写
- `OMRON FINS`：已支持 `FINS/UDP` 读写与连续块合并读取
- `EtherNet/IP`：支持 `Logix Tag` 风格符号地址读写
- `ADS`：支持 `Beckhoff ADS / AMS` 符号读写与订阅
- `KNXnet/IP`：支持组地址采集，支持 `DPT` 类型映射
- `OPC_UA`：支持读/写/订阅/浏览
- `OPC_UA_PLC4X`：作为兼容别名保留，沿用 `OPC_UA` 主实现
- `OPC_DA`：支持 `HTTP` 桥接模式与 `INMEMORY` 模式
- `BACnet`：已覆盖 `IP`、`MS/TP`、`SC` 三类接入形态，支持读写与订阅；其中 `BACNET_SC` 当前按实验性能力提供
- `IEC104`：支持读、总召唤、命令下发、单点召唤
- `IEC61850`：支持模型加载、读写、报告处理
- `MQTT`：支持主题订阅、发布、消息映射，兼容 `MQTT_SSL`
- `SNMP`：支持 GET/SET/WALK，兼容 `SNMP_V1` / `SNMP_V2C` / `SNMP_V3`
- `COAP`：支持 GET/POST/PUT/DELETE/Observe，兼容 `COAP_SSL`
- `HTTP`：支持请求-响应采集，兼容 `HTTPS`
- `WEBSOCKET`：支持消息解析、认证、心跳与缓存回填，兼容 `WEBSOCKET_SSL`
- `CUSTOM_TCP` / `CUSTOM_UDP`：当前只保留生命周期占位能力，真实采集协议待补齐

协议文档入口：

- [协议索引](./docs/protocols/README.md)
- [协议字段汇总](./docs/protocols/FIELD_CONFIG_SUMMARY.md)

### 2. 统一调度模型

调度不是简单定时轮询，而是“时间片 + 批次 + 异步处理”的组合模型：

- 设备启动时生成批次并分配到时间片
- 时间片由独立调度器固定频率触发
- 批次采集与数据处理使用不同线程池解耦
- 支持动态时间片调整
- 支持点位自适应采集频率
- 支持协议级 `ReadPlan` 构建，降低请求次数

这使它更适合多设备、大点位、混合刷新频率的现场环境。

### 3. 统一数据处理

所有协议采集结果最终统一落到 `ProcessResult`：

- 原始值类型转换
- 缩放、偏移、布尔/数值转换
- 数据质量判定
- 结果封装与缓存

这意味着协议层只负责“拿数据”，后续缓存、实时流、历史存储和上报都基于同一个标准结果对象。

### 4. 多级缓存

采集结果会进入统一缓存链路：

- 本地缓存
- Redis 缓存
- 设备影子聚合

对应核心组件：

- `MultiLevelCacheManager`
- `LocalCacheManager`
- `RedisCacheManager`
- `ShadowManager`

### 5. Redis Stream 实时流

项目已经把“处理后的采集结果实时写入 Redis Stream”作为正式能力接入主链路。

特点：

- 保持原有缓存和上报逻辑不变，仅新增实时流分支
- 写入的是处理后的结果，不是裸原始值
- 支持两种保留模式：
  - `COUNT`：保留最近 N 条
  - `TIME`：保留最近 N 秒
- `TIME` 模式通过 `XTRIM MINID` 实现时间窗口裁剪

这让项目天然具备实时消费接口，适合后续接流式分析、实时告警或下游订阅服务。

### 6. 历史存储

项目已支持将 `ProcessResult` 持久化到 TDengine：

- Spring Boot 单数据源接入
- MyBatis 执行 TDengine SQL
- 自动建库、建超级表、建设备子表
- 支持按设备 / 点位 / 时间范围查询历史

这部分不是独立 Demo，而是已经接入采集后的标准链路。

### 7. 云端上报

上报链路支持：

- 设备影子聚合
- 脏设备 flush
- MQTT / HTTP / TCP 分发
- 变化点合并与异步上报
- 告警上报

因此项目不是“只采不报”的本地工具，而是面向平台集成设计的采集服务。

### 8. 配置治理

项目提供完整的配置治理接口：

- 配置摘要查询
- 单设备设备/点位/连接查询
- 差异诊断
- 在线更新设备、点位、连接
- 配置导入 / 导出
- 手动触发同步

这对工业网关很关键，因为现场配置通常不是一次写死，而是持续演进。

### 9. 监控与健康

运维观测能力包括：

- 健康检查
- 缓存指标
- 设备运行指标
- 性能指标
- 调度快照
- 异常指标
- 访问日志治理

可以更快定位“调度慢、连接不稳、缓存异常、上报积压、接口高风险操作”等问题。

## 主链路

理解本项目，先抓住这条主链：

1. `CollectionService` 接收启动请求
2. `CollectionScheduler.startDevice(...)` 启动设备采集
3. `ConfigManager` 加载设备、连接、点位配置
4. `CollectionManager.registerDevice(...)` 通过 `CollectorFactory` 创建协议采集器
5. 采集器建立连接并构建读计划
6. 调度器按时间片触发批次读取
7. `BaseCollector.readPoint/readPoints` 统一生成 `ProcessResult`
8. `CollectorDataCacheAspect` 执行：
   - 多级缓存写入
   - 上报聚合
   - Redis Stream 写入
   - 历史存储写入

从这里可以看出，采集、处理、缓存、实时流、历史存储、上报不是离散模块，而是单条闭环数据链。

## 关键模块

### 调度与设备生命周期

- `CollectionScheduler`
- `CollectionManager`
- `CollectorFactory`
- `DeviceBatchPlanner`

### 协议抽象

- `ProtocolCollector`
- `BaseCollector`
- `core/collector/protocol/*`

### 连接层

- `ConnectionFactory`
- `ConnectionManager`
- `core/connection/adapter/*`

### 数据处理

- `ProcessResult`
- `DataQualityProcessor`
- `CollectedDataProcessor`

### 缓存与实时流

- `CollectorDataCacheAspect`
- `MultiLevelCacheManager`
- `TelemetryStreamServiceImpl`

### 上报

- `CacheReportService`
- `ReportManager`
- `ReportHandler`

### 历史存储

- `HistoryDataService`
- `TimeSeriesService`
- `storage/repository/*`

### 监控与接口

- `MonitorController`
- `DeviceController`
- `DataController`
- `ConfigController`
- `HealthController`

## 目录结构

```text
src/main/java/com/wangbin/collector
├── api
├── common
├── core
│   ├── cache
│   ├── collector
│   ├── config
│   ├── connection
│   ├── processor
│   └── report
├── monitor
└── storage
```

## 技术栈

- Java 17
- Spring Boot 3.x
- Maven 3.9+
- Redis 7
- MyBatis
- TDengine
- Docker / Docker Compose
- MQTT Paho
- Eclipse Milo OPC UA
- SNMP4J
- Californium CoAP
- j60870
- iec61850bean

## 协议支持一览

| 协议 | 状态 | 说明 |
| --- | --- | --- |
| MODBUS_TCP | 已支持 | 读计划 + 批量分块写 |
| MODBUS_RTU / MODBUS_ASCII | 已支持 | 串口参数 + 帧间延时 |
| SIEMENS_S7 | 已支持 | `DB/I/Q/M` 地址读写 + 订阅 |
| MITSUBISHI_MC | 已支持 | 自研 `MC 3E Binary over TCP` 读写 |
| OMRON_FINS | 已支持 | `FINS/UDP` 读写 + 批量块合并 |
| ETHERNET_IP | 已支持 | `Logix Tag` 风格符号地址读写 |
| ADS | 已支持 | `ADS / AMS` 符号读写 + 订阅 |
| KNXNET_IP | 已支持 | 组地址采集 + `DPT` 类型映射 |
| OPC_UA / OPC_UA_PLC4X | 已支持 | 读/写/订阅/浏览 |
| OPC_DA | 已支持 | `HTTP` 桥接 / `INMEMORY` |
| BACNET_IP | 已支持 | `Who-Is/I-Am`、`COV` 订阅、属性读写 |
| BACNET_MSTP | 已支持 | `RS485` 令牌总线接入 |
| BACNET_SC | 实验性 | 安全 WebSocket 形态，已接入统一链路 |
| IEC104 | 已支持 | 读、召唤、命令 |
| IEC61850 | 已支持 | 模型加载、读写、报告 |
| MQTT / MQTT_SSL | 已支持 | 主题映射、订阅、发布 |
| SNMP / SNMP_V1 / SNMP_V2C / SNMP_V3 | 已支持 | GET/SET/WALK，含 SNMPv3 |
| COAP / COAP_SSL | 已支持 | GET/POST/PUT/DELETE/Observe |
| HTTP / HTTPS | 已支持 | 请求-响应采集 |
| WEBSOCKET / WEBSOCKET_SSL | 已支持 | 消息解析、认证、心跳与缓存回填 |
| CUSTOM_TCP / CUSTOM_UDP | 占位 | 生命周期可用，真实协议待补齐 |

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.9+
- Redis 7+（启用缓存/实时流时需要）
- TDengine（启用历史存储时需要）

### 本地启动

```bash
mvn clean package -DskipTests
java -jar target/data-collection-service.jar --spring.profiles.active=dev
```

### 配置说明

当前仓库里与 MQTT 相关的配置有两组，作用不同：

- `collector.report.mqtt`：云端上报与平台侧 MQTT 下行配置，供 `MqttReportHandler`、统一 ACK、`thing.property.set` / `thing.service.invoke` 等链路使用。
- `collector.mqtt`：MQTT 作为采集协议时的默认连接兜底配置，主要供 `MqttCollector` 在设备连接参数不完整时回退使用。

如果你当前主要使用“采集结果上报到云端”的链路，优先关注 `collector.report.mqtt`。
如果你当前要接入“MQTT 设备作为采集源”，再关注 `collector.mqtt`。

### Docker Compose

```bash
docker compose up -d
docker compose logs -f app
```

## 常用接口

| 控制器 | 路径 | 说明 |
| --- | --- | --- |
| `DeviceController` | `/api/device/{deviceId}/start` | 启动设备采集 |
| `DeviceController` | `/api/device/{deviceId}/stop` | 停止设备采集 |
| `DataController` | `/api/data/device/{deviceId}` | 查询设备缓存数据 |
| `DataController` | `/api/data/device/{deviceId}/point/{pointId}` | 查询单点 |
| `DataController` | `/collector/api/data/history/device/{deviceId}/point/{pointId}` | 查询历史 |
| `ConfigController` | `/api/config/**` | 配置治理、导入导出、同步 |
| `MonitorController` | `/monitor/**` | 性能、缓存、系统、异常监控 |
| `HealthController` | `/health` | 健康检查 |

## 文档导航

建议按顺序阅读：

1. [文档导航](./docs/00-文档导航.md)
2. [系统架构与主流程](./docs/01-系统架构与主流程.md)
3. [采集协议支持与实现方式](./docs/02-采集协议支持与实现方式.md)
4. [采集调度逻辑详解](./docs/03-采集调度逻辑详解.md)
5. [处理-缓存-上报-实时流链路](./docs/04-处理-缓存-上报-实时流链路.md)
6. [接口与监控能力](./docs/05-接口与监控能力.md)
7. [历史数据与TDengine存储](./docs/06-历史数据与TDengine存储.md)
8. [配置治理接口](./docs/07-配置治理接口.md)
9. [访问日志治理](./docs/08-访问日志治理.md)
10. [问题与未完成功能清单](./docs/99-问题与未完成功能清单.md)

协议专项：

- [协议索引](./docs/protocols/README.md)
- [协议字段汇总](./docs/protocols/FIELD_CONFIG_SUMMARY.md)

## 当前状态说明

当前仓库已经不是早期的协议样例集合，而是具备以下成熟特征的采集服务：

- 多协议统一接入
- 统一调度模型
- 统一结果对象
- 缓存 / 实时流 / 历史存储 / 上报闭环
- 在线配置治理
- 健康检查、监控与访问日志治理

仍在持续完善的部分，见：

- [问题与未完成功能清单](./docs/99-问题与未完成功能清单.md)

## License

This project is licensed under the Apache License 2.0.

- Full text: [LICENSE](LICENSE)
- Official site: https://www.apache.org/licenses/LICENSE-2.0

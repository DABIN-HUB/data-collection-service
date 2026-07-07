# data-collection-service

面向工业设备与工业物联网场景的多协议数据采集服务，基于 `Spring Boot 3.x + Java 17` 构建，覆盖设备接入、采集调度、数据处理、缓存、实时流、历史存储、云端上报和运行监控的完整链路。

这个仓库的重点不是单协议演示，而是一个可持续扩展的采集底座。

## 项目定位

适合：

- 作为工业现场私有化部署的数据采集网关
- 作为工业物联网平台的采集底座进行二次开发
- 作为多协议采集、缓存、上报、治理一体化的后端服务

## 核心能力

- 多协议统一接入与采集器工厂管理
- 时间片调度、批次规划、协议级读计划构建
- `ProcessResult` 统一数据处理结果
- 本地缓存、Redis 缓存、Redis Stream、历史存储、云端上报闭环
- 在线配置治理、设备启停、运行监控和健康检查

## 协议支持

当前工程内已整理或已接入的协议包括：

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
- `CUSTOM_TCP`（当前仍为占位实现）

协议字段说明优先看：

- [docs/protocols/README.md](docs/protocols/README.md)
- [docs/protocols/FIELD_CONFIG_SUMMARY.md](docs/protocols/FIELD_CONFIG_SUMMARY.md)

## 配置方式

仓库保留当前项目的配置方式，不额外引入演示模式：

- 主配置文件：[src/main/resources/application.yml](src/main/resources/application.yml)
- 开发环境覆盖：[src/main/resources/application-dev.yml](src/main/resources/application-dev.yml)
- 生产环境覆盖：[src/main/resources/application-prod.yml](src/main/resources/application-prod.yml)

当前开源整理只做了两件事：

- 去掉仓库中不适合公开的私有 broker、密码和凭证
- 保留原有 Redis / TDengine / MQTT / 云端配置结构，方便继续按现有方式部署

如果你直接使用这个仓库，请先替换以下配置：

- TDengine 地址、账号、密码
- Redis 地址、密码
- MQTT broker、账号、密码、产品标识
- 运维 token
- 云端配置中心地址与 API token

可选示例环境变量见：

- [.env.example](.env.example)

## 启动说明

### 本地开发

默认激活 `dev` profile：

```bash
mvn -B -ntp spring-boot:run
```

### 指定生产配置启动

```bash
mvn -B -ntp spring-boot:run -Dspring-boot.run.profiles=prod
```

或使用打包后的 Jar：

```bash
java -jar target/data-collection-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

## 文档入口

建议优先阅读：

- [docs/00-文档导航.md](docs/00-文档导航.md)
- [docs/01-系统架构与主流程.md](docs/01-系统架构与主流程.md)
- [docs/02-采集协议支持与实现方式.md](docs/02-采集协议支持与实现方式.md)
- [docs/03-采集调度逻辑详解.md](docs/03-采集调度逻辑详解.md)
- [docs/04-处理-缓存-上报-实时流链路.md](docs/04-处理-缓存-上报-实时流链路.md)
- [docs/07-配置治理接口.md](docs/07-配置治理接口.md)

## 开源说明

这次仓库整理保留的是“现有工程化配置方式”，不是零依赖体验版。

这意味着：

- 不提供额外 demo profile
- 不引入 H2 或单机演示替代存储
- 更适合已有现场环境、已有 Redis / TDengine / MQTT / 云端服务的团队直接接入或二开

## 相关仓库

- 前端管理台：`wangbin-iot-vue3`
- 云端后端：`wangbin-iot-cloud`

## 参与贡献

- [CONTRIBUTING.md](CONTRIBUTING.md)
- [SECURITY.md](SECURITY.md)
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)

## License

This project is licensed under the Apache License 2.0.

- Full text: [LICENSE](LICENSE)
- Official site: https://www.apache.org/licenses/LICENSE-2.0
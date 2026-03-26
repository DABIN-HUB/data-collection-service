# 历史数据与 TDengine 存储

## 1. 实现目标

将采集链路产出的 `ProcessResult` 持久化到 TDengine，支持按设备/点位/时间范围查询历史。

历史数据定义：采集值经过处理链后的结果，包含最终值、质量、消息、原始值/处理值、metadata。

## 2. 集成方式

当前采用你要求的集成方式：

1. Spring Boot 单数据源：`spring.datasource` 直接连接 TDengine。
2. MyBatis 执行 TDengine SQL：DDL/DML/查询均在 Mapper XML 中实现。

关键点：

- 数据源配置：`application.yml` 的 `spring.datasource` 使用 TDengine 驱动与 URL。
- MyBatis 映射：`mapper/storage/*.xml`。
- Mapper 接口：
  - `storage/repository/DataRepository`
  - `storage/repository/DeviceRepository`

## 3. 配置说明

### 3.1 Spring 数据源（TDengine）

```yaml
spring:
  datasource:
    driver-class-name: com.taosdata.jdbc.rs.RestfulDriver
    url: jdbc:TAOS-RS://127.0.0.1:6041/wangbin_collector
    username: root
    password: taosdata
```

### 3.2 历史存储开关

```yaml
telemetry:
  tdengine:
    enabled: false
    database: wangbin_collector
    super-table: wangbin_super
    sub-table-prefix: d_
    auto-create: true
    keep-days: 30
    query-default-limit: 500
    query-max-limit: 5000
```

- `enabled=false`：只关闭“历史写入/查询能力”，不影响采集主链路。
- `enabled=true`：开启 TDengine 历史写入与查询。

补充（配置收敛）：
- 协议采集默认参数已从 `application.yml` 移除 `collector.modbus/opc-ua/iec104/snmp` 本地块。
- 采集协议参数以远程配置中心下发为主；`collector.mqtt` 采集侧兜底参数当前暂保留。

## 4. 自动建库建表逻辑

由 `TimeSeriesService` 在写入前按需触发（幂等）：

1. `CREATE DATABASE IF NOT EXISTS ... KEEP ...`
2. `CREATE STABLE IF NOT EXISTS ...`
3. `CREATE TABLE IF NOT EXISTS <子表> USING <超级表> TAGS (...)`

命名规则：

- 超级表：`telemetry.tdengine.super-table`
- 子表：`sub-table-prefix + 规范化(deviceId)`

## 5. SQL 落点

- `src/main/resources/mapper/storage/DataRepository.xml`
  - 建库
  - 建超级表
  - 插入时序数据
  - 历史查询
- `src/main/resources/mapper/storage/DeviceRepository.xml`
  - 创建设备子表

## 6. 服务职责

- `HistoryDataService`：历史能力入口，负责开关判断、协议类型识别、查询入口。
- `TimeSeriesService`：核心编排层，负责 schema/table 幂等初始化与写入/查询调用。
- `DataRepository`（MyBatis Mapper）：DDL/DML/查询 SQL。
- `DeviceRepository`（MyBatis Mapper）：子表创建 SQL。

## 7. 查询接口

`GET /collector/api/data/history/device/{deviceId}/point/{pointId}?startTs=&endTs=&limit=`

- `startTs/endTs`：毫秒时间戳，可选。
- `limit`：可选，受 `query-max-limit` 限制。

## 8. 验证步骤

1. `telemetry.tdengine.enabled=true`。
2. 启动采集服务并触发设备采集。
3. 观察日志确认建库/建超级表/建子表成功。
4. 调用历史查询接口验证返回数据。
5. 关闭开关重启，验证主链路不受影响。

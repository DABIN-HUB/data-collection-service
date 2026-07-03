# 全面检查与测试报告

## 1. 本轮执行时间

执行日期：2026-07-03

## 2. 已执行测试

### 2.1 全量基线测试

命令：

```bash
mvn test
```

第一轮结果：

1. 测试总数：386
2. 失败：1
3. 错误：0
4. 跳过：0
5. 失败用例：`ProtocolControllerTest.shouldListProtocols`
6. 失败原因：测试断言写死协议数量为 `19`，当前协议注册表实际 primary protocol 数量为 `22`

处理方式：

1. 将 `ProtocolControllerTest` 的固定数量断言改为读取 `ProtocolDescriptorRegistry.primaryDescriptors().size()`。
2. 补充当前已注册关键协议断言：`BACNET_MSTP`、`BACNET_SC`、`MITSUBISHI_MC`、`OMRON_FINS`、`KNXNET_IP`。
3. 单独验证 `ProtocolControllerTest` 通过。

第二轮结果：

1. 测试总数：386
2. 失败：0
3. 错误：0
4. 跳过：0
5. 结论：全量基线通过

## 3. 当前观察到的环境告警

### 3.1 Maven 本地仓库写入告警

测试期间 Maven 多次输出本地仓库 tracking file 写入失败：

```text
D:\Program Files\Java\maven_jar\...\resolver-status.properties
D:\Program Files\Java\maven_jar\...\*.part.lock
```

判断：

1. 当前不影响本轮测试完成。
2. 风险在于后续依赖刷新或新增依赖时，可能因为本地 Maven 仓库目录权限导致下载或元数据更新失败。

建议：

1. 后续测试或构建尽量使用可写的本地 Maven 仓库目录。
2. 如需要稳定复现 CI 行为，可显式指定 `-Dmaven.repo.local=<workspace可写目录>`。

### 3.2 JSON 类重复告警

Spring Boot 测试启动时提示 classpath 中存在多个 `org.json.JSONObject`：

```text
org/json/json/20231013
com/vaadin/external/google/android-json
```

判断：

1. 当前不影响测试通过。
2. 运行时 JSON 行为存在潜在不确定性，建议后续依赖收敛时处理。

## 4. 已修改测试

文件：

```text
src/test/java/com/wangbin/collector/api/controller/ProtocolControllerTest.java
```

修改内容：

1. 去掉旧的固定协议数量断言。
2. 使用 `ProtocolDescriptorRegistry.primaryDescriptors().size()` 作为预期协议数量。
3. 增加当前注册表中新增驱动协议的存在性断言。

## 5. 本轮覆盖结论

当前全量自动化测试已覆盖并通过以下大类：

1. API 控制器：配置、控制、协议、影子等。
2. 认证和日志过滤器。
3. 缓存后处理、多级缓存、Redis 缓存。
4. 多协议采集器和连接适配器的现有单元/集成测试。
5. 调度器启动、停止、超时、代际保护、时间片 revision 等回归测试。
6. 配置管理、配置同步、协议 schema、协议连接校验。
7. 上报、设备影子、字段唯一性校验。

## 6. 后续专项检查计划

下一步按 `COMPREHENSIVE_TEST_PLAN.md` 继续推进：

1. 协议链路专项：逐个检查 `MODBUS_TCP/RTU`、`MQTT`、`SNMP`、`HTTP`、`WEBSOCKET`、`COAP`、`OPC_UA`、`IEC104/IEC61850`、`BACNET_IP` 的配置字段和真实读取路径。
2. 生命周期专项：启动、停止、重载、配置刷新、连接失败、重连失败、旧任务清理。
3. 数据处理专项：类型转换、质量判定、批量部分失败、`ProcessResult` 字段完整性。
4. 缓存上报专项：Redis 不可用、Redis Stream 失败、上报失败、限流、重试耗尽、部分 chunk 成功。
5. 压力与故障专项：大量设备、大量点位、慢设备、离线设备、线程池满载、队列增长和内存释放。

## 7. 当前基线状态

当前自动化测试基线为通过：

```text
Tests run: 386, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

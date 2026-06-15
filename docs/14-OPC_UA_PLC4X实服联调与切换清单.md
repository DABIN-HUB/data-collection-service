# OPC UA PLC4X 实服联调与切换清单

## 1. 当前基线

- `OPC_UA` 主路由已经切到 PLC4X。
- `OPC_UA_PLC4X` 仅保留为兼容别名，实际仍走同一套 PLC4X collector / adapter。
- 本地嵌入式用例已验证匿名 connect / read / write。
- 本机 Prosys Simulation Server 已验证可读，`ns=3;i=1030` 已验证可写。
- 当前实服上 `browse` 仍返回 unsupported。
- 当前实服上订阅注册成功，但值回推尚未证实。
- 当前仅支持标量点位，数组点位仍不支持。

关键入口：

- `src/main/java/com/wangbin/collector/core/collector/protocol/opc/Plc4xOpcUaCollector.java`
- `src/main/java/com/wangbin/collector/core/connection/adapter/Plc4xOpcUaConnectionAdapter.java`
- `src/test/java/com/wangbin/collector/core/collector/protocol/opc/Plc4xOpcUaCollectorRealServerIT.java`
- `src/main/resources/mock/opcuaPlc4xDevice.json`

## 2. 切换后待收口 Checklist

### 2.1 读写基线

- [ ] 用真实服务器验证匿名单点读取。
- [ ] 用真实服务器验证匿名批量读取。
- [ ] 用真实服务器验证至少一个点位可写，并在服务端确认值变化。
- [ ] 记录成功点位的 NodeId、数据类型、服务端产品和 endpoint。

### 2.2 browse 能力

- [ ] 记录 `status.browseable` 的运行时值。
- [ ] 若 `browseable=true`，执行 `browse` 并保存返回结果。
- [ ] 若 `browseable=false`，执行一次 `browse` 并保存 unsupported 证据。
- [ ] 明确现网是否依赖 browse；若依赖而 PLC4X 仍不可用，需要单独处理。

### 2.3 订阅值回推

- [ ] 不只验证订阅注册成功，还要验证服务端值变化后 collector 确实收到回推。
- [ ] 记录 `activeSubscriptions`、`subscriptionEventCount`、`lastSubscriptionEventTs`。
- [ ] 验证取消订阅后不再收到新事件。
- [ ] 若只注册成功但没有值回推，不要把该服务器记为“订阅已通过”。

### 2.4 安全与证书

- [ ] 验证 `authType=USERNAME` 场景。
- [ ] 验证 `keyStoreFile` / `trustStoreFile` 场景。
- [ ] 验证兼容别名 `securityMode`、`clientCertPath`、`requestTimeoutMs`、`connectTimeoutMs`。
- [ ] 保持 `trustAllServerCert=true` 不通过自动生成配置。

### 2.5 文档与回退

- [ ] 把本次联调结果回写到 `docs/protocols/OPC_UA.md`。
- [ ] 若仍继续保留 `OPC_UA_PLC4X` 兼容别名，明确它只是别名，不是独立实现。
- [ ] 记录回退方案；当前回退只能靠重新切回 Milo 路由代码，运行时没有热切换开关。

## 3. 推荐验证命令

```powershell
mvn --% -o -Dmaven.repo.local=.m2 -DskipTests compile
mvn --% -o -Dmaven.repo.local=.m2 -Djdk.net.URLClassPath.disableClassPathURLCheck=true -Dtest=CollectorFactoryProtocolMappingTest,ConnectionFactoryProtocolAliasMappingTest,ProtocolSchemaServiceTest,ProtocolControllerTest,ProtocolBatchStrategyTest,ProtocolConnectionValidatorTest,Plc4xOpcUaCollectorTest,Plc4xOpcUaConnectionAdapterTest,Plc4xOpcUaCollectorIntegrationTest,Plc4xOpcUaCollectorRealServerIT test -Dopcua.real.enabled=true
```

按需追加：

```powershell
-Dopcua.real.startNodeId=1030
-Dopcua.real.endNodeId=1030
-Dopcua.real.expectWriteSuccess=true
-Dopcua.real.expectSubscriptionEvent=true
```

## 4. 实服联调记录模板

```md
# OPC UA PLC4X 实服联调记录

## 基本信息

- 日期：
- 执行人：
- 环境：
- 代码分支 / commit：
- 服务器产品 / 版本：
- endpoint：
- 认证方式：
- 安全策略：
- 样例点位：

## 本次目标

- [ ] 读取
- [ ] 写入
- [ ] browse
- [ ] 订阅值回推
- [ ] 用户名密码
- [ ] X509

## 执行结果

### 用例 1：单点读取
- 输入：
- 实际：
- 证据：
- 结论：PASS / FAIL

### 用例 2：写入
- 输入：
- 实际：
- 证据：
- 结论：PASS / FAIL

### 用例 3：browse
- 输入：
- 实际：
- 证据：
- 结论：PASS / FAIL

### 用例 4：订阅值回推
- 输入：
- 实际：
- 证据：
- 结论：PASS / FAIL

## 运行时状态摘录

- browseable：
- subscribable：
- activeSubscriptions：
- subscriptionEventCount：
- lastSubscriptionEventTs：
- connectionString：

## 结论

- 是否满足当前服务器读写要求：
- 是否满足当前服务器订阅要求：
- 是否存在 browse 缺口：
- 是否允许继续扩大使用范围：
- 剩余问题：
```

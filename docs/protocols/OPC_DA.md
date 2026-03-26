# OPC DA

## 1. 当前实现概览

当前工程中的 OPC DA 采集由 `OpcDaCollector` 负责，对外仍保持统一采集器接口（connect/read/write/subscribe 等）。

实际桥接层通过 `OpcDaBridge` 抽象，支持两种模式：
- `INMEMORY`：内存模拟桥，仅用于本地联调。
- `HTTP`：远程桥接模式，通过 HTTP 调用 OPC DA Bridge 服务（推荐生产使用）。

相关代码：
- `src/main/java/com/wangbin/collector/core/collector/protocol/opc/OpcDaCollector.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/opc/da/OpcDaBridge.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/opc/da/InMemoryOpcDaBridge.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/opc/da/RemoteOpcDaBridge.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/opc/da/OpcDaBridgeMode.java`

## 2. 采集逻辑（端到端）

### 2.1 初始化与连接

1. 平台下发设备，`protocolType=OPC_DA`。
2. `CollectorFactory` 创建 `OpcDaCollector`。
3. `OpcDaCollector.doConnect()` 读取 `DeviceConnection` 配置并组装 `OpcDaConfig`。
4. 根据 `bridgeMode` 选择桥：
- `INMEMORY` -> `InMemoryOpcDaBridge`
- `HTTP` -> `RemoteOpcDaBridge`
5. 调用 `bridge.connect(config)` 建立会话。

### 2.2 点位读取

- 单点：`doReadPoint()` -> `bridge.read(itemId)`
- 批量：`doReadPoints()` -> `bridge.readBatch(itemIds)`
- 读回值会进入当前采集器 `latestValues` 缓存，并继续走已有数据质量处理链（不改原流程）。

### 2.3 点位写入

- 单点：`doWritePoint()` -> `bridge.write(itemId, value)`
- 批量：`doWritePoints()` 内部循环调用单点写入
- 写成功后更新 `latestValues`。

### 2.4 订阅与取消

- 订阅：`doSubscribe()` -> `bridge.subscribe(itemIds)`
- 取消：`doUnsubscribe()` -> `bridge.unsubscribe(itemIds)`
- 采集器本地维护 `subscribedItems` 映射。

### 2.5 命令扩展

`executeCommand` 当前支持：
- `read`
- `write`
- `browse`

其中 `browse` 通过桥接层返回 OPC 树节点列表。

## 3. 地址映射规则

`DataPoint` 到 OPC DA `itemId` 的解析顺序：
1. `point.address`
2. `point.pointCode`
3. `point.pointName`
4. `point.pointId`

建议生产配置中直接使用 `address` 存放标准 OPC itemId，避免歧义。

## 4. HTTP 桥接模式使用方式

## 4.1 设备连接参数（DeviceConnection）

在设备连接的 `extJson` 中增加以下字段（推荐）：

```json
{
  "serverProgId": "Matrikon.OPC.Simulation.1",
  "host": "192.168.1.20",
  "requestTimeout": 5000,
  "updateRate": 1000,

  "bridgeMode": "HTTP",
  "bridgeBaseUrl": "http://127.0.0.1:18080/api/v1/opcda",
  "bridgeToken": "",
  "bridgeRetryCount": 1,
  "bridgeRetryBackoffMs": 200
}
```

字段说明：
- `bridgeMode`：`HTTP` 或 `INMEMORY`。
- `bridgeBaseUrl`：桥接服务基础地址；若缺省，回退 `connection.url`。
- `bridgeToken`：可选，若配置则通过 `Authorization: Bearer <token>` 发送。
- `bridgeRetryCount`：失败重试次数（不含首次）。
- `bridgeRetryBackoffMs`：重试退避毫秒（线性递增）。

## 4.2 HTTP 接口调用约定（采集服务 -> 桥接服务）

`RemoteOpcDaBridge` 默认调用以下接口：
- `POST /open`
- `POST /close`
- `POST /read`
- `POST /read-batch`
- `POST /write`
- `POST /subscribe`
- `POST /unsubscribe`
- `POST /browse`

请求体统一 JSON。

桥接返回支持两种常见结构：
1. `{"success": true, "data": {...}}`
2. `{"code": "0", "message": "OK", "data": {...}}`

其中 `open` 返回必须包含 `sessionId`（`data.sessionId` 或根节点 `sessionId`）。

## 4.3 推荐桥接返回示例

```json
{
  "code": "0",
  "message": "OK",
  "data": {
    "sessionId": "sess-8f3a2c"
  }
}
```

```json
{
  "code": "0",
  "message": "OK",
  "data": {
    "values": {
      "Channel1.Device1.Tag1": 12.3,
      "Channel1.Device1.Tag2": 45
    }
  }
}
```

## 5. 配置示例

`devices.json`（示意）：

```json
{
  "deviceId": "OPCDA-01",
  "deviceName": "OPCDA设备1",
  "protocolType": "OPC_DA",
  "connectionConfig": {
    "url": "http://127.0.0.1:18080/api/v1/opcda",
    "extJson": {
      "serverProgId": "Matrikon.OPC.Simulation.1",
      "host": "192.168.1.20",
      "requestTimeout": 5000,
      "updateRate": 1000,
      "bridgeMode": "HTTP",
      "bridgeBaseUrl": "http://127.0.0.1:18080/api/v1/opcda",
      "bridgeRetryCount": 1,
      "bridgeRetryBackoffMs": 200
    }
  }
}
```

点位配置（示意）：

```json
{
  "pointId": "p1",
  "pointName": "温度",
  "address": "Channel1.Device1.Tag1",
  "dataType": "default"
}
```

## 6. 验证步骤

1. 启动桥接服务，并确保 `/api/v1/opcda/open` 等接口可访问。
2. 配置设备 `protocolType=OPC_DA`，`bridgeMode=HTTP`。
3. 启动采集服务后观察日志，确认连接成功。
4. 触发读点/批量读，检查返回值。
5. 触发写点，检查桥接侧是否收到写请求并返回成功。
6. 切换 `bridgeMode=INMEMORY`，验证仍可联调（兼容回退）。

## 7. 实现原因与风险

采用 HTTP 桥接的原因：
- 采集服务可跨平台部署，不直接依赖 Windows COM/DCOM。
- OPC DA 专有依赖收敛到桥接服务，便于隔离与运维。

复杂度与风险：
- 新增网络调用链路，时延和失败率受桥接服务可用性影响。
- 需要桥接服务与采集服务严格遵守同一接口契约。
- 建议在生产加入桥接健康检查、重试与告警。

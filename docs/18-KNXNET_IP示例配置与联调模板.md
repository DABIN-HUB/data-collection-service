# KNXNET_IP 示例配置与联调模板

## 1. 适用范围

这份文档只解决两件事：

1. `ConfigController` / `DeviceController` 实际该怎么提交 `KNXNET_IP` 配置。
2. 第一次和真实 KNXnet/IP 网关联调时，最小要准备什么。

建议第一次实机联调优先走本地临时设备：

1. `POST /api/config/local/devices`
2. `POST /api/device/{deviceId}/start-local`
3. `GET /api/device/{deviceId}/status`
4. `GET /api/data/device/{deviceId}`

## 2. 关键约束

- `device.protocolType` 建议直接写 `KNXNET_IP`。
- `device.connectionType` 也建议写 `KNXNET_IP`，避免误落到默认 `TCP` 适配器。
- `connection.host` / `connection.port` 是标准连接字段。
- `groupAddressNumLevels`、`knxConnectionType`、`requestTimeout`、`maxFieldsPerRequest`、`knxprojFilePath`、`knxprojPassword` 这些协议字段必须放在 `connection.extJson`。
- `dpt`、`dptId`、`knxDpt` 这些点位协议字段必须放在 `points[].additionalConfig`，或者直接写到 `address` 后缀里。
- 一个点位必须对应一个精确组地址，不支持 `*/*/*` 这种通配地址。
- 如果没有 `knxprojFilePath`，每个点都必须显式提供 DPT。
- 第一次联调建议先用 `POLLING` 跑通，再切 `SUBSCRIPTION` 验证事件回推。

## 3. 最小模板 A

场景：没有 `knxproj`，每个点自己显式带 DPT。这是第一次实机联调最稳妥的入口。

```http
POST /api/config/local/devices
Content-Type: application/json
```

```json
{
  "overwrite": true,
  "startAfterSave": false,
  "device": {
    "deviceId": "KNX_LAB_001",
    "deviceName": "KNX Lab Gateway 001",
    "deviceAlias": "KNX Room A",
    "protocolType": "KNXNET_IP",
    "connectionType": "KNXNET_IP",
    "ipAddress": "192.168.10.20",
    "port": 3671,
    "collectionInterval": 2000,
    "remark": "Minimal bring-up without knxproj"
  },
  "connection": {
    "deviceId": "KNX_LAB_001",
    "connectionType": "KNXNET_IP",
    "host": "192.168.10.20",
    "port": 3671,
    "readTimeoutMs": 10000,
    "timeout": 10000,
    "heartbeatInterval": 30000,
    "heartbeatTimeout": 90000,
    "extJson": {
      "groupAddressNumLevels": 3,
      "knxConnectionType": "LINK_LAYER",
      "requestTimeout": 10000,
      "maxFieldsPerRequest": 30
    }
  },
  "points": [
    {
      "pointId": "knx_room_temp",
      "pointCode": "roomTemp",
      "pointName": "Room Temperature",
      "deviceId": "KNX_LAB_001",
      "address": "1/2/3:DPT9.001",
      "dataType": "FLOAT",
      "readWrite": "R",
      "collectionMode": "POLLING",
      "status": 1,
      "baseCollectionInterval": 2000,
      "cacheEnabled": 1
    },
    {
      "pointId": "knx_room_light",
      "pointCode": "roomLight",
      "pointName": "Room Light Switch",
      "deviceId": "KNX_LAB_001",
      "address": "1/2/4:DPT1.001",
      "dataType": "BOOLEAN",
      "readWrite": "RW",
      "collectionMode": "POLLING",
      "status": 1,
      "baseCollectionInterval": 2000,
      "cacheEnabled": 1
    },
    {
      "pointId": "knx_room_dimmer",
      "pointCode": "roomDimmer",
      "pointName": "Room Dimmer Percent",
      "deviceId": "KNX_LAB_001",
      "address": "1/2/5:DPT5.001",
      "dataType": "INT",
      "readWrite": "RW",
      "collectionMode": "POLLING",
      "status": 1,
      "baseCollectionInterval": 2000,
      "cacheEnabled": 1
    }
  ]
}
```

这版模板适合先验证：

1. 连接能否建立。
2. 轮询读是否通。
3. 写入点位是否真的能改动现场对象。

## 4. 最小模板 B

场景：现场已经有 `.knxproj`，希望点位地址里不重复写 DPT。

```http
POST /api/config/local/devices
Content-Type: application/json
```

```json
{
  "overwrite": true,
  "startAfterSave": false,
  "device": {
    "deviceId": "KNX_LAB_002",
    "deviceName": "KNX Lab Gateway 002",
    "protocolType": "KNXNET_IP",
    "connectionType": "KNXNET_IP",
    "ipAddress": "192.168.10.21",
    "port": 3671,
    "collectionInterval": 2000
  },
  "connection": {
    "deviceId": "KNX_LAB_002",
    "connectionType": "KNXNET_IP",
    "host": "192.168.10.21",
    "port": 3671,
    "readTimeoutMs": 10000,
    "timeout": 10000,
    "extJson": {
      "groupAddressNumLevels": 3,
      "knxConnectionType": "LINK_LAYER",
      "requestTimeout": 10000,
      "maxFieldsPerRequest": 30,
      "knxprojFilePath": "C:/knx/site/project.knxproj",
      "knxprojPassword": ""
    }
  },
  "points": [
    {
      "pointId": "knx_room_temp",
      "pointCode": "roomTemp",
      "pointName": "Room Temperature",
      "deviceId": "KNX_LAB_002",
      "address": "1/2/3",
      "dataType": "FLOAT",
      "readWrite": "R",
      "collectionMode": "POLLING",
      "status": 1
    },
    {
      "pointId": "knx_room_light",
      "pointCode": "roomLight",
      "pointName": "Room Light Switch",
      "deviceId": "KNX_LAB_002",
      "address": "1/2/4",
      "dataType": "BOOLEAN",
      "readWrite": "RW",
      "collectionMode": "SUBSCRIPTION",
      "status": 1
    }
  ]
}
```

这版模板适合后续稳定化接入，但第一次现场联调不建议直接从这里开始。先用模板 A 把 DPT、读写、地址都对实，再切到项目文件模式更稳。

## 5. 分拆接口入参样例

如果现场不想一次性提交完整 bundle，可以拆成三步更新。

### 5.1 更新设备

```http
PUT /api/config/device/KNX_LAB_001
Content-Type: application/json
```

```json
{
  "deviceId": "KNX_LAB_001",
  "deviceName": "KNX Lab Gateway 001",
  "deviceAlias": "KNX Room A",
  "protocolType": "KNXNET_IP",
  "connectionType": "KNXNET_IP",
  "ipAddress": "192.168.10.20",
  "port": 3671,
  "collectionInterval": 2000,
  "remark": "Updated from config API"
}
```

### 5.2 更新连接

```http
PUT /api/config/device/KNX_LAB_001/connection
Content-Type: application/json
```

```json
{
  "deviceId": "KNX_LAB_001",
  "connectionType": "KNXNET_IP",
  "host": "192.168.10.20",
  "port": 3671,
  "readTimeoutMs": 10000,
  "timeout": 10000,
  "extJson": {
    "groupAddressNumLevels": 3,
    "knxConnectionType": "LINK_LAYER",
    "requestTimeout": 10000,
    "maxFieldsPerRequest": 30
  }
}
```

### 5.3 更新点位

```http
PUT /api/config/device/KNX_LAB_001/points
Content-Type: application/json
```

```json
[
  {
    "pointId": "knx_room_temp",
    "pointCode": "roomTemp",
    "pointName": "Room Temperature",
    "deviceId": "KNX_LAB_001",
    "address": "1/2/3",
    "dataType": "FLOAT",
    "readWrite": "R",
    "collectionMode": "POLLING",
    "status": 1,
    "additionalConfig": {
      "dpt": "9.001"
    }
  },
  {
    "pointId": "knx_room_light",
    "pointCode": "roomLight",
    "pointName": "Room Light Switch",
    "deviceId": "KNX_LAB_001",
    "address": "1/2/4",
    "dataType": "BOOLEAN",
    "readWrite": "RW",
    "collectionMode": "SUBSCRIPTION",
    "status": 1,
    "additionalConfig": {
      "dpt": "1.001"
    }
  }
]
```

## 6. 启动与验收最小步骤

### 6.1 启动

```http
POST /api/device/KNX_LAB_001/start-local
```

如果不是本地临时设备，而是正式缓存设备，则改成：

```http
POST /api/device/KNX_LAB_001/start
```

### 6.2 看运行态

```http
GET /api/device/KNX_LAB_001/status
```

关注这些字段：

- `protocol`
- `isConnected`
- `readSupported`
- `writable`
- `subscribable`
- `configuredPointCount`
- `activeSubscriptions`
- `connectionString`

### 6.3 看采集值

```http
GET /api/data/device/KNX_LAB_001
```

第一次联调只要先确认：

1. `knx_room_temp` 能返回数值。
2. `knx_room_light` 能返回布尔值。
3. 写 `knx_room_light` 或 `knx_room_dimmer` 后，现场对象真的变化。

## 7. 现场联调前最小检查表

- [ ] 网关地址和 UDP `3671` 可达。
- [ ] 设备 `protocolType` / `connectionType` 都是 `KNXNET_IP`。
- [ ] 没有 `knxproj` 时，每个点都显式给了 DPT。
- [ ] 点位地址没有使用通配符。
- [ ] 第一次联调先用 `POLLING` 跑通至少一个读点和一个写点。
- [ ] 再切一个 `SUBSCRIPTION` 点验证事件回推。
- [ ] 写点联调前已和现场确认对象是否允许远程写。

## 8. 本地模板文件

- `src/main/resources/mock/knxnetIpLocalDeviceRequest.json`

这个文件直接按 `POST /api/config/local/devices` 的入参结构组织，适合当作本地改名模板。

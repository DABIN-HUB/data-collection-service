# KNXNET_IP

## Current Status

- `KNXNET_IP` is no longer a scaffold-only protocol entry.
- The repository now contains a real PLC4X KNXnet/IP connect/read/write path.
- Factory, connection factory, protocol validator, and schema metadata are all wired.
- The current path supports exact-address event subscription for configured scalar points.
- `executeCommand` now exposes thin wrappers for configured-point `read`, `write`, and `status` / `diagnostic`.

## Implementation Entry Points

- `core/collector/protocol/knx/KnxNetIpCollector`
- `core/connection/adapter/KnxNetIpConnectionAdapter`
- `core/collector/protocol/knx/domain/KnxAddress`
- `core/collector/protocol/knx/util/KnxAddressParser`

## Supported Address Styles

The current implementation accepts exact KNX group addresses and optional explicit DPT suffixes.

1. Three-level group address
   - `1/2/3`
   - `1/2/3:DPT1.001`
2. Two-level group address
   - `1/200`
   - `1/200:DPT9.001`
3. One-level group address
   - `12345`
   - `12345:DPT5.001`

## Address Parsing Rules

- Wildcard addresses such as `*/*/*` are not supported in this repository point model.
- One point must map to one exact group address.
- Point `additionalConfig` can supplement the DPT when the address itself does not include it:
  - `dpt`
  - `dptId`
  - `knxDpt`
- If no `knxprojFilePath` is configured, `read` / `write` / `subscribe` require an explicit DPT either in the address suffix or the point `additionalConfig`.

## Connection Fields

```java
fields.add(createFieldConfig("host", "string", "Device host", false, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "Port", false, "3671", null));
fields.add(createFieldConfig("groupAddressNumLevels", "number", "Group address levels", false, "3", null));
fields.add(createFieldConfig("knxConnectionType", "select", "KNX connection type", false, "LINK_LAYER",
        new String[]{"LINK_LAYER", "RAW", "BUSMONITOR"}));
fields.add(createFieldConfig("requestTimeout", "number", "PLC4X request timeout (ms)", false, "10000", null));
fields.add(createFieldConfig("maxFieldsPerRequest", "number", "Max fields per request", false, "30", null));
fields.add(createFieldConfig("knxprojFilePath", "string", "KNX project file path", false, "", null));
fields.add(createFieldConfig("knxprojPassword", "password", "KNX project password", false, "", null));
fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X connection string", false, "", null));
fields.add(createFieldConfig("readTimeout", "number", "Read timeout (ms)", false, "10000", null));
fields.add(createFieldConfig("timeout", "number", "Protocol timeout (ms)", false, "10000", null));
```

## Notes

- `KnxNetIpConnectionAdapter` builds a real `knxnet-ip://` PLC4X connection.
- `KnxNetIpCollector` supports single-point and batched read/write.
- Scheduler-driven polling frequency is unchanged and still controlled by the existing collection scheduler.
- `KnxNetIpCollector` uses PLC4X event subscriptions and reuses the existing `ingestPushedValue(...)` processing path.
- Subscription events still enter the existing cache / report / telemetry-stream chain through the current framework.
- Batched reads and writes are chunked by `maxFieldsPerRequest`.
- The current path targets scalar points first and does not introduce a separate browse or side-channel collection path.

## Interface Payload Examples

Recommended first bring-up flow:

1. `POST /api/config/local/devices`
2. `POST /api/device/{deviceId}/start-local`
3. `GET /api/device/{deviceId}/status`
4. `GET /api/data/device/{deviceId}`

The important boundary is:

- `DeviceInfo` fields stay at the request top level under `device`.
- `DeviceConnection` standard fields stay under `connection`.
- KNX protocol-specific fields must go inside `connection.extJson`.
- Point-specific protocol hints must go inside `points[].additionalConfig`.

### Local Temporary Device Request

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
    "protocolType": "KNXNET_IP",
    "connectionType": "KNXNET_IP",
    "ipAddress": "192.168.10.20",
    "port": 3671,
    "collectionInterval": 2000,
    "remark": "First KNXnet/IP bring-up"
  },
  "connection": {
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
      "status": 1
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
      "status": 1
    },
    {
      "pointId": "knx_room_dimmer",
      "pointCode": "roomDimmer",
      "pointName": "Room Dimmer",
      "deviceId": "KNX_LAB_001",
      "address": "1/2/5:DPT5.001",
      "dataType": "INT",
      "readWrite": "RW",
      "collectionMode": "POLLING",
      "status": 1
    }
  ]
}
```

### Connection-Only Update

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
    "maxFieldsPerRequest": 30,
    "knxprojFilePath": "C:/knx/site/project.knxproj",
    "knxprojPassword": ""
  }
}
```

### Points-Only Update

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

## Bring-Up Baseline

- Example configuration and interface payloads:
  `docs/18-KNXNET_IP示例配置与联调模板.md`
- Local request template file:
  `src/main/resources/mock/knxnetIpLocalDeviceRequest.json`

## Command Support

`executeCommand` currently supports:
- `read`
- `write`
- `status`
- `diagnostic`

Point resolution for `read` / `write` follows the same configured-point matching order used elsewhere in the project:
1. `reportField`
2. `pointAlias`
3. `pointCode`
4. `pointId`
5. `pointName`
6. `address`

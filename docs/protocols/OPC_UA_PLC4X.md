# OPC UA PLC4X Alias

## 说明

- `OPC_UA_PLC4X` 现在不再是独立验证路由。
- 它保留为历史配置兼容别名，实际实现与 `OPC_UA` 完全相同，都会走 PLC4X。
- 兼容协议别名 `OPCUA_PLC4X`。

## 对应实现

- `core/collector/protocol/opc/Plc4xOpcUaCollector`
- `core/connection/adapter/Plc4xOpcUaConnectionAdapter`

## 何时使用

- 新配置优先使用 `OPC_UA`。
- 旧配置如果仍然写的是 `OPC_UA_PLC4X`，当前无需立即改库，运行时会继续走同一套 PLC4X 实现。

## 配置口径

该别名与 `OPC_UA` 共用同一套字段和校验规则：

- 端点：`url`、`endpointUrl`、`endpoint`、`host`、`port`
- 认证：`authType`、`username`、`password`
- 安全：`securityPolicy`、`messageSecurity`、`securityMode`
- 证书：`keyStoreFile`、`trustStoreFile`
- 覆盖：`plc4xConnectionString`

## 当前边界

- 支持 connect / read / write。
- `browse` 仍受 PLC4X runtime metadata 限制。
- 订阅注册已验证，值回推仍需逐台服务器确认。
- 数组点位仍不支持。

## 联调基线

- 样例配置：[`src/main/resources/mock/opcuaPlc4xDevice.json`](../../src/main/resources/mock/opcuaPlc4xDevice.json)
- 实服联调与记录模板：[`docs/14-OPC_UA_PLC4X实服联调与切换清单.md`](../14-OPC_UA_PLC4X实服联调与切换清单.md)

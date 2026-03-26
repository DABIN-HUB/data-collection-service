# COAP

## 实现类

- `core/collector/protocol/coap/CoapCollector`
- 基类：`core/collector/protocol/coap/base/AbstractCoapCollector`

## 实现方式

- 基于 Californium。
- 支持 GET/POST/PUT/DELETE。
- 支持 Observe 订阅和资源发现 discover。

## 地址与点位配置

- `address` 可填完整 `coap://...` 或 path。
- 扩展项：`method`、`query`、`mediaType`、`observe`、`binary`。

## 使用方式

1. 设备 `protocolType` 设为 `COAP`。
2. 连接配置提供 base uri 或 host/port。
3. 点位配置 path 或完整 URI。

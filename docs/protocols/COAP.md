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

## 连接字段整理（createFieldConfig 写法）

说明：
- `url` 与 `host + port + scheme` 二选一即可。
- 其余字段主要由 `CoapConnectionAdapter` 读取。

```java
fields.add(createFieldConfig("url", "string", "CoAP基础地址", false, "coap://127.0.0.1:5683", null));
fields.add(createFieldConfig("host", "string", "设备IP", false, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "端口", false, "5683", null));
fields.add(createFieldConfig("scheme", "string", "协议方案", false, "coap", new String[]{"coap", "coaps"}));
fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "3000", null));
fields.add(createFieldConfig("maxPendingMessages", "number", "最大排队请求数", false, "1024", null));
fields.add(createFieldConfig("dispatchBatchSize", "number", "批量分发大小", false, "1", null));
fields.add(createFieldConfig("dispatchFlushInterval", "number", "批量分发刷新间隔(ms)", false, "0", null));
fields.add(createFieldConfig("overflowStrategy", "string", "请求溢出策略", false, "BLOCK", new String[]{"BLOCK", "DROP_LATEST", "DROP_OLDEST"}));
```

## 使用方式

1. 设备 `protocolType` 设为 `COAP`。
2. 连接配置提供 base uri 或 host/port。
3. 点位配置 path 或完整 URI。

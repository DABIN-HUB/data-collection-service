# HTTP

## 实现类

- `core/collector/protocol/http/HttpCollector`

## 实现方式

- 已接入 HTTP 连接适配器。
- 采集读写采用请求-响应模型：
  - `read` / `batchRead` 发送 JSON 请求并解析响应中的点位值。
  - `write` 发送写入请求，支持解析 `success/status` ACK。
  - `command` 发送命令请求并解析回包。

## 地址与点位配置

- 设备连接可配置 `url`，或由 `host+port` 自动组装。
- 点位地址含义需按业务协议自行扩展。

## 连接字段整理（createFieldConfig 写法）

说明：
- `url` 与 `host + port + path` 二选一即可。
- 下面字段覆盖了 `HttpCollector` 和 `HttpConnectionAdapter` 真实读取的连接项。

```java
fields.add(createFieldConfig("url", "string", "HTTP基础地址", false, "http://127.0.0.1:8080", null));
fields.add(createFieldConfig("host", "string", "主机", false, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "端口", false, "8080", null));
fields.add(createFieldConfig("sslEnabled", "boolean", "是否启用HTTPS", false, "false", new String[]{"true", "false"}));
fields.add(createFieldConfig("path", "string", "基础路径", false, "", null));
fields.add(createFieldConfig("headers", "object", "自定义请求头", false, "{}", null));
fields.add(createFieldConfig("queryParams", "object", "查询参数", false, "{}", null));
fields.add(createFieldConfig("connectTimeout", "number", "连接超时(ms)", false, "10000", null));
fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
fields.add(createFieldConfig("method", "string", "发送方法", false, "POST", new String[]{"GET", "POST", "PUT", "DELETE", "HEAD"}));
fields.add(createFieldConfig("sendEndpoint", "string", "发送接口路径", false, "/api/data", null));
fields.add(createFieldConfig("receiveEndpoint", "string", "接收接口路径", false, "/api/receive", null));
fields.add(createFieldConfig("receiveMethod", "string", "接收方法", false, "GET", new String[]{"GET", "POST", "PUT", "DELETE"}));
fields.add(createFieldConfig("healthCheckPath", "string", "健康检查路径", false, "/health", null));
fields.add(createFieldConfig("heartbeatEndpoint", "string", "心跳接口路径", false, "/health", null));
fields.add(createFieldConfig("username", "string", "用户名", false, "", null));
fields.add(createFieldConfig("password", "string", "密码", false, "", null));
fields.add(createFieldConfig("authToken", "string", "Bearer令牌", false, "", null));
fields.add(createFieldConfig("authEndpoint", "string", "认证接口路径", false, "/api/auth", null));
fields.add(createFieldConfig("authMethod", "string", "认证方法", false, "POST", new String[]{"GET", "POST", "PUT", "DELETE"}));
fields.add(createFieldConfig("proxyHost", "string", "代理主机", false, "", null));
fields.add(createFieldConfig("proxyPort", "number", "代理端口", false, "8080", null));
fields.add(createFieldConfig("deviceSecret", "string", "设备密钥", false, "", null));
fields.add(createFieldConfig("authParams", "object", "扩展认证参数", false, "{}", null));
```

## 使用方式

1. 设备 `protocolType` 设为 `HTTP`。
2. 在服务端约定以下请求格式字段：`action/deviceId/points(pointId,pointCode,address)`。
3. 响应建议使用以下任一格式：
   - `{\"values\": {\"pointId\": value}}`
   - `{\"pointId\": \"xxx\", \"value\": ...}`
   - `[{\"pointId\": \"xxx\", \"value\": ...}]`

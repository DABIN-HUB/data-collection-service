# 协议字段汇总

本文档将各采集协议实际读取的连接配置字段统一汇总为 `case "XXX": ... break;` 格式，便于直接复制到字段配置生成逻辑中。

说明：
- 以下字段按当前工程代码真实读取情况整理。
- `createFieldConfig` 参数含义依次为：字段名、字段类型、中文说明、是否必填、默认值、固定枚举值。
- `CUSTOM_TCP` 当前仍为占位实现，没有读取专属连接字段，因此仅保留空分支说明。

```java
case "MODBUS_TCP":
    fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", true, "502", null));
    fields.add(createFieldConfig("slaveId", "number", "从站ID", true, "1", null));
    fields.add(createFieldConfig("byteOrder", "string", "字节顺序", true, "BIG_ENDIAN", new String[]{"BIG_ENDIAN", "LITTLE_ENDIAN"}));
    fields.add(createFieldConfig("parity", "string", "兼容校验位字段", false, "none", new String[]{"none", "odd", "even"}));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "3000", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "3000", null));
    break;

case "MODBUS_RTU":
    fields.add(createFieldConfig("slaveId", "number", "从站ID", true, "1", null));
    fields.add(createFieldConfig("serialPort", "string", "串口", true, "COM1", null));
    fields.add(createFieldConfig("baudRate", "number", "波特率", true, "9600", null));
    fields.add(createFieldConfig("dataBits", "number", "数据位", true, "8", null));
    fields.add(createFieldConfig("stopBits", "number", "停止位", true, "1", null));
    fields.add(createFieldConfig("byteOrder", "string", "字节顺序", true, "BIG_ENDIAN", new String[]{"BIG_ENDIAN", "LITTLE_ENDIAN"}));
    fields.add(createFieldConfig("interFrameDelay", "number", "帧间延时(ms)", true, "5", null));
    fields.add(createFieldConfig("parity", "string", "校验位", true, "none", new String[]{"none", "odd", "even"}));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "3000", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "3000", null));
    break;

case "OPC_UA":
    fields.add(createFieldConfig("url", "string", "OPC UA端点地址", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("endpointUrl", "string", "端点地址兼容字段", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("endpoint", "string", "端点地址别名", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("host", "string", "主机", false, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", false, "4840", null));
    fields.add(createFieldConfig("securityPolicy", "string", "安全策略", true, "None", new String[]{"None", "Basic128Rsa15", "Basic256", "Basic256Sha256", "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"}));
    fields.add(createFieldConfig("securityMode", "string", "安全模式", true, "None", new String[]{"None", "Sign", "SignAndEncrypt"}));
    fields.add(createFieldConfig("authType", "string", "认证方式", true, "ANONYMOUS", new String[]{"ANONYMOUS", "USERNAME", "CERT"}));
    fields.add(createFieldConfig("username", "string", "用户名", false, "", null));
    fields.add(createFieldConfig("password", "string", "密码", false, "", null));
    fields.add(createFieldConfig("authParams", "object", "兼容认证参数", false, "{}", null));
    fields.add(createFieldConfig("requestTimeoutMs", "number", "请求超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("requestTimeout", "number", "请求超时兼容字段(ms)", false, "5000", null));
    fields.add(createFieldConfig("connectTimeoutMs", "number", "连接超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("connectTimeout", "number", "连接超时兼容字段(ms)", false, "5000", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("subscriptionInterval", "number", "订阅发布间隔(ms)", false, "1000", null));
    fields.add(createFieldConfig("namespaceUri", "string", "命名空间URI", false, "", null));
    fields.add(createFieldConfig("clientCertPath", "string", "客户端证书路径", false, "", null));
    fields.add(createFieldConfig("clientCertPassword", "string", "客户端证书密码", false, "", null));
    fields.add(createFieldConfig("trustAllServerCert", "boolean", "是否信任所有服务端证书", false, "false", new String[]{"true", "false"}));
    break;

case "OPC_DA":
    fields.add(createFieldConfig("url", "string", "桥接地址或OPC DA访问地址", false, "http://127.0.0.1:18080/api/v1/opcda", null));
    fields.add(createFieldConfig("host", "string", "OPC DA主机", true, "127.0.0.1", null));
    fields.add(createFieldConfig("serverProgId", "string", "OPC DA服务ProgID", true, "Matrikon.OPC.Simulation.1", null));
    fields.add(createFieldConfig("progId", "string", "ProgID兼容字段", false, "Matrikon.OPC.Simulation.1", null));
    fields.add(createFieldConfig("clsid", "string", "CLSID兼容字段", false, "", null));
    fields.add(createFieldConfig("username", "string", "用户名", false, "", null));
    fields.add(createFieldConfig("password", "string", "密码", false, "", null));
    fields.add(createFieldConfig("domain", "string", "Windows域", false, "", null));
    fields.add(createFieldConfig("requestTimeout", "number", "请求超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("updateRate", "number", "订阅刷新周期(ms)", false, "1000", null));
    fields.add(createFieldConfig("bridgeMode", "string", "桥接模式", true, "INMEMORY", new String[]{"INMEMORY", "HTTP"}));
    fields.add(createFieldConfig("bridge-mode", "string", "桥接模式兼容字段", false, "INMEMORY", new String[]{"INMEMORY", "HTTP"}));
    fields.add(createFieldConfig("opcDaBridgeMode", "string", "桥接模式别名", false, "INMEMORY", new String[]{"INMEMORY", "HTTP"}));
    fields.add(createFieldConfig("bridgeBaseUrl", "string", "桥接基础地址", false, "http://127.0.0.1:18080/api/v1/opcda", null));
    fields.add(createFieldConfig("bridge-url", "string", "桥接地址兼容字段", false, "http://127.0.0.1:18080/api/v1/opcda", null));
    fields.add(createFieldConfig("opcDaBridgeUrl", "string", "桥接地址别名", false, "http://127.0.0.1:18080/api/v1/opcda", null));
    fields.add(createFieldConfig("bridgeToken", "string", "桥接访问令牌", false, "", null));
    fields.add(createFieldConfig("bridge-token", "string", "桥接令牌兼容字段", false, "", null));
    fields.add(createFieldConfig("opcDaBridgeToken", "string", "桥接令牌别名", false, "", null));
    fields.add(createFieldConfig("bridgeRetryCount", "number", "桥接重试次数", false, "1", null));
    fields.add(createFieldConfig("bridgeRetryBackoffMs", "number", "桥接重试退避(ms)", false, "200", null));
    break;

case "IEC104":
    fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", true, "2404", null));
    fields.add(createFieldConfig("slaveId", "number", "公共地址(commonAddress)", true, "1", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", true, "5000", null));
    break;

case "IEC61850":
    fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "MMS端口", true, "102", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", true, "10000", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "10000", null));
    break;

case "MQTT":
    fields.add(createFieldConfig("url", "string", "Broker完整地址", false, "tcp://127.0.0.1:1883", null));
    fields.add(createFieldConfig("brokerUrl", "string", "Broker地址兼容字段", false, "tcp://127.0.0.1:1883", null));
    fields.add(createFieldConfig("host", "string", "Broker主机", false, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "Broker端口", false, "1883", null));
    fields.add(createFieldConfig("clientId", "string", "客户端ID", true, "device_mqtt", null));
    fields.add(createFieldConfig("version", "string", "MQTT版本", true, "v5", new String[]{"v5", "v3"}));
    fields.add(createFieldConfig("username", "string", "用户名", false, "", null));
    fields.add(createFieldConfig("password", "string", "密码", false, "", null));
    fields.add(createFieldConfig("sslEnabled", "boolean", "是否启用SSL", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("subscribeTopics", "string", "默认订阅主题列表", false, "devices/${deviceId}/#", null));
    fields.add(createFieldConfig("subscribeQos", "number", "默认订阅QoS", false, "1", new String[]{"0", "1", "2"}));
    fields.add(createFieldConfig("publishTopic", "string", "默认发布主题", false, "devices/${deviceId}/data", null));
    fields.add(createFieldConfig("publishQos", "number", "发布QoS", false, "1", new String[]{"0", "1", "2"}));
    fields.add(createFieldConfig("retained", "boolean", "发布保留标记", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("cleanSession", "boolean", "是否清理会话", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("autoReconnect", "boolean", "是否自动重连", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("connectTimeout", "number", "连接超时(ms)", false, "10000", null));
    fields.add(createFieldConfig("heartbeatInterval", "number", "保活间隔(ms)", false, "60000", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("sessionExpiryInterval", "number", "会话过期间隔(s)", false, "86400", null));
    fields.add(createFieldConfig("receiveMaximum", "number", "接收窗口大小", false, "65535", null));
    fields.add(createFieldConfig("willTopic", "string", "遗嘱主题", false, "", null));
    fields.add(createFieldConfig("willMessage", "string", "遗嘱消息", false, "", null));
    fields.add(createFieldConfig("willQos", "number", "遗嘱QoS", false, "0", new String[]{"0", "1", "2"}));
    fields.add(createFieldConfig("willRetained", "boolean", "遗嘱保留标记", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("authTopic", "string", "额外认证主题", false, "", null));
    fields.add(createFieldConfig("messageProperties", "object", "MQTT v5消息属性", false, "{}", null));
    fields.add(createFieldConfig("maxPendingMessages", "number", "最大缓存消息数", false, "5000", null));
    fields.add(createFieldConfig("dispatchBatchSize", "number", "批量分发大小", false, "1", null));
    fields.add(createFieldConfig("dispatchFlushInterval", "number", "批量分发刷新间隔(ms)", false, "0", null));
    fields.add(createFieldConfig("overflowStrategy", "string", "消息溢出策略", false, "BLOCK", new String[]{"BLOCK", "DROP_LATEST", "DROP_OLDEST"}));
    fields.add(createFieldConfig("productKey", "string", "产品标识", false, "", null));
    fields.add(createFieldConfig("deviceSecret", "string", "设备密钥", false, "", null));
    fields.add(createFieldConfig("authParams", "object", "扩展认证参数", false, "{}", null));
    break;

case "SNMP":
    fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", true, "161", null));
    fields.add(createFieldConfig("community", "string", "团体字", true, "public", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("snmpRetries", "number", "重试次数", false, "1", null));
    fields.add(createFieldConfig("snmpVersion", "string", "SNMP版本", true, "2c", new String[]{"1", "2c", "3"}));
    fields.add(createFieldConfig("snmpSecurityName", "string", "SNMPv3安全用户名", false, "", null));
    fields.add(createFieldConfig("snmpSecurityLevel", "string", "SNMPv3安全级别", false, "authPriv", new String[]{"noAuthNoPriv", "authNoPriv", "authPriv"}));
    fields.add(createFieldConfig("snmpAuthProtocol", "string", "SNMPv3认证协议", false, "SHA", new String[]{"MD5", "SHA", "SHA224", "SHA256", "SHA384", "SHA512", "NONE"}));
    fields.add(createFieldConfig("snmpAuthPassword", "string", "SNMPv3认证密码", false, "", null));
    fields.add(createFieldConfig("snmpPrivProtocol", "string", "SNMPv3加密协议", false, "AES128", new String[]{"DES", "AES128", "AES192", "AES256", "NONE"}));
    fields.add(createFieldConfig("snmpPrivPassword", "string", "SNMPv3加密密码", false, "", null));
    fields.add(createFieldConfig("snmpContextName", "string", "上下文名称", false, "", null));
    fields.add(createFieldConfig("snmpContextEngineId", "string", "上下文引擎ID", false, "", null));
    break;

case "COAP":
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
    break;

case "HTTP":
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
    break;

case "WEBSOCKET":
    fields.add(createFieldConfig("url", "string", "WebSocket地址", false, "ws://127.0.0.1:8080/ws", null));
    fields.add(createFieldConfig("host", "string", "主机", false, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", false, "8080", null));
    fields.add(createFieldConfig("sslEnabled", "boolean", "是否启用WSS", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("path", "string", "连接路径", false, "/ws", null));
    fields.add(createFieldConfig("queryParams", "object", "查询参数", false, "{}", null));
    fields.add(createFieldConfig("headers", "object", "自定义请求头", false, "{}", null));
    fields.add(createFieldConfig("connectTimeout", "number", "连接超时(ms)", false, "10000", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("writeTimeout", "number", "写入超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("username", "string", "用户名", false, "", null));
    fields.add(createFieldConfig("password", "string", "密码", false, "", null));
    fields.add(createFieldConfig("authToken", "string", "Bearer令牌", false, "", null));
    fields.add(createFieldConfig("subprotocol", "string", "子协议", false, "collector-v1", null));
    fields.add(createFieldConfig("binaryMode", "boolean", "是否二进制收发", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("heartbeatInterval", "number", "心跳间隔(ms)", false, "60000", null));
    fields.add(createFieldConfig("heartbeatMessage", "string", "心跳消息", false, "ping", null));
    fields.add(createFieldConfig("heartbeatUsePing", "boolean", "是否使用Ping帧", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("authWaitResponse", "boolean", "认证后是否等待响应", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("productKey", "string", "产品标识", false, "", null));
    fields.add(createFieldConfig("deviceSecret", "string", "设备密钥", false, "", null));
    fields.add(createFieldConfig("authParams", "object", "扩展认证参数", false, "{}", null));
    break;

case "CUSTOM_TCP":
    break;
```

# 协议字段汇总
本文档将各采集协议实际读取的连接配置字段统一汇总为 case "XXX": ... break; 格式，便于直接复制到字段配置生成逻辑中。
说明：

以下字段按当前工程代码真实读取情况整理。

createFieldConfig 参数含义依次为：字段名、字段类型、中文说明、是否必填、默认值、固定枚举值。

CUSTOM_TCP 当前仍为占位实现，没有读取专属连接字段，因此仅保留空分支说明。
```java
case "MODBUS_TCP":
    fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", true, "502", null));
    fields.add(createFieldConfig("slaveId", "number", "从站ID", true, "1", null));
    fields.add(createFieldConfig("byteOrder", "string", "字节顺序", true, "BIG_ENDIAN", new String[]{"BIG_ENDIAN", "LITTLE_ENDIAN"}));
    fields.add(createFieldConfig("parity", "string", "兼容校验位字段", false, "none", new String[]{"none", "odd", "even"}));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X连接串前缀", false, "", null));
    fields.add(createFieldConfig("pingAddress", "string", "PLC4X Ping地址", false, "", null));
    fields.add(createFieldConfig("maxRegistersPerRequest", "number", "单次最大寄存器数", false, "125", null));
    fields.add(createFieldConfig("maxCoilsPerRequest", "number", "单次最大线圈数", false, "2000", null));
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
    fields.add(createFieldConfig("plc4xProtocolCode", "string", "PLC4X串口驱动类型", false, "modbus-rtu", new String[]{"modbus-rtu", "modbus-ascii"}));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X连接串前缀", false, "", null));
    fields.add(createFieldConfig("maxRegistersPerRequest", "number", "单次最大寄存器数", false, "125", null));
    fields.add(createFieldConfig("maxCoilsPerRequest", "number", "单次最大线圈数", false, "2000", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "3000", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "3000", null));
    break;

case "SIEMENS_S7":
    fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", false, "102", null));
    fields.add(createFieldConfig("rack", "number", "机架号", false, "0", null));
    fields.add(createFieldConfig("slot", "number", "槽位号", false, "1", null));
    fields.add(createFieldConfig("controllerType", "select", "控制器型号", false, "S7_1200", new String[]{"S7_300", "S7_400", "S7_1200", "S7_1500", "LOGO"}));
    fields.add(createFieldConfig("pduSize", "number", "PDU大小", false, "1024", null));
    fields.add(createFieldConfig("maxFieldsPerRequest", "number", "单次最大点位数", false, "64", null));
    fields.add(createFieldConfig("localTsap", "number", "本地TSAP", false, "", null));
    fields.add(createFieldConfig("remoteTsap", "number", "远端TSAP", false, "", null));
    fields.add(createFieldConfig("localDeviceGroup", "select", "本地设备组", false, "", new String[]{"PG_OR_PC", "OS", "OTHERS"}));
    fields.add(createFieldConfig("remoteDeviceGroup", "select", "远端设备组", false, "", new String[]{"PG_OR_PC", "OS", "OTHERS"}));
    fields.add(createFieldConfig("ping", "boolean", "启用PLC4X PING", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("pingTime", "number", "PING间隔(s)", false, "", null));
    fields.add(createFieldConfig("retryTime", "number", "重试监测时间(s)", false, "", null));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X连接串覆盖", false, "", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "5000", null));
    break;

case "ETHERNET_IP":
    fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", false, "44818", null));
    fields.add(createFieldConfig("communicationPath", "string", "通信路径", false, "[1,0]", null));
    fields.add(createFieldConfig("backplane", "number", "背板号", false, "1", null));
    fields.add(createFieldConfig("slot", "number", "槽位号", false, "0", null));
    fields.add(createFieldConfig("maxFieldsPerRequest", "number", "单次最大点位数", false, "64", null));
    fields.add(createFieldConfig("bigEndian", "boolean", "大端模式", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("forceUnconnectedOperation", "boolean", "强制非连接模式", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("tcpKeepAlive", "boolean", "TCP KeepAlive", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("tcpNoDelay", "boolean", "TCP NoDelay", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X连接串覆盖", false, "", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "5000", null));
    break;

case "ADS":
    fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "TCP端口", false, "48898", null));
    fields.add(createFieldConfig("targetAmsNetId", "string", "目标AMS Net ID", true, "", null));
    fields.add(createFieldConfig("targetAmsPort", "number", "目标AMS端口", true, "851", null));
    fields.add(createFieldConfig("sourceAmsNetId", "string", "源AMS Net ID", true, "", null));
    fields.add(createFieldConfig("sourceAmsPort", "number", "源AMS端口", true, "", null));
    fields.add(createFieldConfig("loadSymbolAndDataTypeTables", "boolean", "加载符号/类型表", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("timeoutRequest", "number", "ADS请求超时(ms)", false, "4000", null));
    fields.add(createFieldConfig("maxFieldsPerRequest", "number", "单次最大点位数", false, "64", null));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X连接串覆盖", false, "", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "5000", null));
    break;

case "OPC_UA":
    fields.add(createFieldConfig("url", "string", "OPC UA端点地址", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("endpointUrl", "string", "端点地址兼容字段", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("endpoint", "string", "端点地址别名", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("host", "string", "主机", false, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", false, "4840", null));
    fields.add(createFieldConfig("discovery", "boolean", "是否启用 discovery", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("authType", "string", "认证方式", false, "ANONYMOUS", new String[]{"ANONYMOUS", "USERNAME", "CERT"}));
    fields.add(createFieldConfig("securityPolicy", "string", "安全策略", false, "NONE", new String[]{"NONE", "Basic128Rsa15", "Basic256", "Basic256Sha256", "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"}));
    fields.add(createFieldConfig("messageSecurity", "string", "消息安全模式", false, "NONE", new String[]{"NONE", "SIGN", "SIGN_ENCRYPT"}));
    fields.add(createFieldConfig("securityMode", "string", "消息安全模式兼容字段", false, "NONE", new String[]{"NONE", "Sign", "SignAndEncrypt"}));
    fields.add(createFieldConfig("username", "string", "用户名", false, "", null));
    fields.add(createFieldConfig("password", "string", "密码", false, "", null));
    fields.add(createFieldConfig("authParams", "object", "认证参数兼容字段", false, "{}", null));
    fields.add(createFieldConfig("keyStoreFile", "string", "客户端密钥库文件", false, "", null));
    fields.add(createFieldConfig("keyStoreType", "string", "客户端密钥库类型", false, "pkcs12", null));
    fields.add(createFieldConfig("keyStorePassword", "string", "客户端密钥库密码", false, "", null));
    fields.add(createFieldConfig("clientCertPath", "string", "客户端证书兼容字段", false, "", null));
    fields.add(createFieldConfig("clientCertPassword", "string", "客户端证书密码兼容字段", false, "", null));
    fields.add(createFieldConfig("trustStoreFile", "string", "信任库文件", false, "", null));
    fields.add(createFieldConfig("trustStoreType", "string", "信任库类型", false, "pkcs12", null));
    fields.add(createFieldConfig("trustStorePassword", "string", "信任库密码", false, "", null));
    fields.add(createFieldConfig("serverCertificateFile", "string", "服务端证书文件", false, "", null));
    fields.add(createFieldConfig("endpointHost", "string", "端点主机覆盖", false, "", null));
    fields.add(createFieldConfig("endpointPort", "number", "端点端口覆盖", false, "", null));
    fields.add(createFieldConfig("channelLifetime", "number", "安全通道生命周期(ms)", false, "3600000", null));
    fields.add(createFieldConfig("sessionTimeout", "number", "会话超时(ms)", false, "120000", null));
    fields.add(createFieldConfig("negotiationTimeout", "number", "握手超时(ms)", false, "60000", null));
    fields.add(createFieldConfig("connectTimeoutMs", "number", "连接超时兼容字段(ms)", false, "60000", null));
    fields.add(createFieldConfig("connectTimeout", "number", "连接超时兼容字段(ms)", false, "60000", null));
    fields.add(createFieldConfig("requestTimeout", "number", "请求超时(ms)", false, "30000", null));
    fields.add(createFieldConfig("requestTimeoutMs", "number", "请求超时兼容字段(ms)", false, "30000", null));
    fields.add(createFieldConfig("subscriptionInterval", "number", "订阅周期(ms)", false, "1000", null));
    fields.add(createFieldConfig("maxFieldsPerRequest", "number", "单次最大点位数", false, "100", null));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X连接串覆盖", false, "", null));
    break;

case "OPC_UA_PLC4X":
    fields.add(createFieldConfig("url", "string", "OPC UA端点地址", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("endpointUrl", "string", "端点地址兼容字段", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("endpoint", "string", "端点地址别名", false, "opc.tcp://127.0.0.1:4840", null));
    fields.add(createFieldConfig("host", "string", "主机", false, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", false, "4840", null));
    fields.add(createFieldConfig("discovery", "boolean", "是否启用 discovery", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("authType", "string", "认证方式", false, "ANONYMOUS", new String[]{"ANONYMOUS", "USERNAME", "CERT"}));
    fields.add(createFieldConfig("securityPolicy", "string", "安全策略", false, "NONE", new String[]{"NONE", "Basic128Rsa15", "Basic256", "Basic256Sha256", "Aes128_Sha256_RsaOaep", "Aes256_Sha256_RsaPss"}));
    fields.add(createFieldConfig("messageSecurity", "string", "消息安全模式", false, "NONE", new String[]{"NONE", "SIGN", "SIGN_ENCRYPT"}));
    fields.add(createFieldConfig("securityMode", "string", "消息安全模式兼容字段", false, "NONE", new String[]{"NONE", "Sign", "SignAndEncrypt"}));
    fields.add(createFieldConfig("username", "string", "用户名", false, "", null));
    fields.add(createFieldConfig("password", "string", "密码", false, "", null));
    fields.add(createFieldConfig("authParams", "object", "认证参数兼容字段", false, "{}", null));
    fields.add(createFieldConfig("keyStoreFile", "string", "客户端密钥库文件", false, "", null));
    fields.add(createFieldConfig("keyStoreType", "string", "客户端密钥库类型", false, "pkcs12", null));
    fields.add(createFieldConfig("keyStorePassword", "string", "客户端密钥库密码", false, "", null));
    fields.add(createFieldConfig("clientCertPath", "string", "客户端证书兼容字段", false, "", null));
    fields.add(createFieldConfig("clientCertPassword", "string", "客户端证书密码兼容字段", false, "", null));
    fields.add(createFieldConfig("trustStoreFile", "string", "信任库文件", false, "", null));
    fields.add(createFieldConfig("trustStoreType", "string", "信任库类型", false, "pkcs12", null));
    fields.add(createFieldConfig("trustStorePassword", "string", "信任库密码", false, "", null));
    fields.add(createFieldConfig("serverCertificateFile", "string", "服务端证书文件", false, "", null));
    fields.add(createFieldConfig("endpointHost", "string", "端点主机覆盖", false, "", null));
    fields.add(createFieldConfig("endpointPort", "number", "端点端口覆盖", false, "", null));
    fields.add(createFieldConfig("channelLifetime", "number", "安全通道生命周期(ms)", false, "3600000", null));
    fields.add(createFieldConfig("sessionTimeout", "number", "会话超时(ms)", false, "120000", null));
    fields.add(createFieldConfig("negotiationTimeout", "number", "握手超时(ms)", false, "60000", null));
    fields.add(createFieldConfig("connectTimeoutMs", "number", "连接超时兼容字段(ms)", false, "60000", null));
    fields.add(createFieldConfig("connectTimeout", "number", "连接超时兼容字段(ms)", false, "60000", null));
    fields.add(createFieldConfig("requestTimeout", "number", "请求超时(ms)", false, "30000", null));
    fields.add(createFieldConfig("requestTimeoutMs", "number", "请求超时兼容字段(ms)", false, "30000", null));
    fields.add(createFieldConfig("subscriptionInterval", "number", "订阅周期(ms)", false, "1000", null));
    fields.add(createFieldConfig("maxFieldsPerRequest", "number", "单次最大点位数", false, "100", null));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X连接串覆盖", false, "", null));
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
    fields.add(createFieldConfig("community", "string", "团体名", true, "public", null));
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

case "KNXNET_IP":
    fields.add(createFieldConfig("host", "string", "Device host", false, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "Port", false, "3671", null));
    fields.add(createFieldConfig("groupAddressNumLevels", "number", "Group address levels", false, "3", null));
    fields.add(createFieldConfig("knxConnectionType", "string", "KNX connection type", false, "LINK_LAYER", new String[]{"LINK_LAYER", "RAW", "BUSMONITOR"}));
    fields.add(createFieldConfig("requestTimeout", "number", "PLC4X request timeout (ms)", false, "10000", null));
    fields.add(createFieldConfig("maxFieldsPerRequest", "number", "Max fields per request", false, "30", null));
    fields.add(createFieldConfig("knxprojFilePath", "string", "KNX project file path", false, "", null));
    fields.add(createFieldConfig("knxprojPassword", "string", "KNX project password", false, "", null));
    fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X connection string", false, "", null));
    fields.add(createFieldConfig("readTimeout", "number", "Read timeout (ms)", false, "10000", null));
    fields.add(createFieldConfig("timeout", "number", "Protocol timeout (ms)", false, "10000", null));
    break;

case "MITSUBISHI_MC":
    fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "端口", false, "5000", null));
    fields.add(createFieldConfig("networkNo", "number", "网络号", false, "0", null));
    fields.add(createFieldConfig("pcNo", "number", "PC号", false, "255", null));
    fields.add(createFieldConfig("ioNo", "number", "目标I/O号", false, "1023", null));
    fields.add(createFieldConfig("stationNo", "number", "站号", false, "0", null));
    fields.add(createFieldConfig("monitoringTimer", "number", "监视定时器", false, "16", null));
    fields.add(createFieldConfig("frameType", "string", "帧类型", false, "3E_BINARY", new String[]{"3E_BINARY", "3E_ASCII", "4E_BINARY"}));
    fields.add(createFieldConfig("randomReadEnabled", "boolean", "启用随机读", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("maxRandomReadPoints", "number", "随机读最大点数", false, "8", null));
    fields.add(createFieldConfig("randomWriteEnabled", "boolean", "启用随机写", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("maxRandomWritePoints", "number", "随机写最大点数", false, "8", null));
    fields.add(createFieldConfig("maxWordsPerRequest", "number", "单次最大字数", false, "120", null));
    fields.add(createFieldConfig("maxBitsPerRequest", "number", "单次最大位数", false, "256", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("additionalConfig.driverDataType", "string", "驱动原生类型", false, "INT16", new String[]{"BOOL", "INT16", "UINT16", "INT32", "UINT32", "FLOAT32", "FLOAT64", "STRING"}));
    fields.add(createFieldConfig("additionalConfig.bitIndex", "number", "字内位偏移", false, "", null));
    fields.add(createFieldConfig("additionalConfig.stringLength", "number", "字符串长度", false, "", null));
    fields.add(createFieldConfig("additionalConfig.arraySize", "number", "数组长度", false, "", null));
    break;

case "BACNET_IP":
    fields.add(createFieldConfig("host", "string", "设备IP", true, "127.0.0.1", null));
    fields.add(createFieldConfig("port", "number", "UDP端口", false, "47808", null));
    fields.add(createFieldConfig("localBindHost", "string", "本地绑定IP", false, "", null));
    fields.add(createFieldConfig("localBindPort", "number", "本地绑定端口", false, "", null));
    fields.add(createFieldConfig("remoteDeviceInstance", "number", "目标设备实例号", true, "", null));
    fields.add(createFieldConfig("localDeviceInstance", "number", "本地客户端实例号", false, "", null));
    fields.add(createFieldConfig("useWhoIsDiscovery", "boolean", "启用 Who-Is/I-Am 发现", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("networkNumber", "number", "BACnet 网络号", false, "", null));
    fields.add(createFieldConfig("macAddress", "string", "远端 MAC 地址", false, "", null));
    fields.add(createFieldConfig("covEnabled", "boolean", "启用 COV 订阅", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("defaultCovLifetimeSeconds", "number", "默认 COV 生命周期(s)", false, "300", null));
    fields.add(createFieldConfig("defaultCovIncrement", "number", "默认 COV 增量阈值", false, "", null));
    fields.add(createFieldConfig("resubscribeOnReconnect", "boolean", "重连后自动补订阅", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("apduTimeout", "number", "APDU 超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("segmentTimeout", "number", "分段超时(ms)", false, "3000", null));
    fields.add(createFieldConfig("retries", "number", "重试次数", false, "1", null));
    fields.add(createFieldConfig("maxPropertiesPerRequest", "number", "单次最大属性数", false, "32", null));
    fields.add(createFieldConfig("readPropertyMultipleEnabled", "boolean", "启用 RPM 聚合读", false, "true", new String[]{"true", "false"}));
    fields.add(createFieldConfig("writePropertyMultipleEnabled", "boolean", "启用 WPM 聚合写", false, "false", new String[]{"true", "false"}));
    fields.add(createFieldConfig("bbmdHost", "string", "BBMD 地址", false, "", null));
    fields.add(createFieldConfig("bbmdPort", "number", "BBMD 端口", false, "47808", null));
    fields.add(createFieldConfig("foreignDeviceTtlSeconds", "number", "Foreign Device TTL(s)", false, "", null));
    fields.add(createFieldConfig("readTimeout", "number", "读取超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("timeout", "number", "协议超时(ms)", false, "5000", null));
    fields.add(createFieldConfig("additionalConfig.driverDataType", "string", "驱动原生类型", false, "AUTO", new String[]{"AUTO", "BOOLEAN", "UNSIGNED", "SIGNED", "REAL", "DOUBLE", "ENUM", "STRING", "BIT_STRING"}));
    fields.add(createFieldConfig("additionalConfig.arrayIndex", "number", "属性数组下标", false, "", null));
    fields.add(createFieldConfig("additionalConfig.writePriority", "number", "写优先级", false, "", null));
    fields.add(createFieldConfig("additionalConfig.covMode", "string", "COV 模式", false, "OBJECT", new String[]{"OBJECT", "PROPERTY"}));
    fields.add(createFieldConfig("additionalConfig.covIncrement", "number", "点位级 COV 增量阈值", false, "", null));
    break;

case "CUSTOM_TCP":
    break;
    
```
